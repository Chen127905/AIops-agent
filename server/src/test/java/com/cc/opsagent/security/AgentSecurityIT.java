package com.cc.opsagent.security;

import com.cc.opsagent.audit.AuditService;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.tool.application.ToolPolicyService;
import com.cc.opsagent.tool.domain.ToolInvocationRequest;
import com.cc.opsagent.ticket.application.TicketNotFoundException;
import com.cc.opsagent.ticket.application.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.agent.recovery.enabled=false")
class AgentSecurityIT {

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

    @Autowired ToolPolicyService policy;
    @Autowired AuditService audit;
    @Autowired TicketService tickets;
    @Autowired @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate;
    @Value("${local.server.port}") int port;

    long tenantId;
    long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM security_audit_log");
        jdbcTemplate.update("DELETE FROM ticket");
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM tenant");
        jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES ('security-a', 'Security A')");
        tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = 'security-a'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO user_account
                    (tenant_id, username, display_name, password_hash, role)
                VALUES (?, 'security@example.com', 'Security User', 'hash', 'OPERATOR')
                """, tenantId);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUnknownAndCrossTenantToolsAndPersistsBothDecisions() {
        authenticate();

        assertThat(policy.evaluate(request(tenantId, "executeShell")).allowed())
                .isFalse();
        assertThat(policy.evaluate(request(tenantId + 1, "getServiceHealth")).allowed())
                .isFalse();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM security_audit_log
                WHERE tenant_id = ? AND user_id = ?
                  AND event_type = 'TOOL_POLICY_REJECTED'
                  AND outcome = 'REJECTED'
                """, Integer.class, tenantId, userId)).isEqualTo(2);
    }

    @Test
    void redactsAuditDetailsBeforeTheyReachTheDatabase() {
        authenticate();

        audit.recordAuthenticated(
                "SECURITY_TEST", "FAILED", "TEST", "1",
                Map.of("error", "Authorization: Bearer secret-token-value"));

        String details = jdbcTemplate.queryForObject("""
                SELECT CAST(details AS CHAR) FROM security_audit_log
                WHERE event_type = 'SECURITY_TEST'
                """, String.class);
        assertThat(details)
                .contains("[REDACTED]")
                .doesNotContain("secret-token-value");
    }

    @Test
    void invalidBearerTokenReturnsUnauthorizedAndIsAudited() {
        var status = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/api/tickets/999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token-value")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM security_audit_log
                WHERE event_type = 'AUTHENTICATION_FAILED'
                  AND outcome = 'REJECTED'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void crossTenantTicketProbeReturnsNotFoundAndIsAuditedWithoutDisclosure() {
        authenticate();
        long otherTenant = insertOtherTenant();
        long otherUser = insertOtherUser(otherTenant);
        long otherTicket = insertOtherTicket(otherTenant, otherUser);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tickets.get(otherTicket))
                .isInstanceOf(TicketNotFoundException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM security_audit_log
                WHERE tenant_id = ?
                  AND event_type = 'TENANT_RESOURCE_ACCESS_REJECTED'
                  AND resource_type = 'TICKET'
                  AND resource_id = ?
                """, Integer.class, tenantId, Long.toString(otherTicket)))
                .isEqualTo(1);
    }

    private ToolInvocationRequest request(long requestTenant, String toolName) {
        return new ToolInvocationRequest(
                requestTenant, 100, "redis-timeout", toolName,
                Map.of("service", "order-service"), null, "idempotency-1");
    }

    private void authenticate() {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, userId, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of()));
    }

    private long insertOtherTenant() {
        jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES ('security-b', 'Security B')");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = 'security-b'", Long.class);
    }

    private long insertOtherUser(long otherTenant) {
        jdbcTemplate.update("""
                INSERT INTO user_account
                    (tenant_id, username, display_name, password_hash, role)
                VALUES (?, 'other@example.com', 'Other User', 'hash', 'OPERATOR')
                """, otherTenant);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ?",
                Long.class, otherTenant);
    }

    private long insertOtherTicket(long otherTenant, long otherUser) {
        jdbcTemplate.update("""
                INSERT INTO ticket
                    (tenant_id, reporter_id, title, description,
                     affected_service, category, severity, status)
                VALUES (?, ?, 'Other incident', 'Another tenant incident',
                        'other-service', 'other', 'LOW', 'OPEN')
                """, otherTenant, otherUser);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ticket WHERE tenant_id = ?",
                Long.class, otherTenant);
    }
}
