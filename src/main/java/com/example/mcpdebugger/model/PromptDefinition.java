package com.example.mcpdebugger.model;

import java.util.List;

public class PromptDefinition {
    public String name;
    public String description;
    public List<PromptArgument> arguments;

    @Override
    public String toString() {
        return name + (description == null ? "" : " - " + description);
    }
}
