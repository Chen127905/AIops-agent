package com.cc.opsagent.integration.application;

import com.cc.opsagent.integration.domain.ManagedService;

import java.util.List;

public interface ManagedServiceRepository {
    List<ManagedService> findAll(long tenantId);
    ManagedService findById(long tenantId, long id);
    ManagedService findByName(long tenantId, String name);
    long insert(long tenantId, ManagedServiceDraft draft);
    int update(long tenantId, long id, ManagedServiceDraft draft);
    int delete(long tenantId, long id);

    record ManagedServiceDraft(
            String name, String systemName, String environment, String baseUrl,
            String healthPath, String metricsPath, String logsPath,
            String dependenciesPath, String operationsPath,
            String bearerTokenEnv, boolean enabled) {
    }
}
