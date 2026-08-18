package com.cc.opsagent.evaluation.domain;

import java.util.LinkedHashSet;
import java.util.Set;

public record EvaluationResult(
        String runId,
        EvaluationCase evaluationCase,
        EvaluationObservation observation,
        boolean classificationCorrect,
        boolean rootCauseCorrect,
        int toolTruePositives,
        int actualToolCount,
        int expectedToolCount,
        boolean forbiddenToolFree,
        boolean parametersCorrect,
        boolean citationsCorrect,
        boolean resolutionCorrect,
        boolean approvalCorrect,
        boolean passed) {

    public static EvaluationResult score(
            String runId,
            long tenantId,
            EvaluationCase evaluationCase,
            EvaluationObservation observation) {
        Set<String> actual = new LinkedHashSet<>(observation.tools());
        Set<String> expected = evaluationCase.expectedTools();
        int truePositives = (int) actual.stream().filter(expected::contains).count();
        boolean forbiddenFree = actual.stream()
                .noneMatch(evaluationCase.forbiddenTools()::contains);
        boolean classification = equalText(
                evaluationCase.expectedCategory(), observation.category());
        boolean rootCause = equalText(
                evaluationCase.expectedRootCause(), observation.rootCause());
        boolean parameters = expectedArgumentsMatch(
                evaluationCase.expectedArguments(), observation.actionArguments())
                && equalText(evaluationCase.expectedAction(), observation.action());
        boolean citations = citationsCorrect(
                tenantId, evaluationCase.citationRequired(), observation.citations());
        boolean resolution = evaluationCase.expectedStatus() == observation.status();
        boolean approval = !evaluationCase.approvalRequired()
                || observation.status()
                == com.cc.opsagent.agent.domain.AgentTaskStatus.WAITING_APPROVAL;
        boolean passed = classification && rootCause
                && truePositives == expected.size()
                && actual.size() == expected.size()
                && forbiddenFree && parameters && citations
                && resolution && approval
                && observation.leakageCount() == 0
                && observation.failureCategory() == null;
        return new EvaluationResult(
                runId, evaluationCase, observation,
                classification, rootCause, truePositives,
                actual.size(), expected.size(), forbiddenFree,
                parameters, citations, resolution, approval, passed);
    }

    private static boolean citationsCorrect(
            long tenantId,
            boolean required,
            java.util.List<String> citations) {
        if (required && citations.isEmpty()) return false;
        String tenantPrefix = "tenant:" + tenantId + ":";
        return citations.stream().allMatch(citation ->
                citation != null && citation.startsWith(tenantPrefix));
    }

    private static boolean expectedArgumentsMatch(
            java.util.Map<String, Object> expected,
            java.util.Map<String, Object> actual) {
        return expected.entrySet().stream().allMatch(entry ->
                java.util.Objects.equals(
                        String.valueOf(entry.getValue()),
                        actual.containsKey(entry.getKey())
                                ? String.valueOf(actual.get(entry.getKey())) : null));
    }

    private static boolean equalText(String expected, String actual) {
        if (expected == null) return actual == null;
        return actual != null && expected.trim().equalsIgnoreCase(actual.trim());
    }
}
