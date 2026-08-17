package com.cc.opsagent.agent.infrastructure;

import com.cc.opsagent.agent.application.ModelInvocationRecord;
import com.cc.opsagent.agent.application.ToolInvocationRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Repository
public class AgentInvocationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentInvocationRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public long insertModel(long tenantId, ModelInvocationRecord record) {
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO model_invocation
                        (tenant_id, task_id, step_id, provider, model_name,
                         request_hash, status, input_tokens, output_tokens,
                         latency_ms, error_summary)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, record.taskId());
                nullableLong(statement, 3, record.stepId());
                statement.setString(4, record.provider().trim());
                statement.setString(5, record.modelName().trim());
                statement.setString(6, record.requestHash().toLowerCase());
                statement.setString(7, record.status());
                statement.setInt(8, record.inputTokens());
                statement.setInt(9, record.outputTokens());
                statement.setLong(10, record.latencyMs());
                statement.setString(11, record.errorSummary());
                statement.executeUpdate();
                return generatedId(statement, "model invocation");
            }
        });
    }

    public long insertTool(long tenantId, ToolInvocationRecord record) {
        String arguments = writeJson(record.normalizedArguments());
        String argumentsHash = sha256(arguments);
        String result = writeJson(record.resultSummary());
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tool_invocation
                        (tenant_id, task_id, step_id, tool_name,
                         normalized_arguments, arguments_hash,
                         risk, status, idempotency_key,
                         latency_ms, result_summary, error_summary)
                    VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, record.taskId());
                nullableLong(statement, 3, record.stepId());
                statement.setString(4, record.toolName().trim());
                statement.setString(5, arguments);
                statement.setString(6, argumentsHash);
                statement.setString(7, record.risk().name());
                statement.setString(8, record.status().name());
                statement.setString(9, record.idempotencyKey());
                statement.setLong(10, record.latencyMs());
                statement.setString(11, result);
                statement.setString(12, record.errorSummary());
                statement.executeUpdate();
                return generatedId(statement, "tool invocation");
            }
        });
    }

    public ToolIdentity findToolByIdempotency(
            long tenantId,
            long taskId,
            String toolName,
            String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        return jdbcTemplate.query("""
                SELECT id, arguments_hash FROM tool_invocation
                WHERE tenant_id = ? AND task_id = ?
                  AND tool_name = ? AND idempotency_key = ?
                """, resultSet -> resultSet.next()
                        ? new ToolIdentity(resultSet.getLong(1), resultSet.getString(2))
                        : null,
                tenantId, taskId, toolName, idempotencyKey);
    }

    public String argumentsHash(Map<String, Object> arguments) {
        return sha256(writeJson(arguments));
    }

    private long generatedId(PreparedStatement statement, String type)
            throws java.sql.SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new IllegalStateException(type + " ID was not generated");
            }
            return keys.getLong(1);
        }
    }

    private void nullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invocation data cannot be serialized", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ToolIdentity(long id, String argumentsHash) {
    }
}
