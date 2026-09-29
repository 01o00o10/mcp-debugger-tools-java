package com.example.mcpdebugger;

import static org.junit.Assert.*;

import com.example.mcpdebugger.model.ServerConfig;
import com.example.mcpdebugger.persistence.ConfigRepository;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ConfigRepositoryTest {
    @Test
    public void defaultsAreJava8Compatible() {
        ServerConfig s = new ServerConfig();
        assertEquals("stdio", s.transport);
        assertNotNull(s.args);
    }

    @Test
    public void savesAndLoadsServerConfiguration() throws Exception {
        File file = File.createTempFile("mcp-debugger", ".json");
        try {
            ServerConfig server = new ServerConfig();
            server.name = "Java MCP";
            server.transport = "http";
            server.url = "http://127.0.0.1:8080/mcp";
            server.headers.put("Authorization", "Bearer test");
            List<ServerConfig> expected = new ArrayList<ServerConfig>();
            expected.add(server);

            ConfigRepository repository = new ConfigRepository();
            repository.save(file, expected);
            List<ServerConfig> actual = repository.load(file);

            assertEquals(1, actual.size());
            assertEquals("Java MCP", actual.get(0).name);
            assertEquals("http://127.0.0.1:8080/mcp", actual.get(0).url);
            assertEquals("Bearer test", actual.get(0).headers.get("Authorization"));
        } finally {
            file.delete();
        }
    }
}
