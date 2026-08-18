package com.cc.opsagent.integration.web;

import com.cc.opsagent.integration.application.ManagedServiceRepository.ManagedServiceDraft;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ManagedServiceRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 128) String systemName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}") String environment,
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 256) String healthPath,
        @Size(max = 256) String metricsPath,
        @Size(max = 256) String logsPath,
        @Size(max = 256) String dependenciesPath,
        @Size(max = 256) String operationsPath,
        @Size(max = 128) String bearerTokenEnv,
        boolean enabled) {
    ManagedServiceDraft toDraft() {
        return new ManagedServiceDraft(name, systemName, environment, baseUrl,
                healthPath, metricsPath, logsPath, dependenciesPath,
                operationsPath, bearerTokenEnv, enabled);
    }
}
