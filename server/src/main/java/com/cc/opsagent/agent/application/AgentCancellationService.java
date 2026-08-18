package com.cc.opsagent.agent.application;

import com.cc.opsagent.audit.SecurityAuditPort;
import com.cc.opsagent.audit.SecurityAuditPort.SecurityAuditEvent;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.approval.infrastructure.ApprovalRepository;
import com.cc.opsagent.identity.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AgentCancellationService implements CancellationProbe {

    private final AgentTaskService tasks;
    private final AgentEventService events;
    private final ApprovalRepository approvals;
    private final SecurityAuditPort audit;

    public AgentCancellationService(
            AgentTaskService tasks,
            AgentEventService events,
            ApprovalRepository approvals,
            SecurityAuditPort audit) {
        this.tasks = tasks;
        this.events = events;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Transactional
    public AgentTask requestCancel(long taskId) {
        AgentTask task = tasks.get(taskId);
        if (task.status().terminal()) {
            return task;
        }
        if (!tasks.requestCancellation(taskId)) {
            return tasks.get(taskId);
        }
        events.append(taskId, "TASK_CANCEL_REQUESTED", Map.of(
                "status", task.status().name()));
        audit.record(new SecurityAuditEvent(
                TenantContext.requireTenantId(), TenantContext.requireUserId(),
                "TASK_CANCEL_REQUESTED", "REQUESTED", "AGENT_TASK",
                Long.toString(taskId), Map.of("status", task.status().name())));
        if (task.status() == AgentTaskStatus.QUEUED
                || task.status() == AgentTaskStatus.WAITING_APPROVAL) {
            if (task.status() == AgentTaskStatus.WAITING_APPROVAL) {
                approvals.cancelPendingForTask(
                        TenantContext.requireTenantId(), taskId);
            }
            if (tasks.transition(
                    taskId, task.status(), AgentTaskStatus.CANCELLED)) {
                events.append(taskId, "TASK_COMPLETED", Map.of(
                        "status", AgentTaskStatus.CANCELLED.name()));
            }
        }
        return tasks.get(taskId);
    }

    @Override
    public boolean requested(long taskId) {
        return tasks.cancellationRequested(taskId);
    }
}
