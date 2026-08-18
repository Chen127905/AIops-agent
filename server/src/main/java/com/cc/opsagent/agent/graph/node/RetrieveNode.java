package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.knowledge.application.KnowledgeQuery;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;

public class RetrieveNode implements OpsAgentNode {

    private final KnowledgeRetriever retriever;
    private final CancellationProbe cancellation;

    public RetrieveNode(KnowledgeRetriever retriever) {
        this(retriever, CancellationProbe.never());
    }

    public RetrieveNode(
            KnowledgeRetriever retriever,
            CancellationProbe cancellation) {
        this.retriever = retriever;
        this.cancellation = cancellation;
    }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        try {
            state.evidence(retriever.retrieve(new KnowledgeQuery(
                    state.command().title() + " " + state.command().description(), 5)));
            if (!state.terminal()
                    && cancellation.requested(state.command().taskId())) {
                state.cancel();
            }
            state.controlPoint();
        } catch (RuntimeException exception) {
            state.fail("knowledge retrieval failed: " + exception.getMessage());
        }
        return state;
    }
}
