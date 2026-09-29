package com.example.mcpdebugger.mock;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Standalone MCP server for manually testing the debugger. */
public final class MockMcpServer {
    private MockMcpServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--http".equals(args[0])) {
            startHttp(Integer.parseInt(args[1]));
        } else {
            startStdio();
        }
    }

    private static void startStdio() throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println(handle(line));
            System.out.flush();
        }
    }

    private static void startHttp(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(
                "/mcp",
                exchange -> {
                    String request = read(exchange.getRequestBody());
                    byte[] response = handle(request).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        System.err.println("Mock MCP Server started: http://127.0.0.1:" + port + "/mcp");
    }

    private static String handle(String raw) {
        JsonObject request = new JsonParser().parse(raw).getAsJsonObject();
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (request.has("id")) response.add("id", request.get("id"));
        JsonObject result = new JsonObject();
        String method = request.get("method").getAsString();
        if ("initialize".equals(method)) {
            result.addProperty("protocolVersion", "2024-11-05");
            result.add("capabilities", new JsonObject());
        } else if ("tools/list".equals(method)) {
            result.add(
                    "tools",
                    new JsonParser()
                            .parse(
                                    "[{\"name\":\"echo\",\"description\":\"返回输入文本\",\"inputSchema\":{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}}]"));
        } else if ("tools/call".equals(method)) {
            String text =
                    request.getAsJsonObject("params")
                            .getAsJsonObject("arguments")
                            .get("text")
                            .getAsString();
            result.addProperty("text", text);
        } else if ("resources/list".equals(method)) {
            result.add(
                    "resources",
                    new JsonParser()
                            .parse(
                                    "[{\"uri\":\"demo://hello\",\"name\":\"hello\",\"mimeType\":\"text/plain\"}]"));
        } else if ("resources/read".equals(method)) {
            result.add(
                    "contents",
                    new JsonParser()
                            .parse(
                                    "[{\"uri\":\"demo://hello\",\"mimeType\":\"text/plain\",\"text\":\"Hello from Mock MCP Server\"}]"));
        } else if ("prompts/list".equals(method)) {
            result.add(
                    "prompts",
                    new JsonParser()
                            .parse("[{\"name\":\"welcome\",\"description\":\"测试 Prompt\"}]"));
        } else if ("prompts/get".equals(method)) {
            result.addProperty("description", "Mock welcome prompt");
            result.add("messages", new JsonArray());
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty("message", "Method not found: " + method);
            response.add("error", error);
            return response.toString();
        }
        response.add("result", result);
        return response.toString();
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
