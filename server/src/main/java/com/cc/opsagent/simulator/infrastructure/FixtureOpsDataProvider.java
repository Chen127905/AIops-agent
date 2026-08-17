package com.cc.opsagent.simulator.infrastructure;

import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider;
import com.cc.opsagent.simulator.domain.OpsScenario;
import com.cc.opsagent.simulator.domain.ScenarioState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FixtureOpsDataProvider implements OpsDataProvider {

    private final ScenarioCatalog catalog;
    private final Map<RuntimeKey, ScenarioState> runtimeStates = new ConcurrentHashMap<>();

    public FixtureOpsDataProvider(ScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public HealthSnapshot getHealth(OpsContext context, String service) {
        OpsScenario scenario = requireScenario(context, service);
        ScenarioState state = stateOf(context, scenario);
        if (state == ScenarioState.RECOVERED) {
            return new HealthSnapshot(
                    service,
                    "UP",
                    "Recovered after the approved fixture operation.",
                    state);
        }
        return new HealthSnapshot(
                service,
                scenario.health().status(),
                scenario.health().summary(),
                state);
    }

    @Override
    public List<MetricSeries> queryMetrics(
            OpsContext context,
            String service,
            String metricName) {
        OpsScenario scenario = requireScenario(context, service);
        Instant end = Instant.parse(scenario.logs().getFirst().timestamp());
        return scenario.metrics().stream()
                .filter(metric -> metricName == null
                        || metricName.isBlank()
                        || metric.name().equals(metricName))
                .map(metric -> toSeries(service, end, metric))
                .toList();
    }

    @Override
    public List<LogEntry> queryLogs(
            OpsContext context,
            String service,
            String query) {
        OpsScenario scenario = requireScenario(context, service);
        String normalizedQuery = query == null
                ? ""
                : query.toLowerCase(Locale.ROOT);
        return scenario.logs().stream()
                .filter(log -> normalizedQuery.isBlank()
                        || log.message().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .map(log -> new LogEntry(
                        Instant.parse(log.timestamp()), log.level(), log.message()))
                .toList();
    }

    @Override
    public List<DependencyStatus> getDependencies(
            OpsContext context,
            String service) {
        OpsScenario scenario = requireScenario(context, service);
        boolean recovered = stateOf(context, scenario) == ScenarioState.RECOVERED;
        return scenario.dependencies().stream()
                .map(dependency -> new DependencyStatus(
                        dependency.service(), recovered ? "UP" : dependency.status()))
                .toList();
    }

    @Override
    public OperationResult executeApprovedOperation(
            OpsContext context,
            ApprovedOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (operation.tenantId() != context.tenantId()
                || operation.taskId() != context.taskId()) {
            throw new IllegalArgumentException(
                    "Approved operation scope does not match the ops context");
        }
        OpsScenario scenario = requireScenario(context, operation.service());
        assertApprovedOperation(scenario, operation);
        RuntimeKey key = RuntimeKey.from(context);
        AtomicBoolean changed = new AtomicBoolean(false);
        runtimeStates.compute(key, (ignored, current) -> {
            ScenarioState effective = current == null ? scenario.initialState() : current;
            if (effective != scenario.recoveredState()) {
                changed.set(true);
            }
            return scenario.recoveredState();
        });
        return new OperationResult(
                operation.type(),
                operation.service(),
                scenario.recoveredState(),
                changed.get());
    }

    @Override
    public void reset(OpsContext context) {
        catalog.require(context.scenarioKey());
        runtimeStates.remove(RuntimeKey.from(context));
    }

    private OpsScenario requireScenario(OpsContext context, String service) {
        OpsScenario scenario = catalog.require(context.scenarioKey());
        if (service == null || !scenario.service().equals(service)) {
            throw new IllegalArgumentException(
                    "Service %s does not match scenario %s"
                            .formatted(service, context.scenarioKey()));
        }
        return scenario;
    }

    private ScenarioState stateOf(OpsContext context, OpsScenario scenario) {
        return runtimeStates.getOrDefault(
                RuntimeKey.from(context), scenario.initialState());
    }

    private MetricSeries toSeries(
            String service,
            Instant end,
            OpsScenario.MetricFixture fixture) {
        int pointCount = fixture.values().size();
        List<MetricPoint> points = java.util.stream.IntStream
                .range(0, pointCount)
                .mapToObj(index -> new MetricPoint(
                        end.minusSeconds((long) (pointCount - index - 1) * 60),
                        fixture.values().get(index)))
                .toList();
        return new MetricSeries(service, fixture.name(), fixture.unit(), points);
    }

    private void assertApprovedOperation(
            OpsScenario scenario,
            ApprovedOperation operation) {
        OpsScenario.ApprovedOperation approved = scenario.approvedOperation();
        if (!approved.type().equals(operation.type())
                || !approved.service().equals(operation.service())
                || !approved.parameters().equals(operation.parameters())) {
            throw new IllegalArgumentException(
                    "Operation does not match the scenario approved operation");
        }
    }

    private record RuntimeKey(long tenantId, long taskId, String scenarioKey) {

        private static RuntimeKey from(OpsContext context) {
            return new RuntimeKey(
                    context.tenantId(), context.taskId(), context.scenarioKey());
        }
    }
}
