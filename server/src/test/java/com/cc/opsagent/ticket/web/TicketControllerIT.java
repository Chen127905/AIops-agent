package com.cc.opsagent.ticket.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketControllerIT {

    private static final String JWT_SECRET =
            "ticket-api-test-secret-with-at-least-thirty-two-characters";

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

    @Value("${local.server.port}")
    int port;

    @Test
    void createsListsAndGetsOnlyTheAuthenticatedTenantsTickets() {
        Identity owner = createIdentity("ticket-owner", "alice");
        Identity outsider = createIdentity("ticket-outsider", "bob");
        long foreignTicketId = insertTicket(
                outsider.tenantId(), outsider.userId(), "Foreign outage", "RESOLVED");
        RestClient client = client();
        String token = login(client, owner);
        jdbcTemplate.execute(
                "ALTER TABLE ticket AUTO_INCREMENT = 9007199254740992");

        ResponseEntity<Map> created = client.post()
                .uri("/api/tickets")
                .header("Authorization", bearer(token))
                .body(Map.of(
                        "title", "Checkout latency",
                        "description", "Checkout latency has exceeded the service objective.",
                        "affectedService", "checkout-service",
                        "category", "LATENCY",
                        "severity", "HIGH"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Map<?, ?> createdBody = created.getBody();
        assertThat(createdBody).isNotNull();
        assertThat(createdBody.get("id")).isInstanceOf(String.class);
        long ticketId = Long.parseLong((String) createdBody.get("id"));
        assertThat(ticketId).isGreaterThan(9_007_199_254_740_991L);
        assertThat(((Number) createdBody.get("tenantId")).longValue()).isEqualTo(owner.tenantId());
        assertThat(((Number) createdBody.get("reporterId")).longValue()).isEqualTo(owner.userId());
        assertThat(createdBody.get("status")).isEqualTo("OPEN");

        Map<?, ?> found = client.get()
                .uri("/api/tickets/{id}", ticketId)
                .header("Authorization", bearer(token))
                .retrieve()
                .body(Map.class);
        assertThat(found).isNotNull();
        assertThat(found.get("id")).isEqualTo(Long.toString(ticketId));

        Map<?, ?> page = client.get()
                .uri("/api/tickets?page=1&size=20")
                .header("Authorization", bearer(token))
                .retrieve()
                .body(Map.class);
        assertThat(page).isNotNull();
        assertThat(((Number) page.get("total")).longValue()).isEqualTo(1);
        assertThat((List<?>) page.get("items")).hasSize(1);

        HttpStatusCode foreignStatus = client.get()
                .uri("/api/tickets/{id}", foreignTicketId)
                .header("Authorization", bearer(token))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(foreignStatus.value()).isEqualTo(404);
    }

    @Test
    void ignoresTenantAndReporterFieldsFromJson() {
        Identity caller = createIdentity("spoof-caller", "carol");
        Identity victim = createIdentity("spoof-victim", "dave");
        RestClient client = client();

        Map<?, ?> created = client.post()
                .uri("/api/tickets")
                .header("Authorization", bearer(login(client, caller)))
                .body(Map.of(
                        "tenantId", victim.tenantId(),
                        "reporterId", victim.userId(),
                        "title", "Queue backlog",
                        "description", "The order queue backlog is growing continuously."))
                .retrieve()
                .body(Map.class);

        assertThat(created).isNotNull();
        assertThat(((Number) created.get("tenantId")).longValue()).isEqualTo(caller.tenantId());
        assertThat(((Number) created.get("reporterId")).longValue()).isEqualTo(caller.userId());
    }

    @Test
    void rejectsInvalidTicketFields() {
        Identity caller = createIdentity("validation-caller", "erin");
        RestClient client = client();

        HttpStatusCode status = client.post()
                .uri("/api/tickets")
                .header("Authorization", bearer(login(client, caller)))
                .body(Map.of("title", "bad", "description", "too short"))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(400);
    }

    @Test
    void cancelsOnlyANonTerminalTicketUsingAConditionalTransition() {
        Identity caller = createIdentity("cancel-caller", "frank");
        long openTicketId = insertTicket(
                caller.tenantId(), caller.userId(), "Open incident", "OPEN");
        long resolvedTicketId = insertTicket(
                caller.tenantId(), caller.userId(), "Resolved incident", "RESOLVED");
        RestClient client = client();
        String token = login(client, caller);

        HttpStatusCode cancelled = client.post()
                .uri("/api/tickets/{id}/cancel", openTicketId)
                .header("Authorization", bearer(token))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(cancelled.value()).isEqualTo(204);
        assertThat(ticketStatus(openTicketId)).isEqualTo("CANCELLED");

        HttpStatusCode terminal = client.post()
                .uri("/api/tickets/{id}/cancel", resolvedTicketId)
                .header("Authorization", bearer(token))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(terminal.value()).isEqualTo(409);
        assertThat(ticketStatus(resolvedTicketId)).isEqualTo("RESOLVED");
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private String login(RestClient client, Identity identity) {
        Map<?, ?> body = client.post()
                .uri("/api/auth/login")
                .body(Map.of(
                        "tenantCode", identity.tenantCode(),
                        "username", identity.username(),
                        "password", "correct-password"))
                .retrieve()
                .body(Map.class);
        assertThat(body).isNotNull();
        return String.valueOf(body.get("accessToken"));
    }

    private Identity createIdentity(String tenantCode, String username) {
        jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES (?, ?)", tenantCode, tenantCode);
        long tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, tenantCode);
        jdbcTemplate.update("""
                        INSERT INTO user_account
                            (tenant_id, username, password_hash, display_name, role)
                        VALUES (?, ?, ?, ?, 'OPERATOR')
                        """,
                tenantId,
                username,
                new BCryptPasswordEncoder().encode("correct-password"),
                username);
        long userId = jdbcTemplate.queryForObject("""
                SELECT id FROM user_account
                WHERE tenant_id = ? AND username = ?
                """, Long.class, tenantId, username);
        return new Identity(tenantId, userId, tenantCode, username);
    }

    private long insertTicket(long tenantId, long reporterId, String title, String status) {
        jdbcTemplate.update("""
                        INSERT INTO ticket
                            (tenant_id, reporter_id, title, description, status)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                tenantId, reporterId, title, title + " requires investigation.", status);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM ticket
                WHERE tenant_id = ? AND title = ?
                """, Long.class, tenantId, title);
    }

    private String ticketStatus(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(long tenantId, long userId, String tenantCode, String username) {
    }
}
