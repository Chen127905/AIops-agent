package com.cc.opsagent.integration.application;

import com.cc.opsagent.identity.security.TenantContext;
import com.cc.opsagent.integration.domain.ManagedService;
import com.cc.opsagent.simulator.application.OpsContext;
import com.cc.opsagent.simulator.application.OpsDataProvider.HealthSnapshot;
import com.cc.opsagent.simulator.infrastructure.RoutingOpsDataProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@Service
public class ManagedServiceService {
    private final ManagedServiceRepository repository;
    private final RoutingOpsDataProvider provider;

    public ManagedServiceService(
            ManagedServiceRepository repository,
            RoutingOpsDataProvider provider) {
        this.repository = repository;
        this.provider = provider;
    }

    @Transactional(readOnly = true)
    public List<ManagedService> list() {
        return repository.findAll(TenantContext.requireTenantId());
    }

    @Transactional
    public ManagedService create(ManagedServiceRepository.ManagedServiceDraft draft) {
        validate(draft);
        long tenantId = TenantContext.requireTenantId();
        long id = repository.insert(tenantId, normalize(draft));
        return require(tenantId, id);
    }

    @Transactional
    public ManagedService update(long id, ManagedServiceRepository.ManagedServiceDraft draft) {
        validate(draft);
        long tenantId = TenantContext.requireTenantId();
        if (repository.update(tenantId, id, normalize(draft)) != 1) notFound();
        return require(tenantId, id);
    }

    @Transactional
    public void delete(long id) {
        if (repository.delete(TenantContext.requireTenantId(), id) != 1) notFound();
    }

    @Transactional(readOnly = true)
    public HealthSnapshot test(long id) {
        long tenantId = TenantContext.requireTenantId();
        ManagedService service = require(tenantId, id);
        return provider.getHealth(
                new OpsContext(tenantId, Math.max(1, System.currentTimeMillis()),
                        "managed:" + service.name()), service.name());
    }

    private ManagedService require(long tenantId, long id) {
        ManagedService value = repository.findById(tenantId, id);
        if (value == null) notFound();
        return value;
    }

    private void validate(ManagedServiceRepository.ManagedServiceDraft value) {
        URI uri;
        try { uri = URI.create(value.baseUrl()); }
        catch (RuntimeException exception) { throw badRequest("baseUrl 不是合法 URL"); }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw badRequest("baseUrl 仅支持 HTTP 或 HTTPS");
        }
        if (uri.getHost() == null) throw badRequest("baseUrl 必须包含主机名");
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw badRequest("baseUrl 不能包含账号、查询参数或片段");
        }
        String host = uri.getHost().toLowerCase();
        if ("169.254.169.254".equals(host) || "metadata.google.internal".equals(host)) {
            throw badRequest("禁止访问云实例元数据地址");
        }
        validatePath("healthPath", value.healthPath(), true);
        validatePath("metricsPath", value.metricsPath(), false);
        validatePath("logsPath", value.logsPath(), false);
        validatePath("dependenciesPath", value.dependenciesPath(), false);
        validatePath("operationsPath", value.operationsPath(), false);
        if (value.bearerTokenEnv() != null && !value.bearerTokenEnv().isBlank()
                && !value.bearerTokenEnv().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw badRequest("凭证环境变量名格式不正确");
        }
    }

    private void validatePath(String field, String path, boolean required) {
        if (required && (path == null || path.isBlank())) throw badRequest(field + " 不能为空");
        if (path != null && !path.isBlank() && !path.startsWith("/")) {
            throw badRequest(field + " 必须以 / 开头");
        }
    }

    private ManagedServiceRepository.ManagedServiceDraft normalize(
            ManagedServiceRepository.ManagedServiceDraft value) {
        return new ManagedServiceRepository.ManagedServiceDraft(
                value.name().trim(), value.systemName().trim(), value.environment().trim().toUpperCase(),
                stripTrailingSlash(value.baseUrl().trim()), value.healthPath().trim(), blank(value.metricsPath()),
                blank(value.logsPath()), blank(value.dependenciesPath()), blank(value.operationsPath()),
                blank(value.bearerTokenEnv()), value.enabled());
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private void notFound() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接入服务不存在"); }
}
