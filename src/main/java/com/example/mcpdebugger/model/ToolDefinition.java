package com.example.mcpdebugger.model;

import com.google.gson.JsonObject;

public class ToolDefinition {
    public String name;
    public String description;
    public JsonObject inputSchema;

    public String toString() {
        return name + (description == null ? "" : " - " + description);
    }
}
