package com.cc.opsagent.agent.infrastructure;

import com.cc.opsagent.agent.domain.AgentEvent;
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
import java.util.List;
import java.util.Map;

@Repository
public class AgentEventRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentEventRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(
            long tenantId,
            long taskId,
            long sequence,
            String eventType,
            Map<String, Object> payload) {
        String payloadJson = writeJson(payload);
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_event
                        (tenant_id, task_id, sequence, event_type, payload)
                    VALUES (?, ?, ?, ?, CAST(? AS JSON))
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, taskId);
                statement.setLong(3, sequence);
                statement.setString(4, eventType);
                statement.setString(5, payloadJson);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("agent event ID was not generated");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    public AgentEvent find(long tenantId, long eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, task_id, sequence, event_type, payload, created_at
                FROM agent_event WHERE tenant_id = ? AND id = ?
                """, (resultSet, rowNumber) -> mapEvent(resultSet), tenantId, eventId);
    }

    public List<AgentEvent> findAfter(
            long tenantId,
            long taskId,
            long afterSequence,
            int limit) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT id, tenant_id, task_id, sequence, event_type, payload, created_at
                FROM agent_event
                WHERE tenant_id = ? AND task_id = ? AND sequence > ?
                ORDER BY sequence
                LIMIT ?
                """, (resultSet, rowNumber) -> mapEvent(resultSet),
                tenantId, taskId, afterSequence, limit));
    }

    private AgentEvent mapEvent(ResultSet resultSet) throws java.sql.SQLException {
        return new AgentEvent(
                resultSet.getLong("id"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("task_id"),
                resultSet.getLong("sequence"),
                resultSet.getString("event_type"),
                readJson(resultSet.getString("payload")),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event payload cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored event payload is invalid", exception);
        }
    }
}
