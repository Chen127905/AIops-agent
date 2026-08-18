package com.cc.opsagent.audit;

import com.cc.opsagent.identity.security.TenantPrincipal;
import com.cc.opsagent.security.SensitiveDataRedactor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuditService implements SecurityAuditPort {

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate,
            SensitiveDataRedactor redactor) {
        this.jdbcTemplate = jdbcTemplate;
        this.redactor = redactor;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthenticated(
            String eventType,
            String outcome,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {
        TenantPrincipal principal = currentPrincipal();
        persist(new SecurityAuditEvent(
                principal == null ? null : principal.tenantId(),
                principal == null ? null : principal.userId(),
                eventType, outcome, resourceType, resourceId, details));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SecurityAuditEvent event) {
        persist(event);
    }

    private void persist(SecurityAuditEvent event) {
        jdbcTemplate.update("""
                INSERT INTO security_audit_log
                    (tenant_id, user_id, event_type, outcome,
                     resource_type, resource_id, details)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """, event.tenantId(), event.userId(),
                requireText(event.eventType(), 64),
                requireText(event.outcome(), 24),
                optionalText(event.resourceType(), 64),
                optionalText(event.resourceId(), 128),
                writeJson(redact(event.details())));
    }

    private Map<String, Object> redact(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> safe.put(
                requireText(key, 64), redactValue(value)));
        return Map.copyOf(safe);
    }

    private Object redactValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return redactor.redact(String.valueOf(value), 512);
    }

    private TenantPrincipal currentPrincipal() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof TenantPrincipal principal
                ? principal : null;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("security audit details are invalid", exception);
        }
    }

    private String requireText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("security audit text is required");
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength
                ? trimmed : trimmed.substring(0, maxLength);
    }

    private String optionalText(String value, int maxLength) {
        return value == null || value.isBlank()
                ? null : requireText(value, maxLength);
    }
}
