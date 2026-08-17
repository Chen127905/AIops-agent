package com.cc.opsagent.identity.application;

import com.cc.opsagent.identity.domain.UserCredential;
import com.cc.opsagent.identity.security.TenantPrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class UserAuthenticator {

    private static final String BAD_CREDENTIALS = "Invalid tenant, username, or password";

    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserAuthenticator(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public TenantPrincipal authenticate(
            String tenantCode,
            String username,
            String rawPassword) {
        UserCredential credential = repository
                .findEnabledByTenantCodeAndUsername(tenantCode, username)
                .orElseThrow(() -> new BadCredentialsException(BAD_CREDENTIALS));

        if (!passwordEncoder.matches(rawPassword, credential.passwordHash())) {
            throw new BadCredentialsException(BAD_CREDENTIALS);
        }

        return new TenantPrincipal(
                credential.tenantId(),
                credential.userId(),
                credential.username(),
                Set.of(credential.role()));
    }
}
