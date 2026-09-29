package com.example.mcpdebugger;

import static org.junit.Assert.*;

import com.example.mcpdebugger.protocol.McpClient;
import com.example.mcpdebugger.transport.McpTransport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.function.Consumer;
import org.junit.Test;

public class McpClientTest {
    @Test
    public void initializeAndListTools() throws Exception {
        McpClient client = new McpClient(new FakeTransport());
        assertNotNull(client.initialize());
        assertEquals("echo", client.listTools().get(0).name);
    }

    @Test
    public void callToolReturnsResult() throws Exception {
        McpClient client = new McpClient(new FakeTransport());
        client.initialize();
        JsonObject result = client.callTool("echo", new JsonParser().parse("{\"text\":\"hello\"}"));
        assertEquals("hello", result.getAsJsonObject("result").get("text").getAsString());
    }

    @Test
    public void listResourcesAndPrompts() throws Exception {
        McpClient client = new McpClient(new FakeTransport());
        client.initialize();
        assertEquals("demo://hello", client.listResources().get(0).uri);
        assertEquals("welcome", client.listPrompts().get(0).name);
    }

    @Test
    public void serverErrorIsPropagated() throws Exception {
        McpClient client = new McpClient(new ErrorTransport());
        try {
            client.rawRequest("unknown/method", new JsonObject());
            fail("expected JSON-RPC error");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("-32601"));
        }
    }

    @Test
    public void retriesTransientTransportFailure() throws Exception {
        FlakyTransport transport = new FlakyTransport();
        McpClient client = new McpClient(transport);
        client.setRetryPolicy(1, 0);
        assertNotNull(client.initialize());
        assertEquals(2, transport.calls);
    }

    private static class FakeTransport implements McpTransport {
        private Consumer<String> logs = value -> {};

        @Override
        public void start() {}

        @Override
        public void send(String message) {
            logs.accept("notification: " + message);
        }

        @Override
        public String request(String message, long timeout) throws IOException {
            JsonObject request = new JsonParser().parse(message).getAsJsonObject();
            String method = request.get("method").getAsString();
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", request.get("id"));
            JsonObject result = new JsonObject();
            if ("initialize".equals(method)) {
                result.addProperty("protocolVersion", "2024-11-05");
            } else if ("tools/list".equals(method)) {
                result.add(
                        "tools",
                        new JsonParser()
                                .parse(
                                        "[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]"));
            } else if ("tools/call".equals(method)) {
                result.addProperty(
                        "text",
                        request.getAsJsonObject("params")
                                .getAsJsonObject("arguments")
                                .get("text")
                                .getAsString());
            } else if ("resources/list".equals(method)) {
                result.add(
                        "resources",
                        new JsonParser().parse("[{\"uri\":\"demo://hello\",\"name\":\"hello\"}]"));
            } else if ("prompts/list".equals(method)) {
                result.add("prompts", new JsonParser().parse("[{\"name\":\"welcome\"}]"));
            }
            response.add("result", result);
            return response.toString();
        }

        @Override
        public void close() {}

        @Override
        public void setMessageListener(Consumer<String> listener) {}

        @Override
        public void setLogListener(Consumer<String> listener) {
            logs = listener;
        }
    }

    private static class ErrorTransport extends FakeTransport {
        @Override
        public String request(String message, long timeout) {
            JsonObject request = new JsonParser().parse(message).getAsJsonObject();
            JsonObject error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty("message", "Method not found");
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", request.get("id"));
            response.add("error", error);
            return response.toString();
        }
    }

    private static class FlakyTransport extends FakeTransport {
        private int calls;

        @Override
        public String request(String message, long timeout) throws IOException {
            calls++;
            if (calls == 1) throw new IOException("temporary connection failure");
            return super.request(message, timeout);
        }
    }
}
