package com.cc.opsagent.agent.infrastructure;

import com.cc.opsagent.agent.application.AgentBudget;
import com.cc.opsagent.agent.application.StepRecord;
import com.cc.opsagent.agent.domain.AgentStep;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class AgentTaskRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AgentTaskRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean ticketExists(long tenantId, long ticketId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ticket WHERE tenant_id = ? AND id = ?
                """, Integer.class, tenantId, ticketId);
        return count != null && count == 1;
    }

    public long insert(
            long tenantId,
            long ticketId,
            long requestedBy,
            AgentBudget budget) {
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_task
                        (tenant_id, ticket_id, requested_by, status,
                         max_steps, timeout_seconds, max_tokens)
                    VALUES (?, ?, ?, 'QUEUED', ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, ticketId);
                statement.setLong(3, requestedBy);
                statement.setInt(4, budget.maxSteps());
                statement.setInt(5, Math.toIntExact(budget.timeout().toSeconds()));
                statement.setInt(6, budget.maxTokens());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("agent task ID was not generated");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    public AgentTask find(long tenantId, long taskId) {
        List<AgentTask> tasks = jdbcTemplate.query("""
                SELECT id, tenant_id, ticket_id, requested_by, status,
                       max_steps, timeout_seconds, max_tokens,
                       steps_used, tokens_used, worker_id, lease_until,
                       error_summary, cancel_requested_at, recovery_count,
                       created_at, started_at, finished_at
                FROM agent_task
                WHERE tenant_id = ? AND id = ?
                """, (resultSet, rowNumber) -> mapTask(resultSet), tenantId, taskId);
        return tasks.isEmpty() ? null : tasks.getFirst();
    }

    public AgentTask findLatestByTicket(long tenantId, long ticketId) {
        List<AgentTask> tasks = jdbcTemplate.query("""
                SELECT id, tenant_id, ticket_id, requested_by, status,
                       max_steps, timeout_seconds, max_tokens,
                       steps_used, tokens_used, worker_id, lease_until,
                       error_summary, cancel_requested_at, recovery_count,
                       created_at, started_at, finished_at
                FROM agent_task
                WHERE tenant_id = ? AND ticket_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, (resultSet, rowNumber) -> mapTask(resultSet), tenantId, ticketId);
        return tasks.isEmpty() ? null : tasks.getFirst();
    }

    public int claim(
            long tenantId,
            long taskId,
            String workerId,
            Instant leaseUntil,
            Instant now) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET status = 'RUNNING',
                    worker_id = ?,
                    lease_until = ?,
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP(6)),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                  AND status IN ('QUEUED', 'RUNNING')
                  AND (worker_id = ? OR lease_until IS NULL
                       OR lease_until < ?)
                  AND cancel_requested_at IS NULL
                """, workerId, Timestamp.from(leaseUntil), tenantId, taskId,
                workerId, Timestamp.from(now));
    }

    public int renewLease(
            long tenantId,
            long taskId,
            String workerId,
            Instant leaseUntil,
            Instant now) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET lease_until = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                  AND status = 'RUNNING'
                  AND worker_id = ?
                  AND lease_until >= ?
                  AND cancel_requested_at IS NULL
                """, Timestamp.from(leaseUntil), tenantId, taskId,
                workerId, Timestamp.from(now));
    }

    public int requestCancellation(long tenantId, long taskId) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET cancel_requested_at = COALESCE(
                        cancel_requested_at, CURRENT_TIMESTAMP(6)),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                  AND status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL')
                """, tenantId, taskId);
    }

    public boolean cancellationRequested(long tenantId, long taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_task
                WHERE tenant_id = ? AND id = ?
                  AND cancel_requested_at IS NOT NULL
                """, Integer.class, tenantId, taskId);
        return count != null && count == 1;
    }

    public List<RecoveryCandidate> findExpiredRunning(
            Instant now,
            int limit) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT tenant_id, id, requested_by, lease_until
                FROM agent_task
                WHERE status = 'RUNNING' AND lease_until IS NOT NULL
                  AND lease_until < ?
                ORDER BY lease_until
                LIMIT ?
                """, (resultSet, rowNumber) -> new RecoveryCandidate(
                resultSet.getLong("tenant_id"),
                resultSet.getLong("id"),
                resultSet.getLong("requested_by"),
                resultSet.getTimestamp("lease_until").toInstant()),
                Timestamp.from(now), limit));
    }

    public int claimExpiredForRecovery(
            long tenantId,
            long taskId,
            String workerId,
            Instant now,
            Instant leaseUntil) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET worker_id = ?, lease_until = ?,
                    recovery_count = recovery_count + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                  AND lease_until IS NOT NULL AND lease_until < ?
                """, workerId, Timestamp.from(leaseUntil),
                tenantId, taskId, Timestamp.from(now));
    }

    public int expireApprovalWaits(Instant now) {
        int changedRows = jdbcTemplate.update("""
                UPDATE agent_task task
                JOIN approval_request approval
                  ON approval.tenant_id = task.tenant_id
                 AND approval.task_id = task.id
                SET approval.status = 'EXPIRED',
                    approval.updated_at = CURRENT_TIMESTAMP(6),
                    task.status = 'TIMED_OUT',
                    task.worker_id = NULL,
                    task.lease_until = NULL,
                    task.finished_at = CURRENT_TIMESTAMP(6),
                    task.updated_at = CURRENT_TIMESTAMP(6)
                WHERE task.status = 'WAITING_APPROVAL'
                  AND approval.status = 'PENDING'
                  AND approval.expires_at <= ?
                """, Timestamp.from(now));
        // MySQL reports one changed row for each updated table in the join.
        return changedRows / 2;
    }

    public int transition(
            long tenantId,
            long taskId,
            AgentTaskStatus expected,
            AgentTaskStatus target) {
        boolean terminal = target.terminal();
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET status = ?,
                    worker_id = CASE WHEN ? THEN NULL ELSE worker_id END,
                    lease_until = CASE WHEN ? THEN NULL ELSE lease_until END,
                    finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE finished_at END,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ? AND status = ?
                """, target.name(), terminal, terminal, terminal,
                tenantId, taskId, expected.name());
    }

    public void insertStep(long tenantId, StepRecord step) {
        jdbcTemplate.update("""
                INSERT INTO agent_step
                    (tenant_id, task_id, sequence, node_name, status,
                     input_data, output_data, error_summary, duration_ms)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?)
                """, tenantId, step.taskId(), step.sequence(), step.nodeName(),
                step.status(), writeJson(step.input()), writeJson(step.output()),
                step.errorSummary(), step.durationMs());
    }

    public int addUsage(
            long tenantId,
            long taskId,
            int steps,
            int tokens) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET steps_used = steps_used + ?,
                    tokens_used = tokens_used + ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                """, steps, tokens, tenantId, taskId);
    }

    public int updateErrorSummary(
            long tenantId,
            long taskId,
            String errorSummary) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET error_summary = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                """, errorSummary, tenantId, taskId);
    }

    public List<AgentStep> findSteps(long tenantId, long taskId) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT id, tenant_id, task_id, sequence, node_name, status,
                       input_data, output_data, error_summary, duration_ms, created_at
                FROM agent_step
                WHERE tenant_id = ? AND task_id = ?
                ORDER BY sequence
                """, (resultSet, rowNumber) -> new AgentStep(
                resultSet.getLong("id"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("task_id"),
                resultSet.getInt("sequence"),
                resultSet.getString("node_name"),
                resultSet.getString("status"),
                readJson(resultSet.getString("input_data")),
                readJson(resultSet.getString("output_data")),
                resultSet.getString("error_summary"),
                resultSet.getLong("duration_ms"),
                resultSet.getTimestamp("created_at").toInstant()), tenantId, taskId));
    }

    public long lockEventSequence(long tenantId, long taskId) {
        List<Long> sequences = jdbcTemplate.query("""
                SELECT next_event_sequence
                FROM agent_task
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getLong(1), tenantId, taskId);
        if (sequences.isEmpty()) {
            throw new IllegalArgumentException("agent task was not found for the authenticated tenant");
        }
        return sequences.getFirst();
    }

    public int updateEventSequence(long tenantId, long taskId, long sequence) {
        return jdbcTemplate.update("""
                UPDATE agent_task
                SET next_event_sequence = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                """, sequence, tenantId, taskId);
    }

    private AgentTask mapTask(ResultSet resultSet) throws java.sql.SQLException {
        return new AgentTask(
                resultSet.getLong("id"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("ticket_id"),
                resultSet.getLong("requested_by"),
                AgentTaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("max_steps"),
                resultSet.getInt("timeout_seconds"),
                resultSet.getInt("max_tokens"),
                resultSet.getInt("steps_used"),
                resultSet.getInt("tokens_used"),
                resultSet.getString("worker_id"),
                instant(resultSet.getTimestamp("lease_until")),
                resultSet.getString("error_summary"),
                instant(resultSet.getTimestamp("cancel_requested_at")),
                resultSet.getInt("recovery_count"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("agent data cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored agent data is invalid", exception);
        }
    }
}
