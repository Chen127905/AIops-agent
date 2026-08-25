import { flushPromises, mount } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { afterEach, describe, expect, it } from 'vitest'

import { api } from '../api/http'
import TicketConversation from './TicketConversation.vue'

describe('TicketConversation', () => {
  const mock = new MockAdapter(api)

  afterEach(() => mock.reset())

  it('loads persisted messages and sends a follow-up in the ticket', async () => {
    const conversation = {
      id: '9007199254740993', ticketId: '9007199254740992',
      summary: null, summarizedThroughMessageId: null,
      createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z',
      messages: [],
    }
    mock.onGet('/api/tickets/9007199254740992/conversation').reply(200, conversation)
    mock.onPost('/api/tickets/9007199254740992/conversation/messages').reply((request) => [200, {
      ...conversation,
      messages: [
        { id: 1, role: 'USER', status: 'SENT', content: JSON.parse(request.data).content, provider: null, modelName: null, inputTokens: 0, outputTokens: 0, latencyMs: 0, createdAt: '2026-08-25T00:00:00Z' },
        { id: 2, role: 'ASSISTANT', status: 'SENT', content: '先核对连接池指标。', provider: 'QWEN', modelName: 'qwen-plus', inputTokens: 20, outputTokens: 8, latencyMs: 120, createdAt: '2026-08-25T00:00:01Z' },
      ],
    }])

    const wrapper = mount(TicketConversation, {
      props: { ticketId: '9007199254740992' },
    })
    await flushPromises()
    await wrapper.get('textarea').setValue('为什么是连接池问题？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('为什么是连接池问题？')
    expect(wrapper.text()).toContain('先核对连接池指标。')
    expect(wrapper.text()).toContain('qwen-plus')
    expect(mock.history.post[0].url)
      .toBe('/api/tickets/9007199254740992/conversation/messages')
  })

  it('shows the persisted summary and a failed assistant message', async () => {
    mock.onGet('/api/tickets/7/conversation').reply(200, {
      id: 3, ticketId: 7, summary: '用户正在核对 Redis 超时根因。',
      summarizedThroughMessageId: 10,
      createdAt: '', updatedAt: '',
      messages: [{
        id: 11, role: 'ASSISTANT', status: 'FAILED',
        content: 'Model provider is not configured: QWEN',
        provider: 'QWEN', modelName: null, inputTokens: 0,
        outputTokens: 0, latencyMs: 0, createdAt: '',
      }],
    })

    const wrapper = mount(TicketConversation, { props: { ticketId: 7 } })
    await flushPromises()

    expect(wrapper.text()).toContain('上下文摘要已启用')
    expect(wrapper.text()).toContain('用户正在核对 Redis 超时根因。')
    expect(wrapper.text()).toContain('回复失败：Model provider is not configured: QWEN')
  })
})
