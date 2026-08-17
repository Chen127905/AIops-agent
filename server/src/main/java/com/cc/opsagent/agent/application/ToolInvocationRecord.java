package com.cc.opsagent.agent.application;

import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;

import java.util.Map;

public record ToolInvocationRecord(
        long taskId,
        Long stepId,
        String toolName,
        Map<String, Object> normalizedArguments,
        ToolRisk risk,
        ToolExecutionStatus status,
        String idempotencyKey,
        long latencyMs,
        Map<String, Object> resultSummary,
        String errorSummary) {

    public ToolInvocationRecord {
        if (taskId <= 0 || (stepId != null && stepId <= 0)) {
            throw new IllegalArgumentException("taskId and stepId must be positive");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        normalizedArguments = normalizedArguments == null
                ? Map.of() : Map.copyOf(normalizedArguments);
        resultSummary = resultSummary == null ? Map.of() : Map.copyOf(resultSummary);
        if (risk == null || status == null) {
            throw new IllegalArgumentException("tool risk and status are required");
        }
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotencyKey.trim();
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }
}
