package com.cc.opsagent.knowledge.application;

import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.tool.application.ToolPolicyService;
import com.cc.opsagent.tool.domain.ToolDecision;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolInvocationRequest;
import com.cc.opsagent.tool.domain.ToolResult;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class SearchRunbookTool implements AutoCloseable {

    private final ToolPolicyService policy;
    private final KnowledgeRetriever retriever;
    private final ExecutorService executor;

    public SearchRunbookTool(
            ToolPolicyService policy,
            KnowledgeRetriever retriever) {
        this.policy = policy;
        this.retriever = retriever;
        this.executor = new DelegatingSecurityContextExecutorService(
                Executors.newVirtualThreadPerTaskExecutor());
    }

    public ToolResult<List<EvidenceChunk>> searchRunbook(
            OpsContext context,
            String query,
            int topK) {
        KnowledgeQuery knowledgeQuery = new KnowledgeQuery(query, topK);
        ToolInvocationRequest request = new ToolInvocationRequest(
                context.tenantId(),
                context.taskId(),
                context.scenarioKey(),
                "searchRunbook",
                Map.of("query", knowledgeQuery.query(), "topK", knowledgeQuery.topK()),
                null,
                null);
        ToolDecision decision = policy.evaluate(request);
        if (!decision.allowed()) {
            return ToolResult.withoutData(
                    ToolExecutionStatus.REJECTED,
                    decision.reason(),
                    decision.risk());
        }
        Future<List<EvidenceChunk>> future = executor.submit(
                () -> retriever.retrieve(knowledgeQuery));
        Duration timeout = decision.descriptor().timeout();
        try {
            List<EvidenceChunk> evidence = future.get(
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            return ToolResult.success(
                    List.copyOf(evidence), decision.risk(), false);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return ToolResult.withoutData(
                    ToolExecutionStatus.TIMEOUT,
                    "Tool exceeded its execution timeout",
                    decision.risk());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.withoutData(
                    ToolExecutionStatus.FAILED,
                    "Tool execution was interrupted",
                    decision.risk());
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return ToolResult.withoutData(
                    ToolExecutionStatus.FAILED,
                    cause == null ? "Tool execution failed" : cause.getMessage(),
                    decision.risk());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
