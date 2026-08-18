package com.cc.opsagent.agent.domain;

import java.time.Instant;

public record AgentTask(
        long id,
        long tenantId,
        long ticketId,
        long requestedBy,
        AgentTaskStatus status,
        int maxSteps,
        int timeoutSeconds,
        int maxTokens,
        int stepsUsed,
        int tokensUsed,
        String workerId,
        Instant leaseUntil,
        String errorSummary,
        Instant cancelRequestedAt,
        int recoveryCount,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public AgentTask(
            long id,
            long tenantId,
            long ticketId,
            long requestedBy,
            AgentTaskStatus status,
            int maxSteps,
            int timeoutSeconds,
            int maxTokens,
            int stepsUsed,
            int tokensUsed,
            String workerId,
            Instant leaseUntil,
            String errorSummary,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {
        this(id, tenantId, ticketId, requestedBy, status,
                maxSteps, timeoutSeconds, maxTokens, stepsUsed, tokensUsed,
                workerId, leaseUntil, errorSummary, null, 0,
                createdAt, startedAt, finishedAt);
    }
}
