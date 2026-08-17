package com.cc.opsagent.tool.application;

import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider;
import com.cc.opsagent.simulator.application.OpsDataProvider.ChangeConfig;
import com.cc.opsagent.simulator.application.OpsDataProvider.DependencyStatus;
import com.cc.opsagent.simulator.application.OpsDataProvider.HealthSnapshot;
import com.cc.opsagent.simulator.application.OpsDataProvider.LogEntry;
import com.cc.opsagent.simulator.application.OpsDataProvider.MetricPoint;
import com.cc.opsagent.simulator.application.OpsDataProvider.MetricSeries;
import com.cc.opsagent.simulator.application.OpsDataProvider.OperationResult;
import com.cc.opsagent.simulator.application.OpsDataProvider.RestartService;
import com.cc.opsagent.tool.domain.ToolDecision;
import com.cc.opsagent.tool.domain.ToolExecutionStatus;
import com.cc.opsagent.tool.domain.ToolInvocationRequest;
import com.cc.opsagent.tool.domain.ToolResult;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class OpsToolFacade implements AutoCloseable {

    private static final int MAX_LOG_LINES = 200;
    private static final int MAX_LOG_BYTES = 32 * 1024;
    private static final int MAX_METRIC_POINTS = 500;

    private final ToolPolicyService policy;
    private final OpsDataProvider provider;
    private final ExecutorService executor;
    private final Duration executionTimeout;

    @Autowired
    public OpsToolFacade(ToolPolicyService policy, OpsDataProvider provider) {
        this(
                policy,
                provider,
                Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofSeconds(5));
    }

    OpsToolFacade(
            ToolPolicyService policy,
            OpsDataProvider provider,
            ExecutorService executor,
            Duration executionTimeout) {
        this.policy = policy;
        this.provider = provider;
        this.executor = executor;
        this.executionTimeout = executionTimeout;
    }

    public ToolResult<HealthSnapshot> getServiceHealth(
            OpsContext context,
            String service) {
        ToolInvocationRequest request = request(
                context,
                "getServiceHealth",
                Map.of("service", service),
                null,
                null);
        return execute(request, () -> new BoundedResult<>(
                provider.getHealth(context, service), false));
    }

    public ToolResult<List<MetricSeries>> queryMetrics(
            OpsContext context,
            String service,
            String metricName,
            int requestedMaxPoints) {
        requirePositive("requestedMaxPoints", requestedMaxPoints);
        Map<String, Object> arguments = arguments("service", service);
        putIfNotNull(arguments, "metricName", metricName);
        arguments.put("maxPoints", requestedMaxPoints);
        ToolInvocationRequest request = request(
                context, "queryMetrics", arguments, null, null);
        return execute(request, () -> boundMetrics(
                provider.queryMetrics(context, service, metricName),
                Math.min(requestedMaxPoints, MAX_METRIC_POINTS)));
    }

    public ToolResult<List<LogEntry>> queryLogs(
            OpsContext context,
            String service,
            String query,
            int requestedMaxLines) {
        requirePositive("requestedMaxLines", requestedMaxLines);
        Map<String, Object> arguments = arguments("service", service);
        putIfNotNull(arguments, "query", query);
        arguments.put("maxLines", requestedMaxLines);
        ToolInvocationRequest request = request(
                context, "queryLogs", arguments, null, null);
        return execute(request, () -> boundLogs(
                provider.queryLogs(context, service, query),
                Math.min(requestedMaxLines, MAX_LOG_LINES)));
    }

    public ToolResult<List<DependencyStatus>> getServiceDependencies(
            OpsContext context,
            String service) {
        ToolInvocationRequest request = request(
                context,
                "getServiceDependencies",
                Map.of("service", service),
                null,
                null);
        return execute(request, () -> new BoundedResult<>(
                provider.getDependencies(context, service), false));
    }

    public ToolResult<OperationResult> restartService(
            OpsContext context,
            String service,
            String approvedRequestId,
            String idempotencyKey) {
        ToolInvocationRequest request = request(
                context,
                "restartService",
                Map.of("service", service),
                approvedRequestId,
                idempotencyKey);
        return execute(request, () -> new BoundedResult<>(
                provider.executeApprovedOperation(
                        context,
                        new RestartService(
                                context.tenantId(), context.taskId(), service)),
                false));
    }

    public ToolResult<OperationResult> changeConfig(
            OpsContext context,
            String service,
            Map<String, String> changes,
            String approvedRequestId,
            String idempotencyKey) {
        Map<String, Object> arguments = arguments("service", service);
        arguments.put("changes", Map.copyOf(changes));
        ToolInvocationRequest request = request(
                context,
                "changeConfig",
                arguments,
                approvedRequestId,
                idempotencyKey);
        return execute(request, () -> new BoundedResult<>(
                provider.executeApprovedOperation(
                        context,
                        new ChangeConfig(
                                context.tenantId(),
                                context.taskId(),
                                service,
                                changes)),
                false));
    }

    private <T> ToolResult<T> execute(
            ToolInvocationRequest request,
            Callable<BoundedResult<T>> operation) {
        ToolDecision decision = policy.evaluate(request);
        if (!decision.allowed()) {
            return ToolResult.withoutData(
                    ToolExecutionStatus.REJECTED,
                    decision.reason(),
                    decision.risk());
        }
        if (decision.requiresApproval() && !decision.approvalSatisfied()) {
            return ToolResult.withoutData(
                    ToolExecutionStatus.APPROVAL_REQUIRED,
                    decision.reason(),
                    decision.risk());
        }
        Future<BoundedResult<T>> future = executor.submit(operation);
        try {
            Duration policyTimeout = decision.descriptor().timeout();
            Duration timeout = executionTimeout.compareTo(policyTimeout) < 0
                    ? executionTimeout
                    : policyTimeout;
            BoundedResult<T> bounded = future.get(
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            return ToolResult.success(
                    bounded.data(), decision.risk(), bounded.truncated());
        } catch (TimeoutException exception) {
            future.cancel(true);
            return ToolResult.withoutData(
                    ToolExecutionStatus.TIMEOUT,
                    "Tool exceeded its execution timeout",
                    decision.risk());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.withoutData(
                    ToolExecutionStatus.FAILED,
                    "Tool execution was interrupted",
                    decision.risk());
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return ToolResult.withoutData(
                    ToolExecutionStatus.FAILED,
                    cause == null ? "Tool execution failed" : cause.getMessage(),
                    decision.risk());
        }
    }

    private BoundedResult<List<MetricSeries>> boundMetrics(
            List<MetricSeries> source,
            int pointLimit) {
        List<MetricSeries> bounded = new ArrayList<>();
        int remaining = pointLimit;
        int sourceCount = 0;
        for (MetricSeries series : source) {
            sourceCount += series.points().size();
            if (remaining <= 0) {
                continue;
            }
            int keep = Math.min(remaining, series.points().size());
            List<MetricPoint> points = series.points().subList(0, keep);
            bounded.add(new MetricSeries(
                    series.service(), series.name(), series.unit(), points));
            remaining -= keep;
        }
        return new BoundedResult<>(
                List.copyOf(bounded), sourceCount > pointLimit);
    }

    private BoundedResult<List<LogEntry>> boundLogs(
            List<LogEntry> source,
            int lineLimit) {
        List<LogEntry> bounded = new ArrayList<>();
        int usedBytes = 0;
        boolean truncated = source.size() > lineLimit;
        for (LogEntry entry : source) {
            if (bounded.size() >= lineLimit) {
                truncated = true;
                break;
            }
            String prefix = entry.timestamp() + " " + entry.level() + " ";
            int prefixBytes = utf8Bytes(prefix);
            int remainingBytes = MAX_LOG_BYTES - usedBytes - prefixBytes;
            if (remainingBytes <= 0) {
                truncated = true;
                break;
            }
            String message = truncateUtf8(entry.message(), remainingBytes);
            if (!message.equals(entry.message())) {
                truncated = true;
            }
            bounded.add(new LogEntry(entry.timestamp(), entry.level(), message));
            usedBytes += prefixBytes + utf8Bytes(message);
            if (usedBytes >= MAX_LOG_BYTES) {
                truncated = true;
                break;
            }
        }
        return new BoundedResult<>(List.copyOf(bounded), truncated);
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (utf8Bytes(value) <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            int bytes = utf8Bytes(next);
            if (used + bytes > maxBytes) {
                break;
            }
            result.append(next);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private ToolInvocationRequest request(
            OpsContext context,
            String toolName,
            Map<String, Object> arguments,
            String approvedRequestId,
            String idempotencyKey) {
        return new ToolInvocationRequest(
                context.tenantId(),
                context.taskId(),
                context.scenarioKey(),
                toolName,
                arguments,
                approvedRequestId,
                idempotencyKey);
    }

    private Map<String, Object> arguments(String key, Object value) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(key, value);
        return arguments;
    }

    private void putIfNotNull(
            Map<String, Object> target,
            String key,
            Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void requirePositive(String field, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private record BoundedResult<T>(T data, boolean truncated) {
    }
}
