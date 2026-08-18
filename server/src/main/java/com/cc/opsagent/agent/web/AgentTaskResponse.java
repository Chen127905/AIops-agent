package com.cc.opsagent.agent.web;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;

import java.time.Instant;

public record AgentTaskResponse(
        long id,
        long ticketId,
        AgentTaskStatus status,
        int maxSteps,
        int timeoutSeconds,
        int maxTokens,
        int stepsUsed,
        int tokensUsed,
        String errorSummary,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public static AgentTaskResponse from(AgentTask task) {
        return new AgentTaskResponse(
                task.id(), task.ticketId(), task.status(), task.maxSteps(),
                task.timeoutSeconds(), task.maxTokens(), task.stepsUsed(),
                task.tokensUsed(), task.errorSummary(), task.createdAt(),
                task.startedAt(), task.finishedAt());
    }
}
