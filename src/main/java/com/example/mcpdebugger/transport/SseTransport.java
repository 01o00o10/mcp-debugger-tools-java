package com.example.mcpdebugger.transport;

import com.example.mcpdebugger.util.LogSanitizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Basic MCP SSE transport: GET event stream plus POST message endpoint. */
public class SseTransport implements McpTransport {
    private final String sseUrl;
    private final Map<String, String> headers;
    private final ConcurrentMap<Long, CompletableFuture<String>> pending =
            new ConcurrentHashMap<Long, CompletableFuture<String>>();
    private Consumer<String> logs = value -> {};
    private volatile String messageUrl;
    private volatile String sessionId;
    private volatile String lastEventId;
    private volatile boolean running;

    public SseTransport(String sseUrl, Map<String, String> headers) {
        this.sseUrl = sseUrl;
        this.headers = headers;
    }

    @Override
    public void start() throws IOException {
        running = true;
        Thread reader = new Thread(this::readLoop, "mcp-sse-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        while (running) {
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) new URL(sseUrl).openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "text/event-stream");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                if (sessionId != null) connection.setRequestProperty("Mcp-Session-Id", sessionId);
                if (lastEventId != null)
                    connection.setRequestProperty("Last-Event-ID", lastEventId);
                connection.connect();
                int status = connection.getResponseCode();
                logs.accept("SSE GET " + sseUrl + " status=" + status);
                if (status >= 400) throw new IOException("SSE HTTP " + status);
                readEvents(connection);
            } catch (Exception e) {
                if (!running) return;
                logs.accept("SSE 连接断开，2 秒后重连: " + e.getMessage());
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void readEvents(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String event = "";
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.startsWith("id:")) lastEventId = line.substring(3).trim();
                if (line.startsWith("data:")) event += line.substring(5).trim();
                if (line.isEmpty() && !event.isEmpty()) {
                    handleEvent(event);
                    event = "";
                }
            }
        }
    }

    private void handleEvent(String data) {
        logs.accept("SSE ← " + LogSanitizer.mask(data));
        if (data.startsWith("http://") || data.startsWith("https://")) {
            messageUrl = data;
            logs.accept("SSE message endpoint: " + messageUrl);
            return;
        }
        if (data.startsWith("/")) {
            try {
                URL base = new URL(sseUrl);
                messageUrl =
                        new URL(base.getProtocol(), base.getHost(), base.getPort(), data)
                                .toString();
                logs.accept("SSE message endpoint: " + messageUrl);
                return;
            } catch (Exception e) {
                logs.accept("SSE endpoint 解析失败: " + data);
            }
        }
        try {
            JsonObject message = new JsonParser().parse(data).getAsJsonObject();
            if (message.has("id")) {
                CompletableFuture<String> future = pending.remove(message.get("id").getAsLong());
                if (future != null) future.complete(data);
            }
        } catch (Exception e) {
            logs.accept("SSE 无效 JSON: " + data);
        }
    }

    @Override
    public void send(String message) throws IOException {
        if (messageUrl == null) throw new IOException("SSE message endpoint 尚未收到");
        post(message);
    }

    @Override
    public String request(String message, long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        while (messageUrl == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        if (messageUrl == null) throw new TimeoutException("SSE message endpoint 超时未返回");
        JsonObject request = new JsonParser().parse(message).getAsJsonObject();
        long id = request.get("id").getAsLong();
        CompletableFuture<String> future = new CompletableFuture<String>();
        pending.put(id, future);
        post(message);
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    private void post(String message) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(messageUrl).openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
        logs.accept("SSE " + messageUrl + " → " + LogSanitizer.mask(message));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(message.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        logs.accept("SSE POST status=" + status);
        if (status >= 400) throw new IOException("SSE POST HTTP " + status);
        connection.disconnect();
    }

    @Override
    public void close() {
        running = false;
        pending.clear();
    }

    @Override
    public void setMessageListener(Consumer<String> listener) {}

    @Override
    public void setLogListener(Consumer<String> listener) {
        logs = listener;
    }
}
