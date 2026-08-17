package com.cc.opsagent.identity.security;

import java.util.Set;

public record TenantPrincipal(
        long tenantId,
        long userId,
        String username,
        Set<String> roles) {

    public TenantPrincipal {
        roles = Set.copyOf(roles);
    }
}
