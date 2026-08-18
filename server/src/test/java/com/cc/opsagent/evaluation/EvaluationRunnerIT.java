package com.cc.opsagent.evaluation;

import com.cc.opsagent.evaluation.application.EvaluationRunRequest;
import com.cc.opsagent.evaluation.application.EvaluationRunner;
import com.cc.opsagent.evaluation.domain.EvaluationMode;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.model.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.agent.recovery.enabled=false")
class EvaluationRunnerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ops_agent")
            .withUsername("ops_agent")
            .withPassword("test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.business.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.business.username", MYSQL::getUsername);
        registry.add("app.datasource.business.password", MYSQL::getPassword);
        registry.add("app.datasource.business.driver-class-name",
                MYSQL::getDriverClassName);
    }

    @Autowired EvaluationRunner runner;
    @Autowired @Qualifier("businessJdbcTemplate") JdbcTemplate jdbc;

    long tenantId;
    long userId;

    @BeforeEach
    void authenticate() {
        jdbc.update("INSERT INTO tenant (code, name) VALUES ('eval', 'Evaluation')");
        tenantId = jdbc.queryForObject(
                "SELECT id FROM tenant WHERE code = 'eval'", Long.class);
        jdbc.update("""
                INSERT INTO user_account
                    (tenant_id, username, display_name, password_hash, role)
                VALUES (?, 'admin@example.com', 'Evaluation Admin', 'hash', 'ADMIN')
                """, tenantId);
        userId = jdbc.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ?",
                Long.class, tenantId);
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, userId, "admin@example.com", Set.of("ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void persistsReproducibleMockRunAndTenantScopedResults() {
        var summary = runner.run(new EvaluationRunRequest(
                EvaluationMode.MOCK, ModelProvider.QWEN,
                "deterministic-mock-v1", "prompt-v1", "knowledge-v1",
                Set.of("classification-redis-timeout", "attack-db-cross-tenant")));

        assertThat(summary.status()).isEqualTo("COMPLETED");
        assertThat(summary.metrics().totalCases()).isEqualTo(2);
        assertThat(summary.metrics().passedCases()).isEqualTo(2);
        assertThat(summary.metrics().leakageCount()).isZero();
        assertThat(runner.get(summary.runId()).metrics().passRate())
                .isEqualByComparingTo("1.0000");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM evaluation_case_result
                WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, tenantId, summary.runId())).isEqualTo(2);

        var firstBaseline = runner.run(new EvaluationRunRequest(
                EvaluationMode.MOCK, ModelProvider.QWEN,
                "deterministic-mock-v1", "prompt-v1", "knowledge-v1", Set.of()));
        var secondBaseline = runner.run(new EvaluationRunRequest(
                EvaluationMode.MOCK, ModelProvider.QWEN,
                "deterministic-mock-v1", "prompt-v1", "knowledge-v1", Set.of()));

        assertThat(firstBaseline.metrics().totalCases()).isEqualTo(30);
        assertThat(secondBaseline.metrics()).isEqualTo(firstBaseline.metrics());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM security_audit_log
                WHERE tenant_id = ? AND event_type = 'TOOL_EXECUTION'
                """, Integer.class, tenantId)).isGreaterThanOrEqualTo(248);
    }
}
