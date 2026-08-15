# Ops Agent Platform 设计规格

## 1. 项目目标

Ops Agent Platform 是一个面向企业 IT 运维工单的 Java Agent 应用。用户提交故障工单后，系统对工单进行分类，从当前租户的知识库检索故障手册，制定诊断计划，调用受控运维工具收集证据，并在高风险操作前暂停任务等待人工审批。操作完成后，系统验证故障是否恢复并生成带引用的处理报告。

项目用于证明以下能力：

- 使用 Java 构建生产导向的大模型应用和 Agent 工作流；
- 区分确定性业务工作流与不确定性模型推理；
- 实现长任务状态持久化、暂停、恢复、取消和超时；
- 实现租户隔离、工具权限、人工审批和审计；
- 以固定数据集评测 Agent，而不是只展示聊天效果；
- 使用测试、指标和可复现部署证明工程质量。

本项目不以复现现有 Manus、恋爱助手或通用聊天机器人为目标。

## 2. 范围

### 2.1 前六周必须完成

- 企业、用户和基础角色；
- 工单创建、查询、取消和状态流转；
- 一个受控 Agent 工作流；
- 内置可重复故障场景模拟器；
- 至少五种只读诊断工具；
- 两种必须审批的高风险工具；
- 企业知识文档导入、切片、向量检索和引用；
- Agent 任务、步骤、模型调用、工具调用和审批持久化；
- Graph 暂停、审批和恢复；
- SSE 执行进度订阅；
- 租户数据与知识库隔离；
- 基础 Prompt Injection 防护；
- 30 条以上自动评测用例；
- 自动化测试、Docker Compose、README、架构文档和演示脚本。

### 2.2 明确不做

- 多 Agent 协作和 A2A；
- 微服务拆分、Nacos、Kubernetes；
- 任意 Shell、任意 SQL 或任意文件系统访问；
- 通用代码执行沙箱；
- 可视化低代码工作流编辑器；
- 复杂企业组织架构和企业 SSO；
- 真实 Prometheus、日志平台或生产服务器接入；
- 模型训练、微调或后训练；
- 复杂前端视觉设计。

## 3. 技术选型

### 3.1 后端

- Java 21；
- Spring Boot 3.5.8；
- Spring AI Alibaba Agent Framework / Graph 1.1.2.3；
- Spring AI 的 Chat Model、Embedding、Vector Store 和 MCP 能力；
- MyBatis-Plus 3.5.17，用于 MySQL 业务数据访问；
- Flyway；
- JUnit 5、Mockito、Testcontainers、WireMock；
- Micrometer，后续可接 OpenTelemetry。

### 3.2 模型

- 阿里云百炼通义千问为主模型；
- DeepSeek 为备用模型和评测对照；
- 第一版不做动态智能路由；
- 所有业务代码依赖统一模型接口，不直接绑定具体模型 SDK；
- 记录模型名称、请求耗时、Token 用量、重试次数和结果状态。

### 3.3 存储

- MySQL：租户、用户、工单、任务、步骤、模型调用、工具调用、审批、审计、知识文档元数据和评测数据；
- PostgreSQL + pgvector：文档切片、Embedding 和向量检索；
- 第一版不依赖 Redis 或消息队列；
- Docker Compose 启动 MySQL 和 pgvector。

### 3.4 前端

- Vue 3 + TypeScript；
- REST 管理业务数据；
- SSE 订阅 Agent 执行事件；
- 只实现登录、工单、执行轨迹、审批、知识库和评测六类页面。

## 4. 总体架构

系统采用模块化单体，部署为一个 Java 后端和一个 Vue 前端。业务模块通过 Java 接口协作，不直接跨模块访问内部表。

```text
Vue 3
  | REST + SSE
  v
Spring Boot
  |-- identity        用户、租户、角色
  |-- ticket          工单和业务状态
  |-- agent-runtime   Graph、任务、步骤、恢复
  |-- knowledge       文档、切片、检索、引用
  |-- tool-center     工具注册、校验、权限、风险策略
  |-- approval        人工审批和审计
  |-- simulator       故障场景与模拟运维数据
  |-- evaluation      评测集、批量执行、指标
  `-- observability   模型、工具和任务指标
       |-- MySQL
       |-- PostgreSQL + pgvector
       |-- Qwen
       `-- DeepSeek
```

### 4.1 模块职责

- `identity`：维护租户、用户和基础角色。第一版不实现部门树。
- `ticket`：维护工单和工单状态，不直接调用模型。
- `agent-runtime`：启动、暂停、恢复、取消 Graph，持久化执行状态。
- `knowledge`：处理文档并提供强制租户过滤的检索接口。
- `tool-center`：注册工具，在执行前完成参数、权限和风险校验。
- `approval`：创建、通过、拒绝和超时审批，并触发任务恢复。
- `simulator`：以固定场景模拟健康状态、指标、日志、依赖和修复结果。
- `evaluation`：复用生产 Agent 接口运行固定用例并计算指标。
- `observability`：统一记录模型、工具、Graph 节点和任务指标。

## 5. 领域模型

### 5.1 租户模型

```text
Tenant
  |-- User
  |-- KnowledgeDocument
  |-- Ticket
  `-- ToolPolicy
```

所有核心业务表包含 `tenant_id`。Repository、知识检索和工具层都必须检查租户边界，不能只依赖 Controller 传入的租户标识。身份认证固定使用 Spring Security + JWT；第一版不接入企业 SSO。

### 5.2 核心表

| 表 | 作用 |
| --- | --- |
| `tenant` | 企业租户 |
| `user` | 用户、租户和角色 |
| `ticket` | 故障工单和业务状态 |
| `agent_task` | 一次 Agent 执行 |
| `agent_step` | Graph 节点和执行步骤 |
| `model_invocation` | 模型、Token、耗时和状态 |
| `tool_invocation` | 工具参数、结果、风险和幂等键 |
| `approval_request` | 高风险操作审批 |
| `audit_log` | 用户、Agent 和系统审计记录 |
| `knowledge_document` | 文档、版本、来源和处理状态 |
| `evaluation_case` | 输入、标准答案和标签 |
| `evaluation_run` | 一次批量评测 |
| `evaluation_result` | 单条用例结果和指标 |

pgvector 文档切片至少包含 `tenant_id`、`document_id`、`document_version`、`chunk_index`、`content`、`source`、`metadata` 和 `embedding`。

## 6. 状态机

### 6.1 工单状态

```text
OPEN
 -> TRIAGING
 -> DIAGNOSING
 -> WAITING_APPROVAL
 -> EXECUTING
 -> VERIFYING
 -> RESOLVED
```

异常终态为 `FAILED`、`CANCELLED`、`TIMEOUT` 或 `MANUAL_REQUIRED`。审批通过后从 `WAITING_APPROVAL` 进入 `EXECUTING`；审批拒绝后重新规划一次或进入 `MANUAL_REQUIRED`。

### 6.2 Agent 任务状态

```text
CREATED -> RUNNING -> SUSPENDED
                   -> SUCCEEDED
                   -> FAILED
                   -> CANCELLED
                   -> TIMEOUT
```

工单和 Agent 任务分离，一个工单允许保留多次执行历史，但同一工单只能存在一个活动任务。

## 7. Agent 工作流

系统采用“确定性工作流 + 受控 Agent 节点”，不使用全流程纯 ReAct。

1. 创建工单和 Agent 任务；
2. 分类节点输出服务、故障类别和严重级别；
3. 检索节点从当前租户知识库获取证据；
4. 规划节点生成类型安全的诊断步骤；
5. ReAct 诊断节点调用受控只读工具；
6. 决策节点输出根因、证据和建议操作；
7. Java 风险策略判断是否需要审批；
8. 高风险操作创建审批，Graph 持久化 Checkpoint 并暂停；
9. 审批通过后从 Checkpoint 恢复并执行工具；
10. 验证节点重新查询指标；
11. 总结节点生成带引用的处理报告；
12. 更新工单和任务终态。

确定性状态流转、权限和风险决策由 Java 代码控制；模型不能提高权限或绕过审批。

## 8. 场景模拟器

第一版不部署完整微服务靶场。`simulator` 通过 `OpsDataProvider` 接口提供可替换的数据源：

```java
interface OpsDataProvider {
    HealthResult getHealth(OpsContext context, String service);
    MetricResult queryMetrics(OpsContext context, MetricQuery query);
    LogResult queryLogs(OpsContext context, LogQuery query);
    DependencyResult getDependencies(OpsContext context, String service);
    OperationResult executeApprovedOperation(OpsContext context, Operation operation);
}
```

第一版实现 `FixtureOpsDataProvider`，从 YAML 或 JSON 加载场景。后续可增加 Prometheus 或日志平台实现，而不改变 Agent 工具契约。

至少提供以下场景：

- 数据库连接池耗尽；
- Redis 命令超时；
- API 错误率升高；
- 消息队列积压；
- 磁盘空间不足。

每个场景包含服务状态、指标、日志、依赖、根因、期望工具、禁止工具、是否需要审批和修复后的状态。

## 9. 工具和安全

### 9.1 工具集

只读工具至少包括：

- `getServiceHealth`；
- `queryMetrics`；
- `queryLogs`；
- `getServiceDependencies`；
- `searchRunbook`。

高风险工具包括：

- `restartService`；
- `changeConfig`。

高风险工具只改变模拟场景状态，不执行真实系统命令。

### 9.2 风险等级

- `READ_ONLY`：权限校验后自动执行；
- `LOW_RISK_WRITE`：可由租户策略决定是否自动执行；
- `HIGH_RISK`：必须人工审批；
- `FORBIDDEN`：永远禁止。

### 9.3 强制安全规则

- 工具参数使用强类型 Java 对象和 Bean Validation；
- 检查租户和资源归属；
- 设置超时和最大输出长度；
- 敏感字段脱敏；
- 不接受任意 Shell、SQL 或文件路径；
- 记录工具参数摘要、结果摘要、耗时、状态和幂等键；
- 知识文档视为不可信数据；
- Java 策略层拥有最终权限决定权；
- 高风险操作必须审批；
- 敏感信息不进入模型上下文。

## 10. 并发、持久化和恢复

- 同一工单同时只允许一个活动任务，由唯一约束和条件更新保证；
- 审批请求只能完成一次；
- 工具幂等键由 `task_id + step_id + tool_name + normalized_args_hash` 生成；
- SSE 断开只取消订阅，不取消后台任务；
- 用户显式取消才进入 `CANCELLED`；
- Graph Checkpoint、业务状态和执行记录必须协调恢复；
- 服务启动后扫描 `RUNNING` 且租约过期的任务；
- 可安全恢复的任务从 Checkpoint 恢复，否则进入 `MANUAL_REQUIRED`；
- 后台任务使用专用有界线程池、租户级并发限制、超时和明确拒绝策略；
- 应用优雅停机时停止接收新任务，并保存可恢复状态。

## 11. 错误处理

| 错误类型 | 处理 |
| --- | --- |
| 模型超时、429、临时不可用 | 指数退避有限重试，必要时切换 DeepSeek |
| 结构化输出不合法 | 校验并要求模型修复，最多两次 |
| 工具临时错误 | 当前步骤有限重试 |
| 业务状态错误 | 不重试，返回明确业务错误 |
| 权限或租户错误 | 立即拒绝并审计 |
| 安全策略拒绝 | 阻断或转人工审批 |
| 预算耗尽 | 进入 `TIMEOUT` 或 `MANUAL_REQUIRED` |
| 服务重启 | 按 Checkpoint、租约和数据库状态恢复 |

所有异常必须转换为明确状态和错误码，不能只把异常文本追加到模型上下文后继续循环。

## 12. RAG 设计

- 文档上传后进入解析、切片、Embedding 和发布流程；
- 文档支持版本号和处理状态；
- 检索强制添加 `tenant_id` 和有效版本过滤；
- 第一版使用向量检索和元数据过滤；
- 返回结果包含文档、版本、片段和来源；
- Agent 报告中的关键诊断结论必须关联引用；
- 混合检索、重排和复杂 Query Rewrite 属于第六周后可选优化。

## 13. 前端

前端只实现以下页面：

1. 登录与租户选择；
2. 工单列表和创建；
3. 工单详情；
4. Agent 执行轨迹；
5. 待审批操作；
6. 知识库和评测结果。

执行轨迹显示结构化计划、节点、工具、结果摘要、引用和审批状态，不展示模型隐藏推理过程。

## 14. 测试策略

- 单元测试：状态机、权限、风险策略、参数校验和场景解析；
- Repository 测试：唯一约束、条件更新和租户隔离；
- 向量检索测试：切片、过滤和跨租户不可见；
- Graph 测试：成功、暂停、恢复、取消和超时；
- 工具契约测试：合法参数、非法参数、超时和幂等；
- API 集成测试：从创建工单到关闭工单；
- 模型 Mock 测试：CI 不依赖外部模型；
- 真实模型评测：独立任务执行，不阻塞普通构建。

## 15. 评测设计

评测集至少 30 条，包含：

- 工单分类；
- RAG 引用；
- 工具选择和参数；
- 端到端诊断；
- 权限、审批和 Prompt Injection。

每条用例保存：

- `expected_category`；
- `expected_severity`；
- `expected_root_cause`；
- `expected_tools`；
- `forbidden_tools`；
- `requires_approval`；
- `expected_final_status`；
- `required_citations`。

核心指标包括分类准确率、根因准确率、工具选择 Precision / Recall、参数正确率、引用正确率、端到端解决率、审批拦截率、跨租户泄露次数、平均步骤、Token 成本和 P50 / P95 耗时。

不预设虚假的目标准确率。系统先产生基线，再基于失败分类优化 Prompt、检索参数和工作流。

## 16. 可观测性

至少记录：

- Agent 任务数、成功率、失败率和执行时间；
- 当前运行、暂停和超时任务数；
- Graph 节点耗时；
- 模型调用次数、失败、重试、Token 和延迟；
- 工具调用次数、失败、超时和审批次数；
- RAG 检索耗时和引用数量；
- 线程池活跃数、队列长度和拒绝次数。

日志统一包含 `trace_id`、`tenant_id`、`ticket_id`、`task_id` 和 `step_id`。

## 17. 六周里程碑

### 第 1 周：技术验证和骨架

- 模型、SSE、Graph、暂停恢复、Tool Calling、pgvector 和数据库迁移最小验证；
- 创建后端、前端和 Docker Compose；
- 固定模块边界和数据表。

### 第 2 周：工单和模拟器

- 工单状态机；
- 模拟器、五个场景和五种只读工具；
- 单元测试和基础 API。

### 第 3 周：知识库

- 文档上传、切片、Embedding、检索、引用和租户过滤；
- RAG 自动化测试。

### 第 4 周：Agent 闭环

- 分类、检索、规划、诊断、决策、验证和总结；
- 任务、步骤和调用记录；
- SSE 轨迹；
- 可演示 MVP。

### 第 5 周：审批和安全

- 工具风险；
- 审批暂停恢复；
- 幂等、取消、超时、租约恢复；
- Prompt Injection 基础防护和审计。

### 第 6 周：评测和交付

- 30 条以上用例；
- 真实模型评测报告；
- 测试、指标、Docker、README、架构图和演示脚本。

## 18. 完成标准

- Docker Compose 能启动 MySQL 和 pgvector；
- 后端与前端有可复现启动方式；
- 至少五个故障场景、五个只读工具和两个审批工具；
- 工单全流程可运行；
- Graph 可以暂停、审批和恢复；
- SSE 断开不丢失任务；
- 服务重启后任务不会无声丢失；
- 租户业务数据和知识库隔离；
- 至少 30 条评测用例和一份真实模型基线报告；
- 核心自动化测试通过；
- README、架构、接口和演示文档齐全。

## 19. 项目叙事

项目的核心叙事是：确定性业务流程由 Java 和 Graph 控制，不确定性诊断由 Agent 完成；任何模型输出都不能绕过权限、审批和审计。项目与 PictureHub 的 MQ、Outbox 和媒体业务形成互补，重点展示 Java Agent 应用的运行时、安全、RAG 和评测能力。
