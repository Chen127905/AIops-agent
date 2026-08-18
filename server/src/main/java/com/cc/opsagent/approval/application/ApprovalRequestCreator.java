package com.cc.opsagent.approval.application;

import com.cc.opsagent.approval.domain.ApprovalRequest;

import java.time.Duration;
import java.util.Map;

public interface ApprovalRequestCreator {

    ApprovalRequest create(
            long taskId,
            String checkpointId,
            String scenarioKey,
            String toolName,
            Map<String, Object> normalizedArguments,
            Duration ttl);

    static ApprovalRequestCreator noop() {
        return (taskId, checkpointId, scenarioKey, toolName, arguments, ttl) -> null;
    }
}
