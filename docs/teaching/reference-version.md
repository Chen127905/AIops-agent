# Frozen Reference Version

## Implementation identity

- Frozen production commit: `c0fa81088f750ccfb1dee311813467f62d3e2978`
- Branch at verification: `feature/foundation`
- Verification date: 2026-08-18 (Asia/Shanghai)
- This document is committed after the production commit; code comparisons must use the production commit above.

## Verified environment

- Java: Microsoft OpenJDK 21.0.12
- Maven: 3.9.16
- Node.js: 22.23.2
- npm: 10.9.8
- Docker Engine and client: 29.7.2
- Docker Compose: 5.3.1
- Host: Windows 11 amd64 with Docker Desktop

## Verification commands and results

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f server/pom.xml clean verify

npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build

docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build --pull=false
pwsh -File scripts/smoke.ps1

git diff --check
git status --short
```

Results:

- Backend: 99 unit tests and 56 Testcontainers integration tests, 155 total, zero failures.
- Frontend: clean lockfile install, zero reported vulnerabilities, 9 tests, successful TypeScript and Vite production build.
- Packaging: Java and Nginx runtime containers ran as non-root UID 10001 and 101; all four long-running services became healthy; only host port 8088 was published.
- Smoke: login, local embedding, pgvector ingestion/search, cross-tenant knowledge isolation, ticket creation, cross-tenant `404`, Agent task boundary, and all 30 MOCK evaluation cases passed.
- Stored deterministic evaluation run: `6a0d950a-cc01-4eaa-bff6-1cf78c261860`.

## Final review result

Authentication defaults to deny, tenant identifiers are derived from verified JWT principals, and repository operations use tenant predicates. Approval decisions and execution claims are conditional and single-use; high-risk writes require approval and idempotency identity. Ambiguous recovery enters `MANUAL_REQUIRED`. Agent events are persisted before publication and replay from monotonic sequence numbers. Retrieved text is untrusted evidence, secrets are redacted at model/log boundaries, forbidden tools are absent from the Java allowlist, and Compose injects secrets through environment variables.

No critical or high-severity issue remained after the freeze review. Hikari may log harmless connection-retry warnings when individual Testcontainers shut down before cached Spring test contexts; the complete Maven gate still closes with zero failures.

## Live providers and limitations

Qwen (Alibaba Cloud Bailian/DashScope) and DeepSeek are supported behind `ModelGateway`; live execution requires user-supplied keys and is deliberately excluded from deterministic CI. Local knowledge demonstrations use a deterministic lexical embedding, while production semantic retrieval requires explicitly selecting Qwen embedding and re-ingesting documents. The simulator does not connect to real Prometheus, Loki, CMDB, or release systems. The packaged target is single-machine Compose, not Kubernetes. Enterprise SSO, automated secret rotation, and a distributed tracing backend are outside the frozen six-week scope.
