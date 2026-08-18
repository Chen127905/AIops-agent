const labels: Record<string, string> = {
  OPEN: '待处理', TRIAGING: '分诊中', DIAGNOSING: '诊断中', EXECUTING: '执行处置中', VERIFYING: '验证恢复中', RESOLVED: '已解决', CANCELLED: '已取消', TIMEOUT: '执行超时', MANUAL_REQUIRED: '需要人工处理',
  QUEUED: '等待执行', RUNNING: '执行中', WAITING_APPROVAL: '等待审批', SUCCEEDED: '执行成功', FAILED: '执行失败', TIMED_OUT: '执行超时',
  PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝', EXPIRED: '已过期',
  LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '紧急',
  CACHE: '缓存', DATABASE: '数据库', APPLICATION: '应用服务', MESSAGING: '消息队列',
  MOCK: '基线评测', LIVE: '真实模型评测', COMPLETED: '评测完成', COMPLETED_WITH_FAILURES: '评测完成（存在失败）',
  UP: '运行正常', DOWN: '服务异常', UNKNOWN: '状态未知',
  CLASSIFICATION: '意图分类', RETRIEVAL: '知识检索', TOOL: '工具调用', TOOL_USE: '工具调用', END_TO_END: '端到端流程', APPROVAL: '审批拦截', ATTACK: '安全攻击',
  INFRASTRUCTURE: '基础设施', REDIS_TIMEOUT: 'Redis 超时',
  TASK_CREATED: '任务已创建', TASK_STARTED: '任务开始执行', TASK_SUCCEEDED: '任务执行成功', TASK_FAILED: '任务执行失败',
  TASK_CANCELLED: '任务已取消', TASK_COMPLETED: '任务执行完成', TASK_EXECUTION_FAILED: '任务执行失败', TASK_REJECTED: '任务未被执行', TASK_CANCEL_REQUESTED: '已请求取消任务',
  EVIDENCE_RETRIEVED: '已检索诊断证据', MODEL_COMPLETED: '模型分析完成', NODE_STARTED: '开始执行节点', NODE_COMPLETED: '节点执行完成',
  TOOL_REQUESTED: '请求调用工具', TOOL_STARTED: '工具开始执行', TOOL_SUCCEEDED: '工具执行成功', TOOL_FAILED: '工具执行失败',
  APPROVAL_REQUESTED: '已提交人工审批', APPROVAL_GRANTED: '人工审批通过', APPROVAL_APPROVED: '人工审批通过', APPROVAL_REJECTED: '人工审批拒绝',
  APPROVAL_EXECUTION_STARTED: '审批动作开始执行', APPROVAL_EXECUTION_COMPLETED: '审批动作执行完成', APPROVAL_EXECUTION_FAILED: '审批动作执行失败', POST_ACTION_VERIFIED: '恢复效果验证完成',
  FINAL_ANSWER_CREATED: '已生成处置结论',
  NONE: '不执行自动变更', restartService: '重启目标服务', changeConfig: '修改服务配置',
  APPLICATION_INCIDENT: '应用故障', NETWORK_ERROR: '网络异常',
}

export function displayLabel(value: string | null | undefined): string {
  if (!value) return '—'
  return labels[value] ?? value
}

export function formatDate(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

const scenarioLabels: Record<string, string> = {
  'api-error-rate': '支付 API 错误率突增',
  'db-pool-exhausted': '数据库连接池耗尽',
  'disk-full': '日志磁盘空间耗尽',
  'mq-backlog': '消息队列消费积压',
  'redis-timeout': 'Redis 连接池超时',
}

export function scenarioLabel(key: string): string {
  return scenarioLabels[key] ?? key
}
