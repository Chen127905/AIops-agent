package com.cc.opsagent.integration.domain;

import java.time.LocalDateTime;

public record ManagedService(
        long id,
        long tenantId,
        String name,
        String systemName,
        String environment,
        String baseUrl,
        String healthPath,
        String metricsPath,
        String logsPath,
        String dependenciesPath,
        String operationsPath,
        String bearerTokenEnv,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
