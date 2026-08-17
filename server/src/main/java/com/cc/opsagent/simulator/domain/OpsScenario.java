package com.cc.opsagent.simulator.domain;

import com.cc.opsagent.ticket.domain.TicketSeverity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record OpsScenario(
        String key,
        String service,
        String category,
        TicketSeverity severity,
        ScenarioState initialState,
        HealthFixture health,
        List<MetricFixture> metrics,
        List<LogFixture> logs,
        List<DependencyFixture> dependencies,
        String rootCause,
        Set<String> expectedTools,
        Set<String> forbiddenTools,
        boolean requiresApproval,
        ApprovedOperation approvedOperation,
        ScenarioState recoveredState) {

    public OpsScenario {
        metrics = List.copyOf(metrics);
        logs = List.copyOf(logs);
        dependencies = List.copyOf(dependencies);
        expectedTools = Set.copyOf(expectedTools);
        forbiddenTools = Set.copyOf(forbiddenTools);
    }

    public record HealthFixture(String status, String summary) {
    }

    public record MetricFixture(String name, String unit, List<Double> values) {

        public MetricFixture {
            values = List.copyOf(values);
        }
    }

    public record LogFixture(String timestamp, String level, String message) {
    }

    public record DependencyFixture(String service, String status) {
    }

    public record ApprovedOperation(
            String type,
            String service,
            Map<String, String> parameters) {

        public ApprovedOperation {
            parameters = Map.copyOf(parameters);
        }
    }
}
