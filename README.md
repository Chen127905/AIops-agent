# Ops Agent Platform

面向企业运维工单的 Java + AI Agent 平台。项目以 Java 21、Spring Boot 4、Spring AI 2、MySQL、PostgreSQL/pgvector 和 Vue 3 实现可审计诊断、工具策略、人工审批、恢复、评测与可观测性闭环。

## 智能体原理与整体框架

### 智能体到底负责什么

这个项目中的 Agent 不是一个只会回答问题的聊天机器人，也不是一个可以绕过权限直接登录服务器的“万能运维脚本”。它是工单系统中的智能诊断与受控处置执行器：接收故障工单，理解故障类型，检索企业运维知识，采集目标系统的健康状态、指标、日志和依赖信息，综合证据判断根因，最后输出可执行的处置方案。

对于只需要人工处理的故障，Agent 会给出诊断摘要、根因、处置步骤、验证标准和回滚方案；对于平台已经定义并允许执行的动作，例如重启服务或修改配置，Agent 只能提出动作建议。Java 策略层会再次验证工具名称、租户、参数、风险级别和审批状态，高风险动作必须经过人工审批后才会执行。

因此，整个系统遵循一个核心原则：**大模型负责理解和推理，Java 程序负责流程、权限、状态和执行边界。** 模型输出不能自行获得权限，也不能调用白名单之外的接口。

### 一次工单如何变成处置结果

```text
用户创建工单
    ↓
创建 AgentTask，并异步抢占任务租约
    ↓
七节点 Agent Graph 执行
    ↓
分类 → 知识检索 → 工具规划 → 数据采集 → 根因决策 → 风险判断 → 结果汇总
    ↓
无需自动变更                         需要高风险变更
    ↓                                      ↓
输出人工处置方案                    创建人工审批请求
                                           ↓
                                  批准后调用真实/沙箱变更端点
                                           ↓
                                  再次执行健康检查验证恢复
                                           ↓
                             成功关闭工单，失败则转人工处理
```

工单、Agent 任务、节点步骤、模型调用、工具调用、审批记录和 SSE 事件都会持久化到 MySQL。即使用户离开工单页面、浏览器断线或后端进程短暂重启，页面也可以重新加载历史事件，后台则可以根据安全检查点恢复未完成任务。

### 整体运行架构

| 层次 | 主要组件 | 负责内容 |
|---|---|---|
| 交互层 | Vue 3、Pinia、Vue Router | 工单创建、执行时间线、审批、知识库、评测和系统状态页面 |
| 接入层 | Nginx、Spring Security、JWT | 统一入口、反向代理、身份认证、角色校验和租户上下文 |
| 业务控制层 | Ticket、Agent、Approval、Evaluation | 工单状态机、Agent 生命周期、人工审批、恢复与评测 |
| Agent 编排层 | Alibaba Graph、七个 Agent Node | 将一次复杂诊断拆成固定、可观察、可恢复的执行节点 |
| 模型层 | Spring AI、Qwen、DeepSeek | 结构化分类、工具规划和根因推理，不直接执行系统操作 |
| 知识层 | 文档切分、Embedding、PostgreSQL/pgvector | RAG 语义检索、租户过滤、相似度过滤和引用追踪 |
| 工具层 | OpsToolFacade、ToolPolicyService | 工具白名单、参数限制、超时、结果裁剪、风险判断和审计 |
| 数据适配层 | Fixture、Managed HTTP Service | 在内置沙箱或真实业务系统上采集数据、执行获批动作 |
| 持久化层 | MySQL、Flyway | 保存业务状态、执行步骤、调用记录、审批、审计和评测结果 |
| 可观测层 | SSE、Micrometer、Prometheus | 实时事件推送、断线重放、任务指标和平台健康状态 |

这里并不是把所有逻辑写进一个 Prompt。每一层都有明确职责：模型即使返回错误工具名，也会被 Java 白名单拒绝；用户即使在工单描述中写入“忽略规则并重启服务器”，也只会被当作不可信业务数据处理。

### 七节点 Agent Graph

| 节点 | 输入 | 主要工作 | 输出 |
|---|---|---|---|
| `triage` | 工单标题、描述 | 调用模型判断 CACHE、DATABASE、APPLICATION 等故障分类和紧急程度 | 故障类别、紧急程度 |
| `retrieve` | 工单完整问题 | 对问题生成向量并从 pgvector 检索当前租户知识 | 最多 5 个证据片段及不可变引用 |
| `plan` | 故障类别、目标服务 | 让模型从严格白名单中选择只读诊断工具 | 诊断工具计划 |
| `diagnose` | 工具计划、目标系统 | 由 Java 逐个调用健康、指标、日志和依赖工具 | 经过限制和脱敏的真实观察数据 |
| `decision` | 知识证据、工具观察 | 让模型形成结构化根因、置信度、处置步骤、验证标准和回滚方案 | 诊断决策及建议动作 |
| `verify` | 建议动作 | 由确定性 Java 规则判断动作风险和下一状态 | `WAITING_APPROVAL` 或 `MANUAL_REQUIRED` |
| `summarize` | 前述全部状态 | 生成面向运维人员的最终报告并整理引用 | 可展示、可持久化的诊断结果 |

节点之间共享的是受约束的 `OpsAgentState`，其中保存任务预算、类别、知识证据、工具计划、观察结果、根因、建议动作和检查点。每进入一个节点都会消耗步骤预算；模型调用还会记录 Token 使用量，整个任务同时受最大步骤数、最大 Token 数和超时时间控制。

### RAG 如何参与诊断

知识库不是把整篇文档直接塞进 Prompt。管理员发布文档时，平台会先标准化内容并切分为多个 chunk，再通过本地确定性向量器或 Qwen Embedding 生成向量，最终写入 PostgreSQL/pgvector。检索时执行以下过程：

1. 将工单标题和描述组合成检索问题。
2. 使用与入库时相同的 Embedding 提供方生成查询向量。
3. 在数据库中按 `tenantId`、文档版本和最低相似度过滤。
4. 返回最相关的知识片段，而不是整份文档。
5. 为每个片段生成 `tenant:...:doc:...:v...:chunk:...` 引用。
6. 把证据作为不可信上下文交给模型，最终结果同时保留引用编号。

RAG 的作用是让诊断建立在企业自己的运维手册上，并让用户能够追溯“这条建议依据了哪份知识”。它不会替代实时指标和日志，知识证据与现场工具数据必须共同参与判断。

### Tool Calling 与真实系统操作

当前项目使用的是受控 Tool Calling 模式：模型只负责选择工具名称或提出动作，真正的调用由 Java 完成。平台没有把任意 Shell、SQL 或 HTTP 请求直接暴露给模型，也没有把 MCP 原生接入冒充为已经实现的功能。

当前只读工具包括：

- `getServiceHealth`：读取服务健康状态。
- `queryMetrics`：查询受限制数量的指标点。
- `queryLogs`：查询经过行数、字节数和脱敏限制的日志。
- `getServiceDependencies`：读取 MySQL、Redis、消息队列等依赖状态。
- `searchRunbook`：检索运维知识片段。

当前高风险写工具包括：

- `restartService`：调用已接入系统的重启或变更端点。
- `changeConfig`：提交经过结构校验的配置变更。

所有工具首先经过 `ToolPolicyService`。策略层会检查工具是否在白名单中、请求租户是否与登录租户一致、动作是否需要审批、高风险请求是否携带审批标识和幂等键。工具调用还受到超时时间、最大结果数和最大响应字节数限制，避免异常目标系统拖垮 Agent。

### 人工审批为什么不在模型中完成

审批是一条独立于模型推理的确定性业务链路。Agent 提出 `restartService` 或 `changeConfig` 后，任务进入 `WAITING_APPROVAL`，平台创建带有效期的审批记录。管理员可以批准或拒绝，同一审批只允许一个决策成功。

批准后，平台不会简单地把任务标记为成功，而是继续完成以下闭环：

1. 再次校验审批状态、租户、工具策略和参数。
2. 使用幂等键执行一次获批动作。
3. 将动作结果和审计记录持久化。
4. 再次调用只读健康检查。
5. 只有目标系统恢复后才记录 `POST_ACTION_VERIFIED` 并解决工单。
6. 动作失败、结果不明确或恢复检查未通过时转入 `MANUAL_REQUIRED`。

这种设计把“模型建议”“人工授权”“程序执行”和“结果验证”拆成四个独立环节。即使模型判断错误，也无法单独完成生产变更。

### 状态、事件与故障恢复

Agent 执行不是只存在于内存中的一次请求。平台为每个任务维护明确状态，例如 `QUEUED`、`RUNNING`、`WAITING_APPROVAL`、`SUCCEEDED`、`FAILED`、`TIMED_OUT`、`CANCELLED` 和 `MANUAL_REQUIRED`。工单状态会随 Agent 状态同步，但两者是不同聚合，避免页面状态代替真实执行状态。

每个节点开始和结束、模型调用、工具调用、审批以及任务终态都会先落库，再通过 SSE 推送给浏览器。每条事件都有递增序号，浏览器重连时携带最后收到的序号，后端会先重放遗漏事件，再继续推送实时事件，因此切换页面不会丢失执行过程。

后台 Worker 使用任务租约避免同一任务被多个实例同时执行。进程异常后，恢复任务只会从已经持久化的安全 checkpoint 继续；如果高风险写操作已经发出但无法确认结果，系统不会盲目重试，而是进入 `MANUAL_REQUIRED`，防止重复重启或重复修改配置。

### 一个完整例子

假设订单服务大量请求超时，工单描述为“订单接口响应变慢，日志出现 Redis 连接获取超时”。Agent 的实际工作过程如下：

1. `triage` 将故障归类为 `CACHE`，紧急程度判定为 `HIGH`。
2. `retrieve` 从当前租户知识库召回 Redis 延迟和连接池排障手册。
3. `plan` 只能从只读白名单选择健康、指标、日志和依赖工具。
4. `diagnose` 从目标系统发现 Redis 依赖异常、连接等待指标升高，并获得对应错误日志。
5. `decision` 综合实时证据和知识库内容，判断连接池耗尽是最可能根因，生成排查步骤、验证标准和回滚方案。
6. 如果证据只足够给出建议，任务进入 `MANUAL_REQUIRED`，由值班人员按报告处理。
7. 如果目标系统已配置获批变更端点且模型建议的是白名单动作，任务进入审批；管理员批准后平台执行动作，再次检查健康状态并决定是否关闭工单。

最终用户看到的不是几个没有含义的节点名称，而是一条可追溯链路：**工单问题是什么、查了哪些知识、读取了哪些现场数据、为什么判断为这个根因、建议怎么处理、是否执行过动作、执行后是否真的恢复。**

### 当前落地边界

平台目前提供两种数据路径。内置故障沙箱使用固定场景和确定性数据，适合开发、自动化测试和面试演示；真实业务系统路径通过受控 HTTP/Actuator 接口获取健康、指标、日志和依赖，并在显式配置变更端点后执行获批动作。

平台不会自动发现公司内部所有系统，也不会在没有适配器和凭证的情况下直接连接 Kubernetes、Prometheus、Loki 或服务器 Shell。后续可以在现有 `OpsDataProvider`、工具策略和审批框架上增加这些企业适配器，而不需要改变 Agent 的核心安全边界。

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
powershell -ExecutionPolicy Bypass -File scripts/seed-knowledge.ps1
```

也可以登录 `acme / admin`，进入“知识库”后点击“初始化内置知识”。页面和脚本都会调用同一个租户级幂等接口，重复执行不会创建重复文档。平台会发布五份有官方来源的运维手册，覆盖 Redis 超时、HikariCP 连接池耗尽、Spring Boot API 5xx、Kafka 消费积压和 Kubernetes 磁盘压力。不要在同一批已入库文档上切换 embedding 提供方；切换后应重新入库，避免混用不同向量空间。

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

登录 `acme / admin / demo-password` 后进入“知识库”，可以初始化内置知识或新增自己的文档，再使用页面中的语义检索验证召回。页面会返回 `tenant:...:doc:...:chunk:...` 引用。也可以运行：

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

权威验收基线为 99 个后端单元测试、58 个 Testcontainers 集成测试和 13 个前端测试。Smoke 真实检查健康、登录、知识入库与 pgvector 检索、跨租户隔离、工单、Agent 状态边界与 30 条 MOCK 评测，并输出持久化 `evaluationRun`。

## 文档

- [架构](docs/architecture.md)
- [完整体验与代码导读](docs/full-experience-guide.md)
- [评测](docs/evaluation.md)
- [安全边界](docs/security.md)
- [演示脚本](docs/demo-script.md)

## 明确局限

当前已经支持通用 HTTP/Actuator 业务服务接入，但尚未提供 Prometheus、Loki、CMDB、Kubernetes 和发布平台的原生客户端；复杂企业环境可在现有 `OpsDataProvider` 端口上继续增加适配器。部署目标仍是单机 Compose，尚未实现企业 SSO、集中密钥轮换和分布式追踪后端。上述内容不会在简历或演示中冒充已实现能力。
