<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getPrometheusMetrics, getSystemHealth, type HealthResponse } from '../api/system'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import { displayLabel } from '../utils/labels'

const health = ref<HealthResponse | null>(null)
const metrics = ref('')
const error = ref('')
const loading = ref(false)
const metricSummary = computed(() => {
  const value = metrics.value
  const values = (name: string) => [...value.matchAll(new RegExp(`^${name}(?:\\{[^}]*\\})?\\s+([0-9.Ee+-]+)$`, 'gm'))]
    .map((match) => Number(match[1])).filter(Number.isFinite)
  const first = (name: string) => values(name)[0]
  const memory = values('jvm_memory_used_bytes').reduce((sum, item) => sum + item, 0)
  const threads = first('jvm_threads_live_threads')
  const cpu = first('process_cpu_usage')
  return [
    { label: 'JVM 已用内存', value: memory ? (memory / 1024 / 1024).toFixed(1) : '—', unit: 'MB' },
    { label: '活动线程', value: threads === undefined ? '—' : Math.round(threads).toString(), unit: '个线程' },
    { label: '进程 CPU', value: cpu === undefined ? '—' : `${(cpu * 100).toFixed(1)}%`, unit: '当前使用率' },
  ]
})

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  const [healthResult, metricsResult] = await Promise.allSettled([getSystemHealth(), getPrometheusMetrics()])
  if (healthResult.status === 'fulfilled') health.value = healthResult.value
  else error.value = '无法获取健康状态'
  if (metricsResult.status === 'fulfilled') metrics.value = metricsResult.value
  loading.value = false
}
onMounted(() => void load())
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">平台自身可观测性</p><h2>平台运行状态</h2><p class="header-copy">这里只展示智能运维平台自身及其 MySQL、PostgreSQL/pgvector 等实际依赖，不代表用户业务系统。</p></div><button class="secondary-action" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新状态' }}</button></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <section class="health-hero surface-panel"><div><p class="section-label">应用健康</p><h3>{{ displayLabel(health?.status) }}</h3><p>Spring Boot Actuator 实时检查结果</p></div><span class="health-orb" :data-status="health?.status" /></section>
  <div class="metric-grid">
    <article v-for="item in metricSummary" :key="item.label"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.unit }}</small></article>
  </div>
  <section v-if="health?.components" class="surface-panel content-section"><div class="section-heading"><div><p class="section-label">依赖组件</p><h3>健康检查明细</h3></div></div>
    <div class="component-list"><div v-for="(component, name) in health.components" :key="name"><strong>{{ name }}</strong><span class="status-pill" :data-status="component.status">{{ displayLabel(component.status) }}</span></div></div>
  </section>
</ConsoleLayout></template>
