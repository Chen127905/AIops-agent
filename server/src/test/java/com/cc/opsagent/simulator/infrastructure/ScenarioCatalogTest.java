package com.cc.opsagent.simulator.infrastructure;

import com.cc.opsagent.simulator.domain.OpsScenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCatalogTest {

    @Test
    void loadsFiveValidScenariosFromClasspath() {
        ScenarioCatalog catalog = new ScenarioCatalog();

        assertThat(catalog.all())
                .extracting(OpsScenario::key)
                .containsExactlyInAnyOrder(
                        "db-pool-exhausted",
                        "redis-timeout",
                        "api-error-rate",
                        "mq-backlog",
                        "disk-full");
        assertThat(catalog.require("redis-timeout").expectedTools())
                .contains("getServiceHealth", "queryMetrics", "queryLogs");
    }

    @Test
    void rejectsDuplicateScenarioKeys() {
        String scenario = validScenarioYaml("duplicate-key");

        assertThatThrownBy(() -> ScenarioCatalog.fromYaml(List.of(scenario, scenario)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate-key");
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> ScenarioCatalog.fromYaml(List.of("""
                key: incomplete
                service: order-service
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("category");
    }

    @Test
    void exposesImmutableScenarioData() {
        ScenarioCatalog catalog = new ScenarioCatalog();
        OpsScenario scenario = catalog.require("mq-backlog");

        assertThatThrownBy(() -> catalog.all().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scenario.expectedTools().add("executeShell"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scenario.metrics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownScenarioKeys() {
        assertThatThrownBy(() -> new ScenarioCatalog().require("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist");
    }

    private String validScenarioYaml(String key) {
        return """
                key: %s
                service: order-service
                category: CACHE
                severity: HIGH
                initialState: FAULTED
                health:
                  status: DEGRADED
                  summary: cache commands are timing out
                metrics:
                  - name: redis_command_latency_ms
                    unit: ms
                    values: [30.0, 80.0, 250.0]
                logs:
                  - timestamp: "2026-08-17T10:00:00Z"
                    level: ERROR
                    message: Redis command timed out
                dependencies:
                  - service: redis-cluster
                    status: DEGRADED
                rootCause: Redis network latency exceeds the command timeout.
                expectedTools: [getServiceHealth, queryMetrics, queryLogs]
                forbiddenTools: [executeShell]
                requiresApproval: true
                approvedOperation:
                  type: RESTART_SERVICE
                  service: order-service
                  parameters: {}
                recoveredState: RECOVERED
                """.formatted(key);
    }
}
