package com.cc.opsagent.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

public final class CorrelationContext {

    public static final String TRACE_ID = "trace_id";
    public static final String TENANT_ID = "tenant_id";
    public static final String TICKET_ID = "ticket_id";
    public static final String TASK_ID = "task_id";
    public static final String STEP_ID = "step_id";

    private CorrelationContext() { }

    public static Scope open(
            String traceId,
            Long tenantId,
            Long ticketId,
            Long taskId,
            Integer stepId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        put(TRACE_ID, traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString() : traceId);
        put(TENANT_ID, tenantId);
        put(TICKET_ID, ticketId);
        put(TASK_ID, taskId);
        put(STEP_ID, stepId);
        return new Scope(previous);
    }

    private static void put(String key, Object value) {
        if (value == null) MDC.remove(key);
        else MDC.put(key, String.valueOf(value));
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;
        private boolean closed;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        }
    }
}
