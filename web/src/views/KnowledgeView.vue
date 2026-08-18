<script setup lang="ts">
import axios from 'axios'
import { ref } from 'vue'

import { ingestDocument, searchKnowledge, type EvidenceChunk } from '../api/knowledge'
import ConsoleLayout from '../components/ConsoleLayout.vue'

const query = ref('Redis 连接池超时如何排查')
const results = ref<EvidenceChunk[]>([])
const error = ref('')
const notice = ref('')
const searched = ref(false)
const ingesting = ref(false)
const document = ref({
  name: 'Redis 超时排障手册',
  source: 'demo://redis-timeout-runbook',
  content: '# Redis 连接池超时\n检查连接池占用、命令延迟和下游依赖。确认影响范围后，可通过审批重启异常服务。',
})

function describeError(cause: unknown, action: string): string {
  if (axios.isAxiosError(cause)) {
    const body = cause.response?.data as { detail?: string; message?: string } | undefined
    return body?.detail ?? body?.message
      ?? `${action}失败（HTTP ${cause.response?.status ?? '网络错误'}）`
  }
  return `${action}失败，请查看后端日志。`
}

async function ingest(): Promise<void> {
  error.value = ''
  notice.value = ''
  ingesting.value = true
  try {
    const documentId = await ingestDocument({
      ...document.value,
      mediaType: 'text/markdown',
      metadata: { purpose: 'runbook' },
    })
    notice.value = `文档 #${documentId} 已切分、向量化并发布，可以立即检索。`
  } catch (cause) {
    error.value = describeError(cause, '知识入库')
  } finally {
    ingesting.value = false
  }
}

async function search(): Promise<void> {
  error.value = ''
  notice.value = ''
  try {
    results.value = await searchKnowledge(query.value)
    searched.value = true
  } catch (cause) {
    error.value = describeError(cause, '知识检索')
  }
}
</script>

<template>
  <ConsoleLayout>
  <header class="console-header">
    <div><p class="section-label">RAG EVIDENCE</p><h2>知识入库、检索与引用</h2></div>
  </header>
  <form class="inline-form" data-test="ingest-form" @submit.prevent="ingest">
    <input v-model="document.name" data-test="document-name" maxlength="160" placeholder="文档名称" required>
    <input v-model="document.source" data-test="document-source" maxlength="512" placeholder="来源标识" required>
    <textarea v-model="document.content" data-test="document-content" placeholder="Markdown 或纯文本内容" required />
    <button class="primary-button" :disabled="ingesting">{{ ingesting ? '向量化中…' : '入库并发布' }}</button>
  </form>
  <form class="search-bar" @submit.prevent="search">
    <input v-model="query" data-test="knowledge-query" required>
    <button class="primary-button">检索</button>
  </form>
  <p v-if="error" class="error-message">{{ error }}</p>
  <p v-if="notice" class="success-message">{{ notice }}</p>
  <p v-if="searched && !results.length" class="empty-state large">当前租户没有可匹配的已发布知识，请先在上方入库文档。</p>
  <div class="evidence-grid">
    <article v-for="item in results" :key="item.citationId">
      <div class="panel-heading"><span class="mono-label">{{ item.citationId }}</span><strong>{{ item.score.toFixed(3) }}</strong></div>
      <h3>{{ item.source }}</h3><p>{{ item.content }}</p>
    </article>
  </div>
  </ConsoleLayout>
</template>
