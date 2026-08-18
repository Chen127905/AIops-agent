package com.cc.opsagent.approval.application;

import com.cc.opsagent.audit.SecurityAuditPort;
import com.cc.opsagent.audit.SecurityAuditPort.SecurityAuditEvent;
import com.cc.opsagent.agent.application.AgentEventService;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.approval.domain.ApprovalRequest;
import com.cc.opsagent.approval.domain.ApprovalStatus;
import com.cc.opsagent.approval.infrastructure.ApprovalRepository;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.observability.AgentMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.cc.opsagent.ticket.application.TicketService;
import com.cc.opsagent.ticket.domain.TicketStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ApprovalService implements ApprovalRequestCreator {

    private static final Set<String> HIGH_RISK_TOOLS =
            Set.of("restartService", "changeConfig");

    private final ApprovalRepository repository;
    private final AgentTaskService taskService;
    private final AgentEventService eventService;
    private final ApprovalResumeHandler resumeHandler;
    private final SecurityAuditPort audit;
    private final AgentMetrics metrics;
    private final TicketService tickets;

    public ApprovalService(
            ApprovalRepository repository,
            AgentTaskService taskService,
            AgentEventService eventService,
            ApprovalResumeHandler resumeHandler,
            SecurityAuditPort audit,
            AgentMetrics metrics,
            TicketService tickets) {
        this.repository = repository;
        this.taskService = taskService;
        this.eventService = eventService;
        this.resumeHandler = resumeHandler;
        this.audit = audit;
        this.metrics = metrics;
        this.tickets = tickets;
    }

    @Transactional
    @Override
    public ApprovalRequest create(
            long taskId,
            String checkpointId,
            String scenarioKey,
            String toolName,
            Map<String, Object> normalizedArguments,
            Duration ttl) {
        long tenantId = TenantContext.requireTenantId();
        if (!HIGH_RISK_TOOLS.contains(toolName)) {
            throw new IllegalArgumentException("approval tool is not high risk");
        }
        if (checkpointId == null || checkpointId.isBlank()
                || scenarioKey == null || scenarioKey.isBlank()
                || normalizedArguments == null || normalizedArguments.isEmpty()) {
            throw new IllegalArgumentException("approval context is incomplete");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("approval TTL must be positive");
        }
        if (!repository.taskIsWaiting(tenantId, taskId)) {
            throw new IllegalStateException("task is not waiting for approval");
        }
        long approvalId = repository.insert(
                tenantId, taskId, checkpointId.trim(), scenarioKey.trim(), toolName,
                Map.copyOf(normalizedArguments), TenantContext.requireUserId(),
                Instant.now().plus(ttl));
        eventService.append(taskId, "APPROVAL_REQUESTED", Map.of(
                "approvalId", approvalId,
                "tool", toolName));
        record("APPROVAL_REQUESTED", "REQUESTED", approvalId, taskId, toolName);
        metrics.recordApproval("REQUESTED");
        return require(tenantId, approvalId);
    }

    @Transactional(noRollbackFor = ApprovalDecisionException.class)
    public ResumeCommand approve(long approvalId, String comment) {
        TenantPrincipal principal = requireApprover();
        long tenantId = principal.tenantId();
        Instant now = Instant.now();
        if (repository.decide(
                tenantId, approvalId, principal.userId(),
                ApprovalStatus.APPROVED, normalizeComment(comment), now) != 1) {
            throw decisionFailure(tenantId, approvalId, now);
        }
        ApprovalRequest approval = require(tenantId, approvalId);
        ResumeCommand command = resumeCommand(approval);
        eventService.append(approval.taskId(), "APPROVAL_APPROVED", Map.of(
                "approvalId", approvalId,
                "decidedBy", principal.userId()));
        record("APPROVAL_APPROVED", "SUCCEEDED", approvalId,
                approval.taskId(), approval.toolName());
        metrics.recordApproval("APPROVED");
        dispatchAfterCommit(command);
        return command;
    }

    @Transactional(noRollbackFor = ApprovalDecisionException.class)
    public ApprovalRequest reject(long approvalId, String comment) {
        TenantPrincipal principal = requireApprover();
        long tenantId = principal.tenantId();
        Instant now = Instant.now();
        if (repository.decide(
                tenantId, approvalId, principal.userId(),
                ApprovalStatus.REJECTED, normalizeComment(comment), now) != 1) {
            throw decisionFailure(tenantId, approvalId, now);
        }
        ApprovalRequest approval = require(tenantId, approvalId);
        if (!taskService.transition(
                approval.taskId(), AgentTaskStatus.WAITING_APPROVAL,
                AgentTaskStatus.MANUAL_REQUIRED)) {
            throw new IllegalStateException("rejected task state changed concurrently");
        }
        tickets.transition(taskService.get(approval.taskId()).ticketId(),
                TicketStatus.MANUAL_REQUIRED);
        eventService.append(approval.taskId(), "APPROVAL_REJECTED", Map.of(
                "approvalId", approvalId,
                "decidedBy", principal.userId()));
        record("APPROVAL_REJECTED", "REJECTED", approvalId,
                approval.taskId(), approval.toolName());
        metrics.recordApproval("REJECTED");
        return approval;
    }

    public ApprovalRequest get(long approvalId) {
        return require(TenantContext.requireTenantId(), approvalId);
    }

    public List<ApprovalRequest> pending() {
        requireApprover();
        return repository.findPending(TenantContext.requireTenantId());
    }

    private ApprovalDecisionException decisionFailure(
            long tenantId,
            long approvalId,
            Instant now) {
        repository.expirePending(tenantId, approvalId, now);
        ApprovalRequest existing = repository.find(tenantId, approvalId);
        if (existing == null) {
            metrics.recordApproval("FAILED");
            recordDecisionRejection(tenantId, approvalId, "NOT_FOUND");
            return new ApprovalDecisionException("approval was not found");
        }
        recordDecisionRejection(
                tenantId, approvalId, "STATUS_" + existing.status().name());
        metrics.recordApproval("FAILED");
        return new ApprovalDecisionException(
                "approval cannot be decided from status " + existing.status());
    }

    private ApprovalRequest require(long tenantId, long approvalId) {
        ApprovalRequest approval = repository.find(tenantId, approvalId);
        if (approval == null) {
            throw new ApprovalDecisionException("approval was not found");
        }
        return approval;
    }

    private TenantPrincipal requireApprover() {
        TenantPrincipal principal = TenantContext.requirePrincipal();
        if (!principal.roles().contains("ADMIN")
                && !principal.roles().contains("APPROVER")) {
            audit.record(new SecurityAuditEvent(
                    principal.tenantId(), principal.userId(),
                    "APPROVAL_AUTHORIZATION_REJECTED", "REJECTED",
                    "APPROVAL", null, Map.of("reason", "INSUFFICIENT_ROLE")));
            throw new org.springframework.security.access.AccessDeniedException(
                    "approval requires ADMIN or APPROVER role");
        }
        return principal;
    }

    private ResumeCommand resumeCommand(ApprovalRequest approval) {
        return new ResumeCommand(
                approval.id(), approval.tenantId(), approval.taskId(),
                approval.checkpointId(), approval.scenarioKey(), approval.toolName(),
                approval.normalizedArguments(), "approval:" + approval.id());
    }

    private void dispatchAfterCommit(ResumeCommand command) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            resumeHandler.dispatch(command);
                        }
                    });
        } else {
            resumeHandler.dispatch(command);
        }
    }

    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) return null;
        String value = comment.trim();
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private void record(
            String eventType,
            String outcome,
            long approvalId,
            long taskId,
            String toolName) {
        audit.record(new SecurityAuditEvent(
                TenantContext.requireTenantId(), TenantContext.requireUserId(),
                eventType, outcome, "APPROVAL", Long.toString(approvalId),
                Map.of("taskId", taskId, "tool", toolName)));
    }

    private void recordDecisionRejection(
            long tenantId,
            long approvalId,
            String reason) {
        audit.record(new SecurityAuditEvent(
                tenantId, TenantContext.requireUserId(),
                "APPROVAL_DECISION_REJECTED", "REJECTED",
                "APPROVAL", Long.toString(approvalId),
                Map.of("reason", reason)));
    }
}
