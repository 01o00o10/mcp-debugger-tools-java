package com.example.mcpdebugger.persistence;

import com.example.mcpdebugger.model.ServerConfig;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigRepository {
    private final File file =
            new File(System.getProperty("user.home"), ".mcp-debugger/config.json");

    public List<ServerConfig> load() {
        return load(file);
    }

    public List<ServerConfig> load(File source) {
        if (!source.exists()) return new ArrayList<ServerConfig>();
        try {
            Reader r = new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8);
            ServerConfig[] a = new Gson().fromJson(r, ServerConfig[].class);
            r.close();
            return a == null
                    ? new ArrayList<ServerConfig>()
                    : new ArrayList<ServerConfig>(Arrays.asList(a));
        } catch (Exception e) {
            return new ArrayList<ServerConfig>();
        }
    }

    public void save(List<ServerConfig> configs) throws IOException {
        save(file, configs);
    }

    public void save(File target, List<ServerConfig> configs) throws IOException {
        File p = target.getParentFile();
        if (!p.exists()) p.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8);
        new GsonBuilder().setPrettyPrinting().create().toJson(configs, w);
        w.close();
    }
}
