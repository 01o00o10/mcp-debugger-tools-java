package com.example.mcpdebugger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.mcpdebugger.util.LogSanitizer;
import org.junit.Test;

public class LogSanitizerTest {
    @Test
    public void masksSensitiveJsonFields() {
        String result = LogSanitizer.mask("{\"token\":\"secret\",\"name\":\"demo\"}");
        assertFalse(result.contains("secret"));
        assertTrue(result.contains("\"token\":\"***\""));
        assertTrue(result.contains("demo"));
    }
}
