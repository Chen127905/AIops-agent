# Full Build Then Teach Workflow Design

## 1. Purpose

This document replaces the previous practice workflow in which the authoritative implementation and the learner reproduction advanced one small commit at a time.

The project will now use two strictly separated phases:

1. Complete, integrate, and freeze the entire first-six-week Ops Agent Platform deliverable in the authoritative worktree.
2. After the final implementation is stable, teach the learner to reproduce it from a clean baseline using a curriculum derived from the frozen result.

The change prevents intermediate implementation details, temporary test states, and later architecture corrections from repeatedly disrupting the learner's practice branch.

## 2. Authoritative Development Phase

### 2.1 Source of truth

- The authoritative worktree is `D:\develop\ops-agent-platform\.worktrees\foundation`.
- The authoritative branch is `feature/foundation`.
- All production implementation, refactoring, integration, and acceptance work happens there.
- The existing design and implementation plans remain the functional scope unless a later approved design explicitly supersedes them.

### 2.2 User participation during development

The learner is not required to reproduce commits, repair intermediate states, or submit practice hashes during this phase.

After each major milestone, the assistant reports:

- the completed behavior;
- important architecture decisions;
- verification commands and observed results;
- remaining risks and the next milestone.

These reports are progress checkpoints, not teaching assignments.

### 2.3 First-six-week delivery scope

The authoritative implementation is complete only when it includes the following integrated capabilities:

- tenant, user, role, authentication, authorization, and tenant isolation;
- ticket creation, querying, cancellation, state transitions, and concurrency protection;
- deterministic repeatable incident scenarios;
- at least five read-only diagnostic tools and two approval-required high-risk tools;
- Qwen as the primary model path and DeepSeek as an interchangeable comparison or fallback path;
- knowledge ingestion, chunking, pgvector retrieval, tenant filtering, and citations;
- a controlled Java Agent workflow with persisted tasks, steps, model calls, tool calls, and explicit outcomes;
- approval suspension, single-use decisions, safe resume, cancellation, timeout, lease recovery, and manual handoff;
- persistent SSE event replay and reconnect behavior;
- prompt-injection boundaries, sensitive-data redaction, tool-policy enforcement, and audit records;
- at least 30 repeatable evaluation cases with deterministic mock execution and separately runnable live-model evaluation;
- low-cardinality metrics and correlated structured logging;
- the minimal Vue workflow UI needed to demonstrate tickets, execution traces, approvals, knowledge, and evaluation;
- Docker Compose, CI configuration, smoke tests, README, architecture, security, evaluation, and demonstration documentation.

Out-of-scope items remain excluded: microservice decomposition, Nacos, Kubernetes, arbitrary shell or SQL execution, production infrastructure connectors, model training, enterprise SSO, and a general-purpose workflow editor.

## 3. Completion and Freeze Gate

The teaching phase cannot begin merely because feature code exists. The authoritative version must pass all relevant gates below:

- backend unit and integration tests pass from `mvn -f server/pom.xml clean verify` under Java 21;
- frontend dependency installation, tests, type checking, and production build pass;
- Flyway migrations succeed against disposable databases from an empty schema;
- MySQL and pgvector tenant-isolation tests pass;
- deterministic end-to-end scenarios cover successful resolution, approval suspension and resume, rejection, failure, timeout, cancellation, and safe recovery;
- security tests prove that retrieved text cannot elevate permissions or expose another tenant's data;
- the mock evaluation baseline is reproducible and stores actual results rather than invented metrics;
- live Qwen and DeepSeek execution paths are configurable without making ordinary CI depend on external API keys;
- Docker Compose configuration, image builds, service health, and the smoke workflow pass;
- documentation describes the implemented system, known limitations, startup steps, tests, evaluation, security boundaries, and demonstration flow;
- a final whole-system code review finds no unresolved critical or high-severity defect;
- the authoritative branch is clean and a final reference commit is recorded.

Only that recorded reference commit defines the answer used during teaching. Later optional experiments do not silently change the curriculum.

## 4. Practice Worktree Policy

The current practice worktree is `D:\develop\ops-agent-platform\.worktrees\foundation-practice` on branch `practice/foundation-bootstrap`.

Until the authoritative version is frozen:

- no further reproduction work is required there;
- commit `bb25c95`, earlier practice commits, uncommitted changes, and Git stashes are preserved;
- the learner is not required to amend `bb25c95`;
- the assistant does not delete, reset, rewrite, pop, or drop practice data.

When teaching begins, a new clean teaching worktree and branch will be created from the agreed baseline. The current practice worktree remains available as historical evidence unless the user later explicitly authorizes its removal.

## 5. Teaching Phase

### 5.1 Curriculum generation

The curriculum is generated from the frozen architecture and final dependency graph, not mechanically from the chronological development commits.

Lessons are ordered so each one has a clear business outcome and the smallest useful dependency set. A typical lesson contains:

1. business requirement and acceptance criteria;
2. architecture boundary and call flow;
3. database or external dependency design;
4. a targeted failing test or observable failure;
5. production implementation by class, method, SQL, and configuration;
6. verification commands and expected evidence;
7. common mistakes, debugging path, and interview explanation;
8. a learner commit reviewed against the frozen reference.

### 5.2 Comparison policy

The learner does not need to reproduce formatting or implementation trivia exactly. Each submission is classified as one of:

- equivalent and acceptable;
- acceptable with a documented trade-off;
- behaviorally incomplete;
- architecturally unsafe;
- failing verification.

The frozen tests, security boundaries, public contracts, and observable behavior are authoritative. Differences that preserve them can be accepted; differences that bypass tenant, approval, concurrency, recovery, or audit guarantees cannot.

### 5.3 Learning outcomes

At the end of reproduction, the learner must be able to:

- explain the complete request, Agent, retrieval, tool, approval, persistence, and SSE flows;
- diagnose the main failure modes rather than only copy code;
- start and test the system from a clean machine-level environment;
- demonstrate the project with deterministic scenarios and stored evaluation evidence;
- explain the major trade-offs in a Java backend or Java Agent interview;
- identify which parts are production-oriented and which are intentionally simulated.

## 6. Change Control

During authoritative development, implementation details may change when tests, dependency compatibility, or integration evidence reveal a better solution. Such changes are resolved in the authoritative branch before teaching materials are produced.

A material scope change requires explicit user approval when it would:

- remove a first-six-week capability;
- add a new infrastructure dependency or external paid service;
- replace Java as the primary backend and Agent language;
- expand into an excluded system such as Kubernetes or real production operations access;
- materially extend delivery time without improving the target resume or demonstration.

Routine refactoring, bug fixes, dependency-compatible adapter changes, and test improvements do not require the learner to pause for reproduction.

## 7. Immediate Transition

After this workflow design is approved and committed:

1. leave the practice worktree untouched;
2. compare the current `feature/foundation` implementation with the four existing first-six-week plans;
3. revise the implementation plan where dependency versions or already-completed work make it stale;
4. continue authoritative development milestone by milestone;
5. run the complete freeze gate before creating any new teaching assignment.
