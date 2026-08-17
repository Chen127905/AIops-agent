package com.cc.opsagent.knowledge.infrastructure;

import com.cc.opsagent.knowledge.domain.KnowledgeChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class KnowledgeChunkRepository {

    private static final TypeReference<Map<String, String>> METADATA_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeChunkRepository(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("vectorDataSource") DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.objectMapper = new ObjectMapper();
    }

    public void stage(List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("at least one chunk is required");
        }
        KnowledgeChunk first = chunks.getFirst();
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.update("""
                    DELETE FROM knowledge_chunk
                    WHERE tenant_id = ? AND document_id = ? AND document_version = ?
                    """, first.tenantId(), first.documentId(), first.documentVersion());
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO knowledge_chunk
                            (tenant_id, document_id, document_version, chunk_index,
                             source, content, metadata, embedding, published)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS vector), false)
                        """);
                for (KnowledgeChunk chunk : chunks) {
                    statement.setLong(1, chunk.tenantId());
                    statement.setLong(2, chunk.documentId());
                    statement.setInt(3, chunk.documentVersion());
                    statement.setInt(4, chunk.chunkIndex());
                    statement.setString(5, chunk.source());
                    statement.setString(6, chunk.content());
                    statement.setString(7, writeMetadata(chunk.metadata()));
                    statement.setString(8, vectorLiteral(chunk.embedding()));
                    statement.addBatch();
                }
                statement.executeBatch();
                return null;
            });
        });
    }

    public void publish(long tenantId, long documentId, int version, int chunkCount) {
        transactionTemplate.executeWithoutResult(ignored -> {
            jdbcTemplate.update("""
                    UPDATE knowledge_chunk
                    SET published = false, published_at = NULL
                    WHERE tenant_id = ? AND document_id = ? AND published = true
                    """, tenantId, documentId);
            int updated = jdbcTemplate.update("""
                    UPDATE knowledge_chunk
                    SET published = true, published_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND document_id = ?
                      AND document_version = ? AND published = false
                    """, tenantId, documentId, version);
            if (updated != chunkCount) {
                throw new IllegalStateException(
                        "staged chunk count changed before publication");
            }
        });
    }

    public void discard(long tenantId, long documentId, int version) {
        jdbcTemplate.update("""
                DELETE FROM knowledge_chunk
                WHERE tenant_id = ? AND document_id = ?
                  AND document_version = ? AND published = false
                """, tenantId, documentId, version);
    }

    public List<KnowledgeChunk> findActiveByDocument(long tenantId, long documentId) {
        return jdbcTemplate.query("""
                SELECT tenant_id, document_id, document_version, chunk_index,
                       source, content, metadata::text, embedding::text, published
                FROM knowledge_chunk
                WHERE tenant_id = ? AND document_id = ? AND published = true
                ORDER BY chunk_index
                """, (resultSet, rowNumber) -> new KnowledgeChunk(
                resultSet.getLong("tenant_id"),
                resultSet.getLong("document_id"),
                resultSet.getInt("document_version"),
                resultSet.getInt("chunk_index"),
                resultSet.getString("source"),
                resultSet.getString("content"),
                readMetadata(resultSet.getString("metadata")),
                parseVector(resultSet.getString("embedding")),
                resultSet.getBoolean("published")), tenantId, documentId);
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata cannot be serialized", exception);
        }
    }

    private Map<String, String> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored metadata is invalid", exception);
        }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(vector[index]);
        }
        return value.append(']').toString();
    }

    private float[] parseVector(String literal) {
        String[] values = literal.substring(1, literal.length() - 1).split(",");
        float[] vector = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            vector[index] = Float.parseFloat(values[index]);
        }
        return vector;
    }
}
