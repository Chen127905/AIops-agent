package com.cc.opsagent.approval.infrastructure;

import com.cc.opsagent.approval.domain.ApprovalRequest;
import com.cc.opsagent.approval.domain.ApprovalStatus;
import com.cc.opsagent.tool.domain.ToolRisk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Repository
public class ApprovalRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApprovalRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean taskIsWaiting(long tenantId, long taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_task
                WHERE tenant_id = ? AND id = ? AND status = 'WAITING_APPROVAL'
                """, Integer.class, tenantId, taskId);
        return count != null && count == 1;
    }

    public long insert(
            long tenantId,
            long taskId,
            String checkpointId,
            String scenarioKey,
            String toolName,
            Map<String, Object> arguments,
            long requestedBy,
            Instant expiresAt) {
        String json = writeJson(arguments);
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO approval_request
                        (tenant_id, task_id, checkpoint_id, scenario_key,
                         tool_name, normalized_arguments, arguments_hash,
                         risk, status, requested_by, expires_at)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?,
                            'HIGH_RISK', 'PENDING', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, taskId);
                statement.setString(3, checkpointId);
                statement.setString(4, scenarioKey);
                statement.setString(5, toolName);
                statement.setString(6, json);
                statement.setString(7, sha256(json));
                statement.setLong(8, requestedBy);
                statement.setTimestamp(9, Timestamp.from(expiresAt));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("approval ID was not generated");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    public int decide(
            long tenantId,
            long approvalId,
            long decidedBy,
            ApprovalStatus status,
            String comment,
            Instant now) {
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET status = ?, decided_by = ?, decision_comment = ?,
                    decided_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                  AND status = 'PENDING'
                  AND expires_at > ?
                """, status.name(), decidedBy, comment,
                tenantId, approvalId, Timestamp.from(now));
    }

    public int expirePending(long tenantId, long approvalId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ? AND status = 'PENDING'
                  AND expires_at <= ?
                """, tenantId, approvalId, Timestamp.from(now));
    }

    public int claimExecution(long tenantId, long approvalId) {
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET status = 'EXECUTING',
                    execution_started_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ? AND status = 'APPROVED'
                """, tenantId, approvalId);
    }

    public int finishExecution(
            long tenantId,
            long approvalId,
            ApprovalStatus status,
            String errorSummary) {
        return jdbcTemplate.update("""
                UPDATE approval_request
                SET status = ?, error_summary = ?,
                    execution_finished_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ? AND status = 'EXECUTING'
                """, status.name(), errorSummary, tenantId, approvalId);
    }

    public ApprovalRequest find(long tenantId, long approvalId) {
        List<ApprovalRequest> approvals = jdbcTemplate.query("""
                SELECT id, tenant_id, task_id, checkpoint_id, scenario_key,
                       tool_name, normalized_arguments, arguments_hash,
                       risk, status, requested_by, decided_by,
                       decision_comment, expires_at, decided_at, created_at
                FROM approval_request
                WHERE tenant_id = ? AND id = ?
                """, (resultSet, rowNumber) -> map(resultSet), tenantId, approvalId);
        return approvals.isEmpty() ? null : approvals.getFirst();
    }

    public List<ApprovalRequest> findPending(long tenantId) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT id, tenant_id, task_id, checkpoint_id, scenario_key,
                       tool_name, normalized_arguments, arguments_hash,
                       risk, status, requested_by, decided_by,
                       decision_comment, expires_at, decided_at, created_at
                FROM approval_request
                WHERE tenant_id = ? AND status = 'PENDING'
                ORDER BY created_at
                """, (resultSet, rowNumber) -> map(resultSet), tenantId));
    }

    private ApprovalRequest map(ResultSet resultSet) throws java.sql.SQLException {
        long decidedBy = resultSet.getLong("decided_by");
        return new ApprovalRequest(
                resultSet.getLong("id"), resultSet.getLong("tenant_id"),
                resultSet.getLong("task_id"), resultSet.getString("checkpoint_id"),
                resultSet.getString("scenario_key"), resultSet.getString("tool_name"),
                readJson(resultSet.getString("normalized_arguments")),
                resultSet.getString("arguments_hash"),
                ToolRisk.valueOf(resultSet.getString("risk")),
                ApprovalStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("requested_by"),
                resultSet.wasNull() ? null : decidedBy,
                resultSet.getString("decision_comment"),
                resultSet.getTimestamp("expires_at").toInstant(),
                instant(resultSet.getTimestamp("decided_at")),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("approval arguments are invalid", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored approval arguments are invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
