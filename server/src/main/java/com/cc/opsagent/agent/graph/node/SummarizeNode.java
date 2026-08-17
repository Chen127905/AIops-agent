package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;

public class SummarizeNode implements OpsAgentNode {

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        state.report("Root cause: %s. Proposed action: %s. Confidence: %.2f. Citations: %s"
                .formatted(
                        state.rootCause(), state.proposedAction(), state.confidence(),
                        state.evidence().stream().map(chunk -> chunk.citationId()).toList()));
        return state;
    }
}
