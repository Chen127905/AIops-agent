package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.DiagnosticToolGateway;
import com.cc.opsagent.agent.application.ToolObservation;
import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;

import java.util.Map;

public class DiagnoseNode implements OpsAgentNode {

    private final DiagnosticToolGateway tools;
    private final AgentExecutionAudit audit;

    public DiagnoseNode(DiagnosticToolGateway tools) {
        this(tools, AgentExecutionAudit.noop());
    }

    public DiagnoseNode(
            DiagnosticToolGateway tools,
            AgentExecutionAudit audit) {
        this.tools = tools;
        this.audit = audit;
    }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        for (String tool : state.plannedTools()) {
            Map<String, Object> arguments = Map.of(
                    "scenarioKey", state.command().scenarioKey(),
                    "service", state.command().affectedService());
            long started = System.nanoTime();
            ToolObservation result;
            try {
                result = tools.execute(
                        state.command().tenantId(), state.command().taskId(),
                        state.command().scenarioKey(),
                        state.command().affectedService(), tool);
            } catch (RuntimeException exception) {
                audit.toolInvoked(new AgentExecutionAudit.ToolCallAudit(
                        state.command().taskId(), tool, arguments, ToolRisk.READ_ONLY,
                        ToolExecutionStatus.FAILED, elapsed(started), Map.of(),
                        exception.getMessage()));
                state.fail("diagnostic tool failed: " + tool + ": " + exception.getMessage());
                break;
            }
            state.observation(result);
            audit.toolInvoked(new AgentExecutionAudit.ToolCallAudit(
                    state.command().taskId(), tool, arguments, ToolRisk.READ_ONLY,
                    result.success() ? ToolExecutionStatus.SUCCESS
                            : ToolExecutionStatus.FAILED,
                    elapsed(started), result.data(), result.error()));
            if (!result.success()) {
                state.fail("diagnostic tool failed: " + tool + ": " + result.error());
                break;
            }
        }
        return state;
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
