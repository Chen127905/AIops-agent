<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { approve, listPendingApprovals, reject, type Approval } from '../api/approvals'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import ModalDialog from '../components/ModalDialog.vue'
import { displayLabel, formatDate } from '../utils/labels'

const approvals = ref<Approval[]>([]); const error = ref(''); const selected = ref<Approval | null>(null); const decision = ref<'approve' | 'reject'>('approve'); const comment = ref(''); const deciding = ref(false)
const secretKeys = /password|secret|token|authorization|credential/i
function safeArguments(value: Record<string, unknown>) { return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, secretKeys.test(key) ? '[REDACTED]' : item])) }
function readableArguments(value: Record<string, unknown>): { key: string; value: string }[] { return Object.entries(safeArguments(value)).map(([key, item]) => ({ key, value: typeof item === 'object' ? JSON.stringify(item) : String(item) })) }
async function load() { try { approvals.value = await listPendingApprovals() } catch { error.value = '审批列表加载失败' } }
function open(item: Approval, action: 'approve' | 'reject') { selected.value = item; decision.value = action; comment.value = action === 'approve' ? '已核对影响范围与回滚方案，同意执行一次。' : '风险或执行条件不满足，拒绝本次操作。' }
async function decide() {
  if (!selected.value) return
  deciding.value = true; error.value = ''
  try { decision.value === 'approve' ? await approve(selected.value.id, comment.value) : await reject(selected.value.id, comment.value); selected.value = null; await load() }
  catch { error.value = '审批已被其他人处理或已经过期' } finally { deciding.value = false }
}
onMounted(() => void load())
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">人在回路</p><h2>人工审批</h2><p class="header-copy">高风险工具只能凭一次性批准执行，审批意见和参数会完整留痕。</p></div><span class="count-badge">{{ approvals.length }} 项待处理</span></header>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div class="approval-grid"><article v-for="item in approvals" :key="item.id" class="approval-card surface-panel"><div class="panel-heading"><span class="severity" :data-level="item.risk">{{ displayLabel(item.risk) }}风险</span><span class="mono-label">任务 #{{ item.taskId }}</span></div><h3>{{ item.toolName }}</h3><p class="approval-copy">Agent 请求执行以下受控操作，请核对目标与参数。</p><dl class="argument-list"><div v-for="argument in readableArguments(item.normalizedArguments)" :key="argument.key"><dt>{{ argument.key }}</dt><dd>{{ argument.value }}</dd></div></dl><div class="expiry"><span>审批将在</span><strong>{{ formatDate(item.expiresAt) }}</strong><span>过期</span></div><div class="button-row"><button class="primary-button" @click="open(item, 'approve')">批准一次</button><button class="danger-ghost" @click="open(item, 'reject')">拒绝请求</button></div></article><section v-if="!approvals.length" class="empty-illustration surface-panel"><div>✓</div><h3>没有待审批请求</h3><p>Agent 遇到高风险操作时，请求会自动出现在这里。</p></section></div>
  <ModalDialog v-if="selected" :title="decision === 'approve' ? '批准本次工具调用？' : '拒绝本次工具调用？'" eyebrow="审批决策" @close="selected = null"><div class="decision-summary"><span>{{ selected.toolName }}</span><strong>任务 #{{ selected.taskId }} · {{ displayLabel(selected.risk) }}风险</strong></div><label class="field-label"><span>审批意见</span><textarea v-model="comment" maxlength="512" rows="4" required></textarea><small>意见会写入审计记录，建议说明判断依据。</small></label><div class="modal-actions"><button class="text-button" @click="selected = null">取消</button><button :class="decision === 'approve' ? 'primary-button' : 'danger-button'" :disabled="deciding || !comment.trim()" @click="decide">{{ deciding ? '正在提交…' : (decision === 'approve' ? '确认批准一次' : '确认拒绝') }}</button></div></ModalDialog>
</ConsoleLayout></template>
