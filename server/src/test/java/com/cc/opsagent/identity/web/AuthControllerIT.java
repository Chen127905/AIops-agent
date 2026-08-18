package com.cc.opsagent.identity.web;

import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.observability.AgentMetrics;
import com.cc.opsagent.ticket.application.TicketRepository;
import com.cc.opsagent.ticket.domain.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthControllerIT.SecurityProbeConfiguration.class)
class AuthControllerIT {

    private static final String JWT_SECRET =
            "test-only-jwt-secret-with-at-least-thirty-two-characters";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ops_agent")
            .withUsername("ops_agent")
            .withPassword("test-password");

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.business.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.business.username", MYSQL::getUsername);
        registry.add("app.datasource.business.password", MYSQL::getPassword);
        registry.add("app.datasource.business.driver-class-name", MYSQL::getDriverClassName);
        registry.add("app.security.jwt.secret", () -> JWT_SECRET);
        registry.add("app.security.jwt.ttl", () -> "PT2H");
    }

    @Autowired
    @Qualifier("businessJdbcTemplate")
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    AgentMetrics agentMetrics;

    @Value("${local.server.port}")
    int port;

    @Test
    void authenticatesAndIgnoresRequestTenantSpoofing() {
        long tenantId = insertTenant("auth-tenant");
        long userId = insertUser(tenantId, "alice", "correct-password", "OPERATOR");
        RestClient client = RestClient.create("http://localhost:" + port);

        Map<?, ?> login = client.post()
                .uri("/api/auth/login")
                .body(Map.of(
                        "tenantCode", "auth-tenant",
                        "username", "alice",
                        "password", "correct-password"))
                .retrieve()
                .body(Map.class);

        assertThat(login).isNotNull();
        String accessToken = String.valueOf(login.get("accessToken"));
        assertThat(accessToken).isNotBlank();

        Map<?, ?> me = client.get()
                .uri("/api/auth/me")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Tenant-Id", "999999")
                .retrieve()
                .body(Map.class);

        assertThat(me).isNotNull();
        assertThat(((Number) me.get("tenantId")).longValue()).isEqualTo(tenantId);
        assertThat(((Number) me.get("userId")).longValue()).isEqualTo(userId);
        assertThat(me.get("username")).isEqualTo("alice");
    }

    @Test
    void rejectsMissingBearerTokenAsUnauthorized() {
        RestClient client = RestClient.create("http://localhost:" + port);

        HttpStatusCode status = client.get()
                .uri("/api/auth/me")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(401);
    }

    @Test
    void rejectsInvalidPasswordWithoutRevealingWhichCredentialFailed() {
        long tenantId = insertTenant("invalid-password-tenant");
        insertUser(tenantId, "bob", "correct-password", "OPERATOR");
        RestClient client = RestClient.create("http://localhost:" + port);

        HttpStatusCode status = client.post()
                .uri("/api/auth/login")
                .body(Map.of(
                        "tenantCode", "invalid-password-tenant",
                        "username", "bob",
                        "password", "wrong-password"))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(401);
    }

    @Test
    void rejectsMalformedBearerToken() {
        RestClient client = RestClient.create("http://localhost:" + port);

        HttpStatusCode status = client.get()
                .uri("/api/auth/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(401);
    }

    @Test
    void doesNotExposeGeneratedFallbackUser() {
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class))
                .isEmpty();
    }

    @Test
    void rejectsOperatorFromAdminResource() {
        long tenantId = insertTenant("role-tenant");
        insertUser(tenantId, "operator", "correct-password", "OPERATOR");
        RestClient client = RestClient.create("http://localhost:" + port);
        String token = loginToken(
                client, "role-tenant", "operator", "correct-password");

        HttpStatusCode status = client.get()
                .uri("/test/security/admin")
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(403);
    }

    @Test
    void exposesCorrelatedHealthButRestrictsPrometheusMetricsToAdmins() {
        RestClient client = RestClient.create("http://localhost:" + port);

        HttpProbe health = client.get()
                .uri("/actuator/health")
                .header("X-Trace-Id", "health-trace-123")
                .exchange((request, response) -> new HttpProbe(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst("X-Trace-Id"), null));
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.traceId()).isEqualTo("health-trace-123");

        HttpStatusCode anonymousMetrics = client.get()
                .uri("/actuator/prometheus")
                .exchange((request, response) -> response.getStatusCode());
        assertThat(anonymousMetrics.value()).isEqualTo(401);

        long operatorTenant = insertTenant("metrics-operator-tenant");
        insertUser(operatorTenant, "metrics-operator", "correct-password", "OPERATOR");
        String operatorToken = loginToken(
                client, "metrics-operator-tenant", "metrics-operator", "correct-password");
        HttpStatusCode operatorMetrics = client.get()
                .uri("/actuator/prometheus")
                .header("Authorization", "Bearer " + operatorToken)
                .exchange((request, response) -> response.getStatusCode());
        assertThat(operatorMetrics.value()).isEqualTo(403);

        long adminTenant = insertTenant("metrics-admin-tenant");
        insertUser(adminTenant, "metrics-admin", "correct-password", "ADMIN");
        String adminToken = loginToken(
                client, "metrics-admin-tenant", "metrics-admin", "correct-password");
        agentMetrics.recordExecutorRejection();
        HttpProbe metrics = client.get()
                .uri("/actuator/prometheus")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Trace-Id", "metrics-trace-456")
                .exchange((request, response) -> new HttpProbe(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst("X-Trace-Id"),
                        new String(response.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.traceId()).isEqualTo("metrics-trace-456");
        assertThat(metrics.body()).contains("ops_agent_executor_rejections_total");
    }

    @Test
    void doesNotExposeAnotherTenantsTicket() {
        long requestingTenantId = insertTenant("requesting-tenant");
        insertUser(
                requestingTenantId, "requester", "correct-password", "OPERATOR");
        long owningTenantId = insertTenant("owning-tenant");
        long ownerId = insertUser(
                owningTenantId, "owner", "correct-password", "OPERATOR");
        long foreignTicketId = insertTicket(owningTenantId, ownerId, "foreign-ticket");
        RestClient client = RestClient.create("http://localhost:" + port);
        String token = loginToken(
                client, "requesting-tenant", "requester", "correct-password");

        HttpStatusCode status = client.get()
                .uri("/test/security/tickets/{ticketId}", foreignTicketId)
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(404);
    }

    private String loginToken(
            RestClient client,
            String tenantCode,
            String username,
            String password) {
        Map<?, ?> login = client.post()
                .uri("/api/auth/login")
                .body(Map.of(
                        "tenantCode", tenantCode,
                        "username", username,
                        "password", password))
                .retrieve()
                .body(Map.class);
        assertThat(login).isNotNull();
        return String.valueOf(login.get("accessToken"));
    }

    private long insertTenant(String code) {
        jdbcTemplate.update("INSERT INTO tenant (code, name) VALUES (?, ?)", code, code);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, code);
    }

    private long insertUser(
            long tenantId,
            String username,
            String rawPassword,
            String role) {
        String passwordHash = new BCryptPasswordEncoder().encode(rawPassword);
        jdbcTemplate.update("""
                        INSERT INTO user_account
                            (tenant_id, username, password_hash, display_name, role)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                tenantId, username, passwordHash, username, role);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM user_account
                WHERE tenant_id = ? AND username = ?
                """, Long.class, tenantId, username);
    }

    private long insertTicket(long tenantId, long reporterId, String title) {
        jdbcTemplate.update("""
                        INSERT INTO ticket
                            (tenant_id, reporter_id, title, description)
                        VALUES (?, ?, ?, ?)
                        """,
                tenantId, reporterId, title, title);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM ticket
                WHERE tenant_id = ? AND title = ?
                """, Long.class, tenantId, title);
    }

    private record HttpProbe(int status, String traceId, String body) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityProbeConfiguration {

        @Bean
        SecurityProbeController securityProbeController(TicketRepository ticketRepository) {
            return new SecurityProbeController(ticketRepository);
        }
    }

    @RestController
    static class SecurityProbeController {

        private final TicketRepository ticketRepository;

        SecurityProbeController(TicketRepository ticketRepository) {
            this.ticketRepository = ticketRepository;
        }

        @GetMapping("/test/security/admin")
        @PreAuthorize("hasRole('ADMIN')")
        Map<String, Boolean> adminResource() {
            return Map.of("allowed", true);
        }

        @GetMapping("/test/security/tickets/{ticketId}")
        Map<String, Long> ticket(@PathVariable long ticketId) {
            Ticket ticket = ticketRepository.findByTenantIdAndId(
                    TenantContext.requireTenantId(), ticketId);
            if (ticket == null) {
                throw new ResponseStatusException(NOT_FOUND);
            }
            return Map.of("id", ticket.getId());
        }
    }
}
