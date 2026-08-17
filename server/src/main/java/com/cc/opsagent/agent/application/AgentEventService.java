package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentEvent;
import com.cc.opsagent.agent.infrastructure.AgentEventRepository;
import com.cc.opsagent.agent.infrastructure.AgentTaskRepository;
import com.cc.opsagent.identity.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AgentEventService {

    private static final int MAX_REPLAY_EVENTS = 500;

    private final AgentTaskRepository taskRepository;
    private final AgentEventRepository eventRepository;

    public AgentEventService(
            AgentTaskRepository taskRepository,
            AgentEventRepository eventRepository) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public AgentEvent append(
            long taskId,
            String eventType,
            Map<String, Object> payload) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        Map<String, Object> safePayload =
                payload == null ? Map.of() : Map.copyOf(payload);
        long tenantId = TenantContext.requireTenantId();
        long sequence = taskRepository.lockEventSequence(tenantId, taskId) + 1;
        if (taskRepository.updateEventSequence(tenantId, taskId, sequence) != 1) {
            throw new IllegalStateException("agent event sequence could not be advanced");
        }
        long eventId = eventRepository.insert(
                tenantId, taskId, sequence, eventType.trim(), safePayload);
        return eventRepository.find(tenantId, eventId);
    }

    public List<AgentEvent> after(long taskId, long afterSequence, int limit) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        if (limit < 1 || limit > MAX_REPLAY_EVENTS) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        long tenantId = TenantContext.requireTenantId();
        if (taskRepository.find(tenantId, taskId) == null) {
            throw new IllegalArgumentException(
                    "agent task was not found for the authenticated tenant");
        }
        return eventRepository.findAfter(tenantId, taskId, afterSequence, limit);
    }
}
