package com.cc.opsagent.agent.web;

import com.cc.opsagent.agent.application.AgentEventService;
import com.cc.opsagent.agent.application.AgentTaskService;
import com.cc.opsagent.agent.application.OpsAgentWorkflow;
import com.cc.opsagent.agent.application.TaskOutcome;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.identity.security.TenantPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {com.cc.opsagent.OpsAgentApplication.class,
                AgentTaskControllerIT.WorkflowTestConfig.class})
class AgentTaskControllerIT {

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

    @Autowired MockMvc mockMvc;
    final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired AgentTaskService taskService;
    @Autowired AgentEventService eventService;
    @Autowired OpsAgentWorkflow workflow;
    @Autowired @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate;

    long tenantA;
    long userA;
    long ticketA;
    CountDownLatch workflowStarted;
    CountDownLatch releaseWorkflow;

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
        tenantA = insertTenant("sse-a");
        userA = insertUser(tenantA, "sse-a@example.com");
        ticketA = insertTicket(tenantA, userA);
        workflowStarted = new CountDownLatch(1);
        releaseWorkflow = new CountDownLatch(1);
        reset(workflow);
        when(workflow.run(anyLong())).thenAnswer(invocation -> {
            long taskId = invocation.getArgument(0);
            assertThat(taskService.claim(
                    taskId, "test-workflow", Duration.ofMinutes(3))).isTrue();
            eventService.append(taskId, "WORK_STARTED", Map.of());
            workflowStarted.countDown();
            if (!releaseWorkflow.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test workflow release timed out");
            }
            eventService.append(taskId, "WORK_FINISHED", Map.of());
            assertThat(taskService.transition(
                    taskId, AgentTaskStatus.RUNNING, AgentTaskStatus.SUCCEEDED))
                    .isTrue();
            eventService.append(taskId, "TASK_COMPLETED", Map.of(
                    "status", "SUCCEEDED"));
            return new TaskOutcome(
                    AgentTaskStatus.SUCCEEDED, "test", List.of(), List.of(),
                    "NONE", "done", null);
        });
    }

    @Test
    void reconnectReplaysAfterTheLastSequenceAndDisconnectDoesNotCancelTask()
            throws Exception {
        var start = mockMvc.perform(post(
                        "/api/tickets/{ticketId}/agent-tasks", ticketA)
                        .with(authentication(principalAuth(tenantA, userA))))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode body = objectMapper.readTree(start.getResponse().getContentAsString());
        long taskId = body.get("id").asLong();
        assertThat(workflowStarted.await(10, TimeUnit.SECONDS)).isTrue();

        mockMvc.perform(post("/api/tickets/{ticketId}/agent-tasks", ticketA)
                        .with(authentication(principalAuth(tenantA, userA))))
                .andExpect(status().isConflict());

        var firstConnection = mockMvc.perform(get(
                        "/api/agent-tasks/{taskId}/events", taskId)
                        .with(authentication(principalAuth(tenantA, userA))))
                .andExpect(request().asyncStarted())
                .andReturn();
        firstConnection.getRequest().getAsyncContext().complete();

        releaseWorkflow.countDown();
        awaitStatus(taskId, AgentTaskStatus.SUCCEEDED);
        awaitEventSequence(taskId, 4);

        var reconnect = mockMvc.perform(get(
                        "/api/agent-tasks/{taskId}/events?after=2", taskId)
                        .with(authentication(principalAuth(tenantA, userA))))
                .andExpect(request().asyncStarted())
                .andReturn();
        String replay = mockMvc.perform(asyncDispatch(reconnect))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay)
                .contains("id:3", "event:WORK_FINISHED")
                .contains("id:4", "event:TASK_COMPLETED")
                .doesNotContain("id:1", "id:2");
        mockMvc.perform(get("/api/agent-tasks/{taskId}", taskId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherTenantCannotReadTheTaskOrItsEvents() throws Exception {
        var start = mockMvc.perform(post(
                        "/api/tickets/{ticketId}/agent-tasks", ticketA)
                        .with(authentication(principalAuth(tenantA, userA))))
                .andExpect(status().isAccepted())
                .andReturn();
        long taskId = objectMapper.readTree(
                start.getResponse().getContentAsString()).get("id").asLong();
        long tenantB = insertTenant("sse-b");
        long userB = insertUser(tenantB, "sse-b@example.com");

        mockMvc.perform(get("/api/agent-tasks/{taskId}", taskId)
                        .with(authentication(principalAuth(tenantB, userB))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agent-tasks/{taskId}/events", taskId)
                        .with(authentication(principalAuth(tenantB, userB))))
                .andExpect(status().isNotFound());
        releaseWorkflow.countDown();
        awaitStatus(taskId, AgentTaskStatus.SUCCEEDED);
    }

    private void awaitStatus(long taskId, AgentTaskStatus expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM agent_task WHERE id = ?", String.class, taskId);
            if (expected.name().equals(status)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("task did not reach " + expected);
    }

    private void awaitEventSequence(long taskId, long expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            Long sequence = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(sequence), 0) FROM agent_event WHERE task_id = ?",
                    Long.class, taskId);
            if (sequence != null && sequence >= expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("task did not persist event " + expected);
    }

    private UsernamePasswordAuthenticationToken principalAuth(
            long tenantId,
            long userId) {
        TenantPrincipal principal = new TenantPrincipal(
                tenantId, userId, "operator", Set.of("OPERATOR"));
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
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
                VALUES (?, ?, 'SSE Operator', 'hash', 'OPERATOR')
                """, tenantId, username);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE tenant_id = ? AND username = ?",
                Long.class, tenantId, username);
    }

    private long insertTicket(long tenantId, long userId) {
        jdbcTemplate.update("""
                INSERT INTO ticket
                    (tenant_id, reporter_id, title, description,
                     affected_service, category, severity, status)
                VALUES (?, ?, 'Redis latency', 'Pool timeout',
                        'order-service', 'redis-timeout', 'HIGH', 'OPEN')
                """, tenantId, userId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ticket WHERE tenant_id = ? AND title = 'Redis latency'",
                Long.class, tenantId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WorkflowTestConfig {

        @Bean
        OpsAgentWorkflow testOpsAgentWorkflow() {
            return Mockito.mock(OpsAgentWorkflow.class);
        }
    }
}
