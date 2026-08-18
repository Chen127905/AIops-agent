package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.ticket.application.TicketService;
import com.cc.opsagent.ticket.web.TicketResponse;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OpsAgentWorkflow {

    private final AgentTaskService taskService;
    private final AgentEventService eventService;
    private final TicketService ticketService;
    private final AgentWorkflowEngine engine;
    private final ModelProvider provider;
    private final String workerId;
    private final Duration lease;

    public OpsAgentWorkflow(
            AgentTaskService taskService,
            AgentEventService eventService,
            TicketService ticketService,
            AgentWorkflowEngine engine,
            ModelProvider provider,
            String workerId,
            Duration lease) {
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
        this.workerId = workerId.trim();
        this.lease = lease;
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
        TaskOutcome outcome;
        try {
            eventService.append(
                    taskId, "TASK_STARTED", Map.of("workerId", workerId));
            TicketResponse ticket = ticketService.get(task.ticketId());
            outcome = engine.execute(command(task, ticket));
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
        eventService.append(taskId, "TASK_COMPLETED", Map.of(
                "status", outcome.status().name(),
                "toolCount", outcome.toolNames().size(),
                "citationCount", outcome.citations().size()));
        return outcome;
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
                        task.maxSteps(), Duration.ofSeconds(task.timeoutSeconds()),
                        task.maxTokens()));
    }

    private void validateOutcome(TaskOutcome outcome) {
        if (outcome == null || outcome.status() == null
                || outcome.status() == AgentTaskStatus.QUEUED
                || outcome.status() == AgentTaskStatus.RUNNING
                || outcome.status() == AgentTaskStatus.CANCELLED) {
            throw new IllegalStateException("agent engine returned an invalid outcome");
        }
    }

    private String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required for agent execution");
        }
        return value.trim();
    }
}
