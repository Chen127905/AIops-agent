package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.model.ModelGateway;

import java.util.Map;

public class DecisionNode extends StructuredModelNode implements OpsAgentNode {

    public DecisionNode(ModelGateway model) { super(model); }

    public DecisionNode(ModelGateway model, AgentExecutionAudit audit) {
        super(model, audit);
    }

    public DecisionNode(
            ModelGateway model,
            AgentExecutionAudit audit,
            CancellationProbe cancellation) {
        super(model, audit, cancellation);
    }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        try {
            Decision decision = callStructured(state, """
                    Decide the root cause and proposed action from validated observations.
                    Return JSON with rootCause, proposedAction, actionArguments and confidence.
                    Ticket category: %s; observations: %s
                    """.formatted(state.category(), state.observations()), Decision.class);
            if (decision.rootCause() == null || decision.rootCause().isBlank()
                    || decision.proposedAction() == null || decision.proposedAction().isBlank()
                    || decision.confidence() < 0 || decision.confidence() > 1) {
                throw new IllegalArgumentException("invalid diagnostic decision");
            }
            Map<String, Object> arguments = decision.actionArguments() == null
                    ? Map.of() : Map.copyOf(decision.actionArguments());
            if ("changeConfig".equals(decision.proposedAction())
                    && !hasConfigurationChanges(arguments)) {
                throw new IllegalArgumentException(
                        "changeConfig requires non-empty actionArguments.changes");
            }
            state.decision(
                    decision.rootCause(), decision.proposedAction(),
                    arguments, decision.confidence());
        } catch (RuntimeException exception) {
            state.fail(exception.getMessage());
        }
        return state;
    }

    public record Decision(
            String rootCause,
            String proposedAction,
            Map<String, Object> actionArguments,
            double confidence) { }

    private boolean hasConfigurationChanges(Map<String, Object> arguments) {
        return arguments.get("changes") instanceof Map<?, ?> changes
                && !changes.isEmpty();
    }
}
