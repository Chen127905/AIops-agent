# Security Boundaries

## Enforced controls

- JWT 签名、BCrypt 密码、无状态会话和 ADMIN/OPERATOR 角色。
- 租户 ID 只来自已认证 principal，不信任 `X-Tenant-Id`。
- 工单、知识、任务、审批、审计和评测查询均限定租户。
- 检索内容包裹为不可信证据，提示注入不能更改 Java 权限。
- 允许的只读工具仅为 `getServiceHealth`、`queryMetrics`、`queryLogs`、`getServiceDependencies`、`searchRunbook`。
- `restartService`、`changeConfig` 必须经过未过期的单次人工审批；模型不能批准自己的请求。
- 其他工具名全部拒绝，不执行 Shell、任意 HTTP、文件系统或 SQL 工具。
- 密钥、Bearer Token 和配置模式在模型、日志、审计和 UI 边界脱敏。
- 指标标签禁止 tenant、ticket、task、prompt 和原始 error，避免高基数与数据泄露。

## Correlation and audit

HTTP、Agent 线程池和 Graph 节点维护 `trace_id`、`tenant_id`、`ticket_id`、`task_id`、`step_id`，在线程复用前于 `finally` 清理。安全敏感动作写入持久化审计表；Prometheus 仅 ADMIN 可访问，健康摘要允许匿名读取。

## Deployment responsibilities

`.env.example` 只供本机演示。生产必须替换所有数据库密码和 JWT Secret，使用密钥管理服务、TLS、网络策略、备份和轮换机制，并关闭演示账号。平台尚未实现企业 SSO、WAF、KMS 或跨服务分布式 tracing。
