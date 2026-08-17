package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTaskStatus;

import java.util.List;

public record TaskOutcome(
        AgentTaskStatus status,
        String rootCause,
        List<String> toolNames,
        List<String> citations,
        String proposedAction,
        String report,
        String errorSummary) {

    public TaskOutcome {
        toolNames = List.copyOf(toolNames);
        citations = List.copyOf(citations);
    }
}
