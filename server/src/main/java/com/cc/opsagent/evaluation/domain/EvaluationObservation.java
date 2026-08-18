package com.cc.opsagent.evaluation.domain;

import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.model.ModelProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvaluationObservation(
        String category,
        String rootCause,
        List<String> tools,
        List<String> citations,
        String action,
        Map<String, Object> actionArguments,
        AgentTaskStatus status,
        int steps,
        int tokens,
        long latencyMs,
        int leakageCount,
        ModelProvider provider,
        String model,
        String rawStructuredOutput,
        String failureCategory,
        Instant startedAt,
        Instant finishedAt) {

    public EvaluationObservation {
        tools = tools == null ? List.of() : List.copyOf(tools);
        citations = citations == null ? List.of() : List.copyOf(citations);
        actionArguments = actionArguments == null
                ? Map.of() : Map.copyOf(actionArguments);
        if (steps < 0 || tokens < 0 || latencyMs < 0 || leakageCount < 0) {
            throw new IllegalArgumentException("evaluation counters must not be negative");
        }
        if (startedAt == null || finishedAt == null) {
            throw new IllegalArgumentException("evaluation timestamps are required");
        }
    }
}
