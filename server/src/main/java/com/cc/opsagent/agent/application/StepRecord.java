package com.cc.opsagent.agent.application;

import java.util.Map;
import java.util.Set;

public record StepRecord(
        long taskId,
        int sequence,
        String nodeName,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String errorSummary,
        long durationMs) {

    private static final Set<String> STATUSES =
            Set.of("STARTED", "SUCCEEDED", "FAILED", "SKIPPED");

    public StepRecord {
        if (taskId <= 0 || sequence < 1) {
            throw new IllegalArgumentException("taskId and sequence must be positive");
        }
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("nodeName must not be blank");
        }
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported step status");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
        output = output == null ? Map.of() : Map.copyOf(output);
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }
}
