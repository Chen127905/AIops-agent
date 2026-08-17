package com.cc.opsagent.agent.infrastructure;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.cc.opsagent.agent.application.AgentTaskCommand;
import com.cc.opsagent.agent.application.AgentWorkflowEngine;
import com.cc.opsagent.agent.application.TaskOutcome;
import com.cc.opsagent.agent.graph.OpsAgentGraphFactory;
import com.cc.opsagent.agent.graph.OpsAgentState;

import java.util.Map;

public class AlibabaGraphWorkflowEngine implements AgentWorkflowEngine {

    private final CompiledGraph graph;

    public AlibabaGraphWorkflowEngine(OpsAgentGraphFactory factory) {
        this.graph = factory.build();
    }

    @Override
    public TaskOutcome execute(AgentTaskCommand command) {
        OpsAgentState initial = new OpsAgentState(command);
        try {
            OverAllState finalState = graph.invoke(Map.of(
                            OpsAgentGraphFactory.STATE, initial))
                    .orElseThrow(() -> new IllegalStateException("agent graph returned no state"));
            OpsAgentState result = finalState.value(
                            OpsAgentGraphFactory.STATE, OpsAgentState.class)
                    .orElse(initial);
            return result.outcome();
        } catch (RuntimeException exception) {
            initial.fail("agent graph failed: " + exception.getMessage());
            return initial.outcome();
        }
    }
}
