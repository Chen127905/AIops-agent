package com.cc.opsagent.agent.graph.node;

import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.agent.graph.OpsAgentState;
import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.security.SensitiveDataRedactor;
import com.cc.opsagent.security.UntrustedContentPolicy;

import java.util.Map;

public class DecisionNode extends StructuredModelNode implements OpsAgentNode {

    private final UntrustedContentPolicy untrustedContent;

    public DecisionNode(ModelGateway model) {
        this(model, AgentExecutionAudit.noop(), CancellationProbe.never(),
                new SensitiveDataRedactor());
    }

    public DecisionNode(ModelGateway model, AgentExecutionAudit audit) {
        this(model, audit, CancellationProbe.never(),
                new SensitiveDataRedactor());
    }

    public DecisionNode(
            ModelGateway model,
            AgentExecutionAudit audit,
            CancellationProbe cancellation) {
        this(model, audit, cancellation, new SensitiveDataRedactor());
    }

    public DecisionNode(
            ModelGateway model,
            AgentExecutionAudit audit,
            CancellationProbe cancellation,
            SensitiveDataRedactor redactor) {
        super(model, audit, cancellation, redactor);
        this.untrustedContent = new UntrustedContentPolicy(redactor);
    }

    @Override
    public OpsAgentState apply(OpsAgentState state) {
        if (!state.enterStep()) return state;
        try {
            Decision decision = callStructured(state, """
                    Decide the root cause and proposed action from validated observations.
                    Return JSON with rootCause, proposedAction, actionArguments and confidence.
                    Ticket category: %s
                    %s
                    """.formatted(
                            state.category(),
                            untrustedContent.diagnosticEnvelope(
                                    state.evidence(), state.observations())),
                    Decision.class);
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
