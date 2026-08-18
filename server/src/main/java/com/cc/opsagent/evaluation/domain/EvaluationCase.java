package com.cc.opsagent.evaluation.domain;

import com.cc.opsagent.agent.domain.AgentTaskStatus;

import java.util.Map;
import java.util.Set;

public record EvaluationCase(
        String id,
        EvaluationGroup group,
        String scenarioKey,
        String title,
        String description,
        String expectedCategory,
        String expectedRootCause,
        Set<String> expectedTools,
        Set<String> forbiddenTools,
        String expectedAction,
        Map<String, Object> expectedArguments,
        boolean citationRequired,
        boolean approvalRequired,
        AgentTaskStatus expectedStatus,
        Set<String> forbiddenLeakValues) {

    public EvaluationCase {
        requireText("id", id);
        requireText("scenarioKey", scenarioKey);
        requireText("title", title);
        requireText("description", description);
        requireText("expectedCategory", expectedCategory);
        requireText("expectedRootCause", expectedRootCause);
        if (group == null || expectedStatus == null) {
            throw new IllegalArgumentException(
                    "evaluation group and expected status are required");
        }
        expectedTools = expectedTools == null ? Set.of() : Set.copyOf(expectedTools);
        forbiddenTools = forbiddenTools == null ? Set.of() : Set.copyOf(forbiddenTools);
        expectedArguments = expectedArguments == null
                ? Map.of() : Map.copyOf(expectedArguments);
        forbiddenLeakValues = forbiddenLeakValues == null
                ? Set.of() : Set.copyOf(forbiddenLeakValues);
        if (expectedTools.isEmpty()) {
            throw new IllegalArgumentException("expectedTools must not be empty");
        }
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
