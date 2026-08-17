# First-Six-Week Authoritative Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete, integrate, verify, and freeze the entire first-six-week Ops Agent Platform before creating any further learner reproduction assignment.

**Architecture:** Build a modular Java monolith whose deterministic business rules, tenant boundaries, tool policies, and approval decisions remain outside model control. Use MySQL for business and execution state, PostgreSQL with pgvector for tenant-scoped knowledge, persisted events for replayable SSE, project-owned ports around model and Graph libraries, and a minimal Vue application for the demonstration workflow.

**Tech Stack:** Java 21, Spring Boot 4.0.6, Spring AI 2.0.0 GA, Spring AI Alibaba 2.0.0-M1.1, MyBatis-Plus 3.5.17, MySQL 8.4, PostgreSQL 16 with pgvector, Flyway 11, Testcontainers 1.21.4, Vue 3, TypeScript, Vitest, Docker Compose.

## Global Constraints

- Work only in `D:\develop\ops-agent-platform\.worktrees\foundation` on `feature/foundation` during authoritative development.
- Leave `D:\develop\ops-agent-platform\.worktrees\foundation-practice`, its commits, changes, and stashes untouched until the teaching phase.
- Run Maven with `C:\Users\Administrator\.jdks\ms-21.0.12`; `mvn -version` must report Java 21 before any build result is accepted.
- Use strict RED-GREEN-REFACTOR for new behavior and commit after each independently reviewable task.
- Keep Java as the primary backend and Agent implementation language.
- Business modules depend on project-owned ports, not directly on Qwen, DeepSeek, DashScope, OpenAI-compatible clients, pgvector, or Alibaba Graph APIs.
- Derive tenant identity from authenticated server context; never trust a request-supplied tenant ID.
- Every business read and write must enforce tenant ownership at the repository or database boundary.
- Models cannot elevate tool permission, bypass approval, choose arbitrary shell or SQL, or mutate business state directly.
- CI uses deterministic model doubles and disposable database containers; real API keys are required only by separately invoked live-model verification.
- Do not add Nacos, Kubernetes, microservice decomposition, enterprise SSO, production operations connectors, arbitrary code execution, or model training.
- Do not begin learner reproduction until the freeze gate in Task 6 passes and its reference commit is recorded.
- Maven command convention overrides older plan examples: run Surefire `*Test` classes with `-Dtest=... test`, run Failsafe `*IT` classes with `"-Dit.test=..." verify`, and use `clean verify` whenever a gate spans both kinds.

---

## Plan Precedence and Current Baseline

This file is the execution index and cross-milestone gate. The existing focused plans remain the detailed TDD task bodies:

1. `docs/superpowers/plans/2026-08-15-01-foundation-plan.md`
2. `docs/superpowers/plans/2026-08-15-02-ticket-simulator-plan.md`
3. `docs/superpowers/plans/2026-08-15-03-knowledge-agent-plan.md`
4. `docs/superpowers/plans/2026-08-15-04-approval-evaluation-delivery-plan.md`
5. `docs/superpowers/plans/2026-08-16-mysql-flyway-baseline-plan.md`
6. `docs/superpowers/plans/2026-08-16-ticket-domain-persistence-plan.md`

When plans overlap, the newer focused plan wins:

- `2026-08-16-mysql-flyway-baseline-plan.md` supersedes the MySQL portion of Foundation Task 2.
- `2026-08-16-ticket-domain-persistence-plan.md` supersedes Ticket and Simulator Task 1.
- This plan supersedes all earlier instructions that require alternating authoritative work with practice-branch reproduction.

Verified baseline at commit `89c138c` on 2026-08-17:

- Java 21 compilation succeeds.
- `TicketStateMachineTest`: 25 test invocations pass.
- `BusinessDatabaseMigrationIT`: 3 tests pass.
- `TicketMapperIT`: 5 tests pass.
- Flyway applies MySQL V1 and V2 from an empty MySQL 8.4 database.
- `mvn -f server/pom.xml clean verify` exits successfully.
- `docker compose --project-directory . --env-file .env.example -f compose.yml config --quiet` exits successfully.
- The completed code covers backend bootstrap, optional local startup without a database, MySQL datasource/Flyway baseline, ticket vocabulary and state machine, tenant-safe ticket schema, and tenant-scoped ticket persistence.
- PostgreSQL application configuration, authentication, model gateway, frontend, ticket APIs, simulator, tools, knowledge, Agent runtime, approval, evaluation, and delivery packaging are not yet implemented.

### Task 1: Finish the Foundation Boundary

**Files:**
- Follow and update: `docs/superpowers/plans/2026-08-15-01-foundation-plan.md`
- Preserve: `server/src/main/resources/db/mysql/V1__identity.sql`
- Modify: `server/pom.xml`
- Modify: `server/src/main/resources/application.yml`
- Modify: `compose.yml`
- Create under: `server/src/main/java/com/cc/opsagent/identity/`
- Create under: `server/src/main/java/com/cc/opsagent/security/`
- Create under: `server/src/main/java/com/cc/opsagent/model/`
- Create under: `server/src/test/java/com/cc/opsagent/identity/`
- Create under: `server/src/test/java/com/cc/opsagent/model/`
- Create under: `web/`

**Interfaces:**
- Consumes: existing MySQL `tenant` and `user_account` tables, environment-provided Qwen and DeepSeek credentials, PostgreSQL connection properties.
- Produces: authenticated `TenantContext`, JWT login/refresh boundary, project-owned `ModelGateway`, optional Qwen and DeepSeek adapters, PostgreSQL/pgvector application connectivity, and a buildable Vue shell.

- [x] **Step 1: Reconfirm the accepted baseline before new code**

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -version
mvn -f server/pom.xml clean verify
docker compose --project-directory . --env-file .env.example -f compose.yml config --quiet
```

Expected: Maven reports Java 21; 25 unit and 8 integration test invocations pass; Compose validation exits `0`.

- [x] **Step 2: Complete only the missing PostgreSQL half of Foundation Task 2**

Execute Foundation Task 2 without replacing the verified MySQL configuration or editing V1/V2. Add an explicitly configured knowledge datasource and a PostgreSQL Flyway location, prove startup remains possible when the knowledge datasource is disabled, and prove pgvector migrations against a PostgreSQL Testcontainer.

Run the exact PostgreSQL migration test named by Foundation Task 2. Expected: the extension and initial knowledge schema exist in a disposable PostgreSQL 16 database while existing MySQL integration tests remain green.

- [x] **Step 3: Implement JWT authentication and server-derived tenant context**

Execute Foundation Task 3 in full. Its negative tests must cover a missing token, invalid signature, expired token, wrong role, request-supplied tenant spoofing, and access to another tenant's resource. Store password hashes only; never return them or place them in JWT claims.

Run:

```powershell
mvn -f server/pom.xml clean verify
```

Expected: authenticated requests obtain tenant/user/role from the verified token, and spoofed tenant data cannot alter repository scope.

- [x] **Step 4: Verify Qwen and DeepSeek behind `ModelGateway`**

Execute Foundation Task 4 in full, retaining Spring AI 2.0.0. Spring AI Alibaba 2.0.0-M1.1 was removed after an executable configuration test proved it references a pre-GA Spring AI type missing from 2.0.0. Qwen and DeepSeek use their OpenAI-compatible endpoints behind the project-owned gateway. Base application startup and deterministic tests must not require either API key. Wire provider adapters only when their required properties exist.

Run the deterministic gateway contract test from Foundation Task 4. Then, only when credentials exist, run the separately tagged live probes and record provider, model, latency, token usage, and structured-output result without committing secrets.

- [x] **Step 5: Bootstrap the Vue application**

Execute Foundation Task 5 in full. Provide router, API client, authentication state, global error handling, test runner, and production build scripts; do not implement final workflow pages yet.

Run:

```powershell
npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build
```

Expected: the shell tests pass and TypeScript production build completes without errors.

- [x] **Step 6: Run the Foundation acceptance gate**

Run the complete gate from `2026-08-15-01-foundation-plan.md`, followed by existing MySQL ticket tests. Commit each Foundation task separately using the messages defined in that plan. Do not squash unrelated database, security, model, and frontend changes into one commit.

### Task 2: Complete Ticket APIs, Simulator, and Tool Policy

**Files:**
- Follow and update: `docs/superpowers/plans/2026-08-15-02-ticket-simulator-plan.md`
- Preserve completed domain/persistence files under: `server/src/main/java/com/cc/opsagent/ticket/`
- Create under: `server/src/main/java/com/cc/opsagent/ticket/web/`
- Create under: `server/src/main/java/com/cc/opsagent/simulator/`
- Create under: `server/src/main/java/com/cc/opsagent/tool/`
- Create under: `server/src/main/resources/scenarios/`
- Create corresponding tests under: `server/src/test/java/com/cc/opsagent/`

**Interfaces:**
- Consumes: authenticated `TenantContext`, completed `TicketRepository`, ticket state machine, scenario resources.
- Produces: tenant-safe ticket REST APIs, five deterministic fault scenarios, `OpsDataProvider`, five read-only tools, two high-risk tools, strong parameter types, risk decisions, and idempotency keys.

- [x] **Step 1: Mark Ticket and Simulator Task 1 as satisfied by the focused ticket plan**

Do not reimplement or rewrite the state machine, V2 migration, `TicketRepository`, or package-private `TicketMapper`. Record commits `d37fc81`, `8575d85`, `a209175`, and `04343e3` as the accepted implementation for that overlapping task.

Accepted after focused verification: `TicketStateMachineTest` (25 tests) and
`TicketMapperIT` against MySQL 8.4 Testcontainers (5 tests) pass on Java 21.

- [x] **Step 2: Implement ticket application and REST behavior**

Execute Ticket and Simulator Task 2 in full. Create, retrieve, list, cancel, and transition operations must derive tenant/user from security context. Return `404` for an absent ticket and a ticket owned by another tenant so the API does not disclose resource existence.

Run:

```powershell
mvn -f server/pom.xml clean verify
```

Expected: ticket domain, repository, service, security, and controller tests pass, including stale-state and cross-tenant cases.

- [x] **Step 3: Build and validate the deterministic scenario catalog**

Execute Ticket and Simulator Task 3 in full. Include database connection-pool exhaustion, Redis command timeout, API error-rate increase, message backlog, and disk-space exhaustion. Each scenario must declare evidence, root cause, required and forbidden tools, approval need, and post-operation state.

Run the catalog parser tests named in that task. Expected: all five scenarios load, malformed resources fail fast, and scenario IDs are unique.

- [x] **Step 4: Implement `OpsDataProvider` and fixture state transitions**

Execute Ticket and Simulator Task 4 in full. Reads return immutable typed results; approved writes mutate only in-memory/fixture scenario state and never execute operating-system commands.

Run provider tests for health, metrics, logs, dependencies, valid operation, invalid operation, and tenant mismatch.

- [x] **Step 5: Implement tool registry, validation, policy, and diagnostic facade**

Execute Ticket and Simulator Task 5 in full. Register `getServiceHealth`, `queryMetrics`, `queryLogs`, `getServiceDependencies`, `searchRunbook`, `restartService`, and `changeConfig`. Java policy must classify risk and block high-risk execution until approval; the model may propose but cannot authorize a tool.

Run the complete Ticket and Simulator acceptance gate. Expected: five scenarios and seven tools pass deterministic tests with no cross-tenant execution and no high-risk write before approval.

### Task 3: Implement Knowledge and the Controlled Agent Runtime

**Files:**
- Follow and update: `docs/superpowers/plans/2026-08-15-03-knowledge-agent-plan.md`
- Create under: `server/src/main/java/com/cc/opsagent/knowledge/`
- Create under: `server/src/main/java/com/cc/opsagent/agent/`
- Create under: `server/src/main/resources/db/mysql/`
- Create under: `server/src/main/resources/db/postgresql/`
- Create corresponding tests under: `server/src/test/java/com/cc/opsagent/knowledge/` and `server/src/test/java/com/cc/opsagent/agent/`

**Interfaces:**
- Consumes: authenticated tenant, `ModelGateway`, simulator/tool facade, PostgreSQL vector boundary, existing ticket API.
- Produces: versioned knowledge ingestion, tenant-filtered `KnowledgeRetriever`, citations, persisted Agent execution records, controlled Graph workflow, background execution, and replayable SSE.

- [x] **Step 1: Implement versioned knowledge document ingestion**

Execute Knowledge and Agent Task 1 in full. Persist document metadata in MySQL, chunks and embeddings in PostgreSQL, and explicit processing states. Reprocessing a document creates a new version and never exposes partially published chunks.

- [x] **Step 2: Implement tenant-safe retrieval and citations**

Execute Knowledge and Agent Task 2 in full. Apply tenant and published-version filters inside the vector query, not after retrieval. Return citation identifiers that bind tenant, document, version, chunk, and source.

Run the PostgreSQL Testcontainer suite. Expected: another tenant's highest-similarity chunk is never returned or cited.

- [x] **Step 3: Persist task, step, model, tool, and event records**

Execute Knowledge and Agent Task 3 in full. Enforce one active task per ticket, conditional state changes, monotonic event sequence, normalized tool arguments, idempotency identity, token and latency records, and redacted error summaries.

- [ ] **Step 4: Build the deterministic workflow around a controlled diagnostic node**

Execute Knowledge and Agent Task 4 in full. Deterministic Java nodes own state transitions, retrieval, risk decisions, approval boundaries, verification, and terminal outcomes. Model output must be parsed into typed records and validated before it influences a tool request.

- [ ] **Step 5: Add bounded background execution and persistent SSE replay**

Execute Knowledge and Agent Task 5 in full. Persist an event before publishing it, replay `sequence > after` on reconnect, close only the subscription on client disconnect, and keep task lifetime independent from the HTTP connection.

- [ ] **Step 6: Run the Knowledge and Agent acceptance gate**

```powershell
mvn -f server/pom.xml clean verify
```

Expected: ingestion, tenant isolation, citations, task uniqueness, workflow success/failure, background execution, SSE replay, and disconnect behavior pass using deterministic model doubles.

### Task 4: Add Approval, Recovery, Security, Evaluation, and Observability

**Files:**
- Follow and update: `docs/superpowers/plans/2026-08-15-04-approval-evaluation-delivery-plan.md`
- Create under: `server/src/main/java/com/cc/opsagent/approval/`
- Create under: `server/src/main/java/com/cc/opsagent/audit/`
- Create under: `server/src/main/java/com/cc/opsagent/security/`
- Create under: `server/src/main/java/com/cc/opsagent/evaluation/`
- Create under: `server/src/main/java/com/cc/opsagent/observability/`
- Create under: `server/src/main/resources/evaluation/`
- Create corresponding tests under: `server/src/test/java/com/cc/opsagent/`

**Interfaces:**
- Consumes: suspended Agent checkpoint, persisted tool request, authenticated approver, production workflow, scenario ground truth, Micrometer registry.
- Produces: exactly-once approval decisions, safe resume, cancellation/recovery, security envelopes, audit trail, at least 30 evaluation cases, stored metrics, and correlated operational telemetry.

- [ ] **Step 1: Implement single-use approval and resume**

Execute Approval and Delivery Task 1 in full. Atomically consume pending approvals with tenant, role, status, and expiry predicates. Revalidate tool policy during resume and enqueue execution only for the winning decision.

- [ ] **Step 2: Implement cancellation, lease recovery, timeout, and graceful shutdown**

Execute Approval and Delivery Task 2 in full. Check cancellation before each node and after each external call. Resume only from safe checkpoints; ambiguous write outcomes enter `MANUAL_REQUIRED`.

- [ ] **Step 3: Add audit and prompt-injection defenses**

Execute Approval and Delivery Task 3 in full. Treat retrieved documents as untrusted evidence, redact configured secret patterns before model/log boundaries, preserve Java allowlists, and audit every security-sensitive decision.

- [ ] **Step 4: Build deterministic and live evaluation modes**

Execute Approval and Delivery Task 4 in full. Store at least 30 cases across classification, retrieval, tools, end-to-end resolution, approval, and attacks. Deterministic CI mode and live provider mode must call the same production workflow port.

- [ ] **Step 5: Add low-cardinality metrics and correlation logging**

Execute Approval and Delivery Task 5 in full. Tenant, ticket, task, prompt, and raw error text must never become metric tags. Maintain `trace_id`, `tenant_id`, `ticket_id`, `task_id`, and `step_id` through HTTP and executor boundaries and clear MDC in `finally` blocks.

- [ ] **Step 6: Run the control-plane acceptance gate**

Run focused approval, recovery, security, evaluation, and observability tests. Expected: exactly one concurrent approval wins, stale leases recover safely or become manual, injected instructions cannot elevate permission, mock evaluation is reproducible, and metrics avoid high-cardinality labels.

### Task 5: Complete the Demonstration UI and Delivery Package

**Files:**
- Follow: Approval and Delivery Tasks 6 and 7 in `docs/superpowers/plans/2026-08-15-04-approval-evaluation-delivery-plan.md`
- Create under: `web/src/views/`
- Create under: `web/src/components/`
- Create under: `web/src/api/`
- Create under: `web/src/composables/`
- Create: `server/Dockerfile`
- Create: `web/Dockerfile`
- Modify: `compose.yml`
- Modify: `README.md`
- Create: `docs/architecture.md`
- Create: `docs/evaluation.md`
- Create: `docs/demo-script.md`
- Create: `docs/security.md`
- Create: `.github/workflows/ci.yml`
- Create: `scripts/smoke.ps1`

**Interfaces:**
- Consumes: authenticated ticket, Agent event, approval, knowledge, evaluation, and health APIs.
- Produces: the six-page demonstration workflow, reconnectable timeline, production images, one-command Compose deployment, CI, smoke verification, and evidence-based documentation.

- [ ] **Step 1: Complete the minimal Vue workflow UI**

Execute Approval and Delivery Task 6 in full. Display structured plans, node state, redacted tool parameters, result summaries, citations, approvals, and final reports; never display hidden model reasoning. Reconnect SSE from the last persisted sequence.

- [ ] **Step 2: Test and build the frontend**

```powershell
npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build
```

Expected: component and composable tests pass; TypeScript and production bundling complete without error.

- [ ] **Step 3: Package backend and frontend services**

Execute Approval and Delivery Task 7 in full. Use non-root runtime images, database health dependencies, environment-only secrets, persistent data volumes, and only the ports needed for the demonstration.

- [ ] **Step 4: Write evidence-based project documentation**

Document implemented scope, architecture, module boundaries, startup, credentials, scenarios, tests, evaluation commands, security boundaries, limitations, demo sequence, and original engineering contributions. Do not invent evaluation percentages; cite stored run IDs and generated result files.

- [ ] **Step 5: Run packaged smoke verification**

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build
pwsh -File scripts/smoke.ps1
```

Expected: health is UP, login succeeds, a tenant can create and start a ticket, a deterministic workflow finishes or suspends as designed, one mock evaluation completes, and cross-tenant ticket access returns `404`.

### Task 6: Execute the Whole-System Freeze Gate

**Files:**
- Verify all tracked production, test, deployment, and documentation files.
- Modify only files required to fix a reproduced gate failure.
- Record the final reference commit in: `docs/teaching/reference-version.md`

**Interfaces:**
- Consumes: the complete first-six-week implementation and all stored deterministic test fixtures.
- Produces: a clean authoritative branch, reproducible verification evidence, and one immutable reference commit ID for curriculum comparison.

- [ ] **Step 1: Run the complete backend gate from a clean target directory**

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f server/pom.xml clean verify
```

Expected: all unit and integration suites pass; disposable MySQL and PostgreSQL databases migrate from empty schemas; no test depends on Qwen or DeepSeek availability.

- [ ] **Step 2: Run the complete frontend gate**

```powershell
npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build
```

Expected: installation is lockfile-reproducible, all tests pass, and production build has no TypeScript error.

- [ ] **Step 3: Run deployment and smoke gates**

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build
pwsh -File scripts/smoke.ps1
```

Expected: images build, health dependencies settle, deterministic demonstration passes, and tenant-isolation smoke checks return the documented status codes.

- [ ] **Step 4: Run static repository checks**

```powershell
git diff --check
git status --short
git log -1 --oneline
```

Expected: no whitespace errors and no uncommitted authoritative changes. Generated secrets, provider responses containing sensitive content, database data, Maven targets, frontend dependencies, and build output are untracked only when intentionally ignored; none are committed.

- [ ] **Step 5: Perform the final whole-system review**

Review authentication, tenant predicates, approval atomicity, conditional state updates, idempotency, recovery ambiguity, SSE ownership, secret handling, model boundaries, migration compatibility, and deployment defaults. Reproduce and fix every critical or high-severity finding before continuing.

- [ ] **Step 6: Record the reference version**

Create `docs/teaching/reference-version.md` containing the production commit ID that passed Steps 1-5, Java/Node/Docker versions, exact verification commands, test totals, deterministic evaluation run ID, supported live providers, and known limitations. Commit this record separately without changing production behavior. The recorded production commit, rather than the later documentation commit, is the frozen implementation used for code comparison.

### Task 7: Prepare the Post-Freeze Teaching Curriculum

**Files:**
- Create: `docs/teaching/curriculum.md`
- Create: `docs/teaching/environment-setup.md`
- Create: `docs/teaching/debugging-guide.md`
- Create: `docs/teaching/interview-guide.md`
- Create lesson specifications under: `docs/teaching/lessons/`

**Interfaces:**
- Consumes: the frozen reference commit, final dependency graph, complete test suite, and demonstration script.
- Produces: a dependency-ordered reproduction curriculum and a clean teaching worktree without modifying or deleting the historical practice worktree.

- [ ] **Step 1: Derive lessons from final dependencies rather than commit chronology**

Each lesson must state business outcome, accepted interfaces, database changes, RED evidence, implementation files, GREEN evidence, common failure modes, and interview explanation. Lessons must never depend on a later lesson's hidden code.

- [ ] **Step 2: Create the environment and debugging guides**

Pin Java 21, Maven, Node, Docker, MySQL, PostgreSQL, required ports, API-key handling, IDE Maven runner JRE, and PowerShell environment commands. Include diagnosis for JDK class-version mismatches, Docker/Testcontainers failures, Flyway checksum errors, missing provider keys, SSE reconnection, and tenant-context mistakes.

- [ ] **Step 3: Create a new clean teaching worktree**

Create it from commit `79be92f` (`master`, before backend bootstrap) on a new teaching branch. Do not reset, rewrite, delete, pop, or drop anything in `foundation-practice`. Verify the new worktree branch, baseline commit, and clean status before giving the first learner assignment.

- [ ] **Step 4: Begin learner reproduction only after curriculum review**

For each learner submission, compare observable behavior, public contracts, security boundaries, and frozen tests against the reference. Accept equivalent implementations; reject changes that weaken tenant, approval, concurrency, recovery, audit, or secret-handling guarantees.
