package com.example.mcpdebugger.transport;

import com.example.mcpdebugger.util.LogSanitizer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private final String command;
    private final java.util.List<String> args;
    private final String directory;
    private final Map<String, String> env;
    private Process process;
    private BufferedWriter writer;
    private Consumer<String> messages = s -> {};
    private Consumer<String> logs = s -> {};
    private final ConcurrentMap<Long, CompletableFuture<String>> pending =
            new ConcurrentHashMap<Long, CompletableFuture<String>>();

    public StdioTransport(
            String command,
            java.util.List<String> args,
            String directory,
            Map<String, String> env) {
        this.command = command;
        this.args = args;
        this.directory = directory;
        this.env = env;
    }

    public void start() throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<String>();
        cmd.add(command);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (directory != null && !directory.trim().isEmpty()) pb.directory(new File(directory));
        pb.environment().putAll(env);
        process = pb.start();
        writer =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread out = new Thread(() -> readStdout(), "mcp-stdout");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> readStderr(), "mcp-stderr");
        err.setDaemon(true);
        err.start();
    }

    private void readStdout() {
        try {
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                try {
                    JsonObject o = new JsonParser().parse(line).getAsJsonObject();
                    if (o.has("id")) {
                        long id = o.get("id").getAsLong();
                        CompletableFuture<String> f = pending.remove(id);
                        if (f != null) f.complete(line);
                        logs.accept("MCP ← " + LogSanitizer.mask(line));
                    }
                    messages.accept(line);
                } catch (Exception e) {
                    logs.accept("无效 JSON：" + line);
                }
            }
        } catch (IOException e) {
            logs.accept("stdout 读取结束：" + e.getMessage());
        }
    }

    private void readStderr() {
        try {
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getErrorStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) logs.accept("Server stderr: " + line);
        } catch (IOException e) {
            logs.accept("stderr 读取结束：" + e.getMessage());
        }
    }

    public void send(String message) throws IOException {
        logs.accept("MCP → " + LogSanitizer.mask(message));
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    public String request(String message, long timeout) throws Exception {
        JsonObject o = new JsonParser().parse(message).getAsJsonObject();
        long id = o.get("id").getAsLong();
        CompletableFuture<String> f = new CompletableFuture<String>();
        pending.put(id, f);
        send(message);
        try {
            return f.get(timeout, TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    public void close() {
        if (process != null) process.destroy();
        for (CompletableFuture<String> f : pending.values()) f.cancel(true);
        pending.clear();
    }

    public void setMessageListener(Consumer<String> listener) {
        messages = listener;
    }

    public void setLogListener(Consumer<String> listener) {
        logs = listener;
    }
}
