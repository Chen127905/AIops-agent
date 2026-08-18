package com.cc.opsagent.agent.config;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorConfigTest {

    @AfterEach
    void clearCallerContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void propagatesSecurityAndCorrelationContextWithoutLeakingToNextTask()
            throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new AgentExecutorConfig()
                .agentTaskExecutor(1, 1, 1, new AgentMetrics(registry));
        try {
            TenantPrincipal principal = new TenantPrincipal(
                    7, 9, "operator", Set.of("OPERATOR"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            principal, null, List.of()));
            MDC.put("trace_id", "trace-123");
            MDC.put("task_id", "42");

            Future<WorkerContext> first = executor.submit(() ->
                    new WorkerContext(
                            SecurityContextHolder.getContext().getAuthentication()
                                    .getPrincipal(),
                            MDC.get("trace_id"),
                            MDC.get("task_id")));

            WorkerContext propagated = first.get(5, TimeUnit.SECONDS);
            assertThat(propagated.principal()).isEqualTo(principal);
            assertThat(propagated.traceId()).isEqualTo("trace-123");
            assertThat(propagated.taskId()).isEqualTo("42");

            SecurityContextHolder.clearContext();
            MDC.clear();
            Future<WorkerContext> second = executor.submit(() ->
                    new WorkerContext(
                            SecurityContextHolder.getContext().getAuthentication(),
                            MDC.get("trace_id"),
                            MDC.get("task_id")));

            WorkerContext clean = second.get(5, TimeUnit.SECONDS);
            assertThat(clean.principal()).isNull();
            assertThat(clean.traceId()).isNull();
            assertThat(clean.taskId()).isNull();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void recordsCapacityRejectionWithNoUnboundedTags() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new AgentExecutorConfig()
                .agentTaskExecutor(1, 1, 0, new AgentMetrics(registry));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(TaskRejectedException.class)
                    .hasRootCauseMessage("agent executor capacity exhausted");
            assertThat(registry.get("ops.agent.executor.rejections")
                    .counter().count()).isEqualTo(1.0);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private record WorkerContext(Object principal, String traceId, String taskId) {
    }
}
