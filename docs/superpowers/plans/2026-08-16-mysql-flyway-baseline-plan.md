# MySQL and Flyway Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Spring Boot application connect to the MySQL business database, run a versioned identity baseline migration, and prove the migration against a real MySQL container.

**Architecture:** Bind the business database under `app.datasource.business` and expose explicitly named `businessDataSource` and `businessJdbcTemplate` beans. Create an explicit `businessFlyway` bean for `classpath:db/mysql`; PostgreSQL and pgvector remain outside this slice and can later add parallel named beans without changing business consumers.

**Tech Stack:** Java 21, Spring Boot 4.0.6, MySQL 8.4, HikariCP, Flyway 11, Spring JDBC, JUnit 5, AssertJ, Testcontainers 1.21.4.

## Global Constraints

- Work only in the existing `feature/foundation` linked worktree.
- Use Java 21 for Maven and tests.
- Do not use a real developer database in automated tests.
- Do not embed real credentials in tracked files.
- Keep PostgreSQL/pgvector configuration out of this slice.
- Write and observe a failing integration test before production database configuration or SQL.

---

### Task 1: Synchronize the Verified Application Startup Fix

**Files:**
- Modify: `server/src/main/resources/application.yml`
- Modify: `server/src/test/java/com/cc/opsagent/OpsAgentApplicationTest.java`

**Interfaces:**
- Consumes: the reviewed fix from practice commit `ee60cbc`.
- Produces: a default application that starts before database configuration and a test that boots a real random-port web server.

- [ ] **Step 1: Apply the reviewed patch**

Temporarily exclude `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` in `application.yml`. Replace the test-only exclusion with:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
```

- [ ] **Step 2: Verify the baseline**

Run:

```powershell
mvn -f server/pom.xml clean verify
```

Expected: one test passes and the packaged application starts when run with a random port.

- [ ] **Step 3: Commit the synchronized fix**

```powershell
git add server/src/main/resources/application.yml server/src/test/java/com/cc/opsagent/OpsAgentApplicationTest.java
git commit -m "fix: allow foundation app to start without datasource"
```

### Task 2: Prove the Missing MySQL Migration Boundary

**Files:**
- Create: `server/src/test/java/com/cc/opsagent/config/BusinessDatabaseMigrationIT.java`

**Interfaces:**
- Consumes: Testcontainers MySQL 8.4 and dynamic `app.datasource.business.*` properties.
- Produces: a test contract requiring beans named `businessDataSource` and `businessJdbcTemplate`, Flyway history, and the `tenant` and `user_account` tables.

- [ ] **Step 1: Write the failing integration test**

```java
@Testcontainers
@SpringBootTest
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
}
```

- [ ] **Step 2: Run the test and observe RED**

Run:

```powershell
mvn -f server/pom.xml -Dtest=BusinessDatabaseMigrationIT test
```

Expected: FAIL because `businessJdbcTemplate` does not exist. If Docker is unavailable, stop and report the infrastructure blocker instead of treating a skipped test as RED.

### Task 3: Add the Business DataSource and Flyway Migration

**Files:**
- Modify: `server/pom.xml`
- Create: `server/src/main/java/com/cc/opsagent/config/DataSourceConfig.java`
- Create: `server/src/main/java/com/cc/opsagent/config/FlywayConfig.java`
- Create: `server/src/main/resources/db/mysql/V1__identity.sql`
- Modify: `server/src/main/resources/application.yml`

**Interfaces:**
- Produces: `DataSource businessDataSource()`, `JdbcTemplate businessJdbcTemplate(DataSource)`, and `Flyway businessFlyway(DataSource)`.
- Migration contract: unique tenant code and unique `(tenant_id, username)` account identity.

- [ ] **Step 1: Bind and expose the business DataSource**

Use `DataSourceProperties` bound to `app.datasource.business`, build a Hikari data source, mark the business data source and JDBC template `@Primary`, and name every bean explicitly.

- [ ] **Step 2: Register integration tests with Maven Failsafe**

Configure `maven-failsafe-plugin` with the `integration-test` and `verify` goals so every class named `*IT` runs during `mvn verify`. Do not allow missing Docker to turn the database contract into a skipped test.

- [ ] **Step 3: Configure explicit Flyway ownership**

Create `businessFlyway` with `initMethod = "migrate"`, the `businessDataSource`, and location `classpath:db/mysql`. Disable Spring Boot's generic Flyway auto-run so adding a vector data source later cannot make migration ownership ambiguous.

- [ ] **Step 4: Create the identity baseline**

Create `tenant` with a unique `code`, lifecycle status, and microsecond timestamps. Create `user_account` with a foreign key to `tenant`, unique `(tenant_id, username)`, password hash, display name, role, lifecycle status, and timestamps. Use `utf8mb4` with `utf8mb4_0900_ai_ci`.

- [ ] **Step 5: Replace the temporary DataSource exclusion**

Remove `spring.autoconfigure.exclude`. Add environment-overridable local defaults:

```yaml
app:
  datasource:
    business:
      url: ${MYSQL_URL:jdbc:mysql://localhost:3307/ops_agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}
      username: ${MYSQL_USERNAME:ops_agent}
      password: ${MYSQL_PASSWORD:change-me-mysql}
      driver-class-name: com.mysql.cj.jdbc.Driver

spring:
  flyway:
    enabled: false
```

- [ ] **Step 6: Run GREEN and the regression suite**

Run:

```powershell
mvn -f server/pom.xml -Dtest=BusinessDatabaseMigrationIT test
mvn -f server/pom.xml clean verify
docker compose --env-file .env.example config --quiet
git diff --check
```

Expected: migration integration test passes against MySQL 8.4, the application suite passes, Compose is valid, and Git reports no whitespace errors.

- [ ] **Step 7: Commit**

```powershell
git add server/pom.xml server/src/main/java/com/cc/opsagent/config server/src/main/resources server/src/test/java/com/cc/opsagent/config
git commit -m "feat: add MySQL Flyway baseline"
```

## Acceptance Gate

- The integration test uses a real disposable MySQL 8.4 container.
- `mvn verify` executes `BusinessDatabaseMigrationIT` through Maven Failsafe.
- Flyway creates exactly one successful versioned migration.
- `tenant`, `user_account`, and `flyway_schema_history` exist.
- The application no longer excludes `DataSourceAutoConfiguration`.
- No PostgreSQL data source or vector-store bean is added.
- The worktree is clean after commits.
