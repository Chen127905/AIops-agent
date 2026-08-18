package com.cc.opsagent.observability;

import com.cc.opsagent.identity.security.TenantPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void exposesSafeTraceAndTenantResourceContextThenClearsMdc() throws Exception {
        TenantPrincipal principal = new TenantPrincipal(
                7, 9, "operator", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/agent-tasks/42/events");
        request.addHeader(CorrelationFilter.TRACE_HEADER, "trace-safe-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<java.util.Map<String, String>> inside = new AtomicReference<>();

        new CorrelationFilter().doFilter(
                request, response,
                (servletRequest, servletResponse) ->
                        inside.set(MDC.getCopyOfContextMap()));

        assertThat(inside.get())
                .containsEntry("trace_id", "trace-safe-123")
                .containsEntry("tenant_id", "7")
                .containsEntry("task_id", "42")
                .doesNotContainKey("ticket_id");
        assertThat(response.getHeader(CorrelationFilter.TRACE_HEADER))
                .isEqualTo("trace-safe-123");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void rejectsUnsafeCallerTraceAndRestoresOuterContext() throws Exception {
        MDC.put("outer", "preserved");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/tickets/18");
        request.addHeader(CorrelationFilter.TRACE_HEADER, "bad trace\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideTrace = new AtomicReference<>();

        new CorrelationFilter().doFilter(
                request, response,
                (servletRequest, servletResponse) ->
                        insideTrace.set(MDC.get("trace_id")));

        assertThat(insideTrace.get())
                .isNotBlank().isNotEqualTo("bad trace\nvalue");
        assertThat(MDC.get("outer")).isEqualTo("preserved");
        assertThat(MDC.get("trace_id")).isNull();
    }
}
