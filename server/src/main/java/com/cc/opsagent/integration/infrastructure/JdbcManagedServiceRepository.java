package com.cc.opsagent.integration.infrastructure;

import com.cc.opsagent.integration.application.ManagedServiceRepository;
import com.cc.opsagent.integration.domain.ManagedService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class JdbcManagedServiceRepository implements ManagedServiceRepository {

    private static final String COLUMNS = """
            id, tenant_id, name, system_name, environment, base_url,
            health_path, metrics_path, logs_path, dependencies_path,
            operations_path, bearer_token_env, enabled, created_at, updated_at
            """;
    private final JdbcTemplate jdbc;

    public JdbcManagedServiceRepository(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ManagedService> findAll(long tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM managed_service WHERE tenant_id = ? ORDER BY system_name, name",
                (rs, row) -> map(rs), tenantId);
    }

    @Override
    public ManagedService findById(long tenantId, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM managed_service WHERE tenant_id = ? AND id = ?",
                (rs, row) -> map(rs), tenantId, id).stream().findFirst().orElse(null);
    }

    @Override
    public ManagedService findByName(long tenantId, String name) {
        return jdbc.query("SELECT " + COLUMNS + " FROM managed_service WHERE tenant_id = ? AND name = ? AND enabled = TRUE",
                (rs, row) -> map(rs), tenantId, name).stream().findFirst().orElse(null);
    }

    @Override
    public long insert(long tenantId, ManagedServiceDraft value) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO managed_service
                    (tenant_id, name, system_name, environment, base_url, health_path,
                     metrics_path, logs_path, dependencies_path, operations_path,
                     bearer_token_env, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bind(statement, tenantId, value);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("managed service ID was not generated");
        return key.longValue();
    }

    @Override
    public int update(long tenantId, long id, ManagedServiceDraft value) {
        return jdbc.update("""
                UPDATE managed_service SET name=?, system_name=?, environment=?, base_url=?,
                health_path=?, metrics_path=?, logs_path=?, dependencies_path=?,
                operations_path=?, bearer_token_env=?, enabled=?
                WHERE tenant_id=? AND id=?
                """, value.name(), value.systemName(), value.environment(), value.baseUrl(),
                value.healthPath(), value.metricsPath(), value.logsPath(), value.dependenciesPath(),
                value.operationsPath(), value.bearerTokenEnv(), value.enabled(), tenantId, id);
    }

    @Override
    public int delete(long tenantId, long id) {
        return jdbc.update("DELETE FROM managed_service WHERE tenant_id=? AND id=?", tenantId, id);
    }

    private void bind(PreparedStatement statement, long tenantId, ManagedServiceDraft value)
            throws java.sql.SQLException {
        statement.setLong(1, tenantId); statement.setString(2, value.name());
        statement.setString(3, value.systemName()); statement.setString(4, value.environment());
        statement.setString(5, value.baseUrl()); statement.setString(6, value.healthPath());
        statement.setString(7, value.metricsPath()); statement.setString(8, value.logsPath());
        statement.setString(9, value.dependenciesPath()); statement.setString(10, value.operationsPath());
        statement.setString(11, value.bearerTokenEnv()); statement.setBoolean(12, value.enabled());
    }

    private ManagedService map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ManagedService(rs.getLong("id"), rs.getLong("tenant_id"),
                rs.getString("name"), rs.getString("system_name"), rs.getString("environment"),
                rs.getString("base_url"), rs.getString("health_path"), rs.getString("metrics_path"),
                rs.getString("logs_path"), rs.getString("dependencies_path"),
                rs.getString("operations_path"), rs.getString("bearer_token_env"),
                rs.getBoolean("enabled"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
