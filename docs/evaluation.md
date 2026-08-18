# Evaluation

## Dataset and modes

`server/src/main/resources/evaluation/baseline-cases.json` 固化 30 条用例，覆盖分类、检索、工具、端到端、审批和攻击六组。MOCK 使用确定性模型与检索适配器，但仍执行生产七节点 Graph、Java 工具策略和模拟器；LIVE 使用真实模型配置和同一 `OpsAgentWorkflow`。

## Run

管理员在 UI 的“评测中心”运行 MOCK，或调用：

```powershell
$result = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8088/api/evaluations/runs `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType application/json `
  -Body '{"mode":"MOCK"}'
$result.runId
```

LIVE 必须显式提供 Key，并与 MOCK 分开存储：

```json
{"mode":"LIVE","provider":"QWEN","model":"qwen-plus","promptVersion":"v1","knowledgeVersion":"v1"}
```

## Metrics and evidence

持久化结果包含通过率、分类/根因准确率、工具 precision/recall、参数/引用/解决准确率、审批拦截率、泄露数、平均步骤/token 和 P50/P95 延迟。仓库不提交虚构百分比；每次 smoke 输出真实 `evaluationRun=<UUID>`，可用 `GET /api/evaluations/runs/{runId}` 回查。

确定性验收要求同一 30 条 MOCK 基线重复运行结果一致。LIVE 结果受供应商模型、提示词和知识版本影响，不能替代 MOCK 回归门禁。

## Packaged verification evidence

2026-08-18 通过 Docker Compose 对外入口执行增强后的 `scripts/smoke.ps1`，实际持久化运行 ID 为 `6a0d950a-cc01-4eaa-bff6-1cf78c261860`，完成知识入库、pgvector 检索、跨租户引用隔离和 30 条 MOCK 基线。该 ID 来自本机验收数据库；删除 Compose 数据卷后需以新运行 ID 为准。
