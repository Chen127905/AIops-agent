package com.cc.opsagent.agent.graph;

import com.cc.opsagent.agent.application.AgentBudget;
import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.AgentTaskCommand;
import com.cc.opsagent.agent.application.AgentWorkflowEngine;
import com.cc.opsagent.agent.application.DiagnosticToolGateway;
import com.cc.opsagent.agent.application.TaskOutcome;
import com.cc.opsagent.agent.application.ToolObservation;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.agent.graph.node.DecisionNode;
import com.cc.opsagent.agent.graph.node.DiagnoseNode;
import com.cc.opsagent.agent.graph.node.PlanNode;
import com.cc.opsagent.agent.graph.node.RetrieveNode;
import com.cc.opsagent.agent.graph.node.SummarizeNode;
import com.cc.opsagent.agent.graph.node.TriageNode;
import com.cc.opsagent.agent.graph.node.VerifyNode;
import com.cc.opsagent.agent.infrastructure.AlibabaGraphWorkflowEngine;
import com.cc.opsagent.knowledge.application.EvidenceChunk;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.model.ModelReply;
import com.cc.opsagent.model.ModelRequest;
import com.cc.opsagent.model.ModelUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpsAgentWorkflowTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void diagnosesRedisTimeoutWithExpectedEvidenceAndTools() {
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}",
                "{\"tools\":[\"getServiceHealth\",\"queryMetrics\",\"queryLogs\"]}",
                "{\"rootCause\":\"redis_connection_pool_exhausted\","
                        + "\"proposedAction\":\"restartService\",\"confidence\":0.96}");
        RecordingTools tools = new RecordingTools();
        AgentWorkflowEngine workflow = workflow(model, tools, 12);

        TaskOutcome outcome = workflow.execute(command(12));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.WAITING_APPROVAL);
        assertThat(outcome.rootCause())
                .isEqualTo("redis_connection_pool_exhausted");
        assertThat(outcome.toolNames()).containsExactly(
                "getServiceHealth", "queryMetrics", "queryLogs");
        assertThat(outcome.citations())
                .containsExactly("tenant:1:doc:9:v2:chunk:3");
        assertThat(outcome.proposedAction()).isEqualTo("restartService");
        assertThat(outcome.report())
                .contains("redis_connection_pool_exhausted")
                .contains("tenant:1:doc:9:v2:chunk:3");
    }

    @Test
    void refusesAHighRiskToolInsideTheDiagnosticPlan() {
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}",
                "{\"tools\":[\"restartService\"]}");
        RecordingTools tools = new RecordingTools();

        TaskOutcome outcome = workflow(model, tools, 12).execute(command(12));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(outcome.errorSummary()).contains("read-only");
        assertThat(tools.names).isEmpty();
    }

    @Test
    void repairsOneMalformedStructuredModelReply() {
        FakeModel model = new FakeModel(
                "not-json",
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}",
                "{\"tools\":[\"getServiceHealth\"]}",
                "{\"rootCause\":\"pool_exhausted\","
                        + "\"proposedAction\":\"NONE\",\"confidence\":0.8}");

        TaskOutcome outcome = workflow(model, new RecordingTools(), 12)
                .execute(command(12));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(outcome.rootCause()).isEqualTo("pool_exhausted");
        assertThat(model.calls).isEqualTo(4);
    }

    @Test
    void stopsBeforeExecutionWhenTheStepBudgetIsTooSmall() {
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}");

        TaskOutcome outcome = workflow(model, new RecordingTools(), 2)
                .execute(command(2));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(outcome.errorSummary()).contains("step budget");
    }

    @Test
    void stopsWhenTheTokenBudgetIsExceeded() {
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}");

        TaskOutcome outcome = workflow(model, new RecordingTools(), 12)
                .execute(command(12, Duration.ofMinutes(3), 10));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(outcome.errorSummary()).contains("token budget");
    }

    @Test
    void stopsWithAnExplicitTimeoutState() {
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}");
        model.delayMillis = 1_100;

        TaskOutcome outcome = workflow(model, new RecordingTools(), 12)
                .execute(command(12, Duration.ofSeconds(1), 20_000));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.TIMED_OUT);
        assertThat(outcome.errorSummary()).contains("timeout");
    }

    @Test
    void recordsToolFailureAndStopsBeforeDecision() {
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}",
                "{\"tools\":[\"queryLogs\"]}");
        RecordingTools tools = new RecordingTools();
        tools.failure = "simulator unavailable";

        TaskOutcome outcome = workflow(model, tools, 12)
                .execute(command(12));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(outcome.errorSummary())
                .contains("queryLogs")
                .contains("simulator unavailable");
        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void auditsEveryNodeModelAndToolCall() {
        TenantPrincipal principal = new TenantPrincipal(
                1, 7, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of()));
        FakeModel model = new FakeModel(
                "{\"category\":\"REDIS_TIMEOUT\",\"urgency\":\"HIGH\"}",
                "{\"tools\":[\"getServiceHealth\"]}",
                "{\"rootCause\":\"pool_exhausted\"," +
                        "\"proposedAction\":\"NONE\",\"confidence\":0.8}");
        RecordingAudit audit = new RecordingAudit();

        TaskOutcome outcome = workflow(model, new RecordingTools(), audit)
                .execute(command(12));

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(audit.started).containsExactly(
                "triage", "retrieve", "plan", "diagnose",
                "decision", "verify", "summarize");
        assertThat(audit.completed).containsExactlyElementsOf(audit.started);
        assertThat(audit.models).hasSize(3);
        assertThat(audit.tools).containsExactly("getServiceHealth");
        assertThat(audit.tenants).isNotEmpty().containsOnly(1L);
    }

    private AgentWorkflowEngine workflow(
            ModelGateway model,
            DiagnosticToolGateway tools,
            int ignoredBudget) {
        KnowledgeRetriever knowledge = query -> List.of(new EvidenceChunk(
                1, 9, 2, 3, "runbooks/redis.md", "pool tuning procedure",
                Map.of(), 0.99, "tenant:1:doc:9:v2:chunk:3"));
        return new AlibabaGraphWorkflowEngine(new OpsAgentGraphFactory(
                new TriageNode(model),
                new RetrieveNode(knowledge),
                new PlanNode(model),
                new DiagnoseNode(tools),
                new DecisionNode(model),
                new VerifyNode(),
                new SummarizeNode()));
    }

    private AgentWorkflowEngine workflow(
            ModelGateway model,
            DiagnosticToolGateway tools,
            AgentExecutionAudit audit) {
        KnowledgeRetriever knowledge = query -> List.of(new EvidenceChunk(
                1, 9, 2, 3, "runbooks/redis.md", "pool tuning procedure",
                Map.of(), 0.99, "tenant:1:doc:9:v2:chunk:3"));
        return new AlibabaGraphWorkflowEngine(new OpsAgentGraphFactory(
                new TriageNode(model, audit),
                new RetrieveNode(knowledge),
                new PlanNode(model, audit),
                new DiagnoseNode(tools, audit),
                new DecisionNode(model, audit),
                new VerifyNode(),
                new SummarizeNode(),
                audit));
    }

    private AgentTaskCommand command(int maxSteps) {
        return command(maxSteps, Duration.ofMinutes(3), 20_000);
    }

    private AgentTaskCommand command(
            int maxSteps,
            Duration timeout,
            int maxTokens) {
        return new AgentTaskCommand(
                100, 1, 88, "redis-timeout", "order-service",
                "Redis latency incident", "Connection acquisition timed out",
                ModelProvider.QWEN,
                new AgentBudget(maxSteps, timeout, maxTokens));
    }

    private static final class FakeModel implements ModelGateway {

        private final Deque<String> replies;
        private int calls;
        private long delayMillis;

        private FakeModel(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public ModelReply call(ModelProvider provider, ModelRequest request) {
            calls++;
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("model call interrupted", exception);
                }
            }
            return new ModelReply(
                    provider, "fixed-model", replies.removeFirst(),
                    new ModelUsage(10, 5, 15));
        }

        @Override
        public Flux<String> stream(ModelProvider provider, ModelRequest request) {
            return Flux.error(new UnsupportedOperationException());
        }
    }

    private static final class RecordingTools implements DiagnosticToolGateway {

        private final List<String> names = new ArrayList<>();
        private String failure;

        @Override
        public ToolObservation execute(
                long tenantId,
                long taskId,
                String scenarioKey,
                String service,
                String toolName) {
            names.add(toolName);
            return failure == null
                    ? new ToolObservation(
                            toolName, true, Map.of("status", "observed"), null)
                    : new ToolObservation(toolName, false, Map.of(), failure);
        }
    }

    private static final class RecordingAudit implements AgentExecutionAudit {

        private final List<String> started = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();
        private final List<String> models = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();
        private final List<Long> tenants = new ArrayList<>();

        @Override
        public void nodeStarted(NodeAudit audit) {
            started.add(audit.nodeName());
            tenants.add(TenantContext.requireTenantId());
        }

        @Override
        public void nodeCompleted(NodeAudit audit) {
            completed.add(audit.nodeName());
            tenants.add(TenantContext.requireTenantId());
        }

        @Override
        public void modelInvoked(ModelCallAudit audit) {
            models.add(audit.modelName());
            tenants.add(TenantContext.requireTenantId());
        }

        @Override
        public void toolInvoked(ToolCallAudit audit) {
            tools.add(audit.toolName());
            tenants.add(TenantContext.requireTenantId());
        }
    }
}
