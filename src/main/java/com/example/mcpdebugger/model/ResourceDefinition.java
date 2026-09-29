package com.example.mcpdebugger.model;

public class ResourceDefinition {
    public String uri;
    public String name;
    public String description;
    public String mimeType;

    @Override
    public String toString() {
        return name == null ? uri : name + " - " + uri;
    }
}
