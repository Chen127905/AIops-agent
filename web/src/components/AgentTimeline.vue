<script setup lang="ts">
import { computed, toRef } from 'vue'

import { useAgentEvents } from '../composables/useAgentEvents'

const props = defineProps<{ taskId: number }>()
const { events, task, connected, reconnecting, lastSequence } = useAgentEvents(toRef(props, 'taskId'))

const hiddenKeys = /reasoning|thought|prompt|chain.?of.?thought/i
const secretKeys = /password|secret|token|authorization|credential/i
function sanitize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitize)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).flatMap(([key, nested]) => {
      if (hiddenKeys.test(key)) return []
      if (secretKeys.test(key)) return [[key, '[REDACTED]']]
      return [[key, sanitize(nested)]]
    }))
  }
  if (typeof value === 'string' && /bearer\s+[a-z0-9._-]+/i.test(value)) {
    return '[REDACTED]'
  }
  return value
}

function visiblePayload(payload: Record<string, unknown>): Record<string, unknown> {
  return sanitize(payload) as Record<string, unknown>
}

const stateLabel = computed(() => reconnecting.value
  ? `重连中 · after=${lastSequence.value}`
  : connected.value ? '实时连接' : '流已结束')
</script>

<template>
  <section class="timeline-panel">
    <header class="panel-heading">
      <div><span class="mono-label">TASK #{{ taskId }}</span><h3>Agent 执行时间线</h3></div>
      <span class="connection-state" :class="{ live: connected }">{{ stateLabel }}</span>
    </header>
    <div v-if="task" class="task-meter">
      <strong>{{ task.status }}</strong>
      <span>Steps {{ task.stepsUsed }}/{{ task.maxSteps }}</span>
      <span>Tokens {{ task.tokensUsed }}/{{ task.maxTokens }}</span>
    </div>
    <ol v-if="events.length" class="timeline-list">
      <li v-for="event in events" :key="event.sequence">
        <span class="sequence">{{ String(event.sequence).padStart(2, '0') }}</span>
        <div><strong>{{ event.eventType }}</strong><small>{{ new Date(event.createdAt).toLocaleTimeString() }}</small>
          <pre v-if="Object.keys(visiblePayload(event.payload)).length">{{ JSON.stringify(visiblePayload(event.payload), null, 2) }}</pre>
        </div>
      </li>
    </ol>
    <p v-else class="empty-state">等待第一条持久化事件…</p>
    <p v-if="task?.errorSummary" class="error-message">{{ task.errorSummary }}</p>
  </section>
</template>
