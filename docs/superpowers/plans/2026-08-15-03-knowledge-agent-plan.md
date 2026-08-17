# Knowledge Base and Agent Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add tenant-isolated RAG and a persistent Graph workflow that classifies tickets, retrieves evidence, diagnoses through controlled tools, verifies results, and streams auditable progress.

**Architecture:** The knowledge module owns ingestion and retrieval behind a tenant-safe port. The agent runtime persists task, step, model, and tool records in MySQL while Spring AI Alibaba Graph orchestrates deterministic nodes around one bounded diagnostic Agent node. SSE reads persisted events and never owns task lifetime.

**Tech Stack:** Java 21, Spring Boot 4.0.6, Spring AI 2.0.0, Spring AI Alibaba 2.0.0-M1.1, pgvector, MySQL, MyBatis-Plus, Jackson, Reactor, JUnit 5, Testcontainers, WireMock.

## Global Constraints

- Use Qwen as the default model through `ModelGateway`; DeepSeek is a fallback and comparison provider.
- Never expose model chain-of-thought; persist only structured plans, actions, evidence, summaries, and results.
- Every knowledge query includes a server-derived tenant filter and an active document-version filter.
- Only one active task is permitted per ticket.
- Bound Agent execution by maximum steps, wall-clock time, and Token budget.
- Keep Alibaba Graph types inside `agent.infrastructure`; application and domain packages depend only on `AgentWorkflowEngine`.
- SSE disconnect does not cancel a task.
- Write a failing test before production code and commit after each task.

---

## File Structure

```text
server/src/main/java/com/cc/opsagent/
  knowledge/domain/KnowledgeDocument.java
  knowledge/application/DocumentIngestionService.java
  knowledge/application/KnowledgeRetriever.java
  knowledge/application/SearchRunbookTool.java
  knowledge/infrastructure/PgVectorKnowledgeRetriever.java
  knowledge/web/KnowledgeController.java
  agent/domain/AgentTask.java
  agent/domain/AgentStep.java
  agent/domain/AgentTaskStatus.java
  agent/application/AgentTaskService.java
  agent/application/AgentWorkflowEngine.java
  agent/application/OpsAgentWorkflow.java
  agent/application/AgentEventService.java
  agent/graph/OpsAgentState.java
  agent/graph/OpsAgentGraphFactory.java
  agent/graph/node/*.java
  agent/infrastructure/AlibabaGraphWorkflowEngine.java
  agent/web/AgentTaskController.java
server/src/main/resources/db/mysql/V3__knowledge.sql
server/src/main/resources/db/mysql/V4__agent_runtime.sql
server/src/main/resources/db/postgresql/V2__knowledge_chunks.sql
server/src/main/resources/runbooks/*.md
```

### Task 1: Implement Versioned Knowledge Document Ingestion

**Files:**
- Create: `server/src/main/resources/db/mysql/V3__knowledge.sql`
- Create: `server/src/main/resources/db/postgresql/V2__knowledge_chunks.sql`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/domain/KnowledgeDocument.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/application/DocumentIngestionService.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/infrastructure/KnowledgeDocumentMapper.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/infrastructure/KnowledgeChunkRepository.java`
- Create: `server/src/test/java/com/cc/opsagent/knowledge/application/DocumentIngestionServiceIT.java`

**Interfaces:**
- Consumes: authenticated tenant, DashScope embedding model, MySQL and pgvector.
- Produces: `long ingest(IngestDocumentCommand command)` and versioned chunks containing tenant, document, version, index, source, content, metadata, and embedding.

- [x] **Step 1: Write a failing ingestion test**

```java
@Test
void replacesActiveVersionWithoutExposingOldChunks() {
    long id = service.ingest(command("redis-runbook", "v1 content"));
    service.ingest(commandForExisting(id, "v2 content"));
    assertThat(chunkRepository.findActiveByDocument(tenantId, id))
        .allMatch(chunk -> chunk.documentVersion() == 2)
        .extracting(KnowledgeChunk::content).containsExactly("v2 content");
}
```

- [x] **Step 2: Run the test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=DocumentIngestionServiceIT test`

Expected: FAIL because the knowledge schema and service are absent.

- [x] **Step 3: Implement bounded parsing, chunking, embedding, and publication**

Accept UTF-8 Markdown and text up to 2 MiB. Normalize line endings, reject empty documents, split by headings and then by a token-safe maximum size, embed chunks in batches, and publish a new version only after every chunk is stored. Failed ingestion leaves the previous active version unchanged.

- [x] **Step 4: Run ingestion tests and commit**

Run: `mvn -f server/pom.xml -Dtest=DocumentIngestionServiceIT test`

Expected: PASS for first ingest, version replacement, failure rollback, size rejection, and tenant isolation.

```bash
git add server/src/main/java/com/cc/opsagent/knowledge server/src/main/resources/db server/src/test/java/com/cc/opsagent/knowledge
git commit -m "feat: add versioned knowledge ingestion"
```

### Task 2: Implement Tenant-Safe Retrieval and Citations

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/knowledge/application/KnowledgeRetriever.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/application/KnowledgeQuery.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/application/EvidenceChunk.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/application/SearchRunbookTool.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/infrastructure/PgVectorKnowledgeRetriever.java`
- Create: `server/src/main/java/com/cc/opsagent/knowledge/web/KnowledgeController.java`
- Create: `server/src/test/java/com/cc/opsagent/knowledge/infrastructure/PgVectorKnowledgeRetrieverIT.java`

**Interfaces:**
- Consumes: active pgvector chunks from Task 1.
- Produces: `List<EvidenceChunk> retrieve(KnowledgeQuery query)` where the implementation derives tenant from `TenantContext`, plus the allowlisted read-only tool `searchRunbook(String query, int topK)`.

- [x] **Step 1: Write a failing cross-tenant retrieval test**

```java
@Test
void neverReturnsAnotherTenantsNearestChunk() {
    seedChunk(tenantA, "redis timeout runbook", vectorNearQuery);
    seedChunk(tenantB, "secret redis procedure", vectorExactlyQuery);
    authenticate(tenantA);
    assertThat(retriever.retrieve(new KnowledgeQuery("redis timeout", 5)))
        .allMatch(chunk -> chunk.tenantId() == tenantA);
}
```

- [x] **Step 2: Run the test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=PgVectorKnowledgeRetrieverIT test`

Expected: FAIL because retriever is absent.

- [x] **Step 3: Implement vector search with mandatory metadata filters**

Use top K in range 1–10. Filter by `tenant_id`, active document version, and published status in the database query itself. Return citation IDs in the stable form `doc:{documentId}:v{version}:chunk:{chunkIndex}`. Implement `SearchRunbookTool` as the fifth read-only tool: it accepts only query and bounded top K, derives tenant server-side, delegates to `KnowledgeRetriever`, and returns cited evidence through the existing tool-policy envelope.

- [x] **Step 4: Run retrieval tests and commit**

Run: `mvn -f server/pom.xml -Dtest=PgVectorKnowledgeRetrieverIT test`

Expected: PASS for relevance, active version, top-K bounds, citation format, and cross-tenant isolation.

```bash
git add server/src/main/java/com/cc/opsagent/knowledge server/src/test/java/com/cc/opsagent/knowledge
git commit -m "feat: add tenant safe knowledge retrieval"
```

### Task 3: Persist Agent Tasks, Steps, Model Calls, Tool Calls, and Events

**Files:**
- Create: `server/src/main/resources/db/mysql/V4__agent_runtime.sql`
- Create: `server/src/main/java/com/cc/opsagent/agent/domain/AgentTaskStatus.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/domain/AgentTask.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/domain/AgentStep.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/application/AgentTaskService.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/application/AgentEventService.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/infrastructure/*.java`
- Create: `server/src/test/java/com/cc/opsagent/agent/application/AgentTaskServiceIT.java`

**Interfaces:**
- Consumes: ticket ID and tenant context.
- Produces: `AgentTask start(long ticketId, AgentBudget budget)`, `boolean claim(long taskId, String workerId, Duration lease)`, `void appendStep(StepRecord record)`, and monotonic `AgentEvent` sequence numbers.

- [x] **Step 1: Write a failing active-task uniqueness test**

```java
@Test
void allowsOnlyOneActiveTaskPerTicket() {
    service.start(ticketId, defaultBudget());
    assertThatThrownBy(() -> service.start(ticketId, defaultBudget()))
        .isInstanceOf(ActiveTaskExistsException.class);
}
```

- [x] **Step 2: Run test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=AgentTaskServiceIT test`

Expected: FAIL because task persistence is absent.

- [x] **Step 3: Implement persistence and conditional claims**

Create tables `agent_task`, `agent_step`, `model_invocation`, `tool_invocation`, and `agent_event`. Use a generated `active_guard` value of `1` for active states and `NULL` for terminal states, with unique `(tenant_id, ticket_id, active_guard)`. Claim and renew leases through conditional updates.

- [x] **Step 4: Run persistence tests and commit**

Run: `mvn -f server/pom.xml -Dtest=AgentTaskServiceIT test`

Expected: PASS for one-active-task, lease claim, event ordering, tenant isolation, and valid task transitions.

```bash
git add server/src/main/java/com/cc/opsagent/agent server/src/main/resources/db/mysql/V4__agent_runtime.sql server/src/test/java/com/cc/opsagent/agent
git commit -m "feat: persist agent execution state"
```

### Task 4: Build the Deterministic Graph Around One Diagnostic Agent Node

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/OpsAgentState.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/OpsAgentGraphFactory.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/TriageNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/RetrieveNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/PlanNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/DiagnoseNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/DecisionNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/VerifyNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/graph/node/SummarizeNode.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/application/AgentWorkflowEngine.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/application/OpsAgentWorkflow.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/infrastructure/AlibabaGraphWorkflowEngine.java`
- Create: `server/src/test/java/com/cc/opsagent/agent/graph/OpsAgentWorkflowTest.java`

**Interfaces:**
- Consumes: ticket, knowledge retriever, model gateway, tool facade, and agent persistence.
- Produces: `TaskOutcome AgentWorkflowEngine.execute(AgentTaskCommand command)` and `TaskOutcome OpsAgentWorkflow.run(long taskId)`. `OpsAgentWorkflow` owns application orchestration and calls the port; `AlibabaGraphWorkflowEngine` owns all Spring AI Alibaba Graph types and structured state keys for classification, evidence, plan, tool results, decision, verification, citations, and final report.

- [x] **Step 1: Write a failing fixed-model workflow test**

```java
@Test
void diagnosesRedisTimeoutWithExpectedEvidenceAndTools() {
    fakeModel.enqueue(triage("REDIS_TIMEOUT", "HIGH"));
    fakeModel.enqueue(plan("getServiceHealth", "queryMetrics", "queryLogs"));
    fakeModel.enqueue(decision("redis_connection_pool_exhausted", "restartService"));
    var outcome = workflow.run(taskForScenario("redis-timeout"));
    assertThat(outcome.rootCause()).isEqualTo("redis_connection_pool_exhausted");
    assertThat(outcome.toolNames()).containsExactly(
        "getServiceHealth", "queryMetrics", "queryLogs");
    assertThat(outcome.citations()).isNotEmpty();
}
```

- [x] **Step 2: Run test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=OpsAgentWorkflowTest test`

Expected: FAIL because the graph is absent.

- [ ] **Step 3: Implement typed node inputs and outputs**

Implement the graph inside `AlibabaGraphWorkflowEngine`. Use structured Java records for triage, plan, decision, and summary. Persist each node start, success, failure, duration, model call, and tool call. The diagnostic node may choose among allowlisted read-only tools only. Route a proposed high-risk action to a suspension outcome; approval execution is implemented in the next plan. Keep `AgentWorkflowEngine`, its command, and its result free of Alibaba or Spring AI types.

Set default budget to 12 steps, 180 seconds, and a configurable Token ceiling. Stop with an explicit state when any limit is exceeded.

- [ ] **Step 4: Run graph tests and commit**

Run: `mvn -f server/pom.xml -Dtest=OpsAgentWorkflowTest test`

Expected: PASS for resolved read-only case, high-risk suspension case, malformed model repair, tool failure, step limit, timeout, and citation propagation.

```bash
git add server/src/main/java/com/cc/opsagent/agent server/src/test/java/com/cc/opsagent/agent
git commit -m "feat: add controlled ops agent graph"
```

### Task 5: Add Background Execution and Replayable SSE Events

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/agent/config/AgentExecutorConfig.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/application/AgentExecutionService.java`
- Create: `server/src/main/java/com/cc/opsagent/agent/web/AgentTaskController.java`
- Create: `server/src/test/java/com/cc/opsagent/agent/web/AgentTaskControllerIT.java`

**Interfaces:**
- Consumes: `AgentTaskService`, `OpsAgentWorkflow`, and persisted `agent_event` rows.
- Produces: `POST /api/tickets/{ticketId}/agent-tasks`, `GET /api/agent-tasks/{id}`, `GET /api/agent-tasks/{id}/events?after={sequence}`.

- [ ] **Step 1: Write a failing SSE replay test**

```java
@Test
void reconnectReplaysEventsAfterLastSequenceWithoutCancellingTask() {
    long taskId = startTask();
    disconnectSseAfter(taskId, 2L);
    awaitTaskCompletion(taskId);
    assertThat(readSse(taskId, 2L)).extracting(AgentEvent::sequence)
        .containsExactly(3L, 4L, 5L);
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=AgentTaskControllerIT test`

Expected: FAIL because execution API and SSE are absent.

- [ ] **Step 3: Implement a bounded executor and database-backed event stream**

Use a named `ThreadPoolTaskExecutor` with configurable core/max/queue values and abort rejection. Persist events before publishing them. On SSE connection, replay rows with `sequence > after`, then subscribe to new events. Completion or disconnect closes only the subscription.

- [ ] **Step 4: Run API tests and commit**

Run: `mvn -f server/pom.xml -Dtest=AgentTaskControllerIT test`

Expected: PASS for task start, duplicate start rejection, replay, disconnect, completion, and unauthorized access.

```bash
git add server/src/main/java/com/cc/opsagent/agent server/src/test/java/com/cc/opsagent/agent
git commit -m "feat: execute and stream persistent agent tasks"
```

## Knowledge and Agent Acceptance Gate

Run:

```bash
mvn -f server/pom.xml -Dtest='*Knowledge*,*Agent*' test
```

Expected: all tests pass; a fixture ticket reaches a deterministic outcome; evidence is tenant-scoped; reconnecting SSE replays events without owning task lifetime.
