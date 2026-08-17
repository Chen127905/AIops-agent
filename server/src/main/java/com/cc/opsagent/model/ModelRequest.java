package com.cc.opsagent.model;

import java.util.Map;

public record ModelRequest(String prompt, Map<String, Object> metadata) {

    public ModelRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Model prompt must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
