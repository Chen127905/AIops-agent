package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.evaluation.domain.EvaluationResult;

import java.time.Instant;
import java.util.Optional;

public interface EvaluationRunRepository {

    void start(
            String runId,
            long tenantId,
            long requestedBy,
            EvaluationRunRequest request,
            int totalCases,
            Instant startedAt);

    void append(String runId, long tenantId, EvaluationResult result);

    void finish(
            String runId,
            long tenantId,
            String status,
            EvaluationMetrics metrics,
            Instant finishedAt);

    Optional<EvaluationRunSummary> find(long tenantId, String runId);
}
