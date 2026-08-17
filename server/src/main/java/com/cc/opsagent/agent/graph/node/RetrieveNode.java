package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.knowledge.application.KnowledgeQuery;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;

public class RetrieveNode implements OpsAgentNode {

    private final KnowledgeRetriever retriever;

    public RetrieveNode(KnowledgeRetriever retriever) { this.retriever = retriever; }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        try {
            state.evidence(retriever.retrieve(new KnowledgeQuery(
                    state.command().title() + " " + state.command().description(), 5)));
        } catch (RuntimeException exception) {
            state.fail("knowledge retrieval failed: " + exception.getMessage());
        }
        return state;
    }
}
