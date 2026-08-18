package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentRecoveryCoordinator implements RecoveryResumeHandler {

    private final ThreadPoolTaskExecutor executor;
    private final ObjectProvider<OpsAgentWorkflow> workflows;
    private final AgentTaskService tasks;
    private final AgentEventService events;

    public AgentRecoveryCoordinator(
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            ObjectProvider<OpsAgentWorkflow> workflows,
            AgentTaskService tasks,
            AgentEventService events) {
        this.executor = executor;
        this.workflows = workflows;
        this.tasks = tasks;
        this.events = events;
    }

    @Override
    public void dispatch(long taskId, RecoveryCheckpoint checkpoint) {
        OpsAgentWorkflow workflow = workflows.getIfAvailable();
        if (workflow == null) {
            throw new IllegalStateException(
                    "agent recovery requires the vector datasource to be enabled");
        }
        executor.execute(() -> resume(workflow, taskId, checkpoint));
    }

    private void resume(
            OpsAgentWorkflow workflow,
            long taskId,
            RecoveryCheckpoint checkpoint) {
        try {
            workflow.resumeClaimed(taskId, checkpoint);
        } catch (RuntimeException exception) {
            AgentTask task = tasks.get(taskId);
            if (task.status() != AgentTaskStatus.RUNNING
                    || !tasks.transition(
                            taskId, AgentTaskStatus.RUNNING,
                            AgentTaskStatus.MANUAL_REQUIRED)) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            String error = SensitiveDataRedactor.redact(exception.getMessage());
            if (error != null) payload.put("error", error);
            events.append(taskId, "TASK_RECOVERY_FAILED", payload);
        }
    }
}
