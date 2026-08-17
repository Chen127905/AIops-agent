package com.cc.opsagent.simulator.application;

public record OpsContext(long tenantId, long taskId, String scenarioKey) {

    public OpsContext {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        if (scenarioKey == null || scenarioKey.isBlank()) {
            throw new IllegalArgumentException("scenarioKey must not be blank");
        }
    }
}
