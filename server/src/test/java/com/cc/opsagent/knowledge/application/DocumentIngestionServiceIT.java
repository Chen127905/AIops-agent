package com.cc.opsagent.knowledge.application;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.knowledge.domain.KnowledgeChunk;
import com.cc.opsagent.knowledge.infrastructure.KnowledgeChunkRepository;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DocumentIngestionServiceIT.EmbeddingTestConfiguration.class)
class DocumentIngestionServiceIT {

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
    DocumentIngestionService service;

    @Autowired
    KnowledgeChunkRepository chunkRepository;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    JdbcTemplate businessJdbcTemplate;

    @Autowired
    @Qualifier("vectorJdbcTemplate")
    JdbcTemplate vectorJdbcTemplate;

    @BeforeEach
    void clearDatabases() {
        vectorJdbcTemplate.update("DELETE FROM knowledge_chunk");
        businessJdbcTemplate.update("DELETE FROM knowledge_document_version");
        businessJdbcTemplate.update("DELETE FROM knowledge_document");
        businessJdbcTemplate.update("DELETE FROM user_account");
        businessJdbcTemplate.update("DELETE FROM tenant");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replacesActiveVersionOnlyAfterTheNewChunksAreComplete() {
        long tenantId = insertTenant("version-tenant");
        authenticate(tenantId);

        long documentId = service.ingest(newDocument(
                "redis-runbook", "runbooks/redis.md", "v1 content"));
        service.ingest(existingDocument(
                documentId, "redis-runbook", "runbooks/redis.md", "v2 content"));

        assertThat(activeVersion(tenantId, documentId)).isEqualTo(2);
        assertThat(chunkRepository.findActiveByDocument(tenantId, documentId))
                .extracting(KnowledgeChunk::documentVersion, KnowledgeChunk::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "v2 content"));
        assertThat(vectorJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk
                WHERE tenant_id = ? AND document_id = ? AND published = true
                """, Integer.class, tenantId, documentId)).isEqualTo(1);
    }

    @Test
    void embeddingFailureLeavesThePreviousVersionPublished() {
        long tenantId = insertTenant("failure-tenant");
        authenticate(tenantId);
        long documentId = service.ingest(newDocument(
                "database-runbook", "runbooks/database.md", "stable content"));

        assertThatThrownBy(() -> service.ingest(existingDocument(
                documentId,
                "database-runbook",
                "runbooks/database.md",
                "[FAIL_EMBEDDING] replacement")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding");

        assertThat(activeVersion(tenantId, documentId)).isEqualTo(1);
        assertThat(chunkRepository.findActiveByDocument(tenantId, documentId))
                .extracting(KnowledgeChunk::content)
                .containsExactly("stable content");
        assertThat(versionCount(tenantId, documentId)).isEqualTo(1);
    }

    @Test
    void keepsDocumentsAndChunksIsolatedByTenant() {
        long tenantA = insertTenant("knowledge-a");
        long tenantB = insertTenant("knowledge-b");
        authenticate(tenantA);
        long documentA = service.ingest(newDocument(
                "shared-name", "runbooks/a.md", "tenant A procedure"));
        authenticate(tenantB);
        long documentB = service.ingest(newDocument(
                "shared-name", "runbooks/b.md", "tenant B procedure"));

        assertThat(chunkRepository.findActiveByDocument(tenantA, documentA))
                .extracting(KnowledgeChunk::content)
                .containsExactly("tenant A procedure");
        assertThat(chunkRepository.findActiveByDocument(tenantA, documentB)).isEmpty();
        assertThat(chunkRepository.findActiveByDocument(tenantB, documentB))
                .extracting(KnowledgeChunk::content)
                .containsExactly("tenant B procedure");
    }

    @Test
    void rejectsBlankAndOversizedUtf8Documents() {
        long tenantId = insertTenant("validation-tenant");
        authenticate(tenantId);

        assertThatThrownBy(() -> service.ingest(newDocument(
                "blank", "runbooks/blank.md", " \r\n\t")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> service.ingest(newDocument(
                "large", "runbooks/large.md", "x".repeat(2 * 1024 * 1024 + 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 MiB");
    }

    @Test
    void normalizesLineEndingsAndSplitsLongMarkdownIntoBoundedChunks() {
        long tenantId = insertTenant("chunk-tenant");
        authenticate(tenantId);
        String content = "# First\r\n" + "a".repeat(1500)
                + "\r\n## Second\r\n" + "b".repeat(1500);

        long documentId = service.ingest(newDocument(
                "chunked", "runbooks/chunked.md", content));

        List<KnowledgeChunk> chunks =
                chunkRepository.findActiveByDocument(tenantId, documentId);
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks)
                .allSatisfy(chunk -> {
                    assertThat(chunk.content()).doesNotContain("\r");
                    assertThat(chunk.content().length()).isLessThanOrEqualTo(1200);
                });
    }

    private IngestDocumentCommand newDocument(
            String name,
            String source,
            String content) {
        return new IngestDocumentCommand(
                null, name, source, "text/markdown", content, Map.of("team", "ops"));
    }

    private IngestDocumentCommand existingDocument(
            long documentId,
            String name,
            String source,
            String content) {
        return new IngestDocumentCommand(
                documentId,
                name,
                source,
                "text/markdown",
                content,
                Map.of("team", "ops"));
    }

    private long insertTenant(String code) {
        businessJdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES (?, ?)", code, code);
        return businessJdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, code);
    }

    private void authenticate(long tenantId) {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, tenantId * 10, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private int activeVersion(long tenantId, long documentId) {
        return businessJdbcTemplate.queryForObject("""
                SELECT active_version FROM knowledge_document
                WHERE tenant_id = ? AND id = ?
                """, Integer.class, tenantId, documentId);
    }

    private int versionCount(long tenantId, long documentId) {
        return businessJdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_document_version
                WHERE tenant_id = ? AND document_id = ?
                """, Integer.class, tenantId, documentId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmbeddingTestConfiguration {

        @Bean
        @Primary
        EmbeddingGateway deterministicEmbeddingGateway() {
            return texts -> texts.stream()
                    .map(text -> {
                        if (text.contains("[FAIL_EMBEDDING]")) {
                            throw new IllegalStateException("embedding provider failed");
                        }
                        float[] vector = new float[1024];
                        vector[0] = text.length();
                        vector[1] = Math.floorMod(text.hashCode(), 997);
                        vector[2] = 1.0f;
                        return vector;
                    })
                    .toList();
        }
    }
}
