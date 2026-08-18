package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentStep;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.agent.infrastructure.AgentInvocationRepository;
import com.cc.opsagent.agent.infrastructure.RecoveryCandidate;
import com.cc.opsagent.agent.infrastructure.AgentTaskRepository;
import com.cc.opsagent.identity.security.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentTaskService {

    private static final Map<AgentTaskStatus, Set<AgentTaskStatus>> TRANSITIONS =
            transitions();

    private final AgentTaskRepository repository;
    private final AgentInvocationRepository invocationRepository;

    public AgentTaskService(
            AgentTaskRepository repository,
            AgentInvocationRepository invocationRepository) {
        this.repository = repository;
        this.invocationRepository = invocationRepository;
    }

    @Transactional
    public AgentTask start(long ticketId, AgentBudget budget) {
        if (ticketId <= 0) {
            throw new IllegalArgumentException("ticketId must be positive");
        }
        if (budget == null) {
            throw new IllegalArgumentException("agent budget is required");
        }
        long tenantId = TenantContext.requireTenantId();
        if (!repository.ticketExists(tenantId, ticketId)) {
            throw new IllegalArgumentException(
                    "ticket was not found for the authenticated tenant");
        }
        try {
            long taskId = repository.insert(
                    tenantId,
                    ticketId,
                    TenantContext.requireUserId(),
                    budget);
            return requireTask(tenantId, taskId);
        } catch (DuplicateKeyException exception) {
            throw new ActiveTaskExistsException(ticketId);
        }
    }

    public AgentTask get(long taskId) {
        return requireTask(TenantContext.requireTenantId(), taskId);
    }

    public boolean claim(long taskId, String workerId, Duration lease) {
        validateLease(workerId, lease);
        Instant now = Instant.now();
        return repository.claim(
                TenantContext.requireTenantId(),
                taskId,
                workerId.trim(),
                now.plus(lease),
                now) == 1;
    }

    public boolean renewLease(long taskId, String workerId, Duration lease) {
        validateLease(workerId, lease);
        Instant now = Instant.now();
        return repository.renewLease(
                TenantContext.requireTenantId(),
                taskId,
                workerId.trim(),
                now.plus(lease),
                now) == 1;
    }

    public boolean requestCancellation(long taskId) {
        return repository.requestCancellation(
                TenantContext.requireTenantId(), taskId) == 1;
    }

    public boolean cancellationRequested(long taskId) {
        return repository.cancellationRequested(
                TenantContext.requireTenantId(), taskId);
    }

    public List<RecoveryCandidate> expiredRunning(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("invalid recovery scan parameters");
        }
        return repository.findExpiredRunning(now, limit);
    }

    public boolean claimExpiredForRecovery(
            long taskId,
            String workerId,
            Instant now,
            Duration lease) {
        validateLease(workerId, lease);
        if (now == null) {
            throw new IllegalArgumentException("recovery time is required");
        }
        return repository.claimExpiredForRecovery(
                TenantContext.requireTenantId(), taskId, workerId.trim(),
                now, now.plus(lease)) == 1;
    }

    public int expireApprovalWaits(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("expiry time is required");
        }
        return repository.expireApprovalWaits(now);
    }

    public boolean transition(
            long taskId,
            AgentTaskStatus expected,
            AgentTaskStatus target) {
        if (expected == null || target == null
                || !TRANSITIONS.getOrDefault(expected, Set.of()).contains(target)) {
            throw new IllegalArgumentException(
                    "invalid agent task transition from " + expected + " to " + target);
        }
        return repository.transition(
                TenantContext.requireTenantId(), taskId, expected, target) == 1;
    }

    @Transactional
    public void appendStep(StepRecord step) {
        if (step == null) {
            throw new IllegalArgumentException("step record is required");
        }
        long tenantId = TenantContext.requireTenantId();
        requireTask(tenantId, step.taskId());
        repository.insertStep(tenantId, new StepRecord(
                step.taskId(),
                step.sequence(),
                step.nodeName(),
                step.status(),
                step.input(),
                step.output(),
                SensitiveDataRedactor.redact(step.errorSummary()),
                step.durationMs()));
        if (repository.addUsage(tenantId, step.taskId(), 1, 0) != 1) {
            throw new IllegalStateException("agent step usage could not be updated");
        }
    }

    @Transactional
    public long appendModelInvocation(ModelInvocationRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("model invocation record is required");
        }
        long tenantId = TenantContext.requireTenantId();
        requireTask(tenantId, record.taskId());
        long invocationId = invocationRepository.insertModel(
                tenantId, new ModelInvocationRecord(
                record.taskId(),
                record.stepId(),
                record.provider(),
                record.modelName(),
                record.requestHash(),
                record.status(),
                record.inputTokens(),
                record.outputTokens(),
                record.latencyMs(),
                SensitiveDataRedactor.redact(record.errorSummary())));
        int tokens = Math.addExact(record.inputTokens(), record.outputTokens());
        if (repository.addUsage(tenantId, record.taskId(), 0, tokens) != 1) {
            throw new IllegalStateException("agent token usage could not be updated");
        }
        return invocationId;
    }

    @Transactional
    public long appendToolInvocation(ToolInvocationRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("tool invocation record is required");
        }
        long tenantId = TenantContext.requireTenantId();
        requireTask(tenantId, record.taskId());
        ToolInvocationRecord safeRecord = new ToolInvocationRecord(
                record.taskId(),
                record.stepId(),
                record.toolName(),
                record.normalizedArguments(),
                record.risk(),
                record.status(),
                record.idempotencyKey(),
                record.latencyMs(),
                record.resultSummary(),
                SensitiveDataRedactor.redact(record.errorSummary()));
        try {
            return invocationRepository.insertTool(tenantId, safeRecord);
        } catch (DuplicateKeyException exception) {
            AgentInvocationRepository.ToolIdentity existing =
                    invocationRepository.findToolByIdempotency(
                    tenantId,
                    record.taskId(),
                    record.toolName().trim(),
                    record.idempotencyKey());
            if (existing != null) {
                String attemptedHash = invocationRepository.argumentsHash(
                        safeRecord.normalizedArguments());
                if (!existing.argumentsHash().equals(attemptedHash)) {
                    throw new IllegalStateException(
                            "idempotency key was reused with different arguments");
                }
                return existing.id();
            }
            throw exception;
        }
    }

    public List<AgentStep> steps(long taskId) {
        long tenantId = TenantContext.requireTenantId();
        requireTask(tenantId, taskId);
        return repository.findSteps(tenantId, taskId);
    }

    public void recordErrorSummary(long taskId, String errorSummary) {
        if (errorSummary == null || errorSummary.isBlank()) return;
        long tenantId = TenantContext.requireTenantId();
        requireTask(tenantId, taskId);
        String safe = SensitiveDataRedactor.redact(errorSummary);
        if (safe.length() > 512) safe = safe.substring(0, 512);
        if (repository.updateErrorSummary(tenantId, taskId, safe) != 1) {
            throw new IllegalStateException("agent error summary could not be updated");
        }
    }

    private AgentTask requireTask(long tenantId, long taskId) {
        AgentTask task = repository.find(tenantId, taskId);
        if (task == null) {
            throw new IllegalArgumentException(
                    "agent task was not found for the authenticated tenant");
        }
        return task;
    }

    private void validateLease(String workerId, Duration lease) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }

    private static Map<AgentTaskStatus, Set<AgentTaskStatus>> transitions() {
        Map<AgentTaskStatus, Set<AgentTaskStatus>> transitions =
                new EnumMap<>(AgentTaskStatus.class);
        transitions.put(AgentTaskStatus.QUEUED, Set.of(AgentTaskStatus.CANCELLED));
        transitions.put(AgentTaskStatus.RUNNING, Set.of(
                AgentTaskStatus.WAITING_APPROVAL,
                AgentTaskStatus.SUCCEEDED,
                AgentTaskStatus.FAILED,
                AgentTaskStatus.CANCELLED,
                AgentTaskStatus.TIMED_OUT,
                AgentTaskStatus.MANUAL_REQUIRED));
        transitions.put(AgentTaskStatus.WAITING_APPROVAL, Set.of(
                AgentTaskStatus.RUNNING,
                AgentTaskStatus.CANCELLED,
                AgentTaskStatus.TIMED_OUT,
                AgentTaskStatus.MANUAL_REQUIRED));
        return Map.copyOf(transitions);
    }
}
