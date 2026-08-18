package com.cc.opsagent.simulator.infrastructure;

import com.cc.opsagent.integration.application.ManagedServiceRepository;
import com.cc.opsagent.integration.domain.ManagedService;
import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider;
import com.cc.opsagent.simulator.domain.ScenarioState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Primary
@Component
public class RoutingOpsDataProvider implements OpsDataProvider {
    private final FixtureOpsDataProvider fixture;
    private final ScenarioCatalog scenarios;
    private final ManagedServiceRepository services;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public RoutingOpsDataProvider(
            FixtureOpsDataProvider fixture,
            ScenarioCatalog scenarios,
            ManagedServiceRepository services) {
        this.fixture = fixture;
        this.scenarios = scenarios;
        this.services = services;
    }

    @Override
    public HealthSnapshot getHealth(OpsContext context, String service) {
        if (scenarios.contains(context.scenarioKey())) return fixture.getHealth(context, service);
        ManagedService target = targetOrNull(context, service);
        if (target == null) return new HealthSnapshot(service, "UNKNOWN",
                "该服务尚未接入实时可观测端点；本次结论仅依据工单描述与知识库。",
                ScenarioState.FAULTED);
        JsonNode root = getJson(target, target.healthPath());
        String status = text(root, "status", "UNKNOWN");
        String summary = text(root, "summary", "HTTP " + target.healthPath() + " 返回 " + status);
        return new HealthSnapshot(service, status, summary,
                "UP".equalsIgnoreCase(status) || "HEALTHY".equalsIgnoreCase(status)
                        ? ScenarioState.RECOVERED : ScenarioState.FAULTED);
    }

    @Override
    public List<MetricSeries> queryMetrics(OpsContext context, String service, String metricName) {
        if (scenarios.contains(context.scenarioKey())) return fixture.queryMetrics(context, service, metricName);
        ManagedService target = targetOrNull(context, service);
        if (target == null) return List.of();
        if (blank(target.metricsPath())) return List.of();
        String path = target.metricsPath().replace("{metric}", URLEncoder.encode(
                blank(metricName) ? "process.cpu.usage" : metricName, StandardCharsets.UTF_8));
        JsonNode root = getJson(target, path);
        List<MetricPoint> points = new ArrayList<>();
        JsonNode measurements = root.path("measurements");
        if (measurements.isArray()) {
            for (JsonNode item : measurements) if (item.path("value").isNumber()) {
                points.add(new MetricPoint(Instant.now(), item.path("value").asDouble()));
            }
        } else if (root.path("value").isNumber()) {
            points.add(new MetricPoint(Instant.now(), root.path("value").asDouble()));
        }
        String name = text(root, "name", blank(metricName) ? "metric" : metricName);
        return points.isEmpty() ? List.of() : List.of(new MetricSeries(service, name,
                text(root, "unit", "value"), points));
    }

    @Override
    public List<LogEntry> queryLogs(OpsContext context, String service, String query) {
        if (scenarios.contains(context.scenarioKey())) return fixture.queryLogs(context, service, query);
        ManagedService target = targetOrNull(context, service);
        if (target == null) return List.of();
        if (blank(target.logsPath())) return List.of();
        JsonNode root = getJson(target, target.logsPath());
        JsonNode values = root.isArray() ? root : root.path("logs");
        if (!values.isArray()) return List.of();
        List<LogEntry> logs = new ArrayList<>();
        for (JsonNode item : values) {
            String message = text(item, "message", item.asText(""));
            if (blank(query) || message.toLowerCase().contains(query.toLowerCase())) {
                logs.add(new LogEntry(instant(item.path("timestamp").asText(null)),
                        text(item, "level", "INFO"), message));
            }
        }
        return List.copyOf(logs);
    }

    @Override
    public List<DependencyStatus> getDependencies(OpsContext context, String service) {
        if (scenarios.contains(context.scenarioKey())) return fixture.getDependencies(context, service);
        ManagedService target = targetOrNull(context, service);
        if (target == null) return List.of();
        if (blank(target.dependenciesPath())) return List.of();
        JsonNode root = getJson(target, target.dependenciesPath());
        List<DependencyStatus> result = new ArrayList<>();
        JsonNode values = root.isArray() ? root : root.path("dependencies");
        if (values.isArray()) {
            for (JsonNode item : values) result.add(new DependencyStatus(
                    text(item, "service", text(item, "name", "unknown")),
                    text(item, "status", "UNKNOWN")));
        } else if (root.path("components").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.path("components").fields();
            fields.forEachRemaining(item -> result.add(new DependencyStatus(
                    item.getKey(), text(item.getValue(), "status", "UNKNOWN"))));
        }
        return List.copyOf(result);
    }

    @Override
    public OperationResult executeApprovedOperation(OpsContext context, ApprovedOperation operation) {
        if (scenarios.contains(context.scenarioKey())) return fixture.executeApprovedOperation(context, operation);
        ManagedService target = targetOrNull(context, operation.service());
        if (target == null) throw new IllegalStateException(
                "该服务尚未接入，不能执行任何自动变更");
        if (blank(target.operationsPath())) {
            throw new IllegalStateException("该服务没有配置变更操作端点，已停止自动执行并保留人工处置");
        }
        JsonNode response = postJson(target, target.operationsPath(), Map.of(
                "operation", operation.type(), "service", operation.service(),
                "parameters", operation.parameters(), "taskId", operation.taskId()));
        boolean changed = !response.has("success") || response.path("success").asBoolean();
        if (!changed) throw new IllegalStateException(text(response, "message", "远程操作执行失败"));
        return new OperationResult(operation.type(), operation.service(), ScenarioState.RECOVERED, true);
    }

    @Override
    public void reset(OpsContext context) {
        if (scenarios.contains(context.scenarioKey())) fixture.reset(context);
    }

    private ManagedService targetOrNull(OpsContext context, String service) {
        return services.findByName(context.tenantId(), service);
    }

    private JsonNode getJson(ManagedService target, String path) {
        return send(target, HttpRequest.newBuilder(uri(target, path)).GET());
    }

    private JsonNode postJson(ManagedService target, String path, Map<String, Object> body) {
        try {
            return send(target, HttpRequest.newBuilder(uri(target, path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        } catch (IOException exception) {
            throw new IllegalStateException("无法序列化远程操作请求", exception);
        }
    }

    private JsonNode send(ManagedService target, HttpRequest.Builder builder) {
        String envName = target.bearerTokenEnv();
        if (!blank(envName)) {
            String token = System.getenv(envName);
            if (blank(token)) throw new IllegalStateException("服务凭证环境变量 " + envName + " 未配置");
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("远程端点返回 HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("远程诊断请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("无法访问远程诊断端点: " + exception.getMessage(), exception);
        }
    }

    private URI uri(ManagedService target, String path) { return URI.create(target.baseUrl() + path); }
    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field); return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }
    private Instant instant(String value) { try { return value == null ? Instant.now() : Instant.parse(value); } catch (RuntimeException ignored) { return Instant.now(); } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
