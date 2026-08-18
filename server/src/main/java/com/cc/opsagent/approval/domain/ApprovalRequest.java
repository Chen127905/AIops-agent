package com.cc.opsagent.approval.domain;

import com.cc.opsagent.tool.domain.ToolRisk;

import java.time.Instant;
import java.util.Map;

public record ApprovalRequest(
        long id,
        long tenantId,
        long taskId,
        String checkpointId,
        String scenarioKey,
        String toolName,
        Map<String, Object> normalizedArguments,
        String argumentsHash,
        ToolRisk risk,
        ApprovalStatus status,
        long requestedBy,
        Long decidedBy,
        String decisionComment,
        Instant expiresAt,
        Instant decidedAt,
        Instant createdAt) {

    public ApprovalRequest {
        normalizedArguments = Map.copyOf(normalizedArguments);
    }
}
