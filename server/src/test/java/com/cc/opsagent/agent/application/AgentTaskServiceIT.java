package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentEvent;
import com.cc.opsagent.agent.domain.AgentStep;
import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.agent.infrastructure.PersistentAgentExecutionAudit;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentTaskServiceIT {

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

    @Autowired
    AgentTaskService taskService;

    @Autowired
    AgentEventService eventService;

    @Autowired
    AgentEventStream eventStream;

    @Autowired
    PersistentAgentExecutionAudit executionAudit;

    @Autowired
    @Qualifier("businessJdbcTemplate")
    JdbcTemplate jdbcTemplate;

    long tenantA;
    long userA;
    long ticketA;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM agent_event");
        jdbcTemplate.update("DELETE FROM tool_invocation");
        jdbcTemplate.update("DELETE FROM model_invocation");
        jdbcTemplate.update("DELETE FROM agent_step");
        jdbcTemplate.update("DELETE FROM agent_task");
        jdbcTemplate.update("DELETE FROM ticket");
        jdbcTemplate.update("DELETE FROM user_account");
        jdbcTemplate.update("DELETE FROM tenant");
        tenantA = insertTenant("agent-a");
        userA = insertUser(tenantA, "agent-a@example.com");
        ticketA = insertTicket(tenantA, userA, "Redis latency incident");
        authenticate(tenantA, userA);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsOnlyOneActiveTaskPerTicketButAllowsANewTaskAfterCompletion() {
        AgentTask first = taskService.start(ticketA, defaultBudget());

        assertThatThrownBy(() -> taskService.start(ticketA, defaultBudget()))
                .isInstanceOf(ActiveTaskExistsException.class);

        assertThat(taskService.claim(first.id(), "worker-a", Duration.ofSeconds(30)))
                .isTrue();
        assertThat(taskService.transition(
                first.id(), AgentTaskStatus.RUNNING, AgentTaskStatus.SUCCEEDED))
                .isTrue();
        AgentTask second = taskService.start(ticketA, defaultBudget());
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void claimsAndRenewsLeasesOnlyThroughConditionalUpdates() {
        AgentTask task = taskService.start(ticketA, defaultBudget());

        assertThat(taskService.claim(task.id(), "worker-a", Duration.ofSeconds(30)))
                .isTrue();
        assertThat(taskService.claim(task.id(), "worker-b", Duration.ofSeconds(30)))
                .isFalse();
        assertThat(taskService.renewLease(task.id(), "worker-b", Duration.ofSeconds(30)))
                .isFalse();
        assertThat(taskService.renewLease(task.id(), "worker-a", Duration.ofSeconds(60)))
                .isTrue();

        AgentTask claimed = taskService.get(task.id());
        assertThat(claimed.status()).isEqualTo(AgentTaskStatus.RUNNING);
        assertThat(claimed.workerId()).isEqualTo("worker-a");
        assertThat(claimed.leaseUntil()).isAfter(Instant.now());
    }

    @Test
    void appendsStepsAndAllocatesMonotonicEventSequences() {
        AgentTask task = taskService.start(ticketA, defaultBudget());
        taskService.appendStep(new StepRecord(
                task.id(), 1, "TRIAGE", "SUCCEEDED",
                Map.of("ticketId", ticketA), Map.of("category", "REDIS_TIMEOUT"),
                null, 17));

        AgentEvent first = eventService.append(
                task.id(), "TASK_CREATED", Map.of("ticketId", ticketA));
        AgentEvent second = eventService.append(
                task.id(), "STEP_COMPLETED", Map.of("step", 1));
        AgentEvent third = eventService.append(
                task.id(), "STATUS_CHANGED", Map.of("status", "RUNNING"));

        assertThat(List.of(first.sequence(), second.sequence(), third.sequence()))
                .containsExactly(1L, 2L, 3L);
        assertThat(taskService.steps(task.id()))
                .extracting(AgentStep::sequence, AgentStep::nodeName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "TRIAGE"));
        assertThat(eventService.after(task.id(), 1, 10))
                .extracting(AgentEvent::sequence)
                .containsExactly(2L, 3L);
    }

    @Test
    void serializesConcurrentEventSequenceAllocation() throws Exception {
        AgentTask task = taskService.start(ticketA, defaultBudget());
        List<Future<Long>> futures;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = IntStream.range(0, 10)
                    .mapToObj(index -> executor.submit(() -> {
                        authenticate(tenantA, userA);
                        return eventService.append(
                                task.id(), "CONCURRENT", Map.of("index", index))
                                .sequence();
                    }))
                    .toList();
        }

        List<Long> sequences = futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .sorted()
                .toList();
        assertThat(sequences).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 10).mapToObj(Long::valueOf).toList());
    }

    @Test
    void keepsTasksStepsAndEventsIsolatedByAuthenticatedTenant() {
        AgentTask task = taskService.start(ticketA, defaultBudget());
        long tenantB = insertTenant("agent-b");
        long userB = insertUser(tenantB, "agent-b@example.com");
        authenticate(tenantB, userB);

        assertThatThrownBy(() -> taskService.get(task.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThat(taskService.claim(task.id(), "worker-b", Duration.ofSeconds(30)))
                .isFalse();
        assertThatThrownBy(() -> eventService.after(task.id(), 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsInvalidTransitionsAndInvalidBudgets() {
        AgentTask task = taskService.start(ticketA, defaultBudget());

        assertThatThrownBy(() -> taskService.transition(
                task.id(), AgentTaskStatus.QUEUED, AgentTaskStatus.SUCCEEDED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transition");
        assertThatThrownBy(() -> new AgentBudget(0, Duration.ofMinutes(3), 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steps");
    }

    @Test
    void persistsModelAndIdempotentToolInvocationsWithRedactedErrors() {
        AgentTask task = taskService.start(ticketA, defaultBudget());

        long modelInvocationId = taskService.appendModelInvocation(
                new ModelInvocationRecord(
                        task.id(), null, "QWEN", "qwen-plus",
                        "a".repeat(64), "FAILED", 120, 7, 43,
                        "Authorization: Bearer sk-live-secret upstream timeout"));
        ToolInvocationRecord toolRecord = new ToolInvocationRecord(
                task.id(), null, "queryLogs",
                Map.of("service", "order-api", "maxLines", 20),
                ToolRisk.READ_ONLY, ToolExecutionStatus.SUCCESS,
                "logs-once", 12, Map.of(
                        "lines", 20,
                        "observedAt", Instant.parse("2026-08-18T10:00:00Z")), null);
        long firstToolInvocationId = taskService.appendToolInvocation(toolRecord);
        long duplicateToolInvocationId = taskService.appendToolInvocation(toolRecord);
        ToolInvocationRecord conflictingRecord = new ToolInvocationRecord(
                task.id(), null, "queryLogs",
                Map.of("service", "payment-api", "maxLines", 20),
                ToolRisk.READ_ONLY, ToolExecutionStatus.SUCCESS,
                "logs-once", 12, Map.of("lines", 20), null);

        assertThat(modelInvocationId).isPositive();
        assertThat(firstToolInvocationId).isEqualTo(duplicateToolInvocationId);
        assertThatThrownBy(() -> taskService.appendToolInvocation(conflictingRecord))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different arguments");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tool_invocation
                WHERE tenant_id = ? AND task_id = ? AND idempotency_key = 'logs-once'
                """, Integer.class, tenantA, task.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT input_tokens + output_tokens FROM model_invocation WHERE id = ?
                """, Integer.class, modelInvocationId)).isEqualTo(127);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT error_summary FROM model_invocation WHERE id = ?
                """, String.class, modelInvocationId))
                .contains("[REDACTED]")
                .doesNotContain("sk-live-secret");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(normalized_arguments, '$.service'))
                FROM tool_invocation WHERE id = ?
                """, String.class, firstToolInvocationId)).isEqualTo("order-api");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(result_summary, '$.observedAt'))
                FROM tool_invocation WHERE id = ?
                """, String.class, firstToolInvocationId))
                .isEqualTo("2026-08-18T10:00:00Z");
    }

    @Test
    void persistsNodeLifecycleAndItsModelAndToolAudit() {
        AgentTask task = taskService.start(ticketA, defaultBudget());
        executionAudit.nodeStarted(new AgentExecutionAudit.NodeAudit(
                task.id(), 1, "triage", "STARTED",
                Map.of("status", "RUNNING"), Map.of(), null, 0));
        executionAudit.modelInvoked(new AgentExecutionAudit.ModelCallAudit(
                task.id(), "QWEN", "fixed-model", "b".repeat(64),
                "SUCCEEDED", 10, 5, 12, null));
        executionAudit.toolInvoked(new AgentExecutionAudit.ToolCallAudit(
                task.id(), "queryLogs", Map.of("service", "order-api"),
                ToolRisk.READ_ONLY, ToolExecutionStatus.SUCCESS,
                7, Map.of("lines", 4), null));
        executionAudit.nodeCompleted(new AgentExecutionAudit.NodeAudit(
                task.id(), 1, "triage", "SUCCEEDED",
                Map.of("status", "RUNNING"),
                Map.of("category", "REDIS_TIMEOUT"), null, 19));

        assertThat(taskService.steps(task.id()))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.nodeName()).isEqualTo("triage");
                    assertThat(step.durationMs()).isEqualTo(19);
                });
        assertThat(eventService.after(task.id(), 0, 10))
                .extracting(AgentEvent::eventType)
                .containsExactly("NODE_STARTED", "NODE_COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_invocation WHERE task_id = ?",
                Integer.class, task.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tool_invocation WHERE task_id = ?",
                Integer.class, task.id())).isEqualTo(1);
        AgentTask audited = taskService.get(task.id());
        assertThat(audited.stepsUsed()).isEqualTo(1);
        assertThat(audited.tokensUsed()).isEqualTo(15);
    }

    @Test
    void publishesAnEventOnlyAfterItsDatabaseTransactionCommits() {
        AgentTask task = taskService.start(ticketA, defaultBudget());
        List<Long> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        AgentEventStream.Subscription subscription = eventStream.subscribe(
                task.id(), 0, event -> {
                    Integer stored = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM agent_event WHERE id = ?",
                            Integer.class, event.id());
                    if (stored != null && stored == 1) received.add(event.sequence());
                });
        subscription.activate(List.of());

        AgentEvent appended = eventService.append(
                task.id(), "COMMITTED", Map.of());

        assertThat(received).containsExactly(appended.sequence());
        subscription.close();
    }

    private AgentBudget defaultBudget() {
        return new AgentBudget(12, Duration.ofMinutes(3), 20_000);
    }

    private long insertTenant(String code) {
        jdbcTemplate.update("INSERT INTO tenant (code, name) VALUES (?, ?)", code, code);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, code);
    }

    private long insertUser(long tenantId, String username) {
        jdbcTemplate.update("""
                INSERT INTO user_account
                    (tenant_id, username, display_name, password_hash, role)
                VALUES (?, ?, 'Agent Operator', 'hash', 'OPERATOR')
                """, tenantId, username);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ? AND username = ?",
                Long.class, tenantId, username);
    }

    private long insertTicket(long tenantId, long userId, String title) {
        jdbcTemplate.update("""
                INSERT INTO ticket
                    (tenant_id, reporter_id, title, description, severity, status)
                VALUES (?, ?, ?, 'Connection pool timeout alarms', 'HIGH', 'OPEN')
                """, tenantId, userId, title);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ticket WHERE tenant_id = ? AND title = ?",
                Long.class, tenantId, title);
    }

    private void authenticate(long tenantId, long userId) {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, userId, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
