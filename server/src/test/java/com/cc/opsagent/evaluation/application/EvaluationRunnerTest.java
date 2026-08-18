package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.evaluation.domain.EvaluationCase;
import com.cc.opsagent.evaluation.domain.EvaluationGroup;
import com.cc.opsagent.evaluation.domain.EvaluationObservation;
import com.cc.opsagent.evaluation.domain.EvaluationResult;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.simulator.infrastructure.ScenarioCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTest {

    @Test
    void computesExactMicroToolMetricsAndNearestRankLatency() {
        EvaluationCase evaluationCase = evaluationCase();
        EvaluationResult first = EvaluationResult.score(
                "run-1", 1, evaluationCase,
                observation(List.of("health", "metrics"), 100));
        EvaluationResult second = EvaluationResult.score(
                "run-1", 1, evaluationCase,
                observation(List.of("health", "metrics"), 200));

        EvaluationMetrics metrics = EvaluationMetrics.from(List.of(first, second));

        assertThat(metrics.toolPrecision())
                .isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(metrics.toolRecall())
                .isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(metrics.p50LatencyMs()).isEqualTo(100);
        assertThat(metrics.p95LatencyMs()).isEqualTo(200);
    }

    @Test
    void emptyRunProducesDefinedZeroMetrics() {
        EvaluationMetrics metrics = EvaluationMetrics.from(List.of());

        assertThat(metrics.totalCases()).isZero();
        assertThat(metrics.passRate()).isEqualByComparingTo("0.0000");
        assertThat(metrics.p50LatencyMs()).isZero();
        assertThat(metrics.p95LatencyMs()).isZero();
    }

    @Test
    void baselineContainsThirtyCasesAcrossAllRequiredGroups() {
        EvaluationCaseCatalog catalog = new EvaluationCaseCatalog(
                new ScenarioCatalog());

        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(catalog.all().stream().map(EvaluationCase::group).collect(
                        java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(Set.of(EvaluationGroup.values()));
        assertThat(catalog.all()).extracting(EvaluationCase::id)
                .doesNotHaveDuplicates();
    }

    private EvaluationCase evaluationCase() {
        return new EvaluationCase(
                "metric-case", EvaluationGroup.TOOL_USE, "redis-timeout",
                "Metric test", "Metric arithmetic test incident",
                "CACHE", "root", Set.of("health", "logs"), Set.of("shell"),
                "restart", Map.of(), true, true,
                AgentTaskStatus.WAITING_APPROVAL, Set.of());
    }

    private EvaluationObservation observation(List<String> tools, long latency) {
        Instant started = Instant.parse("2026-08-18T00:00:00Z");
        return new EvaluationObservation(
                "CACHE", "root", tools,
                List.of("tenant:1:evaluation:metric-case"),
                "restart", Map.of(), AgentTaskStatus.WAITING_APPROVAL,
                7, 100, latency, 0, ModelProvider.QWEN,
                "mock", "{}", null, started, started.plusMillis(latency));
    }
}
