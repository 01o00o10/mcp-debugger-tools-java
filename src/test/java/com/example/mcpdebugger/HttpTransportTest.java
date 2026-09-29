package com.example.mcpdebugger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.mcpdebugger.transport.HttpTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class HttpTransportTest {
    private HttpServer server;
    private String endpoint;

    @Before
    public void startServer() throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            Assume.assumeNoException("当前环境禁止监听本地端口，跳过 HTTP 集成测试", e);
            return;
        }
        server.createContext("/mcp", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void postsJsonRpcAndSendsHeaders() throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Test-Token", "ok");
        HttpTransport transport = new HttpTransport(endpoint, headers);
        transport.start();
        String response =
                transport.request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", 3000);
        assertTrue(response.contains("\"id\":1"));
        assertTrue(response.contains("pong"));
        transport.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        assertEquals("ok", exchange.getRequestHeaders().getFirst("X-Test-Token"));
        read(exchange.getRequestBody());
        byte[] body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"message\":\"pong\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String read(InputStream input) throws IOException {
        byte[] buffer = new byte[1024];
        StringBuilder result = new StringBuilder();
        int count;
        while ((count = input.read(buffer)) >= 0) {
            result.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
        }
        return result.toString();
    }
}
