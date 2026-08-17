package com.cc.opsagent.agent.domain;

import java.time.Instant;
import java.util.Map;

public record AgentStep(
        long id,
        long tenantId,
        long taskId,
        int sequence,
        String nodeName,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String errorSummary,
        long durationMs,
        Instant createdAt) {

    public AgentStep {
        input = Map.copyOf(input);
        output = Map.copyOf(output);
    }
}
