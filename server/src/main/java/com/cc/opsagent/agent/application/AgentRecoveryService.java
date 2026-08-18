package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentStep;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.approval.domain.ApprovalStatus;
import com.cc.opsagent.approval.infrastructure.ApprovalRepository;
import com.cc.opsagent.identity.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AgentRecoveryService {

    private static final Set<String> CHECKPOINT_NODES = Set.of(
            "triage", "retrieve", "plan", "diagnose",
            "decision", "verify", "summarize");

    private final AgentTaskService tasks;
    private final AgentEventService events;
    private final ApprovalRepository approvals;
    private final RecoveryResumeHandler resumeHandler;
    private final String workerId;
    private final Duration lease;

    public AgentRecoveryService(
            AgentTaskService tasks,
            AgentEventService events,
            ApprovalRepository approvals,
            RecoveryResumeHandler resumeHandler,
            @Value("${app.agent.worker-id:local-agent-worker}") String workerId,
            @Value("${app.agent.lease:PT4M}") Duration lease) {
        this.tasks = tasks;
        this.events = events;
        this.approvals = approvals;
        this.resumeHandler = resumeHandler;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("agent worker ID must not be blank");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("agent lease must be positive");
        }
        this.workerId = workerId.trim();
        this.lease = lease;
    }

    public RecoveryDecision recover(long taskId, Instant now) {
        AgentTask task = tasks.get(taskId);
        if (task.status() != AgentTaskStatus.RUNNING
                || task.leaseUntil() == null
                || !task.leaseUntil().isBefore(now)) {
            return RecoveryDecision.NOT_ELIGIBLE;
        }
        if (task.cancelRequestedAt() != null) {
            if (tasks.transition(
                    taskId, AgentTaskStatus.RUNNING,
                    AgentTaskStatus.CANCELLED)) {
                events.append(taskId, "TASK_COMPLETED", Map.of(
                        "status", AgentTaskStatus.CANCELLED.name(),
                        "recovered", true));
            }
            return RecoveryDecision.CANCELLED;
        }

        long tenantId = TenantContext.requireTenantId();
        Long executingApproval = approvals.findExecutingIdForTask(
                tenantId, taskId);
        if (executingApproval != null) {
            return recoverApprovedWrite(task, executingApproval);
        }

        RecoveryCheckpoint checkpoint = latestCheckpoint(taskId);
        if (!tasks.claimExpiredForRecovery(
                taskId, workerId, now, lease)) {
            return RecoveryDecision.NOT_ELIGIBLE;
        }
        try {
            resumeHandler.dispatch(taskId, checkpoint);
            events.append(taskId, "TASK_RECOVERY_ENQUEUED", Map.of(
                    "checkpoint", checkpoint == null
                            ? "BEGINNING" : checkpoint.lastCompletedNode()));
            return RecoveryDecision.RESUME_ENQUEUED;
        } catch (TaskRejectedException | IllegalStateException exception) {
            tasks.transition(
                    taskId, AgentTaskStatus.RUNNING,
                    AgentTaskStatus.MANUAL_REQUIRED);
            Map<String, Object> details = new LinkedHashMap<>();
            String error = SensitiveDataRedactor.redact(exception.getMessage());
            if (error != null) details.put("error", error);
            events.append(taskId, "TASK_RECOVERY_FAILED", details);
            return RecoveryDecision.MANUAL_REQUIRED;
        }
    }

    private RecoveryDecision recoverApprovedWrite(
            AgentTask task,
            long approvalId) {
        if (approvals.hasSuccessfulExecutionRecord(
                task.tenantId(), task.id(), approvalId)) {
            if (!tasks.transition(
                    task.id(), AgentTaskStatus.RUNNING,
                    AgentTaskStatus.SUCCEEDED)) {
                return RecoveryDecision.NOT_ELIGIBLE;
            }
            events.append(task.id(), "TASK_COMPLETED", Map.of(
                    "status", AgentTaskStatus.SUCCEEDED.name(),
                    "recovered", true));
            if (approvals.finishExecution(
                    task.tenantId(), approvalId,
                    ApprovalStatus.EXECUTED, null) != 1) {
                throw new IllegalStateException(
                        "approval execution state changed during recovery");
            }
            return RecoveryDecision.COMPLETED_FROM_IDEMPOTENCY_RECORD;
        }
        if (approvals.finishExecution(
                task.tenantId(), approvalId,
                ApprovalStatus.FAILED,
                "approved write outcome is ambiguous after worker lease expiry") != 1) {
            return RecoveryDecision.NOT_ELIGIBLE;
        }
        if (!tasks.transition(
                task.id(), AgentTaskStatus.RUNNING,
                AgentTaskStatus.MANUAL_REQUIRED)) {
            throw new IllegalStateException(
                    "agent task state changed during approval recovery");
        }
        events.append(task.id(), "TASK_RECOVERY_MANUAL_REQUIRED", Map.of(
                "approvalId", approvalId,
                "reason", "AMBIGUOUS_WRITE_OUTCOME"));
        return RecoveryDecision.MANUAL_REQUIRED;
    }

    private RecoveryCheckpoint latestCheckpoint(long taskId) {
        AgentStep step = tasks.steps(taskId).stream()
                .filter(candidate -> "SUCCEEDED".equals(candidate.status()))
                .filter(candidate -> CHECKPOINT_NODES.contains(candidate.nodeName()))
                .reduce((first, second) -> second)
                .orElse(null);
        return step == null ? null : new RecoveryCheckpoint(
                step.nodeName(), step.sequence(), step.output());
    }
}
