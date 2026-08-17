package com.cc.opsagent.knowledge.infrastructure;

import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.knowledge.application.EmbeddingGateway;
import com.cc.opsagent.knowledge.application.EvidenceChunk;
import com.cc.opsagent.knowledge.application.KnowledgeQuery;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(
        prefix = "app.datasource.vector",
        name = "enabled",
        havingValue = "true")
public class PgVectorKnowledgeRetriever implements KnowledgeRetriever {

    private static final int EMBEDDING_DIMENSIONS = 1024;
    private static final TypeReference<Map<String, String>> METADATA_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingGateway embeddingGateway;
    private final ObjectMapper objectMapper;

    public PgVectorKnowledgeRetriever(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingGateway embeddingGateway) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingGateway = embeddingGateway;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<EvidenceChunk> retrieve(KnowledgeQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("knowledge query is required");
        }
        long tenantId = TenantContext.requireTenantId();
        List<float[]> embeddings = embeddingGateway.embed(List.of(query.query()));
        if (embeddings.size() != 1
                || embeddings.getFirst() == null
                || embeddings.getFirst().length != EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException(
                    "embedding provider must return one 1024-dimensional query vector");
        }
        String vector = vectorLiteral(embeddings.getFirst());
        return List.copyOf(jdbcTemplate.query("""
                SELECT chunk.tenant_id,
                       chunk.document_id,
                       chunk.document_version,
                       chunk.chunk_index,
                       chunk.source,
                       chunk.content,
                       chunk.metadata::text AS metadata_json,
                       1 - (chunk.embedding <=> CAST(? AS vector)) AS score
                FROM knowledge_chunk chunk
                WHERE chunk.tenant_id = ?
                  AND chunk.published = true
                  AND chunk.document_version = (
                      SELECT MAX(active.document_version)
                      FROM knowledge_chunk active
                      WHERE active.tenant_id = chunk.tenant_id
                        AND active.document_id = chunk.document_id
                        AND active.published = true
                  )
                ORDER BY chunk.embedding <=> CAST(? AS vector),
                         chunk.document_id,
                         chunk.chunk_index
                LIMIT ?
                """, (resultSet, rowNumber) -> {
            long documentId = resultSet.getLong("document_id");
            int version = resultSet.getInt("document_version");
            int chunkIndex = resultSet.getInt("chunk_index");
            return new EvidenceChunk(
                    resultSet.getLong("tenant_id"),
                    documentId,
                    version,
                    chunkIndex,
                    resultSet.getString("source"),
                    resultSet.getString("content"),
                    readMetadata(resultSet.getString("metadata_json")),
                    resultSet.getDouble("score"),
                    citationId(tenantId, documentId, version, chunkIndex));
        }, vector, tenantId, vector, query.topK()));
    }

    private Map<String, String> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored knowledge metadata is invalid", exception);
        }
    }

    private String citationId(
            long tenantId,
            long documentId,
            int version,
            int chunkIndex) {
        return "tenant:%d:doc:%d:v%d:chunk:%d".formatted(
                tenantId, documentId, version, chunkIndex);
    }

    private String vectorLiteral(float[] embedding) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(embedding[index]);
        }
        return value.append(']').toString();
    }
}
