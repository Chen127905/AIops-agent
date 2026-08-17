package com.cc.opsagent.tool.domain;

import java.util.Map;

public record ToolInvocationRequest(
        long tenantId,
        long taskId,
        String scenarioKey,
        String toolName,
        Map<String, Object> arguments,
        String approvedRequestId,
        String idempotencyKey) {

    public ToolInvocationRequest {
        if (tenantId <= 0 || taskId <= 0) {
            throw new IllegalArgumentException("tenantId and taskId must be positive");
        }
        if (scenarioKey == null || scenarioKey.isBlank()) {
            throw new IllegalArgumentException("scenarioKey must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        arguments = Map.copyOf(arguments);
    }
}
