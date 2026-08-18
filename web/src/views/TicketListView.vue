<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createTicket, listScenarios, listTickets, type OpsScenario, type Ticket, type TicketStatus } from '../api/tickets'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import ModalDialog from '../components/ModalDialog.vue'
import { displayLabel, formatDate, scenarioLabel } from '../utils/labels'

const router = useRouter()
const tickets = ref<Ticket[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const status = ref<TicketStatus | ''>('')
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const showCreate = ref(false)
const scenarios = ref<OpsScenario[]>([])
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const form = reactive({ title: '', description: '', affectedService: '', category: '', scenarioKey: '', severity: 'HIGH' as Ticket['severity'] })

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try { const result = await listTickets({ status: status.value || undefined, page: page.value, size }); tickets.value = result.items; total.value = result.total }
  catch { error.value = '工单加载失败，请稍后重试' } finally { loading.value = false }
}
function applyScenario(): void {
  const scenario = scenarios.value.find((item) => item.key === form.scenarioKey)
  if (!scenario) return
  form.affectedService = scenario.service; form.category = scenario.category; form.severity = scenario.severity
}
function openCreate(): void {
  if (!form.scenarioKey && scenarios.value[0]) form.scenarioKey = scenarios.value[0].key
  applyScenario(); showCreate.value = true
}
async function filter(): Promise<void> { page.value = 1; await load() }
async function move(next: number): Promise<void> { page.value = next; await load() }
async function submit(): Promise<void> {
  error.value = ''; submitting.value = true
  try { const ticket = await createTicket(form); showCreate.value = false; await router.push(`/tickets/${ticket.id}`) }
  catch { error.value = '创建失败，请检查标题和描述长度' } finally { submitting.value = false }
}
onMounted(async () => { await Promise.all([load(), listScenarios().then((items) => { scenarios.value = items })]) })
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">事件工作台</p><h2>工单与 Agent</h2><p class="header-copy">从故障受理到智能诊断、审批和恢复验证的统一入口。</p></div><button class="primary-button" @click="openCreate">＋ 新建工单</button></header>
  <div class="toolbar"><div class="filter-tabs"><button :class="{ active: status === '' }" @click="status = ''; filter()">全部</button><button v-for="item in (['OPEN','IN_PROGRESS','RESOLVED','CANCELLED'] as TicketStatus[])" :key="item" :class="{ active: status === item }" @click="status = item; filter()">{{ displayLabel(item) }}</button></div><span>共 {{ total }} 条</span></div>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div class="data-list surface-panel">
    <RouterLink v-for="ticket in tickets" :key="ticket.id" :to="`/tickets/${ticket.id}`" class="data-row">
      <span class="ticket-id">#{{ ticket.id }}</span><div class="row-main"><strong>{{ ticket.title }}</strong><small>{{ ticket.affectedService || '未指定服务' }} · {{ displayLabel(ticket.category) }} · {{ formatDate(ticket.createdAt) }}</small></div>
      <span class="status-pill" :data-status="ticket.status">{{ displayLabel(ticket.status) }}</span><span class="severity" :data-level="ticket.severity">{{ displayLabel(ticket.severity) }}</span><span class="row-arrow">→</span>
    </RouterLink>
    <p v-if="loading" class="empty-state">正在加载工单…</p><p v-else-if="!tickets.length" class="empty-state large">当前筛选条件下没有工单。</p>
  </div>
  <div v-if="totalPages > 1" class="pagination"><button :disabled="page <= 1" @click="move(page - 1)">上一页</button><span>第 {{ page }} / {{ totalPages }} 页</span><button :disabled="page >= totalPages" @click="move(page + 1)">下一页</button></div>
  <ModalDialog v-if="showCreate" title="创建故障工单" eyebrow="事件受理" @close="showCreate = false">
    <form class="form-stack" @submit.prevent="submit">
      <label><span>故障场景</span><select v-model="form.scenarioKey" required @change="applyScenario"><option v-for="scenario in scenarios" :key="scenario.key" :value="scenario.key">{{ scenarioLabel(scenario.key) }} · {{ scenario.service }}</option></select><small>选择场景后自动带出服务、分类与故障等级</small></label>
      <div class="form-summary"><div><span>目标服务</span><strong>{{ form.affectedService || '—' }}</strong></div><div><span>故障分类</span><strong>{{ displayLabel(form.category) }}</strong></div><div><span>严重程度</span><strong>{{ displayLabel(form.severity) }}</strong></div></div>
      <label><span>工单标题</span><input v-model="form.title" minlength="5" maxlength="120" placeholder="简要说明故障现象" required></label>
      <label><span>现象与影响</span><textarea v-model="form.description" minlength="10" maxlength="4000" placeholder="说明发现时间、影响范围、关键错误和已尝试操作" required /></label>
      <div class="modal-actions"><button class="text-button" type="button" @click="showCreate = false">取消</button><button class="primary-button" :disabled="submitting">{{ submitting ? '正在创建…' : '创建并进入详情' }}</button></div>
    </form>
  </ModalDialog>
</ConsoleLayout></template>
