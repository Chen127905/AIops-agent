<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cancelAgentTask, cancelTicket, getAgentTask, getTicket, startAgentTask, type AgentTask, type Ticket } from '../api/tickets'
import AgentTimeline from '../components/AgentTimeline.vue'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import ModalDialog from '../components/ModalDialog.vue'
import { displayLabel, formatDate, scenarioLabel } from '../utils/labels'

const route = useRoute(); const router = useRouter(); const ticketId = Number(route.params.id)
const ticket = ref<Ticket | null>(null); const taskId = ref(route.query.task ? Number(route.query.task) : null); const task = ref<AgentTask | null>(null)
const error = ref(''); const busy = ref(false); const confirmAction = ref<'ticket' | 'task' | null>(null)
const canStart = computed(() => ticket.value && !['RESOLVED', 'CANCELLED'].includes(ticket.value.status) && !['QUEUED','RUNNING','WAITING_APPROVAL'].includes(task.value?.status ?? ''))
const canCancelTask = computed(() => task.value && ['QUEUED','RUNNING','WAITING_APPROVAL'].includes(task.value.status))

async function refresh(): Promise<void> { ticket.value = await getTicket(ticketId); if (taskId.value) task.value = await getAgentTask(taskId.value) }
async function start(): Promise<void> {
  busy.value = true; error.value = ''
  try { task.value = await startAgentTask(ticketId); taskId.value = task.value.id; await router.replace({ query: { task: task.value.id } }); ticket.value = await getTicket(ticketId) }
  catch { error.value = '任务启动失败：可能已有活动任务或执行队列已满' } finally { busy.value = false }
}
async function confirm(): Promise<void> {
  busy.value = true; error.value = ''
  try {
    if (confirmAction.value === 'ticket') { await cancelTicket(ticketId); task.value = null }
    if (confirmAction.value === 'task' && taskId.value) task.value = await cancelAgentTask(taskId.value)
    confirmAction.value = null; ticket.value = await getTicket(ticketId)
  } catch { error.value = '操作失败，当前状态可能已经发生变化' } finally { busy.value = false }
}
function updateTask(next: AgentTask): void {
  task.value = next
  if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'MANUAL_REQUIRED'].includes(next.status)) {
    void getTicket(ticketId).then((value) => { ticket.value = value })
  }
}
onMounted(async () => { try { await refresh() } catch { error.value = '工单不存在或不属于当前租户' } })
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><RouterLink to="/tickets" class="back-link">← 返回工单列表</RouterLink><p class="section-label">工单 #{{ ticketId }}</p><h2>{{ ticket?.title ?? '加载中' }}</h2></div><div class="header-actions"><button v-if="canCancelTask" class="secondary-action" @click="confirmAction = 'task'">停止 Agent</button><button v-if="canStart" class="primary-button" :disabled="busy" @click="start">{{ busy ? '正在入队…' : '启动 Agent 诊断' }}</button><button v-if="ticket && !['RESOLVED','CANCELLED'].includes(ticket.status)" class="danger-ghost" @click="confirmAction = 'ticket'">取消工单</button></div></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <section v-if="ticket" class="ticket-summary surface-panel">
    <div><span>当前状态</span><strong><i class="status-dot" :data-status="ticket.status" />{{ displayLabel(ticket.status) }}</strong></div><div><span>目标服务</span><strong>{{ ticket.affectedService || '—' }}</strong></div><div><span>严重程度</span><strong>{{ displayLabel(ticket.severity) }}</strong></div><div><span>故障场景</span><strong>{{ ticket.scenarioKey ? scenarioLabel(ticket.scenarioKey) : '—' }}</strong></div><div><span>创建时间</span><strong>{{ formatDate(ticket.createdAt) }}</strong></div><div><span>最近更新</span><strong>{{ formatDate(ticket.updatedAt) }}</strong></div>
    <article class="ticket-description"><span>故障描述</span><p>{{ ticket.description }}</p></article><blockquote v-if="ticket.resolutionSummary"><span>处置结论</span>{{ ticket.resolutionSummary }}</blockquote>
  </section>
  <AgentTimeline v-if="taskId" :task-id="taskId" @task-updated="updateTask" />
  <section v-else class="agent-empty surface-panel"><div class="agent-symbol">AI</div><div><h3>尚未启动 Agent 诊断</h3><p>启动后，平台将检索知识、调用只读诊断工具，并在高风险恢复操作前等待人工审批。</p></div></section>
  <ModalDialog v-if="confirmAction" :title="confirmAction === 'ticket' ? '确认取消工单？' : '确认停止 Agent？'" eyebrow="操作确认" @close="confirmAction = null"><p class="confirm-copy">{{ confirmAction === 'ticket' ? '工单取消后不能再次启动 Agent，请确认该事件无需继续处置。' : '系统会请求中止当前执行，已经产生的事件仍会保留用于审计。' }}</p><div class="modal-actions"><button class="text-button" @click="confirmAction = null">返回</button><button class="danger-button" :disabled="busy" @click="confirm">确认操作</button></div></ModalDialog>
</ConsoleLayout></template>
