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

## 核心能力

- 多租户 JWT 身份、角色授权与服务端租户隔离
- 五种确定性故障场景、五个只读工具和两个审批型写工具
- 七节点 Agent Graph、结构化模型输出、RAG 引用和预算控制
- 单次消费审批、幂等恢复、取消、超时和 `MANUAL_REQUIRED` 安全终态
- 可重放 SSE 时间线，浏览器按最后持久化序号断线续传
- 30 条评测基线，MOCK 与 LIVE 复用同一生产工作流端口
- 安全审计、敏感信息脱敏、低基数 Micrometer 指标与 Prometheus

## 模型配置

无模型 Key 时平台仍可启动、运行 MOCK 评测与控制面演示；真实 Agent 诊断会明确失败，而不会伪造模型结果。配置 `AI_DASHSCOPE_API_KEY` 或 `DEEPSEEK_API_KEY` 后启用对应模型，使用 `AGENT_MODEL_PROVIDER=QWEN|DEEPSEEK` 选择默认供应商。

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

权威验收基线为 96 个后端单元测试、56 个 Testcontainers 集成测试和 7 个前端测试。Smoke 真实检查健康、登录、工单、跨租户 404、Agent 状态边界与 30 条 MOCK 评测，并输出持久化 `evaluationRun`。

## 文档

- [架构](docs/architecture.md)
- [评测](docs/evaluation.md)
- [安全边界](docs/security.md)
- [演示脚本](docs/demo-script.md)

## 明确局限

当前运维系统由进程内确定性模拟器替代真实 Prometheus、Loki、CMDB 和发布平台；部署目标是单机 Compose 而非 Kubernetes；未实现企业 SSO、密钥轮换和分布式追踪后端。上述内容不会在简历或演示中冒充已实现能力。
