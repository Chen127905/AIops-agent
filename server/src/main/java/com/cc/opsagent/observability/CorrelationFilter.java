package com.cc.opsagent.observability;

import com.cc.opsagent.identity.security.TenantPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_TRACE =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern TICKET = Pattern.compile("/tickets/(\\d+)");
    private static final Pattern TASK = Pattern.compile("/agent-tasks/(\\d+)");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = traceId(request.getHeader(TRACE_HEADER));
        TenantPrincipal principal = principal();
        try (CorrelationContext.Scope ignored = CorrelationContext.open(
                traceId,
                principal == null ? null : principal.tenantId(),
                id(TICKET, request.getRequestURI()),
                id(TASK, request.getRequestURI()), null)) {
            response.setHeader(TRACE_HEADER, traceId);
            filterChain.doFilter(request, response);
        }
    }

    private String traceId(String candidate) {
        return candidate != null && SAFE_TRACE.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    private Long id(Pattern pattern, String uri) {
        Matcher matcher = pattern.matcher(uri == null ? "" : uri);
        if (!matcher.find()) return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private TenantPrincipal principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof TenantPrincipal value
                ? value : null;
    }
}
