<script setup lang="ts">
import axios from 'axios'
import { nextTick, onMounted, ref } from 'vue'
import {
  getTicketConversation,
  sendTicketConversationMessage,
  type TicketConversation,
} from '../api/tickets'
import type { EntityId } from '../api/types'
import { formatDate } from '../utils/labels'

const props = defineProps<{ ticketId: EntityId }>()
const conversation = ref<TicketConversation | null>(null)
const question = ref('')
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const messageList = ref<HTMLElement | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    conversation.value = await getTicketConversation(props.ticketId)
  } catch {
    error.value = '会话记录加载失败，请稍后刷新'
  } finally {
    loading.value = false
    await scrollToLatest()
  }
}

async function send(): Promise<void> {
  const content = question.value.trim()
  if (!content || busy.value) return
  busy.value = true
  error.value = ''
  question.value = ''
  try {
    conversation.value = await sendTicketConversationMessage(
      props.ticketId, content,
    )
  } catch (requestError) {
    question.value = content
    if (axios.isAxiosError(requestError) && requestError.response?.status === 409) {
      error.value = '该工单正在生成上一条回复，请稍后再发送'
    } else if (axios.isAxiosError(requestError) && requestError.response?.status === 503) {
      error.value = '模型暂时不可用。本次提问已保存，恢复后可以继续追问'
      await load()
    } else {
      error.value = '消息发送失败，请检查模型配置或网络连接'
    }
  } finally {
    busy.value = false
    await scrollToLatest()
  }
}

async function scrollToLatest(): Promise<void> {
  await nextTick()
  if (messageList.value) {
    messageList.value.scrollTop = messageList.value.scrollHeight
  }
}

onMounted(load)
</script>

<template>
  <section class="surface-panel conversation-panel">
    <header class="conversation-heading">
      <div>
        <p class="section-label">持续协作</p>
        <h3>工单内追问</h3>
        <p>Agent 会结合工单、最新诊断结论和历史会话继续回答，不会重复执行处置动作。</p>
      </div>
      <span class="memory-badge" :class="{ active: conversation?.summary }">
        {{ conversation?.summary ? '上下文摘要已启用' : '持久化会话' }}
      </span>
    </header>

    <details v-if="conversation?.summary" class="conversation-summary">
      <summary>查看自动生成的历史摘要</summary>
      <p>{{ conversation.summary }}</p>
    </details>

    <div ref="messageList" class="conversation-messages" aria-live="polite">
      <div v-if="loading" class="conversation-placeholder">正在加载会话记录…</div>
      <div v-else-if="!conversation?.messages.length" class="conversation-placeholder">
        <strong>可以继续问 Agent</strong>
        <span>例如：“为什么判断是这个根因？”、“这个操作有什么风险？”或“如果恢复失败下一步怎么做？”</span>
      </div>
      <article
        v-for="message in conversation?.messages ?? []"
        :key="String(message.id)"
        class="conversation-message"
        :data-role="message.role"
        :data-status="message.status"
      >
        <div class="message-meta">
          <strong>{{ message.role === 'USER' ? '值班人员' : 'Ops Agent' }}</strong>
          <span v-if="message.modelName">{{ message.modelName }}</span>
          <time>{{ formatDate(message.createdAt) }}</time>
        </div>
        <p>{{ message.status === 'FAILED' ? `回复失败：${message.content}` : message.content }}</p>
      </article>
      <article v-if="busy" class="conversation-message" data-role="ASSISTANT">
        <div class="message-meta"><strong>Ops Agent</strong></div>
        <p class="thinking-copy"><i />正在结合工单上下文生成回复…</p>
      </article>
    </div>

    <form class="conversation-composer" @submit.prevent="send">
      <label for="ticket-follow-up">继续追问</label>
      <div>
        <textarea
          id="ticket-follow-up"
          v-model="question"
          maxlength="4000"
          rows="3"
          :disabled="busy"
          placeholder="围绕当前工单询问根因、证据、风险或下一步操作…"
          @keydown.ctrl.enter.prevent="send"
        />
        <button class="primary-button" type="submit" :disabled="busy || !question.trim()">
          {{ busy ? '生成中…' : '发送追问' }}
        </button>
      </div>
      <small>Ctrl + Enter 发送。涉及真实变更时，Agent 仍会要求审批并说明回滚方案。</small>
    </form>
    <p v-if="error" class="error-message conversation-error">{{ error }}</p>
  </section>
</template>
