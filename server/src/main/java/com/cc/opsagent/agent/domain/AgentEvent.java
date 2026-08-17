package com.cc.opsagent.agent.domain;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        long id,
        long tenantId,
        long taskId,
        long sequence,
        String eventType,
        Map<String, Object> payload,
        Instant createdAt) {

    public AgentEvent {
        payload = Map.copyOf(payload);
    }
}
