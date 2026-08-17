package com.cc.opsagent.identity.infrastructure;

import com.cc.opsagent.identity.application.UserCredentialRepository;
import com.cc.opsagent.identity.domain.UserCredential;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcUserCredentialRepository implements UserCredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserCredentialRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserCredential> findEnabledByTenantCodeAndUsername(
            String tenantCode,
            String username) {
        return jdbcTemplate.query("""
                        SELECT u.id, u.tenant_id, u.username, u.password_hash, u.role
                        FROM user_account u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE t.code = ?
                          AND u.username = ?
                          AND t.status = 'ACTIVE'
                          AND u.status = 'ACTIVE'
                        """,
                (resultSet, rowNumber) -> new UserCredential(
                        resultSet.getLong("id"),
                        resultSet.getLong("tenant_id"),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        resultSet.getString("role")),
                tenantCode,
                username).stream().findFirst();
    }
}
