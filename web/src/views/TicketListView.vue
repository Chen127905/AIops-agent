<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createTicket, listTickets, type Ticket } from '../api/tickets'
import ConsoleLayout from '../components/ConsoleLayout.vue'

const router = useRouter()
const tickets = ref<Ticket[]>([])
const loading = ref(false)
const error = ref('')
const showCreate = ref(false)
const form = reactive({ title: '', description: '', affectedService: '', category: 'UNKNOWN', severity: 'HIGH' as const })

async function load(): Promise<void> {
  loading.value = true
  try { tickets.value = (await listTickets()).items } catch { error.value = '工单加载失败' } finally { loading.value = false }
}

async function submit(): Promise<void> {
  error.value = ''
  try { const ticket = await createTicket(form); await router.push(`/tickets/${ticket.id}`) }
  catch { error.value = '创建失败，请检查标题和描述长度' }
}
onMounted(() => void load())
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">INCIDENT INBOX</p><h2>工单与 Agent</h2></div><button class="primary-button" @click="showCreate = !showCreate">新建工单</button></header>
  <form v-if="showCreate" class="inline-form" @submit.prevent="submit">
    <input v-model="form.title" minlength="5" maxlength="120" placeholder="故障标题" required>
    <input v-model="form.affectedService" maxlength="128" placeholder="受影响服务，例如 order-service">
    <textarea v-model="form.description" minlength="10" maxlength="4000" placeholder="描述现象、影响范围与已知线索" required />
    <button class="primary-button" type="submit">保存并进入详情</button>
  </form>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div class="data-list">
    <RouterLink v-for="ticket in tickets" :key="ticket.id" :to="`/tickets/${ticket.id}`" class="data-row">
      <span class="mono-label">#{{ ticket.id }}</span><div><strong>{{ ticket.title }}</strong><small>{{ ticket.affectedService || '未指定服务' }} · {{ ticket.category || '未分类' }}</small></div>
      <span class="status-pill">{{ ticket.status }}</span><span class="severity" :data-level="ticket.severity">{{ ticket.severity }}</span>
    </RouterLink>
    <p v-if="!loading && !tickets.length" class="empty-state">当前租户还没有工单。</p>
  </div>
</ConsoleLayout></template>
