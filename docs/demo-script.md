# Demo Script

## 1. Start and verify

```powershell
docker compose --env-file .env up -d --build
pwsh -File scripts/seed-knowledge.ps1
```

记录 smoke 输出的 knowledgeDocument、citation、ticket、task、taskStatus 和 evaluationRun。打开 `http://localhost:8088`，使用 `acme/admin/demo-password` 登录。

## 2. Show the control loop

1. 在“知识库”检索已初始化的官方 runbook，展示 pgvector 相似度和不可变 citation。
2. 在“工单与 Agent”通过场景下拉框创建 Redis 超时或磁盘压力工单。
3. 启动 Agent，解释任务唯一性、预算和后台执行。
4. 在时间线展示持久化节点、工具、引用和最终摘要；强调不展示隐藏推理。
5. 刷新带 `?task=<id>` 的详情页，说明 SSE 从最后 sequence 重放而不重启任务。
6. 对写工具场景进入“人工审批”，批准或拒绝一次并说明并发决策只有一个成功；批准后展示额外健康检查与 `POST_ACTION_VERIFIED`。

无真实模型 Key 时，真实 Agent 会以可审计失败结束；使用评测中心的 MOCK 路径演示完整确定性 Graph。配置 Qwen 或 DeepSeek Key 后再演示 LIVE 调用。

## 3. Show enterprise controls

1. 用 `beta/operator` 请求 acme 工单，展示 `404` 而非泄露存在性。
2. 展示五个只读工具、两个审批型工具和默认拒绝策略。
3. 在评测中心运行 30 条 MOCK 基线，记录真实 runId。
4. 以 ADMIN 查看 `/actuator/prometheus`，说明低基数标签。
5. 结合 `docs/security.md` 说明提示注入、脱敏、恢复和 `MANUAL_REQUIRED`。

## 4. Cleanup

```powershell
docker compose --env-file .env.example down
```

需要删除本地演示数据时显式执行 `docker compose --env-file .env.example down -v`。
