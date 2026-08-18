package com.cc.opsagent.agent.application;

import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;

import java.util.Map;

public interface AgentExecutionAudit {

    default void nodeStarted(NodeAudit audit) { }

    default void nodeCompleted(NodeAudit audit) { }

    default void modelInvoked(ModelCallAudit audit) { }

    default void toolInvoked(ToolCallAudit audit) { }

    static AgentExecutionAudit noop() {
        return new AgentExecutionAudit() { };
    }

    record NodeAudit(
            long taskId,
            int sequence,
            String nodeName,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            String errorSummary,
            long durationMs) { }

    record ModelCallAudit(
            long taskId,
            String provider,
            String modelName,
            String requestHash,
            String status,
            int inputTokens,
            int outputTokens,
            long latencyMs,
            String errorSummary) { }

    record ToolCallAudit(
            long taskId,
            String toolName,
            Map<String, Object> normalizedArguments,
            ToolRisk risk,
            ToolExecutionStatus status,
            long latencyMs,
            Map<String, Object> resultSummary,
            String errorSummary) { }
}
