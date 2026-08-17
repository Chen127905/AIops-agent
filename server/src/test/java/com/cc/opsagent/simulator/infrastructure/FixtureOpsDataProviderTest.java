package com.cc.opsagent.simulator.infrastructure;

import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider.ChangeConfig;
import com.cc.opsagent.simulator.application.OpsDataProvider.RestartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureOpsDataProviderTest {

    private FixtureOpsDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FixtureOpsDataProvider(new ScenarioCatalog());
    }

    @Test
    void approvedRestartMovesOnlyThatTenantAndTaskToRecoveredState() {
        OpsContext target = new OpsContext(1L, 10L, "redis-timeout");
        OpsContext otherTenant = new OpsContext(2L, 10L, "redis-timeout");
        OpsContext otherTask = new OpsContext(1L, 11L, "redis-timeout");

        assertThat(provider.getHealth(target, "order-service").status())
                .isEqualTo("DEGRADED");
        provider.executeApprovedOperation(
                target, new RestartService(1L, 10L, "order-service"));

        assertThat(provider.getHealth(target, "order-service").status())
                .isEqualTo("UP");
        assertThat(provider.getHealth(otherTenant, "order-service").status())
                .isEqualTo("DEGRADED");
        assertThat(provider.getHealth(otherTask, "order-service").status())
                .isEqualTo("DEGRADED");
    }

    @Test
    void resetRestoresTheDeterministicInitialState() {
        OpsContext context = new OpsContext(1L, 20L, "api-error-rate");
        ChangeConfig rollback = new ChangeConfig(
                1L,
                20L,
                "payment-api",
                Map.of("routingVersion", "stable-2026-08-16"));

        provider.executeApprovedOperation(context, rollback);
        assertThat(provider.getHealth(context, "payment-api").status()).isEqualTo("UP");

        provider.reset(context);

        assertThat(provider.getHealth(context, "payment-api").status()).isEqualTo("DOWN");
        assertThat(provider.queryMetrics(context, "payment-api", null))
                .isEqualTo(provider.queryMetrics(context, "payment-api", null));
    }

    @Test
    void rejectsAServiceOrOperationThatDoesNotMatchTheScenario() {
        OpsContext context = new OpsContext(1L, 30L, "redis-timeout");

        assertThatThrownBy(() -> provider.getHealth(context, "payment-api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment-api");
        assertThatThrownBy(() -> provider.executeApprovedOperation(
                context,
                new ChangeConfig(
                        1L,
                        30L,
                        "order-service",
                        Map.of("timeout", "1s"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved operation");
    }

    @Test
    void rejectsAnApprovedOperationFromAnotherTenantOrTask() {
        OpsContext context = new OpsContext(1L, 35L, "redis-timeout");

        assertThatThrownBy(() -> provider.executeApprovedOperation(
                context, new RestartService(2L, 35L, "order-service")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
        assertThatThrownBy(() -> provider.executeApprovedOperation(
                context, new RestartService(1L, 36L, "order-service")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void returnsImmutableTypedEvidence() {
        OpsContext context = new OpsContext(1L, 40L, "mq-backlog");

        var metrics = provider.queryMetrics(
                context, "notification-consumer", "mq_consumer_lag");
        var logs = provider.queryLogs(
                context, "notification-consumer", "consumer");
        var dependencies = provider.getDependencies(
                context, "notification-consumer");

        assertThat(metrics).hasSize(1);
        assertThat(metrics.getFirst().points()).hasSize(4);
        assertThat(logs).hasSize(1);
        assertThat(dependencies).hasSize(1);
        assertThatThrownBy(metrics::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metrics.getFirst().points().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(logs::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(dependencies::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesContextIdentifiers() {
        assertThatThrownBy(() -> new OpsContext(0L, 1L, "redis-timeout"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpsContext(1L, 0L, "redis-timeout"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpsContext(1L, 1L, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
