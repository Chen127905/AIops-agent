package com.cc.opsagent.conversation.infrastructure;

import com.cc.opsagent.conversation.domain.ConversationMessage;
import com.cc.opsagent.conversation.domain.ConversationMessageStatus;
import com.cc.opsagent.conversation.domain.ConversationRole;
import com.cc.opsagent.conversation.domain.TicketConversation;
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

@Repository
public class TicketConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public TicketConversationRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TicketConversation getOrCreate(
            long tenantId,
            long ticketId) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO ticket_conversation (tenant_id, ticket_id)
                VALUES (?, ?)
                """, tenantId, ticketId);
        TicketConversation conversation = find(tenantId, ticketId);
        if (conversation == null) {
            throw new IllegalStateException("ticket conversation could not be created");
        }
        return conversation;
    }

    public TicketConversation find(long tenantId, long ticketId) {
        List<TicketConversation> conversations = jdbcTemplate.query("""
                SELECT id, tenant_id, ticket_id, summary,
                       summarized_through_message_id, created_at, updated_at
                FROM ticket_conversation
                WHERE tenant_id = ? AND ticket_id = ?
                """, (resultSet, rowNumber) -> new TicketConversation(
                resultSet.getLong("id"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("ticket_id"),
                resultSet.getString("summary"),
                nullableLong(resultSet, "summarized_through_message_id"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()),
                tenantId, ticketId);
        return conversations.isEmpty() ? null : conversations.getFirst();
    }

    public boolean acquire(
            long tenantId,
            long conversationId,
            String owner,
            Instant now,
            Instant leaseUntil) {
        return jdbcTemplate.update("""
                UPDATE ticket_conversation
                SET lease_owner = ?, lease_until = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                  AND (lease_until IS NULL OR lease_until < ? OR lease_owner = ?)
                """, owner, Timestamp.from(leaseUntil), tenantId,
                conversationId, Timestamp.from(now), owner) == 1;
    }

    public void release(
            long tenantId,
            long conversationId,
            String owner) {
        jdbcTemplate.update("""
                UPDATE ticket_conversation
                SET lease_owner = NULL, lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ? AND lease_owner = ?
                """, tenantId, conversationId, owner);
    }

    public long insertMessage(
            long tenantId,
            long conversationId,
            Long userId,
            ConversationRole role,
            ConversationMessageStatus status,
            String content,
            String provider,
            String modelName,
            int inputTokens,
            int outputTokens,
            long latencyMs) {
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ticket_conversation_message
                        (tenant_id, conversation_id, user_id, role, status,
                         content, provider, model_name, input_tokens,
                         output_tokens, latency_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, conversationId);
                if (userId == null) statement.setNull(3, java.sql.Types.BIGINT);
                else statement.setLong(3, userId);
                statement.setString(4, role.name());
                statement.setString(5, status.name());
                statement.setString(6, content);
                statement.setString(7, provider);
                statement.setString(8, modelName);
                statement.setInt(9, Math.max(0, inputTokens));
                statement.setInt(10, Math.max(0, outputTokens));
                statement.setLong(11, Math.max(0, latencyMs));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("conversation message ID was not generated");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    public ConversationMessage findMessage(
            long tenantId,
            long conversationId,
            long messageId) {
        List<ConversationMessage> messages = jdbcTemplate.query("""
                SELECT id, conversation_id, role, status, content,
                       provider, model_name, input_tokens, output_tokens,
                       latency_ms, created_at
                FROM ticket_conversation_message
                WHERE tenant_id = ? AND conversation_id = ? AND id = ?
                """, (resultSet, rowNumber) -> mapMessage(resultSet),
                tenantId, conversationId, messageId);
        return messages.isEmpty() ? null : messages.getFirst();
    }

    public List<ConversationMessage> findLatestMessages(
            long tenantId,
            long conversationId,
            int limit) {
        List<ConversationMessage> descending = jdbcTemplate.query("""
                SELECT id, conversation_id, role, status, content,
                       provider, model_name, input_tokens, output_tokens,
                       latency_ms, created_at
                FROM ticket_conversation_message
                WHERE tenant_id = ? AND conversation_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, (resultSet, rowNumber) -> mapMessage(resultSet),
                tenantId, conversationId, limit);
        return descending.reversed();
    }

    public List<ConversationMessage> findSentAfter(
            long tenantId,
            long conversationId,
            long afterMessageId) {
        return List.copyOf(jdbcTemplate.query("""
                SELECT id, conversation_id, role, status, content,
                       provider, model_name, input_tokens, output_tokens,
                       latency_ms, created_at
                FROM ticket_conversation_message
                WHERE tenant_id = ? AND conversation_id = ?
                  AND id > ? AND status = 'SENT'
                ORDER BY id
                """, (resultSet, rowNumber) -> mapMessage(resultSet),
                tenantId, conversationId, afterMessageId));
    }

    public void updateSummary(
            long tenantId,
            long conversationId,
            String summary,
            long throughMessageId) {
        int updated = jdbcTemplate.update("""
                UPDATE ticket_conversation
                SET summary = ?, summarized_through_message_id = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE tenant_id = ? AND id = ?
                  AND (summarized_through_message_id IS NULL
                       OR summarized_through_message_id < ?)
                """, summary, throughMessageId, tenantId,
                conversationId, throughMessageId);
        if (updated != 1) {
            throw new IllegalStateException("conversation summary was not updated");
        }
    }

    private ConversationMessage mapMessage(ResultSet resultSet)
            throws java.sql.SQLException {
        return new ConversationMessage(
                resultSet.getLong("id"),
                resultSet.getLong("conversation_id"),
                ConversationRole.valueOf(resultSet.getString("role")),
                ConversationMessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("content"),
                resultSet.getString("provider"),
                resultSet.getString("model_name"),
                resultSet.getInt("input_tokens"),
                resultSet.getInt("output_tokens"),
                resultSet.getLong("latency_ms"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private Long nullableLong(ResultSet resultSet, String column)
            throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }
}
