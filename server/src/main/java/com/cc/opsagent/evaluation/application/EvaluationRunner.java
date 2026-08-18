package com.cc.opsagent.evaluation.application;

import com.cc.opsagent.audit.SecurityAuditPort;
import com.cc.opsagent.audit.SecurityAuditPort.SecurityAuditEvent;
import com.cc.opsagent.evaluation.domain.EvaluationCase;
import com.cc.opsagent.evaluation.domain.EvaluationObservation;
import com.cc.opsagent.evaluation.domain.EvaluationResult;
import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.security.SensitiveDataRedactor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvaluationRunner {

    private final EvaluationCaseCatalog catalog;
    private final EvaluationWorkflowPort workflow;
    private final EvaluationRunRepository repository;
    private final SecurityAuditPort audit;
    private final SensitiveDataRedactor redactor;

    public EvaluationRunner(
            EvaluationCaseCatalog catalog,
            EvaluationWorkflowPort workflow,
            EvaluationRunRepository repository,
            SecurityAuditPort audit,
            SensitiveDataRedactor redactor) {
        this.catalog = catalog;
        this.workflow = workflow;
        this.repository = repository;
        this.audit = audit;
        this.redactor = redactor;
    }

    public EvaluationRunSummary run(EvaluationRunRequest request) {
        request = workflow.prepare(request);
        long tenantId = TenantContext.requireTenantId();
        long userId = TenantContext.requireUserId();
        List<EvaluationCase> cases = catalog.select(request.caseIds());
        String runId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        repository.start(
                runId, tenantId, userId, request, cases.size(), started);
        audit.record(new SecurityAuditEvent(
                tenantId, userId, "EVALUATION_RUN_STARTED", "REQUESTED",
                "EVALUATION_RUN", runId,
                Map.of("mode", request.mode().name(), "cases", cases.size())));

        List<EvaluationResult> results = new ArrayList<>(cases.size());
        for (EvaluationCase evaluationCase : cases) {
            EvaluationObservation observation;
            try {
                observation = workflow.execute(evaluationCase, request);
            } catch (RuntimeException exception) {
                Instant failedAt = Instant.now();
                String message = redactor.redact(exception.getMessage(), 512);
                observation = new EvaluationObservation(
                        null, null, List.of(), List.of(), null, Map.of(),
                        null, 0, 0, 0, 0, request.provider(), request.model(),
                        message == null ? "" : message,
                        "EXECUTION_ERROR", failedAt, failedAt);
            }
            EvaluationResult result = EvaluationResult.score(
                    runId, tenantId, evaluationCase, observation);
            repository.append(runId, tenantId, result);
            results.add(result);
        }

        EvaluationMetrics metrics = EvaluationMetrics.from(results);
        String status = metrics.passedCases() == metrics.totalCases()
                ? "COMPLETED" : "COMPLETED_WITH_FAILURES";
        Instant finished = Instant.now();
        repository.finish(runId, tenantId, status, metrics, finished);
        audit.record(new SecurityAuditEvent(
                tenantId, userId, "EVALUATION_RUN_COMPLETED", "SUCCEEDED",
                "EVALUATION_RUN", runId,
                Map.of("status", status,
                        "totalCases", metrics.totalCases(),
                        "passedCases", metrics.passedCases())));
        return new EvaluationRunSummary(
                runId, request.mode(), request.provider(), request.model(),
                request.promptVersion(), request.knowledgeVersion(), status,
                metrics, started, finished);
    }

    public EvaluationRunSummary get(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("evaluation run ID is required");
        }
        return repository.find(TenantContext.requireTenantId(), runId.trim())
                .orElseThrow(() -> new EvaluationRunNotFoundException(runId));
    }

    public List<EvaluationCase> cases() {
        return catalog.all();
    }
}
