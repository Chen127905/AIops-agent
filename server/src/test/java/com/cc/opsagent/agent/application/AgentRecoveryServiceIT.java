package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Import(AgentRecoveryServiceIT.RecoveryTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.agent.recovery.enabled=false")
class AgentRecoveryServiceIT {

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

    @Autowired AgentTaskService tasks;
    @Autowired AgentCancellationService cancellation;
    @Autowired AgentRecoveryService recovery;
    @Autowired CapturingRecoveryHandler recoveryHandler;
    @Autowired @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate;

    long tenantId;
    long userId;
    long ticketId;

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
        tenantId = insertTenant();
        userId = insertUser();
        ticketId = insertTicket();
        authenticate();
        recoveryHandler.reset();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancellationIsImmediateBeforeStartAndCooperativeWhileRunning() {
        AgentTask queued = tasks.start(ticketId, budget());

        assertThat(cancellation.requestCancel(queued.id()).status())
                .isEqualTo(AgentTaskStatus.CANCELLED);

        AgentTask running = tasks.start(ticketId, budget());
        assertThat(tasks.claim(running.id(), "worker-a", Duration.ofMinutes(1)))
                .isTrue();
        assertThat(cancellation.requestCancel(running.id()).status())
                .isEqualTo(AgentTaskStatus.RUNNING);
        expireLease(running.id());

        assertThat(recovery.recover(running.id(), Instant.now()))
                .isEqualTo(RecoveryDecision.CANCELLED);
        assertThat(tasks.get(running.id()).status())
                .isEqualTo(AgentTaskStatus.CANCELLED);
    }

    @Test
    void cancellationClosesAPendingApprovalWithTheTask() {
        AgentTask task = runningTask();
        assertThat(tasks.transition(
                task.id(), AgentTaskStatus.RUNNING,
                AgentTaskStatus.WAITING_APPROVAL)).isTrue();
        long approvalId = insertPendingApproval(
                task.id(), Instant.now().plusSeconds(60));

        assertThat(cancellation.requestCancel(task.id()).status())
                .isEqualTo(AgentTaskStatus.CANCELLED);
        assertThat(approvalStatus(approvalId)).isEqualTo("CANCELLED");
    }

    @Test
    void claimsAnExpiredTaskAndResumesAfterItsLastSuccessfulCheckpoint() {
        AgentTask task = runningTask();
        tasks.appendStep(new StepRecord(
                task.id(), 1, "triage", "SUCCEEDED",
                Map.of("status", "RUNNING"),
                Map.of(
                        "status", "RUNNING", "steps", 1, "tokens", 15,
                        "category", "REDIS_TIMEOUT", "urgency", "HIGH",
                        "evidence", List.of(), "observations", List.of(),
                        "confidence", 0.0),
                null, 12));
        expireLease(task.id());
        Instant now = Instant.now();

        RecoveryDecision decision = recovery.recover(task.id(), now);

        assertThat(decision).isEqualTo(RecoveryDecision.RESUME_ENQUEUED);
        assertThat(recoveryHandler.taskId.get()).isEqualTo(task.id());
        assertThat(recoveryHandler.checkpoint.get().lastCompletedNode())
                .isEqualTo("triage");
        assertThat(tasks.get(task.id()).recoveryCount()).isEqualTo(1);
        assertThat(recovery.recover(task.id(), now))
                .isEqualTo(RecoveryDecision.NOT_ELIGIBLE);
    }

    @Test
    void ambiguousApprovedWriteMovesToManualRequired() {
        AgentTask task = runningTask();
        long approvalId = insertExecutingApproval(task.id());
        expireLease(task.id());

        assertThat(recovery.recover(task.id(), Instant.now()))
                .isEqualTo(RecoveryDecision.MANUAL_REQUIRED);
        assertThat(tasks.get(task.id()).status())
                .isEqualTo(AgentTaskStatus.MANUAL_REQUIRED);
        assertThat(approvalStatus(approvalId)).isEqualTo("FAILED");
        assertThat(recoveryHandler.taskId.get()).isZero();
    }

    @Test
    void completedIdempotentWriteFinishesWithoutExecutingAgain() {
        AgentTask task = runningTask();
        long approvalId = insertExecutingApproval(task.id());
        tasks.appendToolInvocation(new ToolInvocationRecord(
                task.id(), null, "restartService",
                Map.of("service", "order-service"),
                ToolRisk.HIGH_RISK, ToolExecutionStatus.SUCCESS,
                "approval:" + approvalId, 15,
                Map.of("changed", true), null));
        expireLease(task.id());

        assertThat(recovery.recover(task.id(), Instant.now()))
                .isEqualTo(RecoveryDecision.COMPLETED_FROM_IDEMPOTENCY_RECORD);
        assertThat(tasks.get(task.id()).status())
                .isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(approvalStatus(approvalId)).isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tool_invocation WHERE task_id = ?",
                Integer.class, task.id())).isEqualTo(1);
    }

    @Test
    void expiresApprovalAndTaskTogether() {
        AgentTask task = runningTask();
        assertThat(tasks.transition(
                task.id(), AgentTaskStatus.RUNNING,
                AgentTaskStatus.WAITING_APPROVAL)).isTrue();
        long approvalId = insertPendingApproval(task.id(), Instant.now().minusSeconds(1));

        assertThat(tasks.expireApprovalWaits(Instant.now())).isEqualTo(1);
        assertThat(tasks.get(task.id()).status())
                .isEqualTo(AgentTaskStatus.TIMED_OUT);
        assertThat(approvalStatus(approvalId)).isEqualTo("EXPIRED");
    }

    private AgentTask runningTask() {
        AgentTask task = tasks.start(ticketId, budget());
        assertThat(tasks.claim(task.id(), "worker-a", Duration.ofMinutes(1)))
                .isTrue();
        return tasks.get(task.id());
    }

    private void expireLease(long taskId) {
        jdbcTemplate.update(
                "UPDATE agent_task SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), taskId);
    }

    private long insertExecutingApproval(long taskId) {
        long id = insertApproval(taskId, "EXECUTING", Instant.now().plusSeconds(60));
        jdbcTemplate.update("""
                UPDATE approval_request
                SET execution_started_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, id);
        return id;
    }

    private long insertPendingApproval(long taskId, Instant expiresAt) {
        return insertApproval(taskId, "PENDING", expiresAt);
    }

    private long insertApproval(
            long taskId,
            String status,
            Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO approval_request
                    (tenant_id, task_id, checkpoint_id, scenario_key,
                     tool_name, normalized_arguments, arguments_hash,
                     risk, status, requested_by, expires_at)
                VALUES (?, ?, 'checkpoint', 'redis-timeout',
                        'restartService', JSON_OBJECT('service', 'order-service'),
                        ?, 'HIGH_RISK', ?, ?, ?)
                """, tenantId, taskId, "a".repeat(64), status, userId,
                Timestamp.from(expiresAt));
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM approval_request WHERE task_id = ?",
                Long.class, taskId);
    }

    private String approvalStatus(long approvalId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM approval_request WHERE id = ?",
                String.class, approvalId);
    }

    private AgentBudget budget() {
        return new AgentBudget(12, Duration.ofMinutes(3), 20_000);
    }

    private long insertTenant() {
        jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES ('recovery-a', 'Recovery A')");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = 'recovery-a'", Long.class);
    }

    private long insertUser() {
        jdbcTemplate.update("""
                INSERT INTO user_account
                    (tenant_id, username, display_name, password_hash, role)
                VALUES (?, 'recovery@example.com', 'Recovery User', 'hash', 'OPERATOR')
                """, tenantId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertTicket() {
        jdbcTemplate.update("""
                INSERT INTO ticket
                    (tenant_id, reporter_id, title, description,
                     affected_service, category, severity, status)
                VALUES (?, ?, 'Recovery Redis', 'Redis timeout recovery',
                        'order-service', 'redis-timeout', 'HIGH', 'OPEN')
                """, tenantId, userId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ticket WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private void authenticate() {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, userId, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of()));
    }

    @TestConfiguration
    static class RecoveryTestConfiguration {

        @Bean
        @Primary
        CapturingRecoveryHandler capturingRecoveryHandler() {
            return new CapturingRecoveryHandler();
        }
    }

    static class CapturingRecoveryHandler implements RecoveryResumeHandler {

        private final AtomicLong taskId = new AtomicLong();
        private final AtomicReference<RecoveryCheckpoint> checkpoint =
                new AtomicReference<>();

        @Override
        public void dispatch(long taskId, RecoveryCheckpoint checkpoint) {
            this.taskId.set(taskId);
            this.checkpoint.set(checkpoint);
        }

        void reset() {
            taskId.set(0);
            checkpoint.set(null);
        }
    }
}
