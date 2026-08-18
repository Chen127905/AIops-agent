package com.cc.opsagent.evaluation.infrastructure;

import com.cc.opsagent.evaluation.application.EvaluationMetrics;
import com.cc.opsagent.evaluation.application.EvaluationRunRepository;
import com.cc.opsagent.evaluation.application.EvaluationRunRequest;
import com.cc.opsagent.evaluation.application.EvaluationRunSummary;
import com.cc.opsagent.evaluation.domain.EvaluationMode;
import com.cc.opsagent.evaluation.domain.EvaluationResult;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.security.SensitiveDataRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcEvaluationRunRepository implements EvaluationRunRepository {

    private final JdbcTemplate jdbc;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JdbcEvaluationRunRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbc,
            SensitiveDataRedactor redactor) {
        this.jdbc = jdbc;
        this.redactor = redactor;
    }

    @Override
    public void start(
            String runId,
            long tenantId,
            long requestedBy,
            EvaluationRunRequest request,
            int totalCases,
            Instant startedAt) {
        jdbc.update("""
                INSERT INTO evaluation_run
                    (id, tenant_id, requested_by, mode, provider, model_name,
                     prompt_version, knowledge_version, status, total_cases, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
                """, runId, tenantId, requestedBy, request.mode().name(),
                request.provider().name(), request.model(), request.promptVersion(),
                request.knowledgeVersion(), totalCases, Timestamp.from(startedAt));
    }

    @Override
    public void append(String runId, long tenantId, EvaluationResult result) {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("classificationCorrect", result.classificationCorrect());
        scores.put("rootCauseCorrect", result.rootCauseCorrect());
        scores.put("toolTruePositives", result.toolTruePositives());
        scores.put("actualToolCount", result.actualToolCount());
        scores.put("expectedToolCount", result.expectedToolCount());
        scores.put("forbiddenToolFree", result.forbiddenToolFree());
        scores.put("parametersCorrect", result.parametersCorrect());
        scores.put("citationsCorrect", result.citationsCorrect());
        scores.put("resolutionCorrect", result.resolutionCorrect());
        scores.put("approvalCorrect", result.approvalCorrect());
        jdbc.update("""
                INSERT INTO evaluation_case_result
                    (run_id, tenant_id, case_id, case_group, passed,
                     scores, observation, failure_category, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?)
                """, runId, tenantId, result.evaluationCase().id(),
                result.evaluationCase().group().name(), result.passed(),
                json(scores), json(safeObservation(result.observation())),
                result.observation().failureCategory(),
                Timestamp.from(result.observation().startedAt()),
                Timestamp.from(result.observation().finishedAt()));
    }

    @Override
    public void finish(
            String runId,
            long tenantId,
            String status,
            EvaluationMetrics metrics,
            Instant finishedAt) {
        int changed = jdbc.update("""
                UPDATE evaluation_run
                SET status = ?, passed_cases = ?, metrics = CAST(? AS JSON), finished_at = ?
                WHERE id = ? AND tenant_id = ? AND status = 'RUNNING'
                """, status, metrics.passedCases(), json(metrics),
                Timestamp.from(finishedAt), runId, tenantId);
        if (changed != 1) {
            throw new IllegalStateException("evaluation run could not be completed");
        }
    }

    @Override
    public Optional<EvaluationRunSummary> find(long tenantId, String runId) {
        return jdbc.query("""
                SELECT id, mode, provider, model_name, prompt_version,
                       knowledge_version, status, metrics, started_at, finished_at
                FROM evaluation_run
                WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new EvaluationRunSummary(
                rs.getString("id"),
                EvaluationMode.valueOf(rs.getString("mode")),
                ModelProvider.valueOf(rs.getString("provider")),
                rs.getString("model_name"),
                rs.getString("prompt_version"),
                rs.getString("knowledge_version"),
                rs.getString("status"),
                readMetrics(rs.getString("metrics")),
                rs.getTimestamp("started_at").toInstant(),
                instant(rs.getTimestamp("finished_at"))),
                tenantId, runId).stream().findFirst();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("evaluation data is not serializable", exception);
        }
    }

    private Map<String, Object> safeObservation(
            com.cc.opsagent.evaluation.domain.EvaluationObservation value) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("category", redact(value.category()));
        safe.put("rootCause", redact(value.rootCause()));
        safe.put("tools", value.tools().stream().map(this::redact).toList());
        safe.put("citations", value.citations().stream().map(this::redact).toList());
        safe.put("action", redact(value.action()));
        safe.put("actionArguments", safeValue(value.actionArguments()));
        safe.put("status", value.status() == null ? null : value.status().name());
        safe.put("steps", value.steps());
        safe.put("tokens", value.tokens());
        safe.put("latencyMs", value.latencyMs());
        safe.put("leakageCount", value.leakageCount());
        safe.put("provider", value.provider() == null ? null : value.provider().name());
        safe.put("model", redact(value.model()));
        safe.put("rawStructuredOutput", redact(value.rawStructuredOutput()));
        safe.put("failureCategory", redact(value.failureCategory()));
        safe.put("startedAt", value.startedAt());
        safe.put("finishedAt", value.finishedAt());
        return safe;
    }

    private Object safeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(
                    redact(String.valueOf(key)), safeValue(item)));
            return safe;
        }
        if (value instanceof Iterable<?> items) {
            java.util.List<Object> safe = new java.util.ArrayList<>();
            items.forEach(item -> safe.add(safeValue(item)));
            return safe;
        }
        return redact(String.valueOf(value));
    }

    private String redact(String value) {
        return value == null ? null : redactor.redact(value, 8_000);
    }

    private EvaluationMetrics readMetrics(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, EvaluationMetrics.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored evaluation metrics are invalid", exception);
        }
    }
}
