package com.cc.opsagent.identity.application;

import com.cc.opsagent.audit.SecurityAuditPort;
import com.cc.opsagent.audit.SecurityAuditPort.SecurityAuditEvent;
import com.cc.opsagent.identity.domain.UserCredential;
import com.cc.opsagent.identity.security.TenantPrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.Map;

@Service
public class UserAuthenticator {

    private static final String BAD_CREDENTIALS = "Invalid tenant, username, or password";

    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditPort audit;

    public UserAuthenticator(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            SecurityAuditPort audit) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public TenantPrincipal authenticate(
            String tenantCode,
            String username,
            String rawPassword) {
        UserCredential credential = repository
                .findEnabledByTenantCodeAndUsername(tenantCode, username)
                .orElseThrow(this::badCredentials);

        if (!passwordEncoder.matches(rawPassword, credential.passwordHash())) {
            throw badCredentials();
        }

        TenantPrincipal principal = new TenantPrincipal(
                credential.tenantId(),
                credential.userId(),
                credential.username(),
                Set.of(credential.role()));
        audit.record(new SecurityAuditEvent(
                principal.tenantId(), principal.userId(),
                "AUTHENTICATION_SUCCEEDED", "SUCCEEDED",
                "LOGIN", null, Map.of()));
        return principal;
    }

    private BadCredentialsException badCredentials() {
        audit.record(new SecurityAuditEvent(
                null, null, "AUTHENTICATION_FAILED", "REJECTED",
                "LOGIN", null, Map.of("reason", "INVALID_CREDENTIALS")));
        return new BadCredentialsException(BAD_CREDENTIALS);
    }
}
