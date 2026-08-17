package com.cc.opsagent.identity.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TenantContext {

    private TenantContext() {
    }

    public static TenantPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            throw new AccessDeniedException("Authenticated tenant principal is required");
        }
        return principal;
    }

    public static long requireTenantId() {
        return requirePrincipal().tenantId();
    }

    public static long requireUserId() {
        return requirePrincipal().userId();
    }
}
