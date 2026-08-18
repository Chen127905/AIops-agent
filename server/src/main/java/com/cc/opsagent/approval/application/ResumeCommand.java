package com.cc.opsagent.approval.application;

import java.util.Map;

public record ResumeCommand(
        long approvalId,
        long tenantId,
        long taskId,
        String checkpointId,
        String scenarioKey,
        String toolName,
        Map<String, Object> normalizedArguments,
        String idempotencyKey) {

    public ResumeCommand {
        normalizedArguments = Map.copyOf(normalizedArguments);
    }
}
