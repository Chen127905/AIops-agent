package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.evaluation.domain.EvaluationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public record EvaluationMetrics(
        int totalCases,
        int passedCases,
        BigDecimal passRate,
        BigDecimal classificationAccuracy,
        BigDecimal rootCauseAccuracy,
        BigDecimal toolPrecision,
        BigDecimal toolRecall,
        BigDecimal parameterAccuracy,
        BigDecimal citationAccuracy,
        BigDecimal resolutionRate,
        BigDecimal approvalInterceptionRate,
        int leakageCount,
        BigDecimal averageSteps,
        BigDecimal averageTokens,
        long p50LatencyMs,
        long p95LatencyMs) {

    private static final int SCALE = 4;

    public static EvaluationMetrics from(List<EvaluationResult> results) {
        List<EvaluationResult> values = List.copyOf(results);
        int total = values.size();
        int passed = count(values, EvaluationResult::passed);
        int truePositives = values.stream()
                .mapToInt(EvaluationResult::toolTruePositives).sum();
        int actualTools = values.stream()
                .mapToInt(EvaluationResult::actualToolCount).sum();
        int expectedTools = values.stream()
                .mapToInt(EvaluationResult::expectedToolCount).sum();
        int approvalCases = count(values,
                result -> result.evaluationCase().approvalRequired());
        int approvalCorrect = count(values,
                result -> result.evaluationCase().approvalRequired()
                        && result.approvalCorrect());
        List<Long> latencies = values.stream()
                .map(result -> result.observation().latencyMs())
                .sorted(Comparator.naturalOrder())
                .toList();
        return new EvaluationMetrics(
                total, passed, ratio(passed, total),
                ratio(count(values, EvaluationResult::classificationCorrect), total),
                ratio(count(values, EvaluationResult::rootCauseCorrect), total),
                ratio(truePositives, actualTools),
                ratio(truePositives, expectedTools),
                ratio(count(values, EvaluationResult::parametersCorrect), total),
                ratio(count(values, EvaluationResult::citationsCorrect), total),
                ratio(count(values, EvaluationResult::resolutionCorrect), total),
                ratio(approvalCorrect, approvalCases),
                values.stream().mapToInt(
                        result -> result.observation().leakageCount()).sum(),
                average(values.stream().mapToLong(
                        result -> result.observation().steps()).sum(), total),
                average(values.stream().mapToLong(
                        result -> result.observation().tokens()).sum(), total),
                percentile(latencies, 0.50), percentile(latencies, 0.95));
    }

    private static int count(
            List<EvaluationResult> values,
            Predicate<EvaluationResult> predicate) {
        return (int) values.stream().filter(predicate).count();
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(SCALE);
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(long total, int count) {
        return ratio(total, count);
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
