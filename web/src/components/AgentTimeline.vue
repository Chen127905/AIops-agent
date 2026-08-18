<script setup lang="ts">
import { computed, toRef, watch } from 'vue'
import type { AgentTask } from '../api/tickets'

import { useAgentEvents } from '../composables/useAgentEvents'
import { displayLabel, formatDate } from '../utils/labels'

const props = defineProps<{ taskId: number }>()
const emit = defineEmits<{ taskUpdated: [task: AgentTask] }>()
const { events, task, connected, reconnecting, lastSequence } = useAgentEvents(toRef(props, 'taskId'))
watch(task, (value) => { if (value) emit('taskUpdated', value) })

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
      <span class="connection-state" :class="{ live: connected }"><i />{{ stateLabel }}</span>
    </header>
    <div v-if="task" class="task-meter">
      <strong>{{ displayLabel(task.status) }}</strong>
      <span>执行步骤 {{ task.stepsUsed }}/{{ task.maxSteps }}</span>
      <span>Token {{ task.tokensUsed }}/{{ task.maxTokens }}</span>
    </div>
    <ol v-if="events.length" class="timeline-list">
      <li v-for="event in events" :key="event.sequence">
        <span class="sequence">{{ String(event.sequence).padStart(2, '0') }}</span>
        <div><div class="event-title"><strong>{{ displayLabel(event.eventType) }}</strong><span class="event-code">{{ event.eventType }}</span><small>{{ formatDate(event.createdAt) }}</small></div>
          <pre v-if="Object.keys(visiblePayload(event.payload)).length">{{ JSON.stringify(visiblePayload(event.payload), null, 2) }}</pre>
        </div>
      </li>
    </ol>
    <p v-else class="empty-state">等待第一条持久化事件…</p>
    <p v-if="task?.errorSummary" class="error-message">{{ task.errorSummary }}</p>
  </section>
</template>
