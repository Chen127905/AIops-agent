package com.cc.opsagent.agent.application;

import com.cc.opsagent.model.ModelProvider;

public record AgentTaskCommand(
        long taskId,
        long tenantId,
        long ticketId,
        String scenarioKey,
        String affectedService,
        String title,
        String description,
        ModelProvider provider,
        AgentBudget budget) {

    public AgentTaskCommand {
        if (taskId <= 0 || tenantId <= 0 || ticketId <= 0) {
            throw new IllegalArgumentException("task, tenant and ticket IDs must be positive");
        }
        if (scenarioKey == null || scenarioKey.isBlank()
                || affectedService == null || affectedService.isBlank()
                || title == null || title.isBlank()
                || description == null || description.isBlank()) {
            throw new IllegalArgumentException("agent task context must not be blank");
        }
        if (provider == null || budget == null) {
            throw new IllegalArgumentException("model provider and budget are required");
        }
    }
}
