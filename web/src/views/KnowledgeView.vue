<script setup lang="ts">
import { ref } from 'vue'
import { searchKnowledge, type EvidenceChunk } from '../api/knowledge'
import ConsoleLayout from '../components/ConsoleLayout.vue'
const query = ref('Redis 连接池超时如何排查')
const results = ref<EvidenceChunk[]>([])
const error = ref('')
async function search() { try { results.value = await searchKnowledge(query.value) } catch { error.value = '知识检索不可用，请确认 pgvector 已启用' } }
</script>
<template><ConsoleLayout>
  <header class="console-header"><div><p class="section-label">RAG EVIDENCE</p><h2>知识检索与引用</h2></div></header>
  <form class="search-bar" @submit.prevent="search"><input v-model="query" required><button class="primary-button">检索</button></form>
  <p v-if="error" class="error-message">{{ error }}</p>
  <div class="evidence-grid"><article v-for="item in results" :key="item.citationId"><div class="panel-heading"><span class="mono-label">{{ item.citationId }}</span><strong>{{ item.score.toFixed(3) }}</strong></div><h3>{{ item.source }}</h3><p>{{ item.content }}</p></article></div>
</ConsoleLayout></template>
