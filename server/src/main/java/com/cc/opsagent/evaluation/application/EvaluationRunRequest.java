package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.evaluation.domain.EvaluationMode;
import com.cc.opsagent.model.ModelProvider;

import java.util.Set;

public record EvaluationRunRequest(
        EvaluationMode mode,
        ModelProvider provider,
        String model,
        String promptVersion,
        String knowledgeVersion,
        Set<String> caseIds) {

    public EvaluationRunRequest {
        mode = mode == null ? EvaluationMode.MOCK : mode;
        provider = provider == null ? ModelProvider.QWEN : provider;
        model = text(model, mode == EvaluationMode.MOCK
                ? "deterministic-mock-v1" : "provider-default");
        promptVersion = text(promptVersion, "agent-prompt-v1");
        knowledgeVersion = text(knowledgeVersion, "baseline-v1");
        caseIds = caseIds == null ? Set.of() : Set.copyOf(caseIds);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
