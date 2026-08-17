package com.cc.opsagent.identity.application;

import com.cc.opsagent.identity.domain.UserCredential;

import java.util.Optional;

public interface UserCredentialRepository {

    Optional<UserCredential> findEnabledByTenantCodeAndUsername(
            String tenantCode,
            String username);
}
