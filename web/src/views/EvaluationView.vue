<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getEvaluationRun, listEvaluationCases, runEvaluation, type EvaluationCase, type EvaluationRun } from '../api/evaluation'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import ModalDialog from '../components/ModalDialog.vue'
import { displayLabel, formatDate, scenarioLabel } from '../utils/labels'

const cases = ref<EvaluationCase[]>([]); const run = ref<EvaluationRun | null>(null); const running = ref(false); const error = ref(''); const showRun = ref(false); const confirmingLive = ref(false); const lookupId = ref('')
const form = reactive({ mode: 'MOCK' as 'MOCK' | 'LIVE', provider: 'QWEN' as 'QWEN' | 'DEEPSEEK', model: '', promptVersion: 'agent-prompt-v1', knowledgeVersion: 'baseline-v1', caseIds: [] as string[] })
const groups = computed(() => Object.entries(Object.groupBy(cases.value, (item) => item.group)))
function caseTitle(item: EvaluationCase): string {
  const suffix: Record<string, string> = {
    CLASSIFICATION: '故障意图分类', RETRIEVAL: '诊断证据检索', TOOL_USE: '诊断工具选择',
    END_TO_END: '端到端诊断', APPROVAL: '高风险动作审批', ATTACK: '提示注入防护',
  }
  return `${scenarioLabel(item.scenarioKey)} · ${suffix[item.group] ?? item.title}`
}
function selectAll(): void { form.caseIds = form.caseIds.length === cases.value.length ? [] : cases.value.map((item) => item.id) }
function selectSmokeCases(): void {
  const seen = new Set<string>()
  form.caseIds = cases.value.filter((item) => !seen.has(item.group) && Boolean(seen.add(item.group))).map((item) => item.id)
  confirmingLive.value = false
}
async function execute() {
  running.value = true; error.value = ''
  try { run.value = await runEvaluation({ ...form, caseIds: form.caseIds }); showRun.value = false; confirmingLive.value = false; lookupId.value = run.value.runId }
  catch { error.value = '评测运行失败，请确认管理员权限、模型配置和用例选择' } finally { running.value = false }
}
function requestExecution(): void {
  if (form.mode === 'LIVE' && !confirmingLive.value) { confirmingLive.value = true; return }
  void execute()
}
function openRun(): void { confirmingLive.value = false; showRun.value = true }
async function lookup() { if (!lookupId.value.trim()) return; error.value = ''; try { run.value = await getEvaluationRun(lookupId.value.trim()) } catch { error.value = '未找到该评测运行，或它不属于当前租户' } }
onMounted(async () => { try { cases.value = await listEvaluationCases() } catch { error.value = '仅管理员可以查看评测基线' } })
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">回归质量门禁</p><h2>Agent 评测中心</h2><p class="header-copy">用确定性基线验证流程，用真实模型评测验证效果、成本与安全边界。</p></div><button class="primary-button" @click="openRun">＋ 创建评测运行</button></header>
  <div class="evaluation-toolbar surface-panel"><div><strong>查询评测结果</strong><span>通过 Run ID 重新加载已持久化的运行结果</span></div><form @submit.prevent="lookup"><input v-model="lookupId" aria-label="评测运行 ID" placeholder="输入 Run ID"><button class="secondary-action">查询</button></form></div>
  <p v-if="error" class="error-message">{{ error }}</p>
  <section v-if="run" class="run-summary"><div class="section-heading"><div><p class="section-label">评测结果</p><h3>{{ displayLabel(run.mode) }} · {{ run.model }}</h3></div><div class="run-meta"><span class="status-pill" :data-status="run.status">{{ displayLabel(run.status) }}</span><span>{{ formatDate(run.finishedAt ?? run.startedAt) }}</span></div></div>
    <div class="metric-grid"><article><span>用例通过率</span><strong>{{ (run.metrics.passRate * 100).toFixed(1) }}%</strong><small>{{ run.metrics.passedCases }} / {{ run.metrics.totalCases }} 通过</small></article><article><span>分类 / 根因</span><strong>{{ (run.metrics.classificationAccuracy * 100).toFixed(0) }}% / {{ (run.metrics.rootCauseAccuracy * 100).toFixed(0) }}%</strong><small>分类准确率 / 严格根因命中率</small></article><article><span>工具 Precision / Recall</span><strong>{{ run.metrics.toolPrecision.toFixed(2) }} / {{ run.metrics.toolRecall.toFixed(2) }}</strong><small>工具选择质量</small></article><article><span>参数 / 处置状态</span><strong>{{ (run.metrics.parameterAccuracy * 100).toFixed(0) }}% / {{ (run.metrics.resolutionRate * 100).toFixed(0) }}%</strong><small>动作参数与状态机</small></article><article><span>引用 / 审批拦截</span><strong>{{ (run.metrics.citationAccuracy * 100).toFixed(0) }}% / {{ (run.metrics.approvalInterceptionRate * 100).toFixed(0) }}%</strong><small>证据与高风险动作控制</small></article><article><span>成本与延迟</span><strong>{{ Math.round(run.metrics.averageTokens) }} tokens</strong><small>平均 {{ run.metrics.averageSteps.toFixed(1) }} 步 · P95 {{ run.metrics.p95LatencyMs }} ms</small></article><article><span>敏感信息泄露</span><strong>{{ run.metrics.leakageCount }}</strong><small>目标值为 0</small></article></div>
    <div class="version-strip"><span>Provider <strong>{{ run.provider }}</strong></span><span>Prompt <strong>{{ run.promptVersion }}</strong></span><span>Knowledge <strong>{{ run.knowledgeVersion }}</strong></span><span class="mono-label">{{ run.runId }}</span></div>
  </section>
  <section class="surface-panel case-catalog"><div class="section-heading"><div><p class="section-label">用例基线</p><h3>{{ cases.length }} 条质量与安全用例</h3></div></div><div class="case-group" v-for="[group, items] in groups" :key="group"><div><strong>{{ displayLabel(group) }}</strong><span>{{ items?.length ?? 0 }} 条</span></div><div class="case-list"><article v-for="item in items" :key="item.id"><span class="mono-label">{{ item.id }}</span><strong>{{ caseTitle(item) }}</strong><small>{{ scenarioLabel(item.scenarioKey) }}</small></article></div></div></section>
  <ModalDialog v-if="showRun" title="创建评测运行" eyebrow="评测配置" wide @close="showRun = false"><form class="form-stack" @submit.prevent="requestExecution"><div class="mode-selector"><label :class="{ selected: form.mode === 'MOCK' }"><input v-model="form.mode" type="radio" value="MOCK" @change="confirmingLive = false"><span><strong>基线评测</strong><small>确定性执行，不调用模型 API，适合快速回归</small></span></label><label :class="{ selected: form.mode === 'LIVE' }"><input v-model="form.mode" type="radio" value="LIVE" @change="selectSmokeCases"><span><strong>真实模型评测</strong><small>调用模型 API，默认选择六类各一条冒烟用例</small></span></label></div><div v-if="form.mode === 'LIVE'" class="form-columns"><label><span>模型提供商</span><select v-model="form.provider"><option value="QWEN">阿里云百炼 · Qwen</option><option value="DEEPSEEK">DeepSeek</option></select></label><label><span>模型名称（可选）</span><input v-model="form.model" maxlength="128" placeholder="留空使用该 Provider 的已配置模型"></label></div><div class="form-columns"><label><span>Prompt 版本</span><input v-model="form.promptVersion" maxlength="64" required></label><label><span>知识版本</span><input v-model="form.knowledgeVersion" maxlength="64" required></label></div><div class="case-selector-heading"><div><strong>选择用例</strong><small>{{ form.mode === 'LIVE' ? '建议先运行六条冒烟用例；全量运行会产生较多费用' : '不选择时由后端运行全部用例' }}</small></div><button class="text-button" type="button" @click="selectAll">{{ form.caseIds.length === cases.length ? '取消全选' : '全选' }}</button></div><div class="checkbox-grid"><label v-for="item in cases" :key="item.id"><input v-model="form.caseIds" type="checkbox" :value="item.id"><span><strong>{{ caseTitle(item) }}</strong><small>{{ displayLabel(item.group) }} · {{ item.id }}</small></span></label></div><div v-if="confirmingLive" class="cost-warning"><strong>确认调用真实模型 API</strong><p>本次运行会调用 {{ form.provider }} 的已配置模型，共 {{ form.caseIds.length || cases.length }} 条用例，并产生 Token 费用。</p></div><div class="modal-actions"><button class="text-button" type="button" @click="showRun = false">取消</button><button class="primary-button" :disabled="running">{{ running ? '评测运行中…' : (confirmingLive ? '确认并开始真实评测' : '开始评测') }}</button></div></form></ModalDialog>
</ConsoleLayout></template>
