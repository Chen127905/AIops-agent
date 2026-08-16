package com.cc.opsagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessDatabaseMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ops_agent")
            .withUsername("ops_agent")
            .withPassword("test-password");

    @DynamicPropertySource
    static void businessDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.business.url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.business.username", MYSQL::getUsername);
        registry.add("app.datasource.business.password", MYSQL::getPassword);
        registry.add("app.datasource.business.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    @Qualifier("businessJdbcTemplate")
    JdbcTemplate jdbcTemplate;

    @Test
    void migratesIdentityBaseline() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('tenant', 'user_account', 'flyway_schema_history')
                """, Integer.class);

        assertThat(tableCount).isEqualTo(3);

        Integer successfulVersionOne = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1' AND success = 1
                """, Integer.class);

        assertThat(successfulVersionOne).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateTenantCode() {
        jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES (?, ?)",
                "duplicate-code-test", "Tenant One");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES (?, ?)",
                "duplicate-code-test", "Tenant Two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scopesUsernameUniquenessToTenant() {
        jdbcTemplate.update(
                "INSERT INTO tenant (code, name) VALUES (?, ?), (?, ?)",
                "username-tenant-a", "Tenant A",
                "username-tenant-b", "Tenant B");

        Long tenantA = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, "username-tenant-a");
        Long tenantB = jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, "username-tenant-b");

        insertUser(tenantA, "operator");

        assertThatThrownBy(() -> insertUser(tenantA, "operator"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertUser(tenantB, "operator"))
                .doesNotThrowAnyException();
    }

    private void insertUser(Long tenantId, String username) {
        jdbcTemplate.update("""
                        INSERT INTO user_account
                            (tenant_id, username, password_hash, display_name, role)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                tenantId, username, "test-password-hash", "Test Operator", "OPERATOR");
    }
}
