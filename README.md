# Ops Agent Platform

面向企业运维工单的 Java + AI Agent 平台。当前权威实现采用 Java 21、Spring Boot 4、Spring AI 2、MySQL、PostgreSQL/pgvector 与 Vue 3。

## 本地依赖

- JDK 21
- Maven 3.9+
- Node.js 22+
- Docker Desktop

## 启动基础设施

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d mysql pgvector
```

`.env` 只用于本地，禁止提交真实数据库密码、JWT Secret 或模型 API Key。

## 后端

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.jdks\ms-21.0.12'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f server/pom.xml clean verify
mvn -f server/pom.xml spring-boot:run
```

模型 Key 均为可选配置。未设置 Key 时应用仍可启动；设置 `AI_DASHSCOPE_API_KEY` 或 `DEEPSEEK_API_KEY` 后才创建对应 `ChatModel`。本地模型探针还要求启用 `local` profile，并通过 JWT 认证。

## 前端

```powershell
npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build
npm --prefix web run dev
```

开发服务器默认运行于 `http://localhost:5173`，并将 `/api` 代理到 `http://localhost:8080`。

## 当前已验证边界

- MySQL 业务迁移与 PostgreSQL/pgvector 可选数据源
- BCrypt 登录、JWT 校验、角色控制与服务端租户上下文
- Qwen/DeepSeek 供应商中立 `ModelGateway`，同步与流式调用
- Vue 登录、Token 持久化、Bearer 拦截器、401 清理与路由守卫

完整六周功能仍按 `docs/superpowers/plans/2026-08-17-first-six-week-authoritative-build-plan.md` 持续实现；在参考版本冻结前，不以本 README 宣称整个项目已经完成。
