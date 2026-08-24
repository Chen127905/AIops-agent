<script setup lang="ts">
import axios from 'axios'
import { computed, reactive, ref } from 'vue'
import { ingestDocument, initializeBuiltInKnowledge, publishDocumentVersion, searchKnowledge, type EvidenceChunk } from '../api/knowledge'
import ConsoleLayout from '../components/ConsoleLayout.vue'
import ModalDialog from '../components/ModalDialog.vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const canManage = computed(() => auth.user?.roles.includes('ADMIN') ?? false)
const query = ref('Redis 连接池超时如何排查')
const results = ref<EvidenceChunk[]>([])
const error = ref(''); const notice = ref(''); const searched = ref(false); const ingesting = ref(false); const initializing = ref(false)
const dialog = ref<'create' | 'version' | null>(null)
const document = reactive({ documentId: '', name: '', source: '', mediaType: 'text/markdown', content: '', purpose: 'runbook' })

function describeError(cause: unknown, action: string): string {
  if (axios.isAxiosError(cause)) { const body = cause.response?.data as { detail?: string; message?: string } | undefined; return body?.detail ?? body?.message ?? `${action}失败（HTTP ${cause.response?.status ?? '网络错误'}）` }
  return `${action}失败，请查看后端日志。`
}
function open(mode: 'create' | 'version'): void {
  dialog.value = mode; error.value = ''; notice.value = ''
  document.documentId = ''; document.name = ''; document.source = ''; document.content = ''
}
async function ingest(): Promise<void> {
  error.value = ''; notice.value = ''; ingesting.value = true
  const command = { name: document.name, source: document.source, mediaType: document.mediaType, content: document.content, metadata: { purpose: document.purpose } }
  try {
    const id = dialog.value === 'version' ? await publishDocumentVersion(document.documentId, command) : await ingestDocument(command)
    notice.value = dialog.value === 'version' ? `文档 #${id} 的新版本已向量化并发布。` : `文档 #${id} 已切分、向量化并发布，可以立即检索。`
    dialog.value = null
  } catch (cause) { error.value = describeError(cause, '知识入库') } finally { ingesting.value = false }
}
async function search(): Promise<void> {
  error.value = ''; notice.value = ''
  try { results.value = await searchKnowledge(query.value); searched.value = true } catch (cause) { error.value = describeError(cause, '知识检索') }
}
async function initialize(): Promise<void> {
  error.value = ''; notice.value = ''; initializing.value = true
  try {
    const result = await initializeBuiltInKnowledge()
    notice.value = result.published
      ? `初始化完成：新增 ${result.published} 份，已存在 ${result.skipped} 份。`
      : `内置知识已经完整初始化，共 ${result.total} 份，无需重复导入。`
    results.value = await searchKnowledge(query.value)
    searched.value = true
  } catch (cause) { error.value = describeError(cause, '初始化知识库') } finally { initializing.value = false }
}
</script>

<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">RAG 知识中心</p><h2>知识库</h2><p class="header-copy">管理已发布运维知识，并验证 Agent 实际能够召回的证据片段。</p></div><div v-if="canManage" class="header-actions"><button class="secondary-action" :disabled="initializing" @click="initialize">{{ initializing ? '正在初始化…' : '初始化内置知识' }}</button><button class="secondary-action" @click="open('version')">发布新版本</button><button class="primary-button" @click="open('create')">＋ 新增文档</button></div></header>
  <section class="knowledge-search surface-panel"><div><p class="section-label">语义检索</p><h3>验证知识召回</h3><p>输入真实的运维问题，查看向量检索结果、引用编号和相似度。</p></div><form class="search-bar" @submit.prevent="search"><input v-model="query" data-test="knowledge-query" aria-label="知识检索问题" placeholder="例如：Redis 连接池超时如何排查" required><button class="primary-button">检索知识</button></form></section>
  <p v-if="error" class="error-message">{{ error }}</p><p v-if="notice" class="success-message">{{ notice }}</p>
  <div v-if="results.length" class="result-heading"><strong>检索结果</strong><span>找到 {{ results.length }} 个相关片段</span></div>
  <p v-if="searched && !results.length" class="empty-state large">没有匹配的已发布知识。可以新增文档后重新检索。</p>
  <div class="evidence-grid"><article v-for="item in results" :key="item.citationId" class="surface-panel evidence-card"><div class="panel-heading"><span class="citation-badge">{{ item.citationId }}</span><span class="score">相关度 {{ (item.score * 100).toFixed(1) }}%</span></div><h3>{{ item.source }}</h3><p>{{ item.content }}</p><footer>文档 #{{ item.documentId }} · 版本 {{ item.documentVersion }} · 片段 {{ item.chunkIndex }}</footer></article></div>
  <ModalDialog v-if="dialog" :title="dialog === 'create' ? '新增知识文档' : '发布文档新版本'" eyebrow="知识入库" wide @close="dialog = null">
    <form class="form-stack" data-test="ingest-form" @submit.prevent="ingest">
      <label v-if="dialog === 'version'"><span>原文档 ID</span><input v-model="document.documentId" type="text" inputmode="numeric" pattern="[0-9]+" placeholder="例如 12" required><small>新版本会替换该文档当前可检索的已发布版本</small></label>
      <div class="form-columns"><label><span>文档名称</span><input v-model="document.name" data-test="document-name" maxlength="160" placeholder="例如：Redis 超时排障手册" required></label><label><span>来源标识</span><input v-model="document.source" data-test="document-source" maxlength="512" placeholder="例如：internal://runbooks/redis" required></label></div>
      <div class="form-columns"><label><span>内容格式</span><select v-model="document.mediaType"><option value="text/markdown">Markdown</option><option value="text/plain">纯文本</option></select></label><label><span>知识类型</span><select v-model="document.purpose"><option value="runbook">排障手册</option><option value="postmortem">故障复盘</option><option value="standard">运维规范</option></select></label></div>
      <label><span>文档内容</span><textarea v-model="document.content" data-test="document-content" class="editor-area" placeholder="粘贴经过核验的 Markdown 或纯文本知识内容" required /></label>
      <div class="modal-actions"><button class="text-button" type="button" @click="dialog = null">取消</button><button class="primary-button" :disabled="ingesting">{{ ingesting ? '切分与向量化中…' : '发布到知识库' }}</button></div>
    </form>
  </ModalDialog>
</ConsoleLayout></template>
