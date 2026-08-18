package com.cc.opsagent.agent.web;

import com.cc.opsagent.agent.domain.AgentStep;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record AgentTaskResultResponse(
        long taskId,
        long ticketId,
        AgentTaskStatus status,
        String category,
        String urgency,
        String rootCause,
        String proposedAction,
        String diagnosisSummary,
        Map<String, Object> actionArguments,
        double confidence,
        String report,
        List<String> citations,
        List<String> plannedTools,
        List<Map<String, Object>> evidence,
        List<Map<String, Object>> observations,
        List<String> remediationSteps,
        List<String> verificationSteps,
        String rollbackPlan,
        String errorSummary) {

    public static AgentTaskResultResponse from(
            AgentTask task,
            List<AgentStep> steps) {
        Map<String, Object> output = steps == null || steps.isEmpty()
                ? Map.of() : steps.getLast().output();
        return new AgentTaskResultResponse(
                task.id(), task.ticketId(), task.status(),
                text(output.get("category")), text(output.get("urgency")),
                text(output.get("rootCause")), text(output.get("proposedAction")),
                text(output.get("diagnosisSummary")),
                map(output.get("actionArguments")), decimal(output.get("confidence")),
                text(output.get("report")), strings(output.get("citations")),
                strings(output.get("plannedTools")), maps(output.get("evidence")),
                maps(output.get("observations")), strings(output.get("remediationSteps")),
                strings(output.get("verificationSteps")), text(output.get("rollbackPlan")),
                task.errorSummary());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(String::valueOf).toList();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Collections.unmodifiableMap(result);
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().filter(Map.class::isInstance).map(AgentTaskResultResponse::map).toList();
    }
}
