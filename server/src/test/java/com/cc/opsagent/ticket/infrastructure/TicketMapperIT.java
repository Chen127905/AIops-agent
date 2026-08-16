package com.cc.opsagent.ticket.infrastructure;

import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketSeverity;
import com.cc.opsagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketMapperIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ops_agent")
            .withUsername("ops_agent")
            .withPassword("test-password");

    @DynamicPropertySource
    static void businessDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.business.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.business.username", MYSQL::getUsername);
        registry.add("app.datasource.business.password", MYSQL::getPassword);
        registry.add("app.datasource.business.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    @Qualifier("businessJdbcTemplate")
    JdbcTemplate jdbcTemplate;

    @Autowired
    TicketMapper ticketMapper;

    @Test
    void migratesTicketSchema() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ticket'
                """, Integer.class);
        assertThat(tableCount).isEqualTo(1);

        Integer successfulVersionTwo = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '2' AND success = 1
                """, Integer.class);
        assertThat(successfulVersionTwo).isEqualTo(1);
    }

    @Test
    void rejectsReporterFromAnotherTenant() {
        long tenantA = insertTenant("ticket-owner-a");
        long tenantB = insertTenant("ticket-owner-b");
        long tenantBUser = insertUser(tenantB, "tenant-b-reporter");

        assertThatThrownBy(() -> insertTicket(
                tenantA, tenantBUser, "Cross-tenant reporter", "Must be rejected", "OPEN", "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownPersistedStatusAndSeverity() {
        long tenantId = insertTenant("ticket-enum-owner");
        long reporterId = insertUser(tenantId, "enum-reporter");

        assertThatThrownBy(() -> insertTicket(
                tenantId, reporterId, "Invalid status", "Must be rejected", "NOT_A_STATUS", "UNKNOWN"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertTicket(
                tenantId, reporterId, "Invalid severity", "Must be rejected", "OPEN", "URGENTEST"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void findsTicketOnlyInsideOwningTenant() {
        long tenantA = insertTenant("mapper-tenant-a");
        long tenantB = insertTenant("mapper-tenant-b");
        long reporterA = insertUser(tenantA, "mapper-reporter-a");
        Ticket ticket = newTicket(tenantA, reporterA, "Database connection pool exhausted");

        assertThat(ticketMapper.insert(ticket)).isEqualTo(1);
        assertThat(ticket.getId()).isNotNull();

        Ticket owningTenantTicket = ticketMapper.selectByTenantIdAndId(tenantA, ticket.getId());
        Ticket otherTenantTicket = ticketMapper.selectByTenantIdAndId(tenantB, ticket.getId());

        assertThat(owningTenantTicket).isNotNull();
        assertThat(owningTenantTicket.getTitle()).isEqualTo("Database connection pool exhausted");
        assertThat(owningTenantTicket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(otherTenantTicket).isNull();
    }

    @Test
    void allowsOnlyOneExpectedStatusUpdate() {
        long tenantId = insertTenant("transition-tenant");
        long reporterId = insertUser(tenantId, "transition-reporter");
        Ticket ticket = newTicket(tenantId, reporterId, "Redis commands are timing out");
        ticketMapper.insert(ticket);

        int first = ticketMapper.transitionStatus(
                tenantId, ticket.getId(), TicketStatus.OPEN, TicketStatus.TRIAGING);
        int staleSecond = ticketMapper.transitionStatus(
                tenantId, ticket.getId(), TicketStatus.OPEN, TicketStatus.TRIAGING);

        assertThat(first).isEqualTo(1);
        assertThat(staleSecond).isZero();
        assertThat(ticketMapper.selectByTenantIdAndId(tenantId, ticket.getId()).getStatus())
                .isEqualTo(TicketStatus.TRIAGING);
    }

    private Ticket newTicket(long tenantId, long reporterId, String title) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setReporterId(reporterId);
        ticket.setTitle(title);
        ticket.setDescription("The service is unhealthy and requires diagnosis.");
        ticket.setAffectedService("order-service");
        ticket.setSeverity(TicketSeverity.UNKNOWN);
        ticket.setStatus(TicketStatus.OPEN);
        return ticket;
    }

    private void insertTicket(
            long tenantId,
            long reporterId,
            String title,
            String description,
            String status,
            String severity) {
        jdbcTemplate.update("""
                        INSERT INTO ticket
                            (tenant_id, reporter_id, title, description, status, severity)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                tenantId, reporterId, title, description, status, severity);
    }

    private long insertTenant(String code) {
        jdbcTemplate.update("INSERT INTO tenant (code, name) VALUES (?, ?)", code, code);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, code);
    }

    private long insertUser(long tenantId, String username) {
        jdbcTemplate.update("""
                        INSERT INTO user_account
                            (tenant_id, username, password_hash, display_name, role)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                tenantId, username, "test-password-hash", username, "OPERATOR");
        return jdbcTemplate.queryForObject("""
                SELECT id FROM user_account
                WHERE tenant_id = ? AND username = ?
                """, Long.class, tenantId, username);
    }
}
