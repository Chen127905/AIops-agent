<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listEvaluationCases, runMockEvaluation, type EvaluationCase, type EvaluationRun } from '../api/evaluation'
import ConsoleLayout from '../components/ConsoleLayout.vue'
const cases = ref<EvaluationCase[]>([])
const run = ref<EvaluationRun | null>(null)
const running = ref(false)
const error = ref('')
async function execute() { running.value = true; try { run.value = await runMockEvaluation() } catch { error.value = '仅管理员可以运行评测' } finally { running.value = false } }
onMounted(async () => { try { cases.value = await listEvaluationCases() } catch { error.value = '仅管理员可以查看评测基线' } })
</script>
<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">REGRESSION GATE</p><h2>Agent 评测中心</h2></div><button class="primary-button" :disabled="running" @click="execute">{{ running ? '运行中…' : '运行 30 条 MOCK 基线' }}</button></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div v-if="run" class="metric-grid"><article><span>通过率</span><strong>{{ (run.metrics.passRate * 100).toFixed(1) }}%</strong></article><article><span>工具 Precision / Recall</span><strong>{{ run.metrics.toolPrecision }} / {{ run.metrics.toolRecall }}</strong></article><article><span>引用准确率</span><strong>{{ run.metrics.citationAccuracy }}</strong></article><article><span>泄露数</span><strong>{{ run.metrics.leakageCount }}</strong></article></div>
  <div class="case-groups"><span v-for="item in cases" :key="item.id" class="status-pill">{{ item.group }} · {{ item.id }}</span></div>
</ConsoleLayout></template>
