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
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}
