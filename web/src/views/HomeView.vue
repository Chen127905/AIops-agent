<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listPendingApprovals } from '../api/approvals'
import { listEvaluationCases } from '../api/evaluation'
import { getSystemHealth } from '../api/system'
import { listScenarios, listTickets, type Ticket } from '../api/tickets'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import { displayLabel } from '../utils/labels'

const tickets = ref<Ticket[]>([])
const totalTickets = ref(0)
const approvalCount = ref(0)
const scenarioCount = ref(0)
const caseCount = ref(0)
const health = ref('UNKNOWN')
const openTickets = computed(() => tickets.value.filter((item) => !['RESOLVED', 'CANCELLED'].includes(item.status)).length)

onMounted(async () => {
  const [ticketResult, approvals, scenarios, cases, healthResult] = await Promise.allSettled([
    listTickets({ size: 8 }), listPendingApprovals(), listScenarios(), listEvaluationCases(), getSystemHealth(),
  ])
  if (ticketResult.status === 'fulfilled') { tickets.value = ticketResult.value.items; totalTickets.value = ticketResult.value.total }
  if (approvals.status === 'fulfilled') approvalCount.value = approvals.value.length
  if (scenarios.status === 'fulfilled') scenarioCount.value = scenarios.value.length
  if (cases.status === 'fulfilled') caseCount.value = cases.value.length
  if (healthResult.status === 'fulfilled') health.value = healthResult.value.status
})
</script>

<template>
  <ConsoleLayout>
    <header class="console-header">
      <div><p class="section-label">运行总览</p><h2>智能运维控制台</h2><p class="header-copy">统一查看故障工单、Agent 执行、风险审批与平台能力。</p></div>
      <span class="health-indicator" :data-status="health"><i />{{ health === 'UP' ? '平台运行正常' : '平台状态待确认' }}</span>
    </header>
    <div class="metric-grid dashboard-metrics">
      <article><span>工单总数</span><strong>{{ totalTickets }}</strong><small>{{ openTickets }} 条近期工单待闭环</small></article>
      <article><span>待审批请求</span><strong>{{ approvalCount }}</strong><small>高风险动作必须人工确认</small></article>
      <article><span>运维场景</span><strong>{{ scenarioCount }}</strong><small>可复现故障与处置流程</small></article>
      <article><span>评测用例</span><strong>{{ caseCount }}</strong><small>覆盖质量、安全与工具边界</small></article>
    </div>
    <section class="content-section two-column">
      <article class="surface-panel">
        <div class="section-heading"><div><p class="section-label">最近工单</p><h3>处置动态</h3></div><RouterLink to="/tickets" class="text-link">查看全部 →</RouterLink></div>
        <RouterLink v-for="ticket in tickets.slice(0, 5)" :key="ticket.id" :to="`/tickets/${ticket.id}`" class="compact-row">
          <span class="mono-label">#{{ ticket.id }}</span><div><strong>{{ ticket.title }}</strong><small>{{ ticket.affectedService || '未指定服务' }}</small></div><span class="status-pill" :data-status="ticket.status">{{ displayLabel(ticket.status) }}</span>
        </RouterLink>
        <p v-if="!tickets.length" class="empty-state">暂无工单数据</p>
      </article>
      <article class="surface-panel capability-panel">
        <div class="section-heading"><div><p class="section-label">核心链路</p><h3>可审计 Agent</h3></div></div>
        <ol class="capability-list">
          <li><span>01</span><div><strong>证据驱动诊断</strong><p>知识检索、引用标识与诊断事件全程留痕。</p></div></li>
          <li><span>02</span><div><strong>策略约束执行</strong><p>工具白名单、参数校验、风险分级由 Java 策略层控制。</p></div></li>
          <li><span>03</span><div><strong>评测持续回归</strong><p>基线与真实模型评测共同守住质量和安全边界。</p></div></li>
        </ol>
      </article>
    </section>
  </ConsoleLayout>
</template>
