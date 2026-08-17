package com.cc.opsagent.identity.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void derivesIdentityFromAuthenticatedTenantPrincipal() {
        TenantPrincipal principal = new TenantPrincipal(
                7L, 42L, "alice", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        assertThat(TenantContext.requireTenantId()).isEqualTo(7L);
        assertThat(TenantContext.requireUserId()).isEqualTo(42L);
        assertThat(TenantContext.requirePrincipal()).isSameAs(principal);
    }

    @Test
    void rejectsMissingAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsAuthenticationWithoutTenantPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));

        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(AccessDeniedException.class);
    }
}
