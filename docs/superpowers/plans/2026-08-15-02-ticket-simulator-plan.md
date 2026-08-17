# Ticket and Ops Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a tenant-isolated ticket system and deterministic operations simulator with typed, policy-controlled diagnostic tools.

**Architecture:** The ticket module owns business state; the simulator exposes an `OpsDataProvider` port backed by YAML fixtures. The tool center wraps provider methods in strongly typed tool contracts and enforces tenant, validation, timeout, output, and risk policies before execution.

**Tech Stack:** Java 21, Spring Boot 4.0.6, MyBatis-Plus 3.5.17 Spring Boot 4 Starter, MySQL, Spring Validation, Spring Security, Jackson YAML, JUnit 5, Testcontainers.

## Global Constraints

- Continue the package root `com.cc.opsagent` and server-derived `TenantContext`.
- A tenant may never read or mutate another tenant's ticket, scenario, or tool execution.
- Do not let the model provide a tenant ID, Shell command, SQL statement, or file path.
- Keep simulator results deterministic so evaluation can compare them to ground truth.
- Each state transition uses a conditional database update; do not read then blindly overwrite.
- Write a failing test before production code and commit after each task.

---

## File Structure

```text
server/src/main/java/com/cc/opsagent/
  ticket/domain/Ticket.java
  ticket/domain/TicketStatus.java
  ticket/domain/TicketSeverity.java
  ticket/application/TicketService.java
  ticket/infrastructure/TicketMapper.java
  ticket/web/TicketController.java
  simulator/domain/OpsScenario.java
  simulator/application/OpsDataProvider.java
  simulator/infrastructure/FixtureOpsDataProvider.java
  simulator/infrastructure/ScenarioCatalog.java
  tool/domain/ToolRisk.java
  tool/domain/ToolDescriptor.java
  tool/application/ToolPolicyService.java
  tool/application/OpsToolFacade.java
server/src/main/resources/scenarios/*.yml
server/src/main/resources/db/mysql/V2__ticket_and_simulator.sql
```

### Task 1: Implement the Ticket State Machine and Persistence

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/ticket/domain/TicketStatus.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/domain/Ticket.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/application/TicketStateMachine.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/infrastructure/TicketMapper.java`
- Create: `server/src/main/resources/db/mysql/V2__ticket_and_simulator.sql`
- Create: `server/src/test/java/com/cc/opsagent/ticket/application/TicketStateMachineTest.java`
- Create: `server/src/test/java/com/cc/opsagent/ticket/infrastructure/TicketMapperIT.java`

**Interfaces:**
- Consumes: `TenantContext.requireTenantId()` and authenticated user ID.
- Produces: `boolean TicketStateMachine.canTransition(TicketStatus from, TicketStatus to)` and tenant-scoped mapper methods.

- [x] **Step 1: Write failing transition tests**

```java
@ParameterizedTest
@CsvSource({
  "OPEN,TRIAGING", "TRIAGING,DIAGNOSING", "DIAGNOSING,WAITING_APPROVAL",
  "WAITING_APPROVAL,EXECUTING", "EXECUTING,VERIFYING", "VERIFYING,RESOLVED"
})
void allowsExpectedTransitions(TicketStatus from, TicketStatus to) {
    assertThat(machine.canTransition(from, to)).isTrue();
}

@Test
void rejectsResolvedToRunning() {
    assertThat(machine.canTransition(RESOLVED, DIAGNOSING)).isFalse();
}
```

- [x] **Step 2: Run tests to verify failure**

Run: `mvn -f server/pom.xml -Dtest=TicketStateMachineTest test`

Expected: FAIL because the state machine does not exist.

- [x] **Step 3: Implement explicit transition sets**

Define normal and exceptional transitions in immutable maps. `RESOLVED`, `FAILED`, `CANCELLED`, `TIMEOUT`, and `MANUAL_REQUIRED` are terminal. In `V2__ticket_and_simulator.sql`, create the tenant-scoped ticket tables and `tenant_tool_policy`; seed no tenant-specific policy rows in the migration.

- [x] **Step 4: Add tenant and optimistic-transition persistence tests**

Insert two tenants' tickets. Assert tenant A cannot select tenant B's ticket. Execute two updates from `OPEN` to `TRIAGING` using `WHERE id=? AND tenant_id=? AND status='OPEN'`; assert exactly one update succeeds.

- [x] **Step 5: Run ticket tests and commit**

Run: `mvn -f server/pom.xml -Dtest=TicketStateMachineTest,TicketMapperIT test`

Expected: PASS.

```bash
git add server/src/main/java/com/cc/opsagent/ticket server/src/main/resources/db/mysql/V2__ticket_and_simulator.sql server/src/test/java/com/cc/opsagent/ticket
git commit -m "feat: add tenant ticket state machine"
```

### Task 2: Expose Ticket Application APIs

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/ticket/application/TicketService.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/web/CreateTicketRequest.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/web/TicketResponse.java`
- Create: `server/src/main/java/com/cc/opsagent/ticket/web/TicketController.java`
- Create: `server/src/test/java/com/cc/opsagent/ticket/web/TicketControllerIT.java`

**Interfaces:**
- Consumes: state machine and mapper from Task 1.
- Produces: `TicketResponse create(CreateTicketCommand)`, `TicketResponse get(long id)`, `PageResult<TicketResponse> list(TicketQuery)`, and `void cancel(long id)`.

- [x] **Step 1: Write a failing API isolation test**

```java
mockMvc.perform(get("/api/tickets/{id}", tenantBTicketId)
        .header("Authorization", bearer(tenantAToken)))
    .andExpect(status().isNotFound());
```

- [x] **Step 2: Run the test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=TicketControllerIT test`

Expected: FAIL because the API is absent.

- [x] **Step 3: Implement tenant-scoped CRUD and cancellation**

Validate title length 5–120 and description length 10–4000. Ignore any tenant field in JSON. Cancellation is allowed only from non-terminal states and uses a conditional update.

- [x] **Step 4: Run API tests and commit**

Run: `mvn -f server/pom.xml -Dtest=TicketControllerIT test`

Expected: PASS for create, list, get, cancel, validation, and cross-tenant 404.

```bash
git add server/src/main/java/com/cc/opsagent/ticket server/src/test/java/com/cc/opsagent/ticket
git commit -m "feat: expose ticket api"
```

### Task 3: Build the Deterministic Scenario Catalog

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/simulator/domain/OpsScenario.java`
- Create: `server/src/main/java/com/cc/opsagent/simulator/domain/ScenarioState.java`
- Create: `server/src/main/java/com/cc/opsagent/simulator/infrastructure/ScenarioCatalog.java`
- Create: `server/src/main/resources/scenarios/db-pool-exhausted.yml`
- Create: `server/src/main/resources/scenarios/redis-timeout.yml`
- Create: `server/src/main/resources/scenarios/api-error-rate.yml`
- Create: `server/src/main/resources/scenarios/mq-backlog.yml`
- Create: `server/src/main/resources/scenarios/disk-full.yml`
- Create: `server/src/test/java/com/cc/opsagent/simulator/infrastructure/ScenarioCatalogTest.java`

**Interfaces:**
- Consumes: classpath YAML fixtures.
- Produces: `OpsScenario ScenarioCatalog.require(String scenarioKey)` and immutable ground-truth records.

- [ ] **Step 1: Write a failing fixture validation test**

```java
@Test
void loadsFiveValidScenarios() {
    assertThat(catalog.all()).hasSize(5);
    assertThat(catalog.require("redis-timeout").expectedTools())
        .contains("getServiceHealth", "queryMetrics", "queryLogs");
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=ScenarioCatalogTest test`

Expected: FAIL because fixtures and catalog are absent.

- [ ] **Step 3: Implement schema validation and fixtures**

Every fixture must contain `key`, `service`, `category`, `severity`, `initialState`, `health`, `metrics`, `logs`, `dependencies`, `rootCause`, `expectedTools`, `forbiddenTools`, `requiresApproval`, `approvedOperation`, and `recoveredState`. Reject duplicate keys and missing fields at startup.

- [ ] **Step 4: Run test and commit**

Run: `mvn -f server/pom.xml -Dtest=ScenarioCatalogTest test`

Expected: PASS.

```bash
git add server/src/main/java/com/cc/opsagent/simulator server/src/main/resources/scenarios server/src/test/java/com/cc/opsagent/simulator
git commit -m "feat: add deterministic ops scenarios"
```

### Task 4: Implement the Ops Data Provider Port

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/simulator/application/OpsContext.java`
- Create: `server/src/main/java/com/cc/opsagent/simulator/application/OpsDataProvider.java`
- Create: `server/src/main/java/com/cc/opsagent/simulator/infrastructure/FixtureOpsDataProvider.java`
- Create: `server/src/test/java/com/cc/opsagent/simulator/infrastructure/FixtureOpsDataProviderTest.java`

**Interfaces:**
- Consumes: scenario catalog from Task 3.
- Produces: `getHealth`, `queryMetrics`, `queryLogs`, `getDependencies`, and `executeApprovedOperation` with typed records.

- [ ] **Step 1: Write failing deterministic behavior tests**

```java
@Test
void approvedRestartMovesScenarioToRecoveredState() {
    var context = new OpsContext(1L, 10L, "redis-timeout");
    assertThat(provider.getHealth(context, "order-service").status()).isEqualTo("DEGRADED");
    provider.executeApprovedOperation(context, new RestartService("order-service"));
    assertThat(provider.getHealth(context, "order-service").status()).isEqualTo("UP");
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=FixtureOpsDataProviderTest test`

Expected: FAIL because the provider is absent.

- [ ] **Step 3: Implement tenant-and-task scoped runtime state**

Key mutable simulator state by `(tenantId, taskId, scenarioKey)`. Reading a scenario returns immutable copies. An operation fails when the service, operation, tenant, or task does not match the context.

- [ ] **Step 4: Run test and commit**

Run: `mvn -f server/pom.xml -Dtest=FixtureOpsDataProviderTest test`

Expected: PASS including tenant isolation and deterministic reset tests.

```bash
git add server/src/main/java/com/cc/opsagent/simulator server/src/test/java/com/cc/opsagent/simulator
git commit -m "feat: add fixture ops data provider"
```

### Task 5: Add Strongly Typed Tool Policies and Diagnostic Facade

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/tool/domain/ToolRisk.java`
- Create: `server/src/main/java/com/cc/opsagent/tool/domain/ToolDescriptor.java`
- Create: `server/src/main/java/com/cc/opsagent/tool/application/ToolPolicyService.java`
- Create: `server/src/main/java/com/cc/opsagent/tool/application/OpsToolFacade.java`
- Create: `server/src/test/java/com/cc/opsagent/tool/application/ToolPolicyServiceTest.java`
- Create: `server/src/test/java/com/cc/opsagent/tool/application/OpsToolFacadeTest.java`

**Interfaces:**
- Consumes: `OpsDataProvider` and `TenantContext`.
- Produces: typed methods `getServiceHealth`, `queryMetrics`, `queryLogs`, `getServiceDependencies`, `restartService`, and `changeConfig`; `ToolDecision evaluate(ToolInvocationRequest request)`.

- [ ] **Step 1: Write failing policy tests**

```java
@Test
void requiresApprovalForRestart() {
    var decision = policy.evaluate(request("restartService", Map.of("service", "order-service")));
    assertThat(decision.risk()).isEqualTo(ToolRisk.HIGH_RISK);
    assertThat(decision.requiresApproval()).isTrue();
}

@Test
void forbidsUnknownTool() {
    assertThat(policy.evaluate(request("executeShell", Map.of())).allowed()).isFalse();
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -f server/pom.xml -Dtest=ToolPolicyServiceTest,OpsToolFacadeTest test`

Expected: FAIL because policies and facade are absent.

- [ ] **Step 3: Implement an allowlist and bounded results**

Register four simulator-backed read-only tools and two high-risk tools. The fifth read-only tool, `searchRunbook`, is added with the knowledge module in the next plan. Limit log results to 200 lines and 32 KiB, metric points to 500, and every tool timeout to five seconds. `restartService` and `changeConfig` return `APPROVAL_REQUIRED` instead of executing when no approved request ID is present.

- [ ] **Step 4: Run tool tests and commit**

Run: `mvn -f server/pom.xml -Dtest=ToolPolicyServiceTest,OpsToolFacadeTest test`

Expected: PASS including unknown-tool rejection, tenant mismatch rejection, output truncation, and timeout behavior.

```bash
git add server/src/main/java/com/cc/opsagent/tool server/src/test/java/com/cc/opsagent/tool
git commit -m "feat: add controlled ops tool center"
```

## Ticket and Simulator Acceptance Gate

Run:

```bash
mvn -f server/pom.xml -Dtest='*Ticket*,*Scenario*,*OpsData*,*Tool*' test
```

Expected: all tests pass; five scenarios load; cross-tenant ticket and tool access is rejected; high-risk tools cannot execute without approval.
