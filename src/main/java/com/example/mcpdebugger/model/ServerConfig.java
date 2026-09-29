package com.example.mcpdebugger.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ServerConfig {
    public String name = "Demo Server";
    public String transport = "stdio";
    public String url = "";
    public String command = "";
    public List<String> args = new ArrayList<String>();
    public String workingDirectory = "";
    public Map<String, String> environment = new LinkedHashMap<String, String>();
    public Map<String, String> headers = new LinkedHashMap<String, String>();
    public int timeoutMillis = 10000;
    public int maxRetries = 0;
    public long retryDelayMillis = 300;

    public String toString() {
        return name;
    }
}
