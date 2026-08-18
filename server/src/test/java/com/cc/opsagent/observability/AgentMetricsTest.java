package com.cc.opsagent.observability;

import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    @Test
    void recordsModelLatencyFailureAndTokensUsingOnlyBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordModelCall("QWEN", Duration.ofMillis(120), false, 300);
        metrics.recordNode("decision", "FAILED", Duration.ofMillis(40));
        metrics.recordToolCall(
                "queryLogs", ToolRisk.READ_ONLY,
                ToolExecutionStatus.SUCCESS, Duration.ofMillis(12));
        metrics.recordRetrieval(true, 3, Duration.ofMillis(8));
        metrics.recordTask(AgentTaskStatus.WAITING_APPROVAL, Duration.ofSeconds(1));
        metrics.recordApproval("APPROVED");
        metrics.recordExecutorRejection();

        assertThat(registry.get("ops.agent.model.calls")
                .tag("provider", "QWEN").tag("status", "FAILED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.agent.model.tokens")
                .tag("provider", "QWEN").counter().count()).isEqualTo(300.0);
        assertThat(registry.get("ops.agent.model.latency")
                .tag("provider", "QWEN").timer().count()).isEqualTo(1);
        assertThat(registry.get("ops.agent.retrieval.citations")
                .counter().count()).isEqualTo(3.0);

        Set<String> forbiddenTagKeys = Set.of(
                "tenant", "tenant_id", "ticket", "ticket_id",
                "task", "task_id", "prompt", "error", "message");
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> forbiddenTagKeys.contains(tag.getKey())));
    }

    @Test
    void collapsesUnboundedProviderNodeAndToolValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordModelCall("tenant-model-938", Duration.ZERO, true, 1);
        metrics.recordNode("task-123-secret", "SUCCEEDED", Duration.ZERO);
        metrics.recordToolCall(
                "modelInventedTool-456", ToolRisk.READ_ONLY,
                ToolExecutionStatus.REJECTED, Duration.ZERO);

        assertThat(registry.get("ops.agent.model.calls")
                .tag("provider", "UNKNOWN").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.agent.node.executions")
                .tag("node", "unknown").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.agent.tool.calls")
                .tag("tool", "unknown").counter().count()).isEqualTo(1.0);
    }
}
