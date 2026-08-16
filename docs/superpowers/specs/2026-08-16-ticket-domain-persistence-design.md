# Ticket Domain and Persistence Design

## 1. Goal

Build the first production-facing slice of the ticket module: an explicit Java state machine, a tenant-safe MySQL schema, and MyBatis-Plus persistence methods whose database predicates protect tenant and status boundaries.

This slice establishes the deterministic business core that later REST APIs and Agent workflows will call. It does not invoke a model and does not accept a tenant identifier from an HTTP request.

## 2. Scope

### Included

- `TicketStatus` and `TicketSeverity` domain enums.
- A `Ticket` domain entity with no web or Agent dependencies.
- `TicketStateMachine.canTransition(from, to)`.
- Flyway migration `V2__ticket.sql`.
- A MyBatis-Plus `TicketMapper` with tenant-scoped reads and conditional status updates.
- Unit tests for transition rules.
- A real MySQL 8.4 Testcontainers integration test for V2, tenant isolation, database ownership constraints, and competing status updates.

### Excluded

- Controllers, request/response DTOs, pagination, and REST error mapping.
- JWT authentication and `TenantContext`; those are added before exposing ticket APIs.
- Ticket creation and cancellation application services.
- Agent task creation, Graph execution, simulator state, tool policy, and approval records.
- Soft deletion. Operational tickets remain as auditable records.

## 3. Package and File Boundaries

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

The domain package contains business vocabulary. The application package owns transition policy. The infrastructure package owns SQL-facing operations. No class in this slice depends on Spring MVC, Spring AI, or Alibaba Agent Framework.

## 4. Domain Model

### 4.1 Ticket Status

Active statuses:

```text
OPEN
TRIAGING
DIAGNOSING
WAITING_APPROVAL
EXECUTING
VERIFYING
```

Terminal statuses:

```text
RESOLVED
FAILED
CANCELLED
TIMEOUT
MANUAL_REQUIRED
```

The normal path is deliberately explicit:

```text
OPEN -> TRIAGING -> DIAGNOSING -> WAITING_APPROVAL -> EXECUTING -> VERIFYING -> RESOLVED
```

Every active status may transition to `FAILED`, `CANCELLED`, `TIMEOUT`, or `MANUAL_REQUIRED`. Terminal statuses have no outgoing transitions. A same-status transition and any transition containing `null` return `false`.

The first slice does not add retry or re-planning loops. Those rules will be introduced with the Agent runtime only when their persisted task semantics exist.

### 4.2 Ticket Severity

```text
UNKNOWN
LOW
MEDIUM
HIGH
CRITICAL
```

New tickets start as `UNKNOWN`. Classification may assign another severity later.

### 4.3 Ticket Entity

`Ticket` contains:

| Java field | Type | Meaning |
| --- | --- | --- |
| `id` | `Long` | Database-generated ticket ID |
| `tenantId` | `Long` | Owning tenant |
| `reporterId` | `Long` | User account that created the ticket |
| `title` | `String` | Short incident summary, at most 120 characters |
| `description` | `String` | Detailed symptoms |
| `affectedService` | `String` | Optional service name known at creation time |
| `category` | `String` | Optional classification result |
| `severity` | `TicketSeverity` | Current severity, initially `UNKNOWN` |
| `status` | `TicketStatus` | Current business status, initially `OPEN` |
| `resolutionSummary` | `String` | Optional final human-readable outcome |
| `createdAt` | `LocalDateTime` | Creation timestamp |
| `updatedAt` | `LocalDateTime` | Last database update timestamp |

The entity relies on MyBatis naming conventions (`tenantId` to `tenant_id`) instead of importing persistence annotations into the domain package.

## 5. MySQL Schema

`V2__ticket.sql` creates one `ticket` table. It does not create simulator or tool-policy tables.

Important columns and constraints:

- `id BIGINT UNSIGNED AUTO_INCREMENT` primary key.
- `tenant_id` and `reporter_id` are required.
- `title VARCHAR(120)` and `description TEXT` are required.
- `affected_service VARCHAR(128)`, `category VARCHAR(64)`, and `resolution_summary TEXT` are nullable.
- `severity VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'` with a check constraint for the five Java enum names.
- `status VARCHAR(32) NOT NULL DEFAULT 'OPEN'` with a check constraint for the eleven Java enum names.
- Microsecond `created_at` and `updated_at` timestamps follow the V1 convention.
- Index `(tenant_id, status, created_at)` supports tenant ticket lists.
- Index `(tenant_id, reporter_id, created_at)` supports reporter history.

V2 adds a unique parent index `(id, tenant_id)` to `user_account`, then defines a composite foreign key:

```text
ticket(reporter_id, tenant_id)
    -> user_account(id, tenant_id)
```

This makes it impossible to persist a ticket whose reporter belongs to another tenant, even if application code is wrong. A direct `ticket.tenant_id -> tenant.id` foreign key also documents and enforces ticket ownership.

V1 remains immutable. All ticket schema changes belong to V2 or a later migration.

## 6. Persistence Contract

`TicketMapper` extends `BaseMapper<Ticket>` for insertion and exposes two explicit business-safe methods:

```java
Ticket selectByTenantIdAndId(long tenantId, long ticketId);

int transitionStatus(
        long tenantId,
        long ticketId,
        TicketStatus expectedStatus,
        TicketStatus targetStatus);
```

The read query always contains both `id` and `tenant_id`.

The transition is one atomic statement:

```sql
UPDATE ticket
SET status = :targetStatus,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = :ticketId
  AND tenant_id = :tenantId
  AND status = :expectedStatus;
```

The returned row count is the concurrency signal:

- `1`: this caller owned the expected state and changed it.
- `0`: the ticket is absent, belongs to another tenant, or another caller already changed the status.

The mapper does not distinguish those three cases because revealing cross-tenant existence would be unsafe. A later application service may re-read within the same tenant to map the result to a business error.

`TicketStateMachine` decides whether a requested transition is legal before the mapper attempts it. The SQL expected-status predicate remains mandatory because an in-memory check cannot prevent another transaction from winning after the check.

## 7. Test Design

### Unit Tests

`TicketStateMachineTest` proves:

- Every edge in the normal path is allowed.
- Every active state may enter each exceptional terminal state.
- Terminal states cannot transition to active states.
- Same-state and null transitions are rejected.

### MySQL Integration Tests

`TicketMapperIT` starts a disposable MySQL 8.4 container and boots the real Spring application on a random port. Flyway must apply both V1 and V2.

The test proves:

1. `ticket` exists and Flyway history contains successful version `2`.
2. A ticket inserted through MyBatis-Plus can be read by its owning tenant.
3. A different tenant receives `null` for the same ticket ID.
4. A reporter from tenant B cannot be inserted into a tenant A ticket because of the composite foreign key.
5. Two stale `OPEN -> TRIAGING` updates produce row counts `1` and `0`.

Tests use distinct tenant codes and usernames so JUnit execution order does not affect results.

## 8. Error and Security Semantics

- Illegal domain transitions are represented by `canTransition(...) == false`; exception mapping belongs to the future application layer.
- A zero-row conditional update is not retried blindly.
- Cross-tenant reads return no row rather than an authorization detail.
- Database constraints are the final defense for tenant/reporter ownership and valid persisted enum values.
- No tenant ID is accepted from a model, prompt, or untrusted tool input in this slice.

## 9. Acceptance Criteria

- Production code is written only after the corresponding failing test is observed.
- `TicketStateMachineTest` passes all normal, exceptional, terminal, same-state, and null cases.
- `TicketMapperIT` passes against real MySQL 8.4 and confirms successful Flyway V2.
- A cross-tenant reporter insert is rejected by MySQL.
- A cross-tenant ticket read returns no row.
- Competing expected-state updates allow exactly one winner.
- `mvn -f server/pom.xml clean verify` succeeds.
- Docker Compose configuration and `git diff --check` succeed.
- No Controller, JWT, Agent, simulator, or tool-policy implementation is added.
