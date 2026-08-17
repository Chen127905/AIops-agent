package com.cc.opsagent.agent.application;

import java.util.Set;

public record ModelInvocationRecord(
        long taskId,
        Long stepId,
        String provider,
        String modelName,
        String requestHash,
        String status,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        String errorSummary) {

    private static final Set<String> STATUSES =
            Set.of("SUCCEEDED", "FAILED", "TIMEOUT");

    public ModelInvocationRecord {
        if (taskId <= 0 || (stepId != null && stepId <= 0)) {
            throw new IllegalArgumentException("taskId and stepId must be positive");
        }
        requireText("provider", provider);
        requireText("modelName", modelName);
        if (requestHash == null || !requestHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("requestHash must be a SHA-256 hex value");
        }
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported model invocation status");
        }
        if (inputTokens < 0 || outputTokens < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("token counts and latency must not be negative");
        }
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
