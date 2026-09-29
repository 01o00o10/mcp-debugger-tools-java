package com.example.mcpdebugger.util;

public final class LogSanitizer {
    private LogSanitizer() {}

    public static String mask(String value) {
        if (value == null) return null;
        return value.replaceAll(
                "(?i)(\\\"(?:token|password|api[_-]?key|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
                "$1***$2");
    }
}
