package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class AgentExecutionService {

    private final AgentTaskService taskService;
    private final AgentEventService eventService;
    private final ObjectProvider<OpsAgentWorkflow> workflowProvider;
    private final ThreadPoolTaskExecutor executor;

    public AgentExecutionService(
            AgentTaskService taskService,
            AgentEventService eventService,
            ObjectProvider<OpsAgentWorkflow> workflowProvider,
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.workflowProvider = workflowProvider;
        this.executor = executor;
    }

    public AgentTask start(long ticketId, AgentBudget budget) {
        OpsAgentWorkflow workflow = workflowProvider.getIfAvailable();
        if (workflow == null) {
            throw new IllegalStateException(
                    "agent workflow requires the vector datasource to be enabled");
        }
        AgentTask task = taskService.start(ticketId, budget);
        eventService.append(task.id(), "TASK_CREATED", Map.of(
                "ticketId", ticketId,
                "status", task.status().name()));
        try {
            executor.execute(() -> execute(workflow, task.id()));
        } catch (TaskRejectedException exception) {
            taskService.transition(
                    task.id(), AgentTaskStatus.QUEUED, AgentTaskStatus.CANCELLED);
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
            if (task.status() == AgentTaskStatus.QUEUED) {
                taskService.transition(
                        taskId, AgentTaskStatus.QUEUED, AgentTaskStatus.CANCELLED);
            } else if (task.status() == AgentTaskStatus.RUNNING) {
                taskService.transition(
                        taskId, AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            String error = SensitiveDataRedactor.redact(exception.getMessage());
            if (error != null) payload.put("error", error);
            eventService.append(taskId, "TASK_EXECUTION_FAILED", payload);
        }
    }
}
