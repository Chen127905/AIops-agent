package com.cc.opsagent.simulator.application;

import com.cc.opsagent.simulator.domain.ScenarioState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface OpsDataProvider {

    HealthSnapshot getHealth(OpsContext context, String service);

    List<MetricSeries> queryMetrics(
            OpsContext context,
            String service,
            String metricName);

    List<LogEntry> queryLogs(
            OpsContext context,
            String service,
            String query);

    List<DependencyStatus> getDependencies(
            OpsContext context,
            String service);

    OperationResult executeApprovedOperation(
            OpsContext context,
            ApprovedOperation operation);

    void reset(OpsContext context);

    record HealthSnapshot(
            String service,
            String status,
            String summary,
            ScenarioState scenarioState) {
    }

    record MetricSeries(
            String service,
            String name,
            String unit,
            List<MetricPoint> points) {

        public MetricSeries {
            points = List.copyOf(points);
        }
    }

    record MetricPoint(Instant timestamp, double value) {
    }

    record LogEntry(Instant timestamp, String level, String message) {
    }

    record DependencyStatus(String service, String status) {
    }

    sealed interface ApprovedOperation permits RestartService, ChangeConfig {

        long tenantId();

        long taskId();

        String type();

        String service();

        Map<String, String> parameters();
    }

    record RestartService(
            long tenantId,
            long taskId,
            String service) implements ApprovedOperation {

        public RestartService {
            requireScope(tenantId, taskId);
            requireService(service);
        }

        @Override
        public String type() {
            return "RESTART_SERVICE";
        }

        @Override
        public Map<String, String> parameters() {
            return Map.of();
        }
    }

    record ChangeConfig(
            long tenantId,
            long taskId,
            String service,
            Map<String, String> parameters) implements ApprovedOperation {

        public ChangeConfig {
            requireScope(tenantId, taskId);
            requireService(service);
            parameters = Map.copyOf(parameters);
        }

        @Override
        public String type() {
            return "CHANGE_CONFIG";
        }
    }

    record OperationResult(
            String operationType,
            String service,
            ScenarioState state,
            boolean changed) {
    }

    private static void requireService(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service must not be blank");
        }
    }

    private static void requireScope(long tenantId, long taskId) {
        if (tenantId <= 0 || taskId <= 0) {
            throw new IllegalArgumentException(
                    "approved operation tenantId and taskId must be positive");
        }
    }
}
