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
                    proposedAction must be exactly one of: NONE, restartService, changeConfig.
                    Use NONE when no automated write is justified. For restartService use an
                    empty actionArguments object. For changeConfig, actionArguments must be
                    {"changes":{"configurationKey":"configurationValue"}}.
                    Return strict JSON only with these fields:
                    rootCause, diagnosisSummary, proposedAction, actionArguments,
                    confidence (0 to 1), remediationSteps (3 to 8 concrete operator
                    steps), verificationSteps (2 to 5 measurable checks), and rollbackPlan.
                    Write all human-facing fields and steps in Simplified Chinese.
                    Ticket category: %s
                    Affected service: %s
                    %s
                    """.formatted(
                            state.category(),
                            state.command().affectedService(),
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
                    decision.rootCause(), decision.diagnosisSummary(),
                    decision.proposedAction(), arguments, decision.confidence(),
                    decision.remediationSteps(), decision.verificationSteps(),
                    decision.rollbackPlan());
        } catch (RuntimeException exception) {
            state.fail(exception.getMessage());
        }
        return state;
    }

    public record Decision(
            String rootCause,
            String diagnosisSummary,
            String proposedAction,
            Map<String, Object> actionArguments,
            double confidence,
            java.util.List<String> remediationSteps,
            java.util.List<String> verificationSteps,
            String rollbackPlan) { }

    private boolean hasConfigurationChanges(Map<String, Object> arguments) {
        return arguments.get("changes") instanceof Map<?, ?> changes
                && !changes.isEmpty();
    }
}
