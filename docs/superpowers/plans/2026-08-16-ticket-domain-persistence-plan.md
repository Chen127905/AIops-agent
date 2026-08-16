# Ticket Domain and Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit ticket state machine, a tenant-safe Flyway V2 schema, and MyBatis-Plus persistence whose queries and updates enforce tenant and expected-state boundaries.

**Architecture:** Keep ticket vocabulary in `ticket.domain`, deterministic transition policy in `ticket.application`, and SQL-facing operations in `ticket.infrastructure`. Use database constraints as the final ownership and enum defense, then combine an in-memory transition check with a single conditional SQL update so concurrent callers cannot overwrite each other.

**Tech Stack:** Java 21, Spring Boot 4.0.6, MyBatis-Plus 3.5.17, MySQL 8.4, Flyway 11, JUnit 5, AssertJ, Testcontainers 1.21.4.

## Global Constraints

- Work only in the existing `feature/foundation` linked worktree.
- Use Java 21 for Maven and tests.
- Follow strict RED-GREEN-REFACTOR; observe the named failure before adding the production behavior.
- Do not modify the already-applied `V1__identity.sql` migration.
- Keep Controller, JWT, `TenantContext`, Agent, simulator, tool-policy, and PostgreSQL code out of this slice.
- Every ticket read and update method must require `tenantId` in its SQL predicate.
- Do not use a developer database in automated tests; use disposable MySQL 8.4 Testcontainers.

---

## File Structure

```text
server/src/main/java/com/cc/opsagent/ticket/
  domain/Ticket.java
  domain/TicketSeverity.java
  domain/TicketStatus.java
  application/TicketStateMachine.java
  infrastructure/TicketMapper.java
server/src/main/resources/db/mysql/
  V2__ticket.sql
server/src/test/java/com/cc/opsagent/ticket/
  application/TicketStateMachineTest.java
  infrastructure/TicketMapperIT.java
```

### Task 1: Implement the Deterministic Ticket State Machine

**Files:**
- Create: `server/src/test/java/com/cc/opsagent/ticket/application/TicketStateMachineTest.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/domain/TicketStatus.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/domain/TicketSeverity.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/application/TicketStateMachine.java`

**Interfaces:**
- Produces: `boolean TicketStateMachine.canTransition(TicketStatus from, TicketStatus to)`.
- Produces: `boolean TicketStatus.isTerminal()` for later application-service decisions.

- [ ] **Step 1: Write the failing state-machine test**

The tests catch a removed normal edge, a missing exceptional exit, a terminal-state escape, and accidental acceptance of same/null states. Create:

```java
package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStateMachineTest {

    private final TicketStateMachine stateMachine = new TicketStateMachine();

    @ParameterizedTest
    @CsvSource({
            "OPEN, TRIAGING",
            "TRIAGING, DIAGNOSING",
            "DIAGNOSING, WAITING_APPROVAL",
            "WAITING_APPROVAL, EXECUTING",
            "EXECUTING, VERIFYING",
            "VERIFYING, RESOLVED"
    })
    void allowsNormalPath(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {
            "OPEN", "TRIAGING", "DIAGNOSING",
            "WAITING_APPROVAL", "EXECUTING", "VERIFYING"
    })
    void allowsEveryActiveStateToEnterExceptionalTerminalState(TicketStatus from) {
        assertThat(stateMachine.canTransition(from, TicketStatus.FAILED)).isTrue();
        assertThat(stateMachine.canTransition(from, TicketStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.canTransition(from, TicketStatus.TIMEOUT)).isTrue();
        assertThat(stateMachine.canTransition(from, TicketStatus.MANUAL_REQUIRED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {
            "RESOLVED", "FAILED", "CANCELLED", "TIMEOUT", "MANUAL_REQUIRED"
    })
    void rejectsLeavingTerminalState(TicketStatus from) {
        assertThat(stateMachine.canTransition(from, TicketStatus.TRIAGING)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "RESOLVED, FAILED",
            "FAILED, CANCELLED",
            "CANCELLED, TIMEOUT",
            "TIMEOUT, MANUAL_REQUIRED",
            "MANUAL_REQUIRED, RESOLVED"
    })
    void rejectsTransitionsBetweenTerminalStates(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.canTransition(from, to)).isFalse();
    }

    @Test
    void rejectsSkippingTheNormalPath() {
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, TicketStatus.DIAGNOSING)).isFalse();
        assertThat(stateMachine.canTransition(TicketStatus.DIAGNOSING, TicketStatus.EXECUTING)).isFalse();
    }

    @Test
    void rejectsSameState() {
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, TicketStatus.OPEN)).isFalse();
    }

    @Test
    void rejectsNullState() {
        assertThat(stateMachine.canTransition(null, TicketStatus.OPEN)).isFalse();
        assertThat(stateMachine.canTransition(TicketStatus.OPEN, null)).isFalse();
    }
}
```

- [ ] **Step 2: Run RED and confirm the reason**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketStateMachineTest test
```

Expected: test compilation fails because `TicketStatus` and `TicketStateMachine` do not exist. This is the intended missing-feature failure; fix any unrelated syntax or JDK failure before continuing.

- [ ] **Step 3: Add the two domain enums**

Create `TicketStatus.java`:

```java
package com.cc.opsagent.ticket.domain;

public enum TicketStatus {
    OPEN(false),
    TRIAGING(false),
    DIAGNOSING(false),
    WAITING_APPROVAL(false),
    EXECUTING(false),
    VERIFYING(false),
    RESOLVED(true),
    FAILED(true),
    CANCELLED(true),
    TIMEOUT(true),
    MANUAL_REQUIRED(true);

    private final boolean terminal;

    TicketStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
```

Create `TicketSeverity.java`:

```java
package com.cc.opsagent.ticket.domain;

public enum TicketSeverity {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
```

- [ ] **Step 4: Implement the minimal transition policy**

Create `TicketStateMachine.java`:

```java
package com.cc.opsagent.ticket.application;

import com.cc.opsagent.ticket.domain.TicketStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class TicketStateMachine {

    private static final Set<TicketStatus> EXCEPTIONAL_TERMINALS = EnumSet.of(
            TicketStatus.FAILED,
            TicketStatus.CANCELLED,
            TicketStatus.TIMEOUT,
            TicketStatus.MANUAL_REQUIRED);

    private static final Map<TicketStatus, TicketStatus> NORMAL_TRANSITIONS = Map.of(
            TicketStatus.OPEN, TicketStatus.TRIAGING,
            TicketStatus.TRIAGING, TicketStatus.DIAGNOSING,
            TicketStatus.DIAGNOSING, TicketStatus.WAITING_APPROVAL,
            TicketStatus.WAITING_APPROVAL, TicketStatus.EXECUTING,
            TicketStatus.EXECUTING, TicketStatus.VERIFYING,
            TicketStatus.VERIFYING, TicketStatus.RESOLVED);

    public boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null || from == to || from.isTerminal()) {
            return false;
        }
        return NORMAL_TRANSITIONS.get(from) == to || EXCEPTIONAL_TERMINALS.contains(to);
    }
}
```

- [ ] **Step 5: Run GREEN and commit**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketStateMachineTest test
```

Expected: all parameterized and ordinary test invocations pass with no failures.

Commit:

```powershell
git add server/src/main/java/com/cc/opsagent/ticket server/src/test/java/com/cc/opsagent/ticket/application
git commit -m "feat: add ticket state machine"
```

### Task 2: Add the Tenant-Safe Flyway V2 Schema

**Files:**
- Create: `server/src/test/java/com/cc/opsagent/ticket/infrastructure/TicketMapperIT.java`
- Create: `server/src/main/resources/db/mysql/V2__ticket.sql`

**Interfaces:**
- Consumes: V1 `tenant` and `user_account` tables.
- Produces: `ticket` table and successful Flyway version `2`.
- Produces: database-level tenant/reporter ownership and enum-value guarantees.

- [ ] **Step 1: Write the missing-migration test**

Create `TicketMapperIT.java` with the container, dynamic properties, helpers, and first test:

```java
package com.cc.opsagent.ticket.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketMapperIT {

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
    void migratesTicketSchema() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ticket'
                """, Integer.class);
        assertThat(tableCount).isEqualTo(1);

        Integer successfulVersionTwo = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '2' AND success = 1
                """, Integer.class);
        assertThat(successfulVersionTwo).isEqualTo(1);
    }

    private long insertTenant(String code) {
        jdbcTemplate.update("INSERT INTO tenant (code, name) VALUES (?, ?)", code, code);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant WHERE code = ?", Long.class, code);
    }

    private long insertUser(long tenantId, String username) {
        jdbcTemplate.update("""
                        INSERT INTO user_account
                            (tenant_id, username, password_hash, display_name, role)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                tenantId, username, "test-password-hash", username, "OPERATOR");
        return jdbcTemplate.queryForObject("""
                SELECT id FROM user_account
                WHERE tenant_id = ? AND username = ?
                """, Long.class, tenantId, username);
    }
}
```

- [ ] **Step 2: Run RED and confirm the reason**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketMapperIT test
```

Expected: the application starts with Flyway V1, then `migratesTicketSchema` fails because `ticket` and successful version `2` are absent.

- [ ] **Step 3: Add the minimal V2 table without the behavior constraints**

Create `V2__ticket.sql` initially as:

```sql
CREATE TABLE ticket
(
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT UNSIGNED NOT NULL,
    reporter_id        BIGINT UNSIGNED NOT NULL,
    title              VARCHAR(120)    NOT NULL,
    description        TEXT            NOT NULL,
    affected_service   VARCHAR(128)    NULL,
    category           VARCHAR(64)     NULL,
    severity           VARCHAR(16)     NOT NULL DEFAULT 'UNKNOWN',
    status             VARCHAR(32)     NOT NULL DEFAULT 'OPEN',
    resolution_summary TEXT            NULL,
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ticket_tenant_status_created_at (tenant_id, status, created_at),
    KEY idx_ticket_tenant_reporter_created_at (tenant_id, reporter_id, created_at),
    CONSTRAINT fk_ticket_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
```

Run the same test. Expected: `migratesTicketSchema` passes and proves V2 discovery before ownership/check constraints are introduced.

- [ ] **Step 4: Add failing database behavior tests**

Append these tests before the helper methods in `TicketMapperIT`:

```java
@Test
void rejectsReporterFromAnotherTenant() {
    long tenantA = insertTenant("ticket-owner-a");
    long tenantB = insertTenant("ticket-owner-b");
    long tenantBUser = insertUser(tenantB, "tenant-b-reporter");

    assertThatThrownBy(() -> insertTicket(
            tenantA, tenantBUser, "Cross-tenant reporter", "Must be rejected", "OPEN", "UNKNOWN"))
            .isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void rejectsUnknownPersistedStatusAndSeverity() {
    long tenantId = insertTenant("ticket-enum-owner");
    long reporterId = insertUser(tenantId, "enum-reporter");

    assertThatThrownBy(() -> insertTicket(
            tenantId, reporterId, "Invalid status", "Must be rejected", "NOT_A_STATUS", "UNKNOWN"))
            .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(() -> insertTicket(
            tenantId, reporterId, "Invalid severity", "Must be rejected", "OPEN", "URGENTEST"))
            .isInstanceOf(DataAccessException.class);
}

private void insertTicket(
        long tenantId,
        long reporterId,
        String title,
        String description,
        String status,
        String severity) {
    jdbcTemplate.update("""
                    INSERT INTO ticket
                        (tenant_id, reporter_id, title, description, status, severity)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
            tenantId, reporterId, title, description, status, severity);
}
```

- [ ] **Step 5: Run the behavior tests and observe RED**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketMapperIT test
```

Expected: `rejectsReporterFromAnotherTenant` and `rejectsUnknownPersistedStatusAndSeverity` fail because no exception is thrown. The migration-discovery test remains green.

- [ ] **Step 6: Add the minimal ownership and enum constraints**

Update V2 so it begins with:

```sql
ALTER TABLE user_account
    ADD UNIQUE KEY uk_user_account_id_tenant (id, tenant_id);
```

Add these table constraints after the two secondary indexes:

```sql
CONSTRAINT ck_ticket_severity
    CHECK (severity IN ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
CONSTRAINT ck_ticket_status
    CHECK (status IN (
        'OPEN', 'TRIAGING', 'DIAGNOSING', 'WAITING_APPROVAL',
        'EXECUTING', 'VERIFYING', 'RESOLVED', 'FAILED',
        'CANCELLED', 'TIMEOUT', 'MANUAL_REQUIRED'
    )),
CONSTRAINT fk_ticket_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenant (id),
CONSTRAINT fk_ticket_reporter_tenant
    FOREIGN KEY (reporter_id, tenant_id) REFERENCES user_account (id, tenant_id)
```

Ensure there is only one `fk_ticket_tenant` declaration in the final file.

- [ ] **Step 7: Run GREEN and commit the migration slice**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketMapperIT test
```

Expected: 3 tests pass, V2 is successful, the cross-tenant foreign key raises `DataIntegrityViolationException`, and invalid CHECK values raise `DataAccessException`. MySQL 8.4 reports CHECK violations with SQLState `HY000`, so Spring JDBC does not classify them as the narrower integrity subtype.

Commit:

```powershell
git add server/src/main/resources/db/mysql/V2__ticket.sql server/src/test/java/com/cc/opsagent/ticket/infrastructure/TicketMapperIT.java
git commit -m "feat: add tenant-safe ticket schema"
```

### Task 3: Add MyBatis-Plus Ticket Persistence

**Files:**
- Modify: `server/src/main/resources/application.yml`
- Create: `server/src/main/java/com/cc/opsagent/ticket/domain/Ticket.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/infrastructure/TicketMapper.java`
- Modify: `server/src/test/java/com/cc/opsagent/ticket/infrastructure/TicketMapperIT.java`

**Interfaces:**
- Consumes: V2 `ticket` table and the two domain enums.
- Produces: `TicketMapper.selectByTenantIdAndId(long, long)`.
- Produces: `TicketMapper.transitionStatus(long, long, TicketStatus, TicketStatus)` returning an affected-row count.

- [ ] **Step 1: Write failing mapper behavior tests**

Add these imports and field to `TicketMapperIT`:

```java
import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketSeverity;
import com.cc.opsagent.ticket.domain.TicketStatus;

@Autowired
TicketMapper ticketMapper;
```

Add tests:

```java
@Test
void findsTicketOnlyInsideOwningTenant() {
    long tenantA = insertTenant("mapper-tenant-a");
    long tenantB = insertTenant("mapper-tenant-b");
    long reporterA = insertUser(tenantA, "mapper-reporter-a");
    Ticket ticket = newTicket(tenantA, reporterA, "Database connection pool exhausted");

    assertThat(ticketMapper.insert(ticket)).isEqualTo(1);
    assertThat(ticket.getId()).isNotNull();

    Ticket owningTenantTicket = ticketMapper.selectByTenantIdAndId(tenantA, ticket.getId());
    Ticket otherTenantTicket = ticketMapper.selectByTenantIdAndId(tenantB, ticket.getId());

    assertThat(owningTenantTicket).isNotNull();
    assertThat(owningTenantTicket.getTitle()).isEqualTo("Database connection pool exhausted");
    assertThat(owningTenantTicket.getStatus()).isEqualTo(TicketStatus.OPEN);
    assertThat(otherTenantTicket).isNull();
}

@Test
void allowsOnlyOneExpectedStatusUpdate() {
    long tenantId = insertTenant("transition-tenant");
    long reporterId = insertUser(tenantId, "transition-reporter");
    Ticket ticket = newTicket(tenantId, reporterId, "Redis commands are timing out");
    ticketMapper.insert(ticket);

    int first = ticketMapper.transitionStatus(
            tenantId, ticket.getId(), TicketStatus.OPEN, TicketStatus.TRIAGING);
    int staleSecond = ticketMapper.transitionStatus(
            tenantId, ticket.getId(), TicketStatus.OPEN, TicketStatus.TRIAGING);

    assertThat(first).isEqualTo(1);
    assertThat(staleSecond).isZero();
    assertThat(ticketMapper.selectByTenantIdAndId(tenantId, ticket.getId()).getStatus())
            .isEqualTo(TicketStatus.TRIAGING);
}

private Ticket newTicket(long tenantId, long reporterId, String title) {
    Ticket ticket = new Ticket();
    ticket.setTenantId(tenantId);
    ticket.setReporterId(reporterId);
    ticket.setTitle(title);
    ticket.setDescription("The service is unhealthy and requires diagnosis.");
    ticket.setAffectedService("order-service");
    ticket.setSeverity(TicketSeverity.UNKNOWN);
    ticket.setStatus(TicketStatus.OPEN);
    return ticket;
}
```

- [ ] **Step 2: Run RED and confirm the reason**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketMapperIT test
```

Expected: test compilation fails because `Ticket` and `TicketMapper` do not exist. No production mapper or entity should exist before this run.

- [ ] **Step 3: Configure database-generated MyBatis IDs**

Append to `application.yml`:

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto
```

This preserves a persistence-annotation-free domain entity while making `BaseMapper.insert` use the V2 auto-increment key.

- [ ] **Step 4: Create the Ticket JavaBean**

Create `Ticket.java` with exactly these fields and conventional public getters/setters:

```java
package com.cc.opsagent.ticket.domain;

import java.time.LocalDateTime;

public class Ticket {
    private Long id;
    private Long tenantId;
    private Long reporterId;
    private String title;
    private String description;
    private String affectedService;
    private String category;
    private TicketSeverity severity;
    private TicketStatus status;
    private String resolutionSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getReporterId() { return reporterId; }
    public void setReporterId(Long reporterId) { this.reporterId = reporterId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAffectedService() { return affectedService; }
    public void setAffectedService(String affectedService) { this.affectedService = affectedService; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public TicketSeverity getSeverity() { return severity; }
    public void setSeverity(TicketSeverity severity) { this.severity = severity; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public String getResolutionSummary() { return resolutionSummary; }
    public void setResolutionSummary(String resolutionSummary) { this.resolutionSummary = resolutionSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

Formatting may expand one-line accessors, but method names and types must remain identical.

- [ ] **Step 5: Implement tenant-scoped and conditional mapper SQL**

Create `TicketMapper.java`:

```java
package com.cc.opsagent.ticket.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cc.opsagent.ticket.domain.Ticket;
import com.cc.opsagent.ticket.domain.TicketStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    @Select("""
            SELECT id, tenant_id, reporter_id, title, description,
                   affected_service, category, severity, status,
                   resolution_summary, created_at, updated_at
            FROM ticket
            WHERE tenant_id = #{tenantId} AND id = #{ticketId}
            """)
    Ticket selectByTenantIdAndId(
            @Param("tenantId") long tenantId,
            @Param("ticketId") long ticketId);

    @Update("""
            UPDATE ticket
            SET status = #{targetStatus}, updated_at = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = #{tenantId}
              AND id = #{ticketId}
              AND status = #{expectedStatus}
            """)
    int transitionStatus(
            @Param("tenantId") long tenantId,
            @Param("ticketId") long ticketId,
            @Param("expectedStatus") TicketStatus expectedStatus,
            @Param("targetStatus") TicketStatus targetStatus);
}
```

- [ ] **Step 6: Run GREEN and perform a mutation check**

Run:

```powershell
mvn -f server/pom.xml -Dtest=TicketMapperIT test
```

Expected: 5 tests pass.

Mutation check without committing the mutation:

1. Temporarily remove `tenant_id = #{tenantId}` from the select SQL.
2. Run `TicketMapperIT`; `findsTicketOnlyInsideOwningTenant` must fail because tenant B sees the ticket.
3. Restore the tenant predicate.
4. Temporarily remove `status = #{expectedStatus}` from the update SQL.
5. Run `TicketMapperIT`; `allowsOnlyOneExpectedStatusUpdate` must fail because both updates affect one row.
6. Restore the status predicate and rerun the test to GREEN.

- [ ] **Step 7: Commit the persistence slice**

```powershell
git add server/src/main/resources/application.yml server/src/main/java/com/cc/opsagent/ticket server/src/test/java/com/cc/opsagent/ticket/infrastructure/TicketMapperIT.java
git commit -m "feat: add tenant-scoped ticket persistence"
```

### Task 4: Run the Acceptance Gate

**Files:**
- Verify only; no production files should change.

**Interfaces:**
- Confirms the complete slice integrates with the existing MySQL/Flyway baseline.

- [ ] **Step 1: Run a clean full Maven lifecycle**

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f server/pom.xml clean verify
```

Expected:

- `TicketStateMachineTest` passes through Surefire.
- `BusinessDatabaseMigrationIT` and `TicketMapperIT` pass through Failsafe.
- Flyway applies V1 and V2 in each disposable database.
- Maven ends with `BUILD SUCCESS`.

- [ ] **Step 2: Validate deployment configuration and Git state**

```powershell
docker compose --project-directory . --env-file .env.example -f compose.yml config --quiet
git diff --check
git status --short
```

Expected: Compose and diff checks exit `0`; the worktree is clean after commits.

## Acceptance Checklist

- All normal and exceptional transition behaviors are protected by unit tests.
- Terminal, same-state, and null transitions are rejected.
- Flyway history contains successful V1 and V2 migrations.
- MySQL rejects invalid status, invalid severity, and a cross-tenant reporter.
- MyBatis insert returns an auto-increment ticket ID.
- Tenant B cannot read tenant A's ticket.
- Exactly one stale expected-state update succeeds.
- No out-of-scope Controller, JWT, Agent, simulator, tool-policy, or PostgreSQL code exists.
