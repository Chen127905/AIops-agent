package com.cc.opsagent.agent.infrastructure;

import com.cc.opsagent.agent.application.AgentEventService;
import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.application.ModelInvocationRecord;
import com.cc.opsagent.agent.application.StepRecord;
import com.cc.opsagent.agent.application.ToolInvocationRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PersistentAgentExecutionAudit implements AgentExecutionAudit {

    private final AgentTaskService taskService;
    private final AgentEventService eventService;

    public PersistentAgentExecutionAudit(
            AgentTaskService taskService,
            AgentEventService eventService) {
        this.taskService = taskService;
        this.eventService = eventService;
    }

    @Override
    public void nodeStarted(NodeAudit audit) {
        eventService.append(audit.taskId(), "NODE_STARTED", Map.of(
                "step", audit.sequence(),
                "node", audit.nodeName()));
    }

    @Override
    public void nodeCompleted(NodeAudit audit) {
        taskService.appendStep(new StepRecord(
                audit.taskId(), audit.sequence(), audit.nodeName(), audit.status(),
                audit.input(), audit.output(), audit.errorSummary(), audit.durationMs()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", audit.sequence());
        payload.put("node", audit.nodeName());
        payload.put("status", audit.status());
        payload.put("durationMs", audit.durationMs());
        if (audit.errorSummary() != null && !audit.errorSummary().isBlank()) {
            payload.put("error", audit.errorSummary());
        }
        eventService.append(audit.taskId(), "NODE_COMPLETED", payload);
    }

    @Override
    public void modelInvoked(ModelCallAudit audit) {
        taskService.appendModelInvocation(new ModelInvocationRecord(
                audit.taskId(), null, audit.provider(), audit.modelName(),
                audit.requestHash(), audit.status(), audit.inputTokens(),
                audit.outputTokens(), audit.latencyMs(), audit.errorSummary()));
    }

    @Override
    public void toolInvoked(ToolCallAudit audit) {
        taskService.appendToolInvocation(new ToolInvocationRecord(
                audit.taskId(), null, audit.toolName(), audit.normalizedArguments(),
                audit.risk(), audit.status(), null, audit.latencyMs(),
                audit.resultSummary(), audit.errorSummary()));
    }
}
