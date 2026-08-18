package com.cc.opsagent.approval.application;

import com.cc.opsagent.agent.application.AgentBudget;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.approval.domain.ApprovalRequest;
import com.cc.opsagent.approval.domain.ApprovalStatus;
import com.cc.opsagent.identity.security.TenantPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalServiceIT {

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
        registry.add("app.datasource.business.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired ApprovalService approvalService;
    @Autowired AgentTaskService taskService;
    @Autowired @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate;

    long tenantA;
    long operatorA;
    long adminA;
    long ticketA;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM approval_request");
        jdbcTemplate.update("DELETE FROM agent_event");
        jdbcTemplate.update("DELETE FROM tool_invocation");
        jdbcTemplate.update("DELETE FROM model_invocation");
        jdbcTemplate.update("DELETE FROM agent_step");
        jdbcTemplate.update("DELETE FROM agent_task");
        jdbcTemplate.update("DELETE FROM ticket");
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM tenant");
        tenantA = insertTenant("approval-a");
        operatorA = insertUser(tenantA, "operator-a", "OPERATOR");
        adminA = insertUser(tenantA, "admin-a", "ADMIN");
        ticketA = insertTicket(tenantA, operatorA);
        authenticate(tenantA, operatorA, "OPERATOR");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void consumesApprovalExactlyOnceAndExecutesTheToolOnce() throws Exception {
        AgentTask task = waitingTask();
        ApprovalRequest approval = approvalService.create(
                task.id(), "task:" + task.id() + ":verify", "redis-timeout",
                "restartService", Map.of("service", "order-service"),
                Duration.ofMinutes(5));

        List<Boolean> decisions = concurrently(2, () -> {
            authenticate(tenantA, adminA, "ADMIN");
            try {
                approvalService.approve(approval.id(), "approved");
                return true;
            } catch (ApprovalDecisionException exception) {
                return false;
            }
        });

        assertThat(decisions).containsExactlyInAnyOrder(true, false);
        awaitApproval(approval.id(), ApprovalStatus.EXECUTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tool_invocation
                WHERE task_id = ? AND tool_name = 'restartService'
                """, Integer.class, task.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tool_invocation
                WHERE task_id = ? AND tool_name = 'getServiceHealth'
                  AND JSON_UNQUOTE(JSON_EXTRACT(normalized_arguments, '$.phase'))
                      = 'post-action'
                """, Integer.class, task.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_task WHERE id = ?",
                String.class, task.id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void resumesAnApprovedConfigurationChangeWithStoredArguments() throws Exception {
        long configTicket = insertTicket(
                tenantA, operatorA, "payment-api", "api-error-rate");
        AgentTask task = waitingTask(configTicket);
        ApprovalRequest approval = approvalService.create(
                task.id(), "task:" + task.id() + ":verify", "api-error-rate",
                "changeConfig", Map.of(
                        "service", "payment-api",
                        "changes", Map.of(
                                "routingVersion", "stable-2026-08-16")),
                Duration.ofMinutes(5));
        authenticate(tenantA, adminA, "ADMIN");

        approvalService.approve(approval.id(), "restore stable routing");

        awaitApproval(approval.id(), ApprovalStatus.EXECUTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tool_invocation
                WHERE task_id = ? AND tool_name = 'changeConfig'
                  AND status = 'SUCCESS'
                """, Integer.class, task.id())).isEqualTo(1);
        assertThat(taskService.get(task.id()).status())
                .isEqualTo(AgentTaskStatus.SUCCEEDED);
    }

    @Test
    void rejectsExpiredDuplicateWrongTenantAndWrongRoleDecisions()
            throws Exception {
        AgentTask task = waitingTask();
        ApprovalRequest approval = approvalService.create(
                task.id(), "checkpoint", "redis-timeout", "restartService",
                Map.of("service", "order-service"), Duration.ofMillis(20));

        assertThatThrownBy(() -> approvalService.approve(approval.id(), "no"))
                .isInstanceOf(AccessDeniedException.class);
        Thread.sleep(50);
        authenticate(tenantA, adminA, "ADMIN");
        assertThatThrownBy(() -> approvalService.approve(approval.id(), "late"))
                .isInstanceOf(ApprovalDecisionException.class)
                .hasMessageContaining("EXPIRED");
        assertThat(approvalService.get(approval.id()).status())
                .isEqualTo(ApprovalStatus.EXPIRED);

        long tenantB = insertTenant("approval-b");
        long adminB = insertUser(tenantB, "admin-b", "ADMIN");
        authenticate(tenantB, adminB, "ADMIN");
        assertThatThrownBy(() -> approvalService.get(approval.id()))
                .isInstanceOf(ApprovalDecisionException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectionIsSingleUseAndMovesTaskToManualRequired() {
        AgentTask task = waitingTask();
        ApprovalRequest approval = approvalService.create(
                task.id(), "checkpoint", "redis-timeout", "restartService",
                Map.of("service", "order-service"), Duration.ofMinutes(5));
        authenticate(tenantA, adminA, "ADMIN");

        ApprovalRequest rejected = approvalService.reject(approval.id(), "unsafe now");

        assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(taskService.get(task.id()).status())
                .isEqualTo(AgentTaskStatus.MANUAL_REQUIRED);
        assertThatThrownBy(() -> approvalService.reject(approval.id(), "again"))
                .isInstanceOf(ApprovalDecisionException.class);
    }

    @Test
    void failedApprovedExecutionPersistsTaskErrorSummary() throws Exception {
        AgentTask task = waitingTask();
        ApprovalRequest approval = approvalService.create(
                task.id(), "checkpoint", "redis-timeout", "restartService",
                Map.of("service", "wrong-service"), Duration.ofMinutes(5));
        authenticate(tenantA, adminA, "ADMIN");

        approvalService.approve(approval.id(), "exercise failure audit");

        awaitApproval(approval.id(), ApprovalStatus.FAILED);
        AgentTask failed = taskService.get(task.id());
        assertThat(failed.status()).isIn(
                AgentTaskStatus.FAILED, AgentTaskStatus.MANUAL_REQUIRED);
        assertThat(failed.errorSummary()).isNotBlank();
    }

    private AgentTask waitingTask() {
        return waitingTask(ticketA);
    }

    private AgentTask waitingTask(long ticketId) {
        AgentTask task = taskService.start(
                ticketId, new AgentBudget(12, Duration.ofMinutes(3), 20_000));
        assertThat(taskService.claim(task.id(), "approval-test", Duration.ofMinutes(3)))
                .isTrue();
        assertThat(taskService.transition(
                task.id(), AgentTaskStatus.RUNNING,
                AgentTaskStatus.WAITING_APPROVAL)).isTrue();
        return task;
    }

    private List<Boolean> concurrently(int count, Callable<Boolean> action)
            throws Exception {
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(action));
            }
        }
        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) results.add(future.get());
        return List.copyOf(results);
    }

    private void awaitApproval(long approvalId, ApprovalStatus expected)
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM approval_request WHERE id = ?",
                    String.class, approvalId);
            if (expected.name().equals(status)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("approval did not reach " + expected);
    }

    private long insertTenant(String code) {
        jdbcTemplate.update("INSERT INTO tenant (code, name) VALUES (?, ?)", code, code);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, code);
    }

    private long insertUser(long tenantId, String username, String role) {
        jdbcTemplate.update("""
                INSERT INTO user_account
                    (tenant_id, username, display_name, password_hash, role)
                VALUES (?, ?, 'Approval User', 'hash', ?)
                """, tenantId, username, role);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ? AND username = ?",
                Long.class, tenantId, username);
    }

    private long insertTicket(long tenantId, long userId) {
        return insertTicket(
                tenantId, userId, "order-service", "redis-timeout");
    }

    private long insertTicket(
            long tenantId,
            long userId,
            String service,
            String category) {
        jdbcTemplate.update("""
                INSERT INTO ticket
                    (tenant_id, reporter_id, title, description,
                     affected_service, category, severity, status)
                VALUES (?, ?, CONCAT('Approval ', ?), 'Approval flow',
                        ?, ?, 'HIGH', 'OPEN')
                """, tenantId, userId, category, service, category);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ticket WHERE tenant_id = ? AND title = CONCAT('Approval ', ?)",
                Long.class, tenantId, category);
    }

    private void authenticate(long tenantId, long userId, String role) {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, userId, role.toLowerCase(), Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
