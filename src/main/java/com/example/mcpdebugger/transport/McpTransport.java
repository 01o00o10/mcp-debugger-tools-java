package com.example.mcpdebugger.transport;

import java.io.IOException;
import java.util.function.Consumer;

public interface McpTransport {
    void start() throws IOException;

    void send(String message) throws IOException;

    String request(String message, long timeoutMillis) throws Exception;

    void close();

    void setMessageListener(Consumer<String> listener);

    void setLogListener(Consumer<String> listener);
}
