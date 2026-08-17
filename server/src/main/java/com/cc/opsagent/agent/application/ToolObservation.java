package com.cc.opsagent.agent.application;

import java.util.Map;

public record ToolObservation(
        String toolName,
        boolean success,
        Map<String, Object> data,
        String error) {

    public ToolObservation {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
