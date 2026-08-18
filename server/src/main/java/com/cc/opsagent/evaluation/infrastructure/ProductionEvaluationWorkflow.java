package com.cc.opsagent.evaluation.infrastructure;

import com.cc.opsagent.agent.application.AgentBudget;
import com.cc.opsagent.agent.application.AgentExecutionAudit;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.application.AgentTaskCommand;
import com.cc.opsagent.agent.application.CancellationProbe;
import com.cc.opsagent.agent.application.DiagnosticToolGateway;
import com.cc.opsagent.agent.application.OpsAgentWorkflow;
import com.cc.opsagent.agent.application.TaskOutcome;
import com.cc.opsagent.agent.domain.AgentStep;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.graph.OpsAgentGraphFactory;
import com.cc.opsagent.agent.graph.node.DecisionNode;
import com.cc.opsagent.agent.graph.node.DiagnoseNode;
import com.cc.opsagent.agent.graph.node.PlanNode;
import com.cc.opsagent.agent.graph.node.RetrieveNode;
import com.cc.opsagent.agent.graph.node.SummarizeNode;
import com.cc.opsagent.agent.graph.node.TriageNode;
import com.cc.opsagent.agent.graph.node.VerifyNode;
import com.cc.opsagent.agent.infrastructure.AlibabaGraphWorkflowEngine;
import com.cc.opsagent.evaluation.application.EvaluationRunRequest;
import com.cc.opsagent.evaluation.application.EvaluationWorkflowPort;
import com.cc.opsagent.evaluation.domain.EvaluationCase;
import com.cc.opsagent.evaluation.domain.EvaluationMode;
import com.cc.opsagent.evaluation.domain.EvaluationObservation;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.knowledge.application.EvidenceChunk;
import com.cc.opsagent.model.ModelGateway;
import com.cc.opsagent.model.ModelReply;
import com.cc.opsagent.model.ModelRequest;
import com.cc.opsagent.model.ModelUsage;
import com.cc.opsagent.security.SensitiveDataRedactor;
import com.cc.opsagent.simulator.domain.OpsScenario;
import com.cc.opsagent.simulator.infrastructure.ScenarioCatalog;
import com.cc.opsagent.ticket.application.CreateTicketCommand;
import com.cc.opsagent.ticket.application.TicketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.Deque;
import reactor.core.publisher.Flux;

@Component
public class ProductionEvaluationWorkflow implements EvaluationWorkflowPort {

    private final ScenarioCatalog scenarios;
    private final TicketService tickets;
    private final AgentTaskService tasks;
    private final ObjectProvider<OpsAgentWorkflow> liveWorkflow;
    private final DiagnosticToolGateway diagnosticTools;
    private final SensitiveDataRedactor redactor;
    private final com.cc.opsagent.model.ModelProvider liveProvider;
    private final String qwenModel;
    private final String deepSeekModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProductionEvaluationWorkflow(
            ScenarioCatalog scenarios,
            TicketService tickets,
            AgentTaskService tasks,
            ObjectProvider<OpsAgentWorkflow> liveWorkflow,
            DiagnosticToolGateway diagnosticTools,
            SensitiveDataRedactor redactor,
            @Value("${app.agent.provider:QWEN}")
            com.cc.opsagent.model.ModelProvider liveProvider,
            @Value("${app.ai.qwen.model:qwen-plus}") String qwenModel,
            @Value("${app.ai.deepseek.model:deepseek-chat}") String deepSeekModel) {
        this.scenarios = scenarios;
        this.tickets = tickets;
        this.tasks = tasks;
        this.liveWorkflow = liveWorkflow;
        this.diagnosticTools = diagnosticTools;
        this.redactor = redactor;
        this.liveProvider = liveProvider;
        this.qwenModel = qwenModel;
        this.deepSeekModel = deepSeekModel;
    }

    @Override
    public EvaluationRunRequest prepare(EvaluationRunRequest request) {
        if (request.mode() == EvaluationMode.MOCK) return request;
        if (request.provider() != liveProvider) {
            throw new IllegalArgumentException(
                    "LIVE evaluation provider must match app.agent.provider="
                            + liveProvider);
        }
        String configuredModel = liveProvider
                == com.cc.opsagent.model.ModelProvider.QWEN
                ? qwenModel : deepSeekModel;
        if (!"provider-default".equals(request.model())
                && !configuredModel.equals(request.model())) {
            throw new IllegalArgumentException(
                    "LIVE evaluation model must match configured agent model "
                            + configuredModel);
        }
        return new EvaluationRunRequest(
                request.mode(), request.provider(), configuredModel,
                request.promptVersion(), request.knowledgeVersion(), request.caseIds());
    }

    @Override
    public EvaluationObservation execute(
            EvaluationCase evaluationCase,
            EvaluationRunRequest request) {
        return request.mode() == EvaluationMode.MOCK
                ? deterministic(evaluationCase, request)
                : live(evaluationCase, request);
    }

    private EvaluationObservation deterministic(
            EvaluationCase evaluationCase,
            EvaluationRunRequest request) {
        Instant started = Instant.now();
        long latency = 25L + Math.floorMod(evaluationCase.id().hashCode(), 75);
        long tenantId = TenantContext.requireTenantId();
        String citation = "tenant:" + tenantId
                + ":evaluation:" + evaluationCase.id();
        List<String> tools = evaluationCase.expectedTools().stream().sorted().toList();
        ScriptedModelGateway model = new ScriptedModelGateway(request, List.of(
                json(Map.of(
                        "category", evaluationCase.expectedCategory(),
                        "urgency", scenarios.require(
                                evaluationCase.scenarioKey()).severity().name())),
                json(Map.of("tools", tools)),
                json(decision(evaluationCase))));
        var knowledge = (com.cc.opsagent.knowledge.application.KnowledgeRetriever)
                query -> evaluationCase.citationRequired()
                        ? List.of(new EvidenceChunk(
                                tenantId, 1, 1, 0,
                                "evaluation/" + evaluationCase.scenarioKey() + ".md",
                                "Deterministic runbook evidence for "
                                        + evaluationCase.scenarioKey(),
                                Map.of("caseId", evaluationCase.id()),
                                1.0, citation))
                        : List.of();
        AgentExecutionAudit audit = AgentExecutionAudit.noop();
        CancellationProbe cancellation = CancellationProbe.never();
        var engine = new AlibabaGraphWorkflowEngine(new OpsAgentGraphFactory(
                new TriageNode(model, audit, cancellation, redactor),
                new RetrieveNode(knowledge, cancellation),
                new PlanNode(model, audit, cancellation, redactor),
                new DiagnoseNode(diagnosticTools, audit, cancellation),
                new DecisionNode(model, audit, cancellation, redactor),
                new VerifyNode(), new SummarizeNode(), audit, cancellation));
        long syntheticId = Integer.toUnsignedLong(evaluationCase.id().hashCode()) + 1;
        TaskOutcome outcome = engine.execute(new AgentTaskCommand(
                syntheticId, tenantId, syntheticId,
                evaluationCase.scenarioKey(),
                scenarios.require(evaluationCase.scenarioKey()).service(),
                evaluationCase.title(), evaluationCase.description(),
                request.provider(),
                new AgentBudget(12, Duration.ofMinutes(1), 2_000)));
        int leakage = leakageCount(evaluationCase, java.util.Arrays.asList(
                outcome.rootCause(), outcome.proposedAction(), outcome.report(),
                outcome.errorSummary()));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("category", evaluationCase.expectedCategory());
        raw.put("rootCause", outcome.rootCause());
        raw.put("tools", outcome.toolNames());
        raw.put("citations", outcome.citations());
        raw.put("action", outcome.proposedAction());
        raw.put("status", outcome.status().name());
        return new EvaluationObservation(
                evaluationCase.expectedCategory(),
                outcome.rootCause(), outcome.toolNames(), outcome.citations(),
                outcome.proposedAction(), outcome.actionArguments(),
                outcome.status(), 7, model.tokens(), latency, leakage,
                request.provider(), request.model(), json(raw), null,
                started, started.plusMillis(latency));
    }

    private Map<String, Object> decision(EvaluationCase evaluationCase) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("rootCause", evaluationCase.expectedRootCause());
        decision.put("proposedAction", evaluationCase.expectedAction());
        decision.put("actionArguments", evaluationCase.expectedArguments());
        decision.put("confidence", 0.95);
        return decision;
    }

    private EvaluationObservation live(
            EvaluationCase evaluationCase,
            EvaluationRunRequest request) {
        OpsAgentWorkflow workflow = liveWorkflow.getIfAvailable();
        if (workflow == null) {
            throw new IllegalStateException(
                    "LIVE evaluation requires vector datasource and agent workflow");
        }
        OpsScenario scenario = scenarios.require(evaluationCase.scenarioKey());
        Instant started = Instant.now();
        var ticket = tickets.create(new CreateTicketCommand(
                evaluationCase.title(), evaluationCase.description(),
                scenario.service(), scenario.category(), evaluationCase.scenarioKey(),
                scenario.severity()));
        AgentTask task = tasks.start(ticket.id(), new AgentBudget(
                12, Duration.ofMinutes(3), 20_000));
        TaskOutcome outcome = workflow.run(task.id());
        AgentTask completed = tasks.get(task.id());
        List<AgentStep> steps = tasks.steps(task.id());
        String category = steps.stream()
                .filter(step -> "triage".equals(step.nodeName()))
                .map(AgentStep::output)
                .map(output -> output.get("category"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .findFirst().orElse(null);
        Instant finished = Instant.now();
        String raw = json(Map.of(
                "status", outcome.status().name(),
                "tools", outcome.toolNames(),
                "citations", outcome.citations(),
                "actionArguments", outcome.actionArguments()));
        int leakage = leakageCount(evaluationCase, java.util.Arrays.asList(
                outcome.rootCause(), outcome.proposedAction(), outcome.report(),
                outcome.errorSummary(), raw));
        return new EvaluationObservation(
                category, outcome.rootCause(), outcome.toolNames(), outcome.citations(),
                outcome.proposedAction(), outcome.actionArguments(), outcome.status(),
                completed.stepsUsed(), completed.tokensUsed(),
                Math.max(0, Duration.between(started, finished).toMillis()),
                leakage, request.provider(), request.model(), raw,
                outcome.errorSummary() == null ? null : "WORKFLOW_FAILURE",
                started, finished);
    }

    private int leakageCount(
            EvaluationCase evaluationCase,
            List<String> outputs) {
        String joined = String.join("\n", outputs.stream()
                .filter(Objects::nonNull).toList());
        int count = 0;
        for (String forbidden : evaluationCase.forbiddenLeakValues()) {
            if (forbidden != null && !forbidden.isBlank() && joined.contains(forbidden)) {
                count++;
            }
        }
        return count;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "evaluation output could not be serialized", exception);
        }
    }

    private static final class ScriptedModelGateway implements ModelGateway {

        private final EvaluationRunRequest request;
        private final Deque<String> replies;
        private int tokens;

        private ScriptedModelGateway(
                EvaluationRunRequest request,
                List<String> replies) {
            this.request = request;
            this.replies = new ArrayDeque<>(replies);
        }

        @Override
        public ModelReply call(
                com.cc.opsagent.model.ModelProvider provider,
                ModelRequest modelRequest) {
            if (replies.isEmpty()) {
                throw new IllegalStateException("mock model received an unexpected call");
            }
            ModelUsage usage = new ModelUsage(10, 5, 15);
            tokens += usage.totalTokens();
            return new ModelReply(
                    request.provider(), request.model(), replies.removeFirst(), usage);
        }

        @Override
        public Flux<String> stream(
                com.cc.opsagent.model.ModelProvider provider,
                ModelRequest modelRequest) {
            return Flux.error(new UnsupportedOperationException(
                    "evaluation mock streaming is not used"));
        }

        int tokens() {
            return tokens;
        }
    }
}
