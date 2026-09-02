# Ops Agent Platform

面向企业运维工单的智能诊断与受控处置平台。系统将大模型推理、企业知识检索、运行数据采集、工具调用、人工审批和效果评测串联为一条可审计、可恢复的运维工作流。

后端基于 Java 21、Spring Boot 4 和 Spring AI 2，前端基于 Vue 3，使用 MySQL 保存业务数据，并通过 PostgreSQL/pgvector 提供运维知识的向量检索能力。

## 项目概览

平台围绕运维工单组织 Agent 执行过程：接收故障描述后完成问题分类、知识检索、诊断规划、现场数据采集和根因判断，最终生成包含处置步骤、验证标准与回滚方案的诊断结果。

对于可能改变系统状态的操作，平台不会直接执行模型输出，而是通过工具白名单、参数校验、风险判断和人工审批控制执行边界。获批动作执行后，系统会再次检查目标服务状态，形成完整的处置闭环。

```text
故障工单
   ↓
问题分类 → 知识检索 → 工具规划 → 数据采集 → 根因判断
   ↓
处置建议 → 风险校验 → 人工审批 → 动作执行 → 恢复验证
   ↓
诊断报告、引用证据、执行记录与审计事件
```

## 核心能力

- 七节点 Agent Graph，覆盖分类、检索、规划、诊断、决策、验证和结果汇总
- 基于 PostgreSQL/pgvector 的多租户运维知识库与 RAG 引用追踪
- 健康状态、指标、日志和服务依赖等只读诊断工具
- 服务重启、配置变更等审批型写操作，以及执行后的健康复查
- 工具白名单、参数约束、超时控制、结果裁剪和敏感信息脱敏
- 工单内持续追问、消息持久化、长对话摘要和上下文窗口管理
- 任务租约、执行检查点、幂等恢复、取消、超时和人工接管状态
- 可重放的 SSE 执行时间线，以及模型调用、工具调用和审批审计记录
- MOCK 与 LIVE 两种评测模式，支持基线用例、指标统计和结果持久化
- JWT 身份认证、角色授权、租户隔离和低基数 Micrometer 指标

## 系统架构

| 层次 | 主要组件 | 职责 |
|---|---|---|
| Web | Vue 3、Pinia、Vue Router | 工单、诊断时间线、审批、知识库和评测界面 |
| 接入与安全 | Nginx、Spring Security、JWT | 统一入口、身份认证、角色授权和租户上下文 |
| 业务服务 | Ticket、Agent、Approval、Evaluation | 工单状态、Agent 生命周期、审批、恢复和评测 |
| Agent 编排 | Spring AI Alibaba Graph | 编排七个诊断节点并维护受约束的任务状态 |
| 模型服务 | Spring AI、Qwen、DeepSeek | 结构化分类、工具规划、根因判断和工单追问 |
| 知识检索 | PostgreSQL、pgvector、Embedding | 文档切分、向量入库、租户过滤和语义检索 |
| 工具与适配 | Tool Policy、Ops Data Provider | 受控采集业务数据并执行经过审批的动作 |
| 数据与事件 | MySQL、Flyway、SSE | 持久化业务状态、执行步骤、审计记录和实时事件 |
| 可观测性 | Micrometer、Prometheus | 输出平台健康状态与任务运行指标 |

## 技术栈

| 范围 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 4.0.6、Spring AI 2.0.0、Spring Security |
| Agent | Spring AI Alibaba Graph、Qwen、DeepSeek |
| 数据库 | MySQL、PostgreSQL、pgvector、Flyway |
| 前端 | Vue 3、TypeScript、Pinia、Vue Router、Vite |
| 测试 | JUnit、Testcontainers、Vitest |
| 部署 | Docker Compose、Nginx |
| 监控 | Spring Boot Actuator、Micrometer、Prometheus |

## 快速启动

准备 Docker Desktop 或兼容 Docker Compose 的运行环境，然后在项目根目录执行：

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d --build
```

启动完成后访问：

- Web 控制台：<http://localhost:8088>
- 后端接口通过 Web 网关统一访问

本地演示账号的密码均为 `demo-password`：

| 租户 | 用户名 | 角色 |
|---|---|---|
| `acme` | `admin` | ADMIN |
| `acme` | `operator` | OPERATOR |
| `beta` | `operator` | OPERATOR |

`.env.example` 仅包含本地演示配置。真实模型 Key、JWT Secret、数据库密码和业务系统令牌应通过未提交的 `.env` 或外部密钥管理系统注入。

## 模型与知识库配置

不配置模型 Key 时，平台仍可启动，并可使用本地确定性向量器体验知识入库、检索、MOCK 评测和控制面功能。真实 Agent 诊断和工单追问需要配置 Qwen 或 DeepSeek。

在 `.env` 中设置模型与向量服务：

```dotenv
AI_DASHSCOPE_API_KEY=your-dashscope-key
DEEPSEEK_API_KEY=your-deepseek-key
AGENT_MODEL_PROVIDER=QWEN
AGENT_CONVERSATION_MODEL_PROVIDER=QWEN
KNOWLEDGE_EMBEDDING_PROVIDER=QWEN
KNOWLEDGE_MIN_SCORE=0.25
```

重新构建服务并初始化内置知识：

```powershell
docker compose --env-file .env up -d --build
powershell -ExecutionPolicy Bypass -File scripts/seed-knowledge.ps1
```

也可以使用 `acme / admin` 登录，在“知识库”页面初始化内置运维知识。切换 Embedding 提供方后应重新入库文档，避免在同一知识版本中混用不同向量空间。

## 接入业务系统

平台支持通过受控 HTTP 接口接入真实业务服务。最小接入只需要提供兼容 Spring Boot Actuator 的健康端点，其他能力可按需配置：

- 健康检查：`GET /actuator/health`
- 指标查询：支持带 `{metric}` 占位符的指标地址
- 日志查询：返回日志数组或包含 `logs` 字段的 JSON
- 依赖查询：返回 MySQL、Redis、消息队列等依赖状态
- 变更端点：接收人工审批后的受控服务操作

容器访问宿主机服务时可使用 `http://host.docker.internal:端口`。需要认证的业务系统只在平台中保存环境变量名，令牌本身应注入 Server 容器，不写入数据库。

## 项目验证

```powershell
mvn -f server/pom.xml clean verify
npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build
pwsh -File scripts/smoke.ps1
```

Smoke 流程会检查服务健康、用户登录、知识入库与向量检索、租户隔离、工单状态、Agent 状态边界和 MOCK 评测。

## 相关文档

- [系统架构](docs/architecture.md)
- [评测方案](docs/evaluation.md)
- [安全边界](docs/security.md)

## 当前范围

项目当前提供单机 Docker Compose 部署，以及通用 HTTP/Actuator 业务系统接入。Prometheus、Loki、CMDB、Kubernetes、发布平台原生适配器，企业 SSO、集中密钥轮换和分布式追踪后端尚未纳入当前版本。
