package com.cc.opsagent.observability;

import com.cc.opsagent.agent.domain.AgentTaskStatus;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolRisk;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static AgentMetrics noop() {
        return new AgentMetrics(null);
    }

    public void recordNode(String node, String status, Duration duration) {
        if (registry == null) return;
        Counter.builder("ops.agent.node.executions")
                .tag("node", boundedNode(node))
                .tag("status", status(status))
                .register(registry).increment();
        Timer.builder("ops.agent.node.duration")
                .tag("node", boundedNode(node))
                .tag("status", status(status))
                .register(registry).record(nonNegative(duration));
    }

    public void recordModelCall(
            String provider,
            Duration duration,
            boolean success,
            int tokens) {
        if (registry == null) return;
        String safeProvider = provider(provider);
        String outcome = success ? "SUCCEEDED" : "FAILED";
        Counter.builder("ops.agent.model.calls")
                .tag("provider", safeProvider)
                .tag("status", outcome)
                .register(registry).increment();
        Counter.builder("ops.agent.model.tokens")
                .tag("provider", safeProvider)
                .register(registry).increment(Math.max(0, tokens));
        Timer.builder("ops.agent.model.latency")
                .tag("provider", safeProvider)
                .tag("status", outcome)
                .register(registry).record(nonNegative(duration));
    }

    public void recordToolCall(
            String tool,
            ToolRisk risk,
            ToolExecutionStatus status,
            Duration duration) {
        if (registry == null) return;
        Counter.builder("ops.agent.tool.calls")
                .tag("tool", boundedTool(tool))
                .tag("risk", risk == null ? "UNKNOWN" : risk.name())
                .tag("status", status == null ? "UNKNOWN" : status.name())
                .register(registry).increment();
        Timer.builder("ops.agent.tool.latency")
                .tag("tool", boundedTool(tool))
                .tag("status", status == null ? "UNKNOWN" : status.name())
                .register(registry).record(nonNegative(duration));
    }

    public void recordRetrieval(boolean success, int citations, Duration duration) {
        if (registry == null) return;
        String status = success ? "SUCCEEDED" : "FAILED";
        Counter.builder("ops.agent.retrieval.calls")
                .tag("status", status).register(registry).increment();
        Counter.builder("ops.agent.retrieval.citations")
                .register(registry).increment(Math.max(0, citations));
        Timer.builder("ops.agent.retrieval.latency")
                .tag("status", status).register(registry)
                .record(nonNegative(duration));
    }

    public void recordTask(AgentTaskStatus status, Duration duration) {
        if (registry == null) return;
        String outcome = status == null ? "UNKNOWN" : status.name();
        Counter.builder("ops.agent.tasks")
                .tag("status", outcome).register(registry).increment();
        Timer.builder("ops.agent.task.duration")
                .tag("status", outcome).register(registry)
                .record(nonNegative(duration));
    }

    public void recordApproval(String decision) {
        if (registry == null) return;
        Counter.builder("ops.agent.approvals")
                .tag("decision", approval(decision))
                .register(registry).increment();
    }

    public void recordExecutorRejection() {
        if (registry == null) return;
        registry.counter("ops.agent.executor.rejections").increment();
    }

    private Duration nonNegative(Duration value) {
        return value == null || value.isNegative() ? Duration.ZERO : value;
    }

    private String provider(String value) {
        if ("QWEN".equalsIgnoreCase(value)) return "QWEN";
        if ("DEEPSEEK".equalsIgnoreCase(value)) return "DEEPSEEK";
        return "UNKNOWN";
    }

    private String status(String value) {
        return switch (value == null ? "" : value.toUpperCase()) {
            case "STARTED", "SUCCEEDED", "FAILED", "SKIPPED" ->
                    value.toUpperCase();
            default -> "UNKNOWN";
        };
    }

    private String approval(String value) {
        return switch (value == null ? "" : value.toUpperCase()) {
            case "REQUESTED", "APPROVED", "REJECTED", "EXPIRED", "FAILED" ->
                    value.toUpperCase();
            default -> "UNKNOWN";
        };
    }

    private String boundedNode(String value) {
        return switch (value == null ? "" : value) {
            case "triage", "retrieve", "plan", "diagnose",
                 "decision", "verify", "summarize" -> value;
            default -> "unknown";
        };
    }

    private String boundedTool(String value) {
        return switch (value == null ? "" : value) {
            case "getServiceHealth", "queryMetrics", "queryLogs",
                 "getServiceDependencies", "searchRunbook",
                 "restartService", "changeConfig" -> value;
            default -> "unknown";
        };
    }
}
