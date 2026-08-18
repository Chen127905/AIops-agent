package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;

public class SummarizeNode implements OpsAgentNode {

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        Summary summary = new Summary(
                state.rootCause(),
                state.proposedAction(),
                state.confidence(),
                state.evidence().stream().map(chunk -> chunk.citationId()).toList());
        state.report("Root cause: %s. Proposed action: %s. Confidence: %.2f. Citations: %s"
                .formatted(summary.rootCause(), summary.proposedAction(),
                        summary.confidence(), summary.citations()));
        state.completeVerification();
        return state;
    }

    public record Summary(
            String rootCause,
            String proposedAction,
            double confidence,
            java.util.List<String> citations) { }
}
