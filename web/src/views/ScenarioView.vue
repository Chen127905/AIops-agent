<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listScenarios, type OpsScenario } from '../api/tickets'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import { displayLabel, scenarioLabel } from '../utils/labels'

const scenarios = ref<OpsScenario[]>([])
const error = ref('')
onMounted(async () => {
  try { scenarios.value = await listScenarios() } catch { error.value = '场景目录加载失败' }
})
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">场景目录</p><h2>运维故障场景</h2><p class="header-copy">场景定义故障数据、诊断工具和需要审批的恢复动作。</p></div><RouterLink class="primary-button" to="/tickets">基于场景创建工单</RouterLink></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div class="scenario-grid">
    <article v-for="scenario in scenarios" :key="scenario.key" class="surface-panel scenario-card">
      <div class="panel-heading"><span class="status-pill">{{ displayLabel(scenario.category) }}</span><span class="severity" :data-level="scenario.severity">{{ displayLabel(scenario.severity) }}</span></div>
      <h3>{{ scenarioLabel(scenario.key) }}</h3><p class="mono-label">{{ scenario.key }}</p>
      <dl><div><dt>目标服务</dt><dd>{{ scenario.service }}</dd></div><div><dt>恢复审批</dt><dd>{{ scenario.requiresApproval ? '需要人工审批' : '无需审批' }}</dd></div></dl>
    </article>
  </div>
</ConsoleLayout></template>
