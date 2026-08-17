package com.cc.opsagent.knowledge.infrastructure;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.knowledge.application.EmbeddingGateway;
import com.cc.opsagent.knowledge.application.EvidenceChunk;
import com.cc.opsagent.knowledge.application.KnowledgeQuery;
import com.cc.opsagent.knowledge.application.KnowledgeRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PgVectorKnowledgeRetrieverIT.EmbeddingTestConfiguration.class)
class PgVectorKnowledgeRetrieverIT {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ops_agent")
            .withUsername("ops_agent")
            .withPassword("test-password");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
            .withDatabaseName("ops_agent_vector")
            .withUsername("ops_agent")
            .withPassword("test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.business.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.business.username", MYSQL::getUsername);
        registry.add("app.datasource.business.password", MYSQL::getPassword);
        registry.add("app.datasource.business.driver-class-name", MYSQL::getDriverClassName);
        registry.add("app.datasource.vector.enabled", () -> true);
        registry.add("app.datasource.vector.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.vector.username", POSTGRES::getUsername);
        registry.add("app.datasource.vector.password", POSTGRES::getPassword);
        registry.add("app.datasource.vector.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    KnowledgeRetriever retriever;

    @Autowired
    @Qualifier("vectorJdbcTemplate")
    JdbcTemplate vectorJdbcTemplate;

    @BeforeEach
    void clearChunks() {
        vectorJdbcTemplate.update("DELETE FROM knowledge_chunk");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void neverReturnsAnotherTenantsNearestChunk() {
        seedChunk(11, 101, 1, 0, "runbooks/tenant-a.md",
                "redis timeout runbook", vector(0.9f, 0.1f), true);
        seedChunk(22, 202, 1, 0, "runbooks/tenant-b.md",
                "secret redis procedure", vector(1.0f, 0.0f), true);
        authenticate(11);

        List<EvidenceChunk> evidence = retriever.retrieve(
                new KnowledgeQuery("redis timeout", 5));

        assertThat(evidence)
                .extracting(EvidenceChunk::tenantId)
                .containsOnly(11L);
        assertThat(evidence)
                .extracting(EvidenceChunk::content)
                .containsExactly("redis timeout runbook");
    }

    @Test
    void returnsOnlyTheLatestPublishedVersionWithStableCitations() {
        seedChunk(11, 101, 1, 0, "runbooks/redis.md",
                "obsolete procedure", vector(1.0f, 0.0f), true);
        seedChunk(11, 101, 2, 3, "runbooks/redis.md",
                "current procedure", vector(0.8f, 0.2f), true);
        seedChunk(11, 303, 1, 0, "runbooks/draft.md",
                "unpublished exact match", vector(1.0f, 0.0f), false);
        authenticate(11);

        List<EvidenceChunk> evidence = retriever.retrieve(
                new KnowledgeQuery("redis timeout", 5));

        assertThat(evidence)
                .extracting(EvidenceChunk::content)
                .containsExactly("current procedure");
        assertThat(evidence.getFirst().citationId())
                .isEqualTo("tenant:11:doc:101:v2:chunk:3");
        assertThat(evidence.getFirst().source()).isEqualTo("runbooks/redis.md");
    }

    @Test
    void ordersByCosineSimilarityAndHonorsTopK() {
        seedChunk(11, 101, 1, 0, "runbooks/a.md",
                "nearest", vector(1.0f, 0.0f), true);
        seedChunk(11, 202, 1, 0, "runbooks/b.md",
                "second", vector(0.8f, 0.2f), true);
        seedChunk(11, 303, 1, 0, "runbooks/c.md",
                "third", vector(0.0f, 1.0f), true);
        authenticate(11);

        assertThat(retriever.retrieve(new KnowledgeQuery("redis timeout", 2)))
                .extracting(EvidenceChunk::content)
                .containsExactly("nearest", "second");
    }

    @Test
    void rejectsBlankQueriesAndTopKOutsideOneToTwenty() {
        assertThatThrownBy(() -> new KnowledgeQuery(" ", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> new KnowledgeQuery("redis", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
        assertThatThrownBy(() -> new KnowledgeQuery("redis", 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    private void seedChunk(
            long tenantId,
            long documentId,
            int version,
            int chunkIndex,
            String source,
            String content,
            float[] embedding,
            boolean published) {
        vectorJdbcTemplate.update("""
                INSERT INTO knowledge_chunk
                    (tenant_id, document_id, document_version, chunk_index,
                     source, content, metadata, embedding, published, published_at)
                VALUES (?, ?, ?, ?, ?, ?, '{"team":"ops"}'::jsonb,
                        CAST(? AS vector), ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, tenantId, documentId, version, chunkIndex, source, content,
                vectorLiteral(embedding), published, published);
    }

    private float[] vector(float first, float second) {
        float[] vector = new float[1024];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    private void authenticate(long tenantId) {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, tenantId * 10, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmbeddingTestConfiguration {

        @Bean
        @Primary
        EmbeddingGateway deterministicEmbeddingGateway() {
            return texts -> texts.stream()
                    .map(ignored -> {
                        float[] vector = new float[1024];
                        vector[0] = 1.0f;
                        return vector;
                    })
                    .toList();
        }
    }
}
