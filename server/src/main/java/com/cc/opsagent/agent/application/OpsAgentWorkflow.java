package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.approval.application.ApprovalRequestCreator;
import com.cc.opsagent.ticket.application.TicketService;
import com.cc.opsagent.ticket.web.TicketResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

public class OpsAgentWorkflow {

    private final AgentTaskService taskService;
    private final AgentEventService eventService;
    private final TicketService ticketService;
    private final AgentWorkflowEngine engine;
    private final ModelProvider provider;
    private final String workerId;
    private final Duration lease;
    private final ApprovalRequestCreator approvals;
    private final Duration approvalTtl;

    public OpsAgentWorkflow(
            AgentTaskService taskService,
            AgentEventService eventService,
            TicketService ticketService,
            AgentWorkflowEngine engine,
            ModelProvider provider,
            String workerId,
            Duration lease) {
        this(taskService, eventService, ticketService, engine, provider,
                workerId, lease, ApprovalRequestCreator.noop(), Duration.ofMinutes(30));
    }

    public OpsAgentWorkflow(
            AgentTaskService taskService,
            AgentEventService eventService,
            TicketService ticketService,
            AgentWorkflowEngine engine,
            ModelProvider provider,
            String workerId,
            Duration lease,
            ApprovalRequestCreator approvals,
            Duration approvalTtl) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.ticketService = ticketService;
        this.engine = engine;
        this.provider = provider;
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("agent worker ID must not be blank");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("agent lease must be positive");
        }
        if (approvals == null) {
            throw new IllegalArgumentException("approval creator must not be null");
        }
        if (approvalTtl == null || approvalTtl.isZero() || approvalTtl.isNegative()) {
            throw new IllegalArgumentException("approval TTL must be positive");
        }
        this.workerId = workerId.trim();
        this.lease = lease;
        this.approvals = approvals;
        this.approvalTtl = approvalTtl;
    }

    public TaskOutcome run(long taskId) {
        AgentTask task = taskService.get(taskId);
        if (task.status() != AgentTaskStatus.QUEUED
                && task.status() != AgentTaskStatus.RUNNING) {
            throw new IllegalStateException(
                    "agent task is not executable from " + task.status());
        }
        if (!taskService.claim(taskId, workerId, lease)) {
            throw new IllegalStateException("agent task lease could not be claimed");
        }
        return executeClaimed(taskService.get(taskId), null, false);
    }

    public TaskOutcome resumeClaimed(
            long taskId,
            RecoveryCheckpoint checkpoint) {
        AgentTask task = taskService.get(taskId);
        if (task.status() != AgentTaskStatus.RUNNING) {
            throw new IllegalStateException(
                    "recovered agent task is not running");
        }
        return executeClaimed(task, checkpoint, true);
    }

    private TaskOutcome executeClaimed(
            AgentTask task,
            RecoveryCheckpoint checkpoint,
            boolean recovered) {
        long taskId = task.id();
        TaskOutcome outcome;
        try {
            eventService.append(
                    taskId,
                    recovered ? "TASK_RECOVERY_STARTED" : "TASK_STARTED",
                    !recovered
                            ? Map.of("workerId", workerId)
                            : Map.of(
                                    "workerId", workerId,
                                    "afterNode", checkpoint == null
                                            ? "BEGINNING" : checkpoint.lastCompletedNode(),
                                    "afterSequence", checkpoint == null
                                            ? 0 : checkpoint.completedSequence()));
            TicketResponse ticket = ticketService.get(task.ticketId());
            if (deadlineExceeded(task)) {
                outcome = new TaskOutcome(
                        AgentTaskStatus.TIMED_OUT, null, List.of(), List.of(),
                        null, null, "agent timeout budget exceeded");
            } else {
                AgentTaskCommand command = command(task, ticket);
                outcome = checkpoint == null
                        ? engine.execute(command)
                        : engine.resume(command, checkpoint);
            }
            validateOutcome(outcome);
        } catch (RuntimeException exception) {
            outcome = new TaskOutcome(
                    AgentTaskStatus.FAILED, null, List.of(), List.of(),
                    null, null, SensitiveDataRedactor.redact(exception.getMessage()));
        }

        if (!taskService.transition(
                taskId, AgentTaskStatus.RUNNING, outcome.status())) {
            throw new IllegalStateException(
                    "agent task status changed before workflow completion");
        }
        if (outcome.status() == AgentTaskStatus.WAITING_APPROVAL) {
            outcome = createApprovalOrRequireManual(task, outcome);
        }
        String eventType = outcome.status() == AgentTaskStatus.WAITING_APPROVAL
                ? "TASK_SUSPENDED" : "TASK_COMPLETED";
        eventService.append(taskId, eventType, Map.of(
                "status", outcome.status().name(),
                "toolCount", outcome.toolNames().size(),
                "citationCount", outcome.citations().size()));
        return outcome;
    }

    private TaskOutcome createApprovalOrRequireManual(
            AgentTask task,
            TaskOutcome outcome) {
        try {
            TicketResponse ticket = ticketService.get(task.ticketId());
            Map<String, Object> arguments =
                    new LinkedHashMap<>(outcome.actionArguments());
            arguments.put("service", requireText(
                    "affected service", ticket.affectedService()));
            approvals.create(
                    task.id(), "task:" + task.id() + ":verify",
                    requireText("ticket category", ticket.category())
                            .toLowerCase(Locale.ROOT).replace('_', '-'),
                    requireText("proposed action", outcome.proposedAction()),
                    arguments, approvalTtl);
            return outcome;
        } catch (RuntimeException exception) {
            if (!taskService.transition(
                    task.id(), AgentTaskStatus.WAITING_APPROVAL,
                    AgentTaskStatus.MANUAL_REQUIRED)) {
                throw new IllegalStateException(
                        "approval creation failed and task state changed concurrently",
                        exception);
            }
            String error = SensitiveDataRedactor.redact(exception.getMessage());
            eventService.append(task.id(), "APPROVAL_CREATION_FAILED",
                    error == null ? Map.of() : Map.of("error", error));
            return new TaskOutcome(
                    AgentTaskStatus.MANUAL_REQUIRED,
                    outcome.rootCause(), outcome.toolNames(), outcome.citations(),
                    outcome.proposedAction(), outcome.report(), error,
                    outcome.actionArguments());
        }
    }

    private AgentTaskCommand command(AgentTask task, TicketResponse ticket) {
        String category = requireText("ticket category", ticket.category());
        return new AgentTaskCommand(
                task.id(), task.tenantId(), task.ticketId(),
                category.trim().toLowerCase(Locale.ROOT).replace('_', '-'),
                requireText("affected service", ticket.affectedService()),
                requireText("ticket title", ticket.title()),
                requireText("ticket description", ticket.description()),
                provider,
                new AgentBudget(
                        task.maxSteps(), remainingTimeout(task),
                        task.maxTokens()));
    }

    private Duration remainingTimeout(AgentTask task) {
        Duration configured = Duration.ofSeconds(task.timeoutSeconds());
        if (task.startedAt() == null) return configured;
        Duration elapsed = Duration.between(task.startedAt(), Instant.now());
        Duration remaining = configured.minus(elapsed);
        return remaining.compareTo(Duration.ofSeconds(1)) < 0
                ? Duration.ofSeconds(1) : remaining;
    }

    private void validateOutcome(TaskOutcome outcome) {
        if (outcome == null || outcome.status() == null
                || outcome.status() == AgentTaskStatus.QUEUED
                || outcome.status() == AgentTaskStatus.RUNNING) {
            throw new IllegalStateException("agent engine returned an invalid outcome");
        }
    }

    private boolean deadlineExceeded(AgentTask task) {
        return task.startedAt() != null
                && !Instant.now().isBefore(task.startedAt().plusSeconds(
                        task.timeoutSeconds()));
    }

    private String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required for agent execution");
        }
        return value.trim();
    }
}
