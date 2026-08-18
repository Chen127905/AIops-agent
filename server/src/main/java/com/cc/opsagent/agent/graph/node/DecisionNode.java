package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.model.ModelGateway;

public class DecisionNode extends StructuredModelNode implements OpsAgentNode {

    public DecisionNode(ModelGateway model) { super(model); }

    public DecisionNode(ModelGateway model, AgentExecutionAudit audit) {
        super(model, audit);
    }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        try {
            Decision decision = callStructured(state, """
                    Decide the root cause and proposed action from validated observations.
                    Return JSON with rootCause, proposedAction and confidence.
                    Ticket category: %s; observations: %s
                    """.formatted(state.category(), state.observations()), Decision.class);
            if (decision.rootCause() == null || decision.rootCause().isBlank()
                    || decision.proposedAction() == null || decision.proposedAction().isBlank()
                    || decision.confidence() < 0 || decision.confidence() > 1) {
                throw new IllegalArgumentException("invalid diagnostic decision");
            }
            state.decision(
                    decision.rootCause(), decision.proposedAction(), decision.confidence());
        } catch (RuntimeException exception) {
            state.fail(exception.getMessage());
        }
        return state;
    }

    public record Decision(String rootCause, String proposedAction, double confidence) { }
}
