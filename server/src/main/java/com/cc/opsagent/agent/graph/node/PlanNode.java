package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.model.ModelGateway;

import java.util.List;
import java.util.Set;

public class PlanNode extends StructuredModelNode implements OpsAgentNode {

    private static final Set<String> READ_ONLY = Set.of(
            "getServiceHealth", "queryMetrics", "queryLogs", "getServiceDependencies");

    public PlanNode(ModelGateway model) { super(model); }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        try {
            Plan plan = callStructured(state, """
                    Select only read-only diagnostic tools. Return JSON {"tools": [...]}.
                    Category: %s, service: %s
                    """.formatted(state.category(), state.command().affectedService()), Plan.class);
            List<String> tools = plan.tools() == null ? List.of() : List.copyOf(plan.tools());
            if (tools.isEmpty() || !READ_ONLY.containsAll(tools)) {
                throw new IllegalArgumentException(
                        "diagnostic plan may contain read-only tools only");
            }
            state.plannedTools(tools);
        } catch (RuntimeException exception) {
            state.fail(exception.getMessage());
        }
        return state;
    }

    public record Plan(List<String> tools) { }
}
