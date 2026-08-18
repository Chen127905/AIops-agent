<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTicket, startAgentTask, type Ticket } from '../api/tickets'
import AgentTimeline from '../components/AgentTimeline.vue'
import ConsoleLayout from '../components/ConsoleLayout.vue'

const route = useRoute()
const router = useRouter()
const ticketId = Number(route.params.id)
const ticket = ref<Ticket | null>(null)
const taskId = ref(route.query.task ? Number(route.query.task) : null)
const error = ref('')
const running = ref(false)
const canStart = computed(() => ticket.value && !['RESOLVED', 'CANCELLED'].includes(ticket.value.status))

async function start(): Promise<void> {
  running.value = true; error.value = ''
  try { const task = await startAgentTask(ticketId); taskId.value = task.id; await router.replace({ query: { task: task.id } }) }
  catch { error.value = '任务启动失败：可能已有活动任务或执行队列已满' }
  finally { running.value = false }
}
onMounted(async () => { try { ticket.value = await getTicket(ticketId) } catch { error.value = '工单不存在或不属于当前租户' } })
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">TICKET #{{ ticketId }}</p><h2>{{ ticket?.title ?? '加载中' }}</h2></div><button v-if="canStart" class="primary-button" :disabled="running" @click="start">{{ running ? '正在入队' : '启动 Agent' }}</button></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <section v-if="ticket" class="ticket-summary"><div><span>状态</span><strong>{{ ticket.status }}</strong></div><div><span>服务</span><strong>{{ ticket.affectedService || '—' }}</strong></div><div><span>等级</span><strong>{{ ticket.severity }}</strong></div><p>{{ ticket.description }}</p><blockquote v-if="ticket.resolutionSummary">{{ ticket.resolutionSummary }}</blockquote></section>
  <AgentTimeline v-if="taskId" :task-id="taskId" />
  <p v-else class="empty-state large">启动 Agent 后，这里会实时展示节点、工具、引用、审批和最终结果；不会展示隐藏推理。</p>
</ConsoleLayout></template>
