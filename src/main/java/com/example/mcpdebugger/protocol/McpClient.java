package com.example.mcpdebugger.protocol;

import com.example.mcpdebugger.model.PromptDefinition;
import com.example.mcpdebugger.model.ResourceDefinition;
import com.example.mcpdebugger.model.ToolDefinition;
import com.example.mcpdebugger.transport.McpTransport;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class McpClient {
    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private long timeout = 10000;
    private int maxRetries;
    private long retryDelayMillis = 300;
    private Consumer<JsonObject> notificationListener = value -> {};

    public McpClient(McpTransport transport) {
        this.transport = transport;
        transport.setMessageListener(
                raw -> {
                    try {
                        JsonObject message = new JsonParser().parse(raw).getAsJsonObject();
                        if (!message.has("id")) notificationListener.accept(message);
                    } catch (Exception ignored) {
                        // Transport already records malformed messages in its diagnostics log.
                    }
                });
    }

    public void setNotificationListener(Consumer<JsonObject> listener) {
        notificationListener = listener == null ? value -> {} : listener;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public void setRetryPolicy(int maxRetries, long retryDelayMillis) {
        this.maxRetries = Math.max(0, maxRetries);
        this.retryDelayMillis = Math.max(0, retryDelayMillis);
    }

    private JsonObject call(String method, JsonObject params) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", ids.getAndIncrement());
        o.addProperty("method", method);
        if (params != null) o.add("params", params);
        String request = new Gson().toJson(o);
        String raw = null;
        Exception lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                raw = transport.request(request, timeout);
                break;
            } catch (Exception failure) {
                lastFailure = failure;
                if (attempt == maxRetries) throw failure;
                try {
                    Thread.sleep(retryDelayMillis * (1L << Math.min(attempt, 5)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        if (raw == null && lastFailure != null) throw lastFailure;
        JsonObject response = new JsonParser().parse(raw).getAsJsonObject();
        if (response.has("error"))
            throw new IllegalStateException(response.get("error").toString());
        return response;
    }

    public JsonObject initialize() throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("protocolVersion", "2024-11-05");
        JsonObject c = new JsonObject();
        c.addProperty("name", "mcp-debugger-tools-java");
        c.addProperty("version", "0.1.0");
        p.add("clientInfo", c);
        p.add("capabilities", new JsonObject());
        JsonObject result = call("initialize", p).getAsJsonObject("result");
        JsonObject n = new JsonObject();
        n.addProperty("jsonrpc", "2.0");
        n.addProperty("method", "notifications/initialized");
        transport.send(new Gson().toJson(n));
        return result;
    }

    public List<ToolDefinition> listTools() throws Exception {
        List<ToolDefinition> list = new ArrayList<ToolDefinition>();
        String cursor = null;
        Set<String> seenCursors = new HashSet<String>();
        do {
            if (cursor != null && !seenCursors.add(cursor))
                throw new IllegalStateException("tools/list 返回重复 cursor: " + cursor);
            JsonObject params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            JsonObject result = call("tools/list", params).getAsJsonObject("result");
            if (result != null && result.has("tools"))
                for (JsonElement e : result.getAsJsonArray("tools"))
                    list.add(new Gson().fromJson(e, ToolDefinition.class));
            cursor = nextCursor(result);
        } while (cursor != null);
        return list;
    }

    public JsonObject callTool(String name, JsonElement arguments) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.add("arguments", arguments == null ? new JsonObject() : arguments);
        return call("tools/call", p);
    }

    public JsonObject rawRequest(String method, JsonObject params) throws Exception {
        return call(method, params);
    }

    public List<ResourceDefinition> listResources() throws Exception {
        List<ResourceDefinition> list = new ArrayList<ResourceDefinition>();
        String cursor = null;
        Set<String> seenCursors = new HashSet<String>();
        do {
            if (cursor != null && !seenCursors.add(cursor))
                throw new IllegalStateException("resources/list 返回重复 cursor: " + cursor);
            JsonObject params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            JsonObject result = call("resources/list", params).getAsJsonObject("result");
            if (result != null && result.has("resources"))
                for (JsonElement element : result.getAsJsonArray("resources"))
                    list.add(new Gson().fromJson(element, ResourceDefinition.class));
            cursor = nextCursor(result);
        } while (cursor != null);
        return list;
    }

    public JsonObject readResource(String uri) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return call("resources/read", params);
    }

    public List<PromptDefinition> listPrompts() throws Exception {
        List<PromptDefinition> list = new ArrayList<PromptDefinition>();
        String cursor = null;
        Set<String> seenCursors = new HashSet<String>();
        do {
            if (cursor != null && !seenCursors.add(cursor))
                throw new IllegalStateException("prompts/list 返回重复 cursor: " + cursor);
            JsonObject params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            JsonObject result = call("prompts/list", params).getAsJsonObject("result");
            if (result != null && result.has("prompts"))
                for (JsonElement element : result.getAsJsonArray("prompts"))
                    list.add(new Gson().fromJson(element, PromptDefinition.class));
            cursor = nextCursor(result);
        } while (cursor != null);
        return list;
    }

    private String nextCursor(JsonObject result) {
        if (result != null && result.has("nextCursor") && !result.get("nextCursor").isJsonNull())
            return result.get("nextCursor").getAsString();
        return null;
    }

    public JsonObject getPrompt(String name, JsonElement arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        return call("prompts/get", params);
    }
}
