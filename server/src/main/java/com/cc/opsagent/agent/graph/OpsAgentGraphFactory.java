package com.cc.opsagent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.agent.graph.node.DecisionNode;
import com.cc.opsagent.agent.graph.node.DiagnoseNode;
import com.cc.opsagent.agent.graph.node.OpsAgentNode;
import com.cc.opsagent.agent.graph.node.PlanNode;
import com.cc.opsagent.agent.graph.node.RetrieveNode;
import com.cc.opsagent.agent.graph.node.SummarizeNode;
import com.cc.opsagent.agent.graph.node.TriageNode;
import com.cc.opsagent.agent.graph.node.VerifyNode;
import com.cc.opsagent.observability.CorrelationContext;
import org.slf4j.MDC;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

public class OpsAgentGraphFactory {

    public static final String STATE = "opsAgentState";

    private final TriageNode triage;
    private final RetrieveNode retrieve;
    private final PlanNode plan;
    private final DiagnoseNode diagnose;
    private final DecisionNode decision;
    private final VerifyNode verify;
    private final SummarizeNode summarize;
    private final AgentExecutionAudit audit;
    private final CancellationProbe cancellation;

    public OpsAgentGraphFactory(
            TriageNode triage,
            RetrieveNode retrieve,
            PlanNode plan,
            DiagnoseNode diagnose,
            DecisionNode decision,
            VerifyNode verify,
            SummarizeNode summarize) {
        this(triage, retrieve, plan, diagnose, decision, verify, summarize,
                AgentExecutionAudit.noop(), CancellationProbe.never());
    }

    public OpsAgentGraphFactory(
            TriageNode triage,
            RetrieveNode retrieve,
            PlanNode plan,
            DiagnoseNode diagnose,
            DecisionNode decision,
            VerifyNode verify,
            SummarizeNode summarize,
            AgentExecutionAudit audit) {
        this(triage, retrieve, plan, diagnose, decision, verify, summarize,
                audit, CancellationProbe.never());
    }

    public OpsAgentGraphFactory(
            TriageNode triage,
            RetrieveNode retrieve,
            PlanNode plan,
            DiagnoseNode diagnose,
            DecisionNode decision,
            VerifyNode verify,
            SummarizeNode summarize,
            AgentExecutionAudit audit,
            CancellationProbe cancellation) {
        this.triage = triage;
        this.retrieve = retrieve;
        this.plan = plan;
        this.diagnose = diagnose;
        this.decision = decision;
        this.verify = verify;
        this.summarize = summarize;
        this.audit = audit;
        this.cancellation = cancellation;
    }

    public CompiledGraph build() {
        try {
            StateGraph graph = new StateGraph(KeyStrategy.builder()
                    .addStrategy(STATE, KeyStrategy.REPLACE)
                    .build());
            add(graph, "triage", triage);
            add(graph, "retrieve", retrieve);
            add(graph, "plan", plan);
            add(graph, "diagnose", diagnose);
            add(graph, "decision", decision);
            add(graph, "verify", verify);
            add(graph, "summarize", summarize);
            graph.addEdge(StateGraph.START, "triage")
                    .addEdge("triage", "retrieve")
                    .addEdge("retrieve", "plan")
                    .addEdge("plan", "diagnose")
                    .addEdge("diagnose", "decision")
                    .addEdge("decision", "verify")
                    .addEdge("verify", "summarize")
                    .addEdge("summarize", StateGraph.END);
            return graph.compile();
        } catch (Exception exception) {
            throw new IllegalStateException("agent graph could not be compiled", exception);
        }
    }

    private void add(StateGraph graph, String name, OpsAgentNode node) throws Exception {
        graph.addNode(name, node_async(overall -> {
            OpsAgentState state = overall.value(STATE, OpsAgentState.class)
                    .orElseThrow(() -> new IllegalStateException("agent state is missing"));
            if (!state.terminal()
                    && cancellation.requested(state.command().taskId())) {
                state.cancel();
            }
            if (!state.terminal() && state.shouldExecute(name)) {
                int sequence = state.nextStepSequence();
                try (CorrelationContext.Scope ignored = CorrelationContext.open(
                        MDC.get(CorrelationContext.TRACE_ID),
                        state.command().tenantId(), state.command().ticketId(),
                        state.command().taskId(), sequence)) {
                    Map<String, Object> input = state.auditSnapshot();
                    audit.nodeStarted(new AgentExecutionAudit.NodeAudit(
                            state.command().taskId(), sequence, name, "STARTED",
                            input, Map.of(), null, 0));
                    long started = System.nanoTime();
                    try {
                        node.apply(state);
                    } catch (RuntimeException exception) {
                        state.fail("agent node " + name + " failed: "
                                + exception.getMessage());
                    }
                    if (!state.terminal()
                            && cancellation.requested(state.command().taskId())) {
                        state.cancel();
                    }
                    long durationMs = Math.max(
                            0, (System.nanoTime() - started) / 1_000_000);
                    String status = state.status()
                            == com.cc.opsagent.agent.domain.AgentTaskStatus.FAILED
                            || state.status()
                            == com.cc.opsagent.agent.domain.AgentTaskStatus.TIMED_OUT
                            ? "FAILED" : "SUCCEEDED";
                    audit.nodeCompleted(new AgentExecutionAudit.NodeAudit(
                            state.command().taskId(), sequence, name, status,
                            input, state.checkpointSnapshot(),
                            state.error(), durationMs));
                }
            }
            return Map.of(STATE, state);
        }));
    }
}
