package com.cc.opsagent.tool.application;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider;
import com.cc.opsagent.simulator.application.OpsDataProvider.HealthSnapshot;
import com.cc.opsagent.simulator.application.OpsDataProvider.LogEntry;
import com.cc.opsagent.simulator.application.OpsDataProvider.MetricPoint;
import com.cc.opsagent.simulator.application.OpsDataProvider.MetricSeries;
import com.cc.opsagent.simulator.domain.ScenarioState;
import com.cc.opsagent.simulator.infrastructure.FixtureOpsDataProvider;
import com.cc.opsagent.simulator.infrastructure.ScenarioCatalog;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsToolFacadeTest {

    private final List<OpsToolFacade> facades = new ArrayList<>();
    private ToolPolicyService policy;

    @BeforeEach
    void setUp() {
        policy = new ToolPolicyService();
        TenantPrincipal principal = new TenantPrincipal(
                1L, 10L, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        facades.forEach(OpsToolFacade::close);
        SecurityContextHolder.clearContext();
    }

    @Test
    void executesReadOnlyToolsAndBlocksHighRiskToolsUntilApproved() {
        FixtureOpsDataProvider provider = new FixtureOpsDataProvider(new ScenarioCatalog());
        OpsToolFacade facade = facade(provider, Duration.ofSeconds(5));
        OpsContext context = new OpsContext(1L, 100L, "redis-timeout");

        var health = facade.getServiceHealth(context, "order-service");
        assertThat(health.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(health.data().status()).isEqualTo("DEGRADED");

        var waiting = facade.restartService(
                context, "order-service", null, "restart-100");
        assertThat(waiting.status()).isEqualTo(ToolExecutionStatus.APPROVAL_REQUIRED);
        assertThat(provider.getHealth(context, "order-service").status())
                .isEqualTo("DEGRADED");

        var restarted = facade.restartService(
                context, "order-service", "approval-100", "restart-100");
        assertThat(restarted.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(provider.getHealth(context, "order-service").status())
                .isEqualTo("UP");
    }

    @Test
    void rejectsCrossTenantAccessBeforeCallingTheProvider() {
        OpsDataProvider provider = mock(OpsDataProvider.class);
        OpsToolFacade facade = facade(provider, Duration.ofSeconds(5));

        var result = facade.getServiceHealth(
                new OpsContext(2L, 100L, "redis-timeout"), "order-service");

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.REJECTED);
        assertThat(result.message()).contains("tenant");
    }

    @Test
    void truncatesLogsByLineCountAndUtf8ByteSize() {
        OpsDataProvider provider = mock(OpsDataProvider.class);
        List<LogEntry> logs = IntStream.range(0, 250)
                .mapToObj(index -> new LogEntry(
                        Instant.EPOCH.plusSeconds(index),
                        "ERROR",
                        "故障".repeat(100)))
                .toList();
        when(provider.queryLogs(any(), anyString(), any())).thenReturn(logs);
        OpsToolFacade facade = facade(provider, Duration.ofSeconds(5));

        var result = facade.queryLogs(
                new OpsContext(1L, 100L, "redis-timeout"),
                "order-service",
                null,
                500);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.data()).hasSizeLessThanOrEqualTo(200);
        int bytes = result.data().stream()
                .mapToInt(log -> log.message().getBytes(StandardCharsets.UTF_8).length)
                .sum();
        assertThat(bytes).isLessThanOrEqualTo(32 * 1024);
    }

    @Test
    void truncatesMetricPointsToFiveHundred() {
        OpsDataProvider provider = mock(OpsDataProvider.class);
        List<MetricPoint> points = IntStream.range(0, 600)
                .mapToObj(index -> new MetricPoint(
                        Instant.EPOCH.plusSeconds(index), index))
                .toList();
        when(provider.queryMetrics(any(), anyString(), any()))
                .thenReturn(List.of(new MetricSeries(
                        "order-service", "latency", "ms", points)));
        OpsToolFacade facade = facade(provider, Duration.ofSeconds(5));

        var result = facade.queryMetrics(
                new OpsContext(1L, 100L, "redis-timeout"),
                "order-service",
                null,
                1000);

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCESS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.data())
                .flatExtracting(MetricSeries::points)
                .hasSize(500);
    }

    @Test
    void returnsTimeoutInsteadOfWaitingIndefinitely() throws Exception {
        OpsDataProvider provider = mock(OpsDataProvider.class);
        when(provider.getHealth(any(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(250);
            return new HealthSnapshot(
                    "order-service", "UP", "late", ScenarioState.RECOVERED);
        });
        OpsToolFacade facade = facade(provider, Duration.ofMillis(20));

        var result = facade.getServiceHealth(
                new OpsContext(1L, 100L, "redis-timeout"), "order-service");

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.TIMEOUT);
    }

    private OpsToolFacade facade(OpsDataProvider provider, Duration timeout) {
        OpsToolFacade facade = new OpsToolFacade(
                policy,
                provider,
                Executors.newVirtualThreadPerTaskExecutor(),
                timeout);
        facades.add(facade);
        return facade;
    }
}
