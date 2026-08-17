package com.cc.opsagent.tool.domain;

import java.time.Duration;

public record ToolDescriptor(
        String name,
        ToolRisk risk,
        boolean requiresApproval,
        Duration timeout,
        int maxItems,
        int maxBytes) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (risk == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("risk and positive timeout are required");
        }
        if (maxItems < 1 || maxBytes < 0) {
            throw new IllegalArgumentException("tool limits must not be negative");
        }
    }
}
