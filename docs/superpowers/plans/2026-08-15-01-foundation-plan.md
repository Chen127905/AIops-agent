# Ops Agent Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a bootable Java/Vue project with reproducible MySQL and pgvector infrastructure, authenticated tenant context, database migrations, and verified Qwen/DeepSeek model adapters.

**Architecture:** Use one Spring Boot modular monolith under `server/` and one Vue 3 application under `web/`. MySQL stores business state; PostgreSQL with pgvector stores embeddings. Spring Security authenticates JWTs and exposes a server-derived tenant context to downstream modules.

**Tech Stack:** Java 21, Spring Boot 4.0.6, Spring AI 2.0.0, Spring AI Alibaba 2.0.0-M1.1, MyBatis-Plus 3.5.17, MySQL 8.4, pgvector/pgvector:pg16, Flyway, JUnit 5, Testcontainers, Vue 3, TypeScript, Vite.

## Global Constraints

- Use Java 21 and Spring Boot 4.0.6.
- Pin Spring AI to 2.0.0 GA and Spring AI Alibaba to 2.0.0-M1.1.
- Use AI core libraries rather than model or vector-store Starters; create Qwen, DeepSeek, and pgvector beans explicitly in project configuration.
- Isolate Alibaba Graph behind the project-owned `AgentWorkflowEngine` port.
- Use package root `com.cc.opsagent`.
- Use Spring Security + JWT; never accept `tenantId` from a request as proof of identity.
- Store secrets only in environment variables; commit `.env.example`, never `.env`.
- Keep a modular monolith; do not add Redis, MQ, Nacos, Kubernetes, multi-agent, A2A, Shell, arbitrary SQL, or arbitrary file access.
- Write a failing test before each production change and commit after each task.

---

## File Structure

```text
ops-agent-platform/
  server/
    pom.xml
    src/main/java/com/cc/opsagent/
      OpsAgentApplication.java
      common/api/ApiResponse.java
      common/error/ApiException.java
      common/error/GlobalExceptionHandler.java
      config/AiModelConfig.java
      identity/domain/Tenant.java
      identity/domain/UserAccount.java
      identity/security/JwtService.java
      identity/security/JwtAuthenticationFilter.java
      identity/security/SecurityConfig.java
      identity/security/TenantContext.java
      identity/web/AuthController.java
    src/main/resources/
      application.yml
      application-local.yml
      db/mysql/V1__identity.sql
      db/postgresql/V1__vector_extension.sql
  web/
    package.json
    src/main.ts
    src/router/index.ts
    src/views/LoginView.vue
  compose.yml
  .env.example
  README.md
```

### Task 1: Bootstrap the Backend and Container Infrastructure

**Files:**
- Create: `server/pom.xml`
- Create: `server/src/main/java/com/cc/opsagent/OpsAgentApplication.java`
- Create: `server/src/main/resources/application.yml`
- Create: `server/src/test/java/com/cc/opsagent/OpsAgentApplicationTest.java`
- Create: `compose.yml`
- Create: `.env.example`
- Create: `.gitignore`

**Interfaces:**
- Consumes: none.
- Produces: Spring application on port `8080`; MySQL at `localhost:3307`; pgvector at `localhost:5433`.

- [ ] **Step 1: Write the failing application context test**

```java
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
class OpsAgentApplicationTest {
    @Test void contextLoads() {}
}
```

- [ ] **Step 2: Run the test and confirm the project is absent**

Run: `mvn -f server/pom.xml -Dtest=OpsAgentApplicationTest test`

Expected: FAIL because `server/pom.xml` does not exist.

- [ ] **Step 3: Create the Maven project with pinned dependencies**

Create `server/pom.xml` with parent `org.springframework.boot:spring-boot-starter-parent:4.0.6`, Java 21, and explicit dependencies for web, validation, security, actuator, JDBC, MySQL driver, PostgreSQL driver, Flyway MySQL/PostgreSQL, `mybatis-plus-spring-boot4-starter:3.5.17`, `spring-ai-alibaba-agent-framework:2.0.0-M1.1`, `spring-ai-alibaba-dashscope:2.0.0-M1.1`, `spring-ai-openai:2.0.0`, `spring-ai-pgvector-store:2.0.0`, test, security-test, and Testcontainers. Do not add the corresponding AI Starter artifacts.

The application entry point must be:

```java
@SpringBootApplication
public class OpsAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsAgentApplication.class, args);
    }
}
```

Set `spring.application.name=ops-agent-platform` and `server.port=8080` in `application.yml`. Do not configure provider API keys or AI auto-configuration in this task; core libraries must allow the context to load without external services.

- [ ] **Step 4: Add reproducible containers and environment contract**

Create `compose.yml` with services:

```yaml
services:
  mysql:
    image: mysql:8.4
    ports: ["3307:3306"]
    environment:
      MYSQL_DATABASE: ops_agent
      MYSQL_USER: ops_agent
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 5s
      timeout: 3s
      retries: 20
  pgvector:
    image: pgvector/pgvector:pg16
    ports: ["5433:5432"]
    environment:
      POSTGRES_DB: ops_agent_vector
      POSTGRES_USER: ops_agent
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ops_agent -d ops_agent_vector"]
      interval: 5s
      timeout: 3s
      retries: 20
```

Commit `.env.example` with variable names and dummy values only.

- [ ] **Step 5: Run the backend test and validate Compose**

Run: `mvn -f server/pom.xml -Dtest=OpsAgentApplicationTest test`

Expected: PASS.

Run: `docker compose --env-file .env.example config`

Expected: exit code 0 and two services.

- [ ] **Step 6: Commit**

```bash
git add server/pom.xml server/src compose.yml .env.example .gitignore
git commit -m "build: bootstrap ops agent platform"
```

### Task 2: Configure Two Databases and Flyway Migrations

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/config/DataSourceConfig.java`
- Create: `server/src/main/java/com/cc/opsagent/config/FlywayConfig.java`
- Create: `server/src/main/resources/db/mysql/V1__identity.sql`
- Create: `server/src/main/resources/db/postgresql/V1__vector_extension.sql`
- Create: `server/src/test/java/com/cc/opsagent/config/DatabaseMigrationIT.java`
- Modify: `server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: container endpoints from Task 1.
- Produces: `@Primary DataSource businessDataSource()` and `DataSource vectorDataSource()`; Flyway schemas `tenant`, `user_account`, and pgvector extension.

- [ ] **Step 1: Write the failing migration integration test**

```java
@Testcontainers
@SpringBootTest
class DatabaseMigrationIT {
    @Test
    void createsIdentityTablesAndVectorExtension(
            @Qualifier("businessJdbcTemplate") JdbcTemplate mysql,
            @Qualifier("vectorJdbcTemplate") JdbcTemplate postgres) {
        assertThat(mysql.queryForObject("select count(*) from tenant", Long.class)).isZero();
        assertThat(postgres.queryForObject(
            "select count(*) from pg_extension where extname='vector'", Long.class)).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run the test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=DatabaseMigrationIT test`

Expected: FAIL because datasource beans and migrations do not exist.

- [ ] **Step 3: Implement explicit datasource and Flyway beans**

Bind `app.datasource.business` to MySQL and `app.datasource.vector` to PostgreSQL. Define named `JdbcTemplate` beans. Configure two Flyway instances with locations `classpath:db/mysql` and `classpath:db/postgresql`, and migrate them during bean creation.

The MySQL migration creates `tenant` and `user_account` with unique `(tenant_id, username)` and timestamps. The PostgreSQL migration executes:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 4: Run the migration test**

Run: `mvn -f server/pom.xml -Dtest=DatabaseMigrationIT test`

Expected: PASS with both Testcontainers healthy.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/cc/opsagent/config server/src/main/resources server/src/test/java/com/cc/opsagent/config
git commit -m "feat: configure business and vector databases"
```

### Task 3: Implement JWT Authentication and Server-Derived Tenant Context

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/identity/security/TenantPrincipal.java`
- Create: `server/src/main/java/com/cc/opsagent/identity/security/TenantContext.java`
- Create: `server/src/main/java/com/cc/opsagent/identity/security/JwtService.java`
- Create: `server/src/main/java/com/cc/opsagent/identity/security/JwtAuthenticationFilter.java`
- Create: `server/src/main/java/com/cc/opsagent/identity/security/SecurityConfig.java`
- Create: `server/src/main/java/com/cc/opsagent/identity/web/AuthController.java`
- Create: `server/src/test/java/com/cc/opsagent/identity/security/TenantContextTest.java`
- Create: `server/src/test/java/com/cc/opsagent/identity/web/AuthControllerIT.java`

**Interfaces:**
- Consumes: `tenant` and `user_account` tables from Task 2.
- Produces: `TenantContext.requireTenantId(): long`, `TenantContext.requireUserId(): long`, and bearer tokens containing `sub`, `tenant_id`, and `roles`.

- [x] **Step 1: Write failing tenant-context tests**

```java
@Test
void derivesTenantFromAuthenticatedPrincipal() {
    var auth = new UsernamePasswordAuthenticationToken(
        new TenantPrincipal(7L, 42L, "alice", Set.of("OPERATOR")), null, List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);
    assertThat(TenantContext.requireTenantId()).isEqualTo(7L);
}

@Test
void rejectsMissingAuthentication() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(TenantContext::requireTenantId)
        .isInstanceOf(AccessDeniedException.class);
}
```

- [x] **Step 2: Run tests to verify failure**

Run: `mvn -f server/pom.xml -Dtest=TenantContextTest test`

Expected: FAIL because security types are absent.

- [x] **Step 3: Implement JWT and tenant context**

Use an HMAC secret from `JWT_SECRET`. `TenantContext` reads only the authenticated `TenantPrincipal`; controllers and request bodies must not set it. Configure stateless security, allow `/api/auth/login` and `/actuator/health`, and require authentication elsewhere.

- [x] **Step 4: Add an authentication integration test**

Seed one tenant and BCrypt user, POST `/api/auth/login`, assert a token is returned, then GET `/api/auth/me` with the token and assert the tenant comes from the token even when a conflicting `X-Tenant-Id` header is supplied.

- [x] **Step 5: Run security tests**

Run: `mvn -f server/pom.xml -Dtest=TenantContextTest,AuthControllerIT test`

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add server/src/main/java/com/cc/opsagent/identity server/src/test/java/com/cc/opsagent/identity
git commit -m "feat: add jwt tenant authentication"
```

### Task 4: Verify Qwen and DeepSeek Behind One Model Gateway

**Files:**
- Create: `server/src/main/java/com/cc/opsagent/model/ModelProvider.java`
- Create: `server/src/main/java/com/cc/opsagent/model/ModelRequest.java`
- Create: `server/src/main/java/com/cc/opsagent/model/ModelReply.java`
- Create: `server/src/main/java/com/cc/opsagent/model/ModelGateway.java`
- Create: `server/src/main/java/com/cc/opsagent/model/SpringAiModelGateway.java`
- Create: `server/src/main/java/com/cc/opsagent/config/AiModelConfig.java`
- Create: `server/src/main/java/com/cc/opsagent/model/ModelProbeController.java`
- Create: `server/src/test/java/com/cc/opsagent/model/SpringAiModelGatewayTest.java`
- Modify: `server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Qwen `ChatModel`, OpenAI-compatible DeepSeek `ChatModel`.
- Produces: `ModelReply ModelGateway.call(ModelProvider provider, ModelRequest request)` and `Flux<String> ModelGateway.stream(ModelProvider provider, ModelRequest request)`.

- [x] **Step 1: Write a failing routing test**

```java
@Test
void routesToRequestedProvider() {
    when(qwen.call(any(Prompt.class))).thenReturn(response("qwen-ok"));
    var reply = gateway.call(ModelProvider.QWEN, new ModelRequest("reply ok", Map.of()));
    assertThat(reply.content()).isEqualTo("qwen-ok");
    verifyNoInteractions(deepseek);
}
```

- [x] **Step 2: Run the test to verify failure**

Run: `mvn -f server/pom.xml -Dtest=SpringAiModelGatewayTest test`

Expected: FAIL because the gateway does not exist.

- [x] **Step 3: Implement the provider-neutral gateway**

Create separately named `ChatModel` beans. Configure Qwen from `AI_DASHSCOPE_API_KEY`; configure DeepSeek through the OpenAI-compatible base URL and `DEEPSEEK_API_KEY`. Keep retry and fallback outside the raw model beans so later tasks can record every attempt.

Compatibility decision recorded during implementation: Spring AI Alibaba `2.0.0-M1.1` references a pre-GA Spring AI type (`ToolExecutionEligibilityPredicate`) that is absent from Spring AI `2.0.0`. Both Qwen and DeepSeek therefore use Spring AI `2.0.0`'s OpenAI-compatible client behind the project-owned gateway; Qwen targets DashScope's compatible-mode base URL. This removes the runtime linkage failure without leaking provider SDK types into application modules.

Expose authenticated probe endpoints only under the `local` profile:

```text
POST /api/local/model/probe/{provider}
GET  /api/local/model/probe/{provider}/stream
```

- [x] **Step 4: Run unit and optional live probes**

Run: `mvn -f server/pom.xml -Dtest=SpringAiModelGatewayTest test`

Expected: PASS.

With keys configured, run the app and call both probe endpoints. Expected: Qwen and DeepSeek each return `ok`; SSE emits at least one data event.

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/cc/opsagent/model server/src/main/java/com/cc/opsagent/config/AiModelConfig.java server/src/test/java/com/cc/opsagent/model server/src/main/resources/application.yml
git commit -m "feat: add qwen and deepseek model gateway"
```

### Task 5: Bootstrap the Minimal Vue Application

**Files:**
- Create: `web/package.json`
- Create: `web/vite.config.ts`
- Create: `web/src/main.ts`
- Create: `web/src/router/index.ts`
- Create: `web/src/api/http.ts`
- Create: `web/src/stores/auth.ts`
- Create: `web/src/views/LoginView.vue`
- Create: `web/src/views/HomeView.vue`
- Create: `web/src/views/LoginView.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes: `/api/auth/login` and `/api/auth/me` from Task 3.
- Produces: authenticated Vue shell and bearer-token HTTP interceptor used by later pages.

- [ ] **Step 1: Write the failing login component test**

```ts
it('stores token and redirects after login', async () => {
  mock.onPost('/api/auth/login').reply(200, { data: { token: 'jwt' } })
  const wrapper = mount(LoginView, { global: { plugins: [router, pinia] } })
  await wrapper.get('[data-test=username]').setValue('alice')
  await wrapper.get('[data-test=password]').setValue('password')
  await wrapper.get('form').trigger('submit')
  expect(useAuthStore().token).toBe('jwt')
})
```

- [ ] **Step 2: Run the test to verify failure**

Run: `npm --prefix web test -- --run`

Expected: FAIL because the Vue project is absent.

- [ ] **Step 3: Create Vue 3, Router, Pinia, Axios, Vitest and login shell**

Persist the token in local storage, attach `Authorization: Bearer <token>`, clear it on 401, and guard authenticated routes. Do not implement additional pages in this task.

- [ ] **Step 4: Run frontend verification**

Run: `npm --prefix web test -- --run`

Expected: PASS.

Run: `npm --prefix web run build`

Expected: PASS without TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add web README.md
git commit -m "feat: add authenticated vue shell"
```

## Foundation Acceptance Gate

Run:

```bash
docker compose --env-file .env.example config
mvn -f server/pom.xml test
npm --prefix web test -- --run
npm --prefix web run build
```

Expected: all commands pass; live model probes are documented separately because they require paid API keys.
