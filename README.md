# Ops Agent Platform

面向企业运维工单的 Java + AI Agent 平台。项目以 Java 21、Spring Boot 4、Spring AI 2、MySQL、PostgreSQL/pgvector 和 Vue 3 实现可审计诊断、工具策略、人工审批、恢复、评测与可观测性闭环。

## 一键启动

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d --build
```

打开 `http://localhost:8088`。演示账号密码统一为 `demo-password`：

| 租户 | 用户 | 角色 |
|---|---|---|
| `acme` | `admin` | ADMIN |
| `acme` | `operator` | OPERATOR |
| `beta` | `operator` | OPERATOR |

`.env.example` 只含本地演示值。真实 JWT Secret、数据库密码和模型 Key 只能通过未提交的 `.env` 或密钥管理系统注入。

## 完整体验模式

在 `.env` 中配置真实 Key 后，推荐使用 Qwen 作为默认 Agent 模型和知识向量模型：

```dotenv
AI_DASHSCOPE_API_KEY=你的百炼Key
DEEPSEEK_API_KEY=你的DeepSeekKey
AGENT_MODEL_PROVIDER=QWEN
KNOWLEDGE_EMBEDDING_PROVIDER=QWEN
KNOWLEDGE_MIN_SCORE=0.25
```

重新创建服务，使环境变量进入容器：

```powershell
docker compose --env-file .env up -d --build
pwsh -File scripts/seed-knowledge.ps1
```

初始化脚本会通过平台 API 发布五份有官方来源的运维手册，覆盖 Redis 超时、HikariCP 连接池耗尽、Spring Boot API 5xx、Kafka 消费积压和 Kubernetes 磁盘压力。不要在同一批已入库文档上切换 embedding 提供方；切换后应重新入库，避免混用不同向量空间。

登录后可创建三类工单：已接入的真实服务、自定义知识诊断、内置故障沙箱。Agent 会输出诊断摘要、根因、处置步骤、验证标准、回滚方案和证据；高风险动作进入人工审批，批准后才会调用变更端点并再次检查健康状态。离开详情页不会丢失执行上下文，重新进入工单会自动恢复最近一次任务。

## 接入真实业务系统

在“业务系统接入”中按工单使用的服务唯一名注册应用。平台本身运行状态与业务系统状态已经分开展示。容器访问宿主机进程时，Base URL 使用 `http://host.docker.internal:端口`，容器间访问使用 Compose 服务名。

最小接入只需一个兼容 Spring Boot Actuator 的健康端点：

- 健康：`GET /actuator/health`，读取 `{ "status": "UP" }`。
- 指标（可选）：路径支持 `{metric}` 占位符，兼容 Actuator `measurements[].value`。
- 日志（可选）：返回 JSON 数组，或 `{ "logs": [...] }`；每项包含 `timestamp`、`level`、`message`。
- 依赖（可选）：返回 `{ "dependencies": [{ "service": "mysql", "status": "UP" }] }`，也兼容 Actuator `components`。
- 变更（可选）：人工审批后接收 `POST`，请求包含 `operation`、`service`、`parameters`、`taskId`，返回 `{ "success": true }`。未配置该端点时平台绝不会自动变更。

需要 Bearer Token 时只在页面保存环境变量名，例如 `ORDER_SERVICE_TOKEN`，实际 Token 必须注入 server 容器，数据库不保存明文凭证。注册后先点击“连通测试”，再创建“已接入的真实服务”工单。

## 离线验证知识库

一键启动会启用 PostgreSQL/pgvector，并默认使用 `KNOWLEDGE_EMBEDDING_PROVIDER=LOCAL` 的本地确定性向量器，因此不配置模型 Key 也能验证完整的“文档切分 → 向量写入 → 租户过滤 → 相似度检索 → 引用”链路。

登录 `acme / admin / demo-password` 后进入“知识库”，点击“新增文档”完成入库发布，再使用页面中的语义检索验证召回。页面会返回 `tenant:...:doc:...:chunk:...` 引用。也可以运行：

```powershell
pwsh -File scripts/smoke.ps1 -SkipComposeUp
```

Smoke 会真实入库一份文档、执行 pgvector 检索并检查跨租户引用隔离，不再只检查容器健康。LOCAL 与 Qwen 向量不能混在同一检索语料中；完整体验模式请使用上面的初始化脚本。

## 核心能力

- 多租户 JWT 身份、角色授权与服务端租户隔离
- 任意故障工单、真实 HTTP 业务服务接入、五种确定性演练场景
- 四个只读诊断工具、两个审批型写工具，以及真实/沙箱数据源路由
- 七节点 Agent Graph、面向处置结果的结构化输出、RAG 引用和预算控制
- 单次消费审批、审批后健康复查、幂等恢复、取消、超时和 `MANUAL_REQUIRED` 安全终态
- 可重放 SSE 时间线，浏览器按最后持久化序号断线续传
- 30 条评测基线，MOCK 与 LIVE 复用生产节点但与工单和审批队列隔离
- 安全审计、敏感信息脱敏、低基数 Micrometer 指标与 Prometheus

## 模型配置

无模型 Key 时平台仍可启动、使用本地词法向量验证知识库、运行 MOCK 评测与控制面演示；真实 Agent 诊断会明确失败，而不会伪造模型结果。配置 `AI_DASHSCOPE_API_KEY` 或 `DEEPSEEK_API_KEY` 后启用对应对话模型，使用 `AGENT_MODEL_PROVIDER=QWEN|DEEPSEEK` 选择默认供应商。

本地向量器只用于离线演示和可重复验收，不冒充生产语义模型。需要 Qwen embedding 时，同时配置：

```dotenv
AI_DASHSCOPE_API_KEY=your-key
KNOWLEDGE_EMBEDDING_PROVIDER=QWEN
```

切换 embedding 提供方后应重新入库文档，避免在同一知识版本中混用不同向量空间。`KNOWLEDGE_MIN_SCORE` 会在数据库查询阶段过滤低相关度结果，默认值为 `0.25`。

## 验证

```powershell
mvn -f server/pom.xml clean verify
npm --prefix web ci
npm --prefix web test -- --run
npm --prefix web run build
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example build
pwsh -File scripts/smoke.ps1
```

权威验收基线为 99 个后端单元测试、57 个 Testcontainers 集成测试和 9 个前端测试。Smoke 真实检查健康、登录、知识入库与 pgvector 检索、跨租户隔离、工单、Agent 状态边界与 30 条 MOCK 评测，并输出持久化 `evaluationRun`。

## 文档

- [架构](docs/architecture.md)
- [完整体验与代码导读](docs/full-experience-guide.md)
- [评测](docs/evaluation.md)
- [安全边界](docs/security.md)
- [演示脚本](docs/demo-script.md)

## 明确局限

当前已经支持通用 HTTP/Actuator 业务服务接入，但尚未提供 Prometheus、Loki、CMDB、Kubernetes 和发布平台的原生客户端；复杂企业环境可在现有 `OpsDataProvider` 端口上继续增加适配器。部署目标仍是单机 Compose，尚未实现企业 SSO、集中密钥轮换和分布式追踪后端。上述内容不会在简历或演示中冒充已实现能力。
