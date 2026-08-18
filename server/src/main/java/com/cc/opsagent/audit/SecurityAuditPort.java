package com.cc.opsagent.audit;

import java.util.Map;

@FunctionalInterface
public interface SecurityAuditPort {

    void record(SecurityAuditEvent event);

    static SecurityAuditPort noop() {
        return event -> { };
    }

    record SecurityAuditEvent(
            Long tenantId,
            Long userId,
            String eventType,
            String outcome,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {

        public SecurityAuditEvent {
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("audit event type is required");
            }
            if (outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("audit outcome is required");
            }
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
