<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { approve, listPendingApprovals, reject, type Approval } from '../api/approvals'
import ConsoleLayout from '../components/ConsoleLayout.vue'

const approvals = ref<Approval[]>([])
const error = ref('')
const secretKeys = /password|secret|token|authorization|credential/i
function safeArguments(argumentsValue: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(argumentsValue)
    .map(([key, value]) => [key, secretKeys.test(key) ? '[REDACTED]' : value]))
}
async function load() { try { approvals.value = await listPendingApprovals() } catch { error.value = '审批列表加载失败' } }
async function decide(item: Approval, accepted: boolean) {
  try { accepted ? await approve(item.id, '控制台人工确认') : await reject(item.id, '控制台人工拒绝'); await load() }
  catch { error.value = '审批已被其他人处理或已经过期' }
}
onMounted(() => void load())
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">HUMAN IN THE LOOP</p><h2>高风险工具审批</h2></div></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div class="approval-grid"><article v-for="item in approvals" :key="item.id" class="approval-card">
    <div class="panel-heading"><span class="severity" data-level="CRITICAL">{{ item.risk }}</span><span class="mono-label">TASK #{{ item.taskId }}</span></div>
    <h3>{{ item.toolName }}</h3><pre>{{ JSON.stringify(safeArguments(item.normalizedArguments), null, 2) }}</pre><small>过期时间 {{ new Date(item.expiresAt).toLocaleString() }}</small>
    <div class="button-row"><button class="primary-button" @click="decide(item, true)">批准一次</button><button class="danger-button" @click="decide(item, false)">拒绝</button></div>
  </article><p v-if="!approvals.length" class="empty-state large">暂无待审批请求。模型无法自行绕过这里。</p></div>
</ConsoleLayout></template>
