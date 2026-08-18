package com.cc.opsagent.agent.application;

import com.cc.opsagent.agent.domain.AgentTask;
import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.model.ModelProvider;
import com.cc.opsagent.ticket.application.TicketService;
import com.cc.opsagent.ticket.domain.TicketSeverity;
import com.cc.opsagent.ticket.domain.TicketStatus;
import com.cc.opsagent.ticket.web.TicketResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsAgentWorkflowTest {

    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final TicketService tickets = mock(TicketService.class);
    private final AgentWorkflowEngine engine = mock(AgentWorkflowEngine.class);

    @Test
    void claimsBuildsCommandAndPersistsTheTerminalOutcome() {
        AgentTask task = queuedTask();
        when(tasks.get(100)).thenReturn(task);
        when(tasks.claim(100, "worker-a", java.time.Duration.ofSeconds(30)))
                .thenReturn(true);
        when(tickets.get(88)).thenReturn(ticket("REDIS_TIMEOUT"));
        TaskOutcome expected = new TaskOutcome(
                AgentTaskStatus.SUCCEEDED, "pool_exhausted",
                List.of("queryLogs"), List.of("citation-1"),
                "NONE", "resolved", null);
        when(engine.execute(any())).thenReturn(expected);
        when(tasks.transition(100, AgentTaskStatus.RUNNING,
                AgentTaskStatus.SUCCEEDED)).thenReturn(true);
        OpsAgentWorkflow workflow = new OpsAgentWorkflow(
                tasks, events, tickets, engine, ModelProvider.QWEN,
                "worker-a", java.time.Duration.ofSeconds(30));

        TaskOutcome outcome = workflow.run(100);

        assertThat(outcome).isEqualTo(expected);
        ArgumentCaptor<AgentTaskCommand> command =
                ArgumentCaptor.forClass(AgentTaskCommand.class);
        verify(engine).execute(command.capture());
        assertThat(command.getValue().scenarioKey()).isEqualTo("redis-timeout");
        assertThat(command.getValue().budget())
                .isEqualTo(new AgentBudget(12, java.time.Duration.ofSeconds(180), 20_000));
        verify(tasks).transition(
                100, AgentTaskStatus.RUNNING, AgentTaskStatus.SUCCEEDED);
        verify(events).append(eq(100L), eq("TASK_COMPLETED"), any());
    }

    @Test
    void convertsAnEngineExceptionIntoAFailedTaskOutcome() {
        when(tasks.get(100)).thenReturn(queuedTask());
        when(tasks.claim(100, "worker-a", java.time.Duration.ofSeconds(30)))
                .thenReturn(true);
        when(tickets.get(88)).thenReturn(ticket("redis-timeout"));
        when(engine.execute(any())).thenThrow(
                new IllegalStateException("Bearer secret-value upstream failed"));
        when(tasks.transition(100, AgentTaskStatus.RUNNING,
                AgentTaskStatus.FAILED)).thenReturn(true);
        OpsAgentWorkflow workflow = new OpsAgentWorkflow(
                tasks, events, tickets, engine, ModelProvider.DEEPSEEK,
                "worker-a", java.time.Duration.ofSeconds(30));

        TaskOutcome outcome = workflow.run(100);

        assertThat(outcome.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(outcome.errorSummary())
                .contains("[REDACTED]")
                .doesNotContain("secret-value");
        verify(tasks).transition(
                100, AgentTaskStatus.RUNNING, AgentTaskStatus.FAILED);
    }

    private AgentTask queuedTask() {
        return new AgentTask(
                100, 1, 88, 7, AgentTaskStatus.QUEUED,
                12, 180, 20_000, 0, 0, null, null, null,
                Instant.now(), null, null);
    }

    private TicketResponse ticket(String category) {
        return new TicketResponse(
                88, 1, 7, "Redis latency", "Pool acquisition timed out",
                "order-service", category, TicketSeverity.HIGH, TicketStatus.OPEN,
                null, LocalDateTime.now(), LocalDateTime.now());
    }
}
