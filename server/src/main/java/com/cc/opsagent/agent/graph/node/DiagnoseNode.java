package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.DiagnosticToolGateway;
import com.cc.opsagent.agent.application.ToolObservation;
import com.cc.opsagent.agent.graph.OpsAgentState;

public class DiagnoseNode implements OpsAgentNode {

    private final DiagnosticToolGateway tools;

    public DiagnoseNode(DiagnosticToolGateway tools) { this.tools = tools; }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        for (String tool : state.plannedTools()) {
            ToolObservation result = tools.execute(
                    state.command().tenantId(), state.command().taskId(),
                    state.command().scenarioKey(), state.command().affectedService(), tool);
            state.observation(result);
            if (!result.success()) {
                state.fail("diagnostic tool failed: " + tool + ": " + result.error());
                break;
            }
        }
        return state;
    }
}
