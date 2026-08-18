package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.evaluation.domain.EvaluationMode;
import com.cc.opsagent.model.ModelProvider;

import java.time.Instant;

public record EvaluationRunSummary(
        String runId,
        EvaluationMode mode,
        ModelProvider provider,
        String model,
        String promptVersion,
        String knowledgeVersion,
        String status,
        EvaluationMetrics metrics,
        Instant startedAt,
        Instant finishedAt) {
}
