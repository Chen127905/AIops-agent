package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import com.cc.opsagent.ticket.application.TicketService;
import com.cc.opsagent.ticket.domain.TicketStatus;

import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class AgentExecutionService {

    private final AgentTaskService taskService;
    private final AgentEventService eventService;
    private final ObjectProvider<OpsAgentWorkflow> workflowProvider;
    private final ThreadPoolTaskExecutor executor;
    private final TicketService tickets;

    public AgentExecutionService(
            AgentTaskService taskService,
            AgentEventService eventService,
            ObjectProvider<OpsAgentWorkflow> workflowProvider,
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            TicketService tickets) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.workflowProvider = workflowProvider;
        this.executor = executor;
        this.tickets = tickets;
    }

    public AgentTask start(long ticketId, AgentBudget budget) {
        OpsAgentWorkflow workflow = workflowProvider.getIfAvailable();
        if (workflow == null) {
            throw new IllegalStateException(
                    "agent workflow requires the vector datasource to be enabled");
        }
        AgentTask task = taskService.start(ticketId, budget);
        try {
            tickets.transition(ticketId, TicketStatus.TRIAGING);
        } catch (RuntimeException exception) {
            taskService.transition(
                    task.id(), AgentTaskStatus.QUEUED, AgentTaskStatus.CANCELLED);
            throw exception;
        }
        eventService.append(task.id(), "TASK_CREATED", Map.of(
                "ticketId", ticketId,
                "status", task.status().name()));
        try {
            executor.execute(() -> execute(workflow, task.id()));
        } catch (TaskRejectedException exception) {
            taskService.transition(
                    task.id(), AgentTaskStatus.QUEUED, AgentTaskStatus.CANCELLED);
            tickets.transition(ticketId, TicketStatus.CANCELLED);
            eventService.append(task.id(), "TASK_REJECTED", Map.of());
            throw new AgentExecutionRejectedException();
        }
        return task;
    }

    private void execute(OpsAgentWorkflow workflow, long taskId) {
        try {
            workflow.run(taskId);
        } catch (RuntimeException exception) {
            AgentTask task = taskService.get(taskId);
            TicketStatus ticketStatus = TicketStatus.FAILED;
            if (task.status() == AgentTaskStatus.QUEUED) {
                taskService.transition(
                        taskId, AgentTaskStatus.QUEUED, AgentTaskStatus.CANCELLED);
                ticketStatus = TicketStatus.CANCELLED;
            } else if (task.status() == AgentTaskStatus.RUNNING) {
                taskService.transition(
                        taskId, AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
            }
            try {
                var ticket = tickets.get(task.ticketId());
                if (!ticket.status().isTerminal()) {
                    tickets.transition(task.ticketId(), ticketStatus);
                }
            } catch (RuntimeException ignored) {
                // The workflow may already have completed the ticket concurrently.
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            String error = SensitiveDataRedactor.redact(exception.getMessage());
            taskService.recordErrorSummary(taskId, error);
            if (error != null) payload.put("error", error);
            eventService.append(taskId, "TASK_EXECUTION_FAILED", payload);
        }
    }
}
