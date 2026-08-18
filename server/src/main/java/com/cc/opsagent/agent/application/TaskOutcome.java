package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTaskStatus;

import java.util.List;
import java.util.Map;

public record TaskOutcome(
        AgentTaskStatus status,
        String rootCause,
        List<String> toolNames,
        List<String> citations,
        String proposedAction,
        String report,
        String errorSummary,
        Map<String, Object> actionArguments) {

    public TaskOutcome(
            AgentTaskStatus status,
            String rootCause,
            List<String> toolNames,
            List<String> citations,
            String proposedAction,
            String report,
            String errorSummary) {
        this(status, rootCause, toolNames, citations, proposedAction,
                report, errorSummary, Map.of());
    }

    public TaskOutcome {
        toolNames = List.copyOf(toolNames);
        citations = List.copyOf(citations);
        actionArguments = actionArguments == null
                ? Map.of() : Map.copyOf(actionArguments);
    }
}
