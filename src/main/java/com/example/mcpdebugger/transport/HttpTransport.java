package com.example.mcpdebugger.transport;

import com.example.mcpdebugger.util.LogSanitizer;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/** Basic JSON-RPC over HTTP transport for Java 8 MCP servers. */
public class HttpTransport implements McpTransport {
    private final String endpoint;
    private final Map<String, String> headers;
    private int timeoutMillis = 10000;
    private Consumer<String> logs = s -> {};
    private volatile String sessionId;

    public HttpTransport(String endpoint, Map<String, String> headers) {
        this.endpoint = endpoint;
        this.headers = headers;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void start() throws IOException {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("HTTP URL 不能为空");
        }
        new URL(endpoint);
        logs.accept("HTTP endpoint 校验通过: " + endpoint);
    }

    @Override
    public void send(String message) throws IOException {
        requestHttp(message, false);
    }

    @Override
    public String request(String message, long timeout) throws Exception {
        return requestHttp(message, true);
    }

    private String requestHttp(String message, boolean expectResponse) throws IOException {
        // MCP 本地调试默认直连，避免系统代理拦截 127.0.0.1/localhost。
        HttpURLConnection connection =
                (HttpURLConnection) new URL(endpoint).openConnection(Proxy.NO_PROXY);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
        if (sessionId != null) connection.setRequestProperty("Mcp-Session-Id", sessionId);
        logs.accept("HTTP " + endpoint + " → " + LogSanitizer.mask(message));
        try (OutputStream output = connection.getOutputStream()) {
            output.write(message.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        String returnedSession = connection.getHeaderField("Mcp-Session-Id");
        if (returnedSession != null && !returnedSession.trim().isEmpty()) {
            sessionId = returnedSession;
            logs.accept("MCP session: " + sessionId);
        }
        String contentType = connection.getHeaderField("Content-Type");
        InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response =
                stream == null
                        ? ""
                        : contentType != null
                                        && contentType.toLowerCase().contains("text/event-stream")
                                ? readEventStream(stream)
                                : read(stream);
        logs.accept("HTTP " + endpoint + " ← status=" + status + " " + LogSanitizer.mask(response));
        if (status >= 400) throw new IOException("HTTP " + status + ": " + response);
        return expectResponse ? response : "";
    }

    private String read(InputStream stream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString().trim();
    }

    private String readEventStream(InputStream stream) throws IOException {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    logs.accept("HTTP SSE ← " + LogSanitizer.mask(data));
                    if (!data.isEmpty()) json.append(data);
                }
            }
        }
        return json.toString();
    }

    @Override
    public void close() {
        logs.accept("HTTP 连接已释放");
    }

    @Override
    public void setMessageListener(Consumer<String> listener) {}

    @Override
    public void setLogListener(Consumer<String> listener) {
        logs = listener;
    }
}
