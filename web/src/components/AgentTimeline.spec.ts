import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AgentTimeline from './AgentTimeline.vue'
import { getAgentTask, streamAgentEvents } from '../api/tickets'

vi.mock('../api/tickets', () => ({
  getAgentTask: vi.fn(),
  streamAgentEvents: vi.fn(),
}))

describe('AgentTimeline', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(getAgentTask).mockResolvedValue({
      id: 9, ticketId: 3, status: 'RUNNING', maxSteps: 12,
      timeoutSeconds: 180, maxTokens: 20000, stepsUsed: 1,
      tokensUsed: 20, errorSummary: null, createdAt: '', startedAt: '', finishedAt: null,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('reconnects after the last persisted sequence', async () => {
    vi.mocked(streamAgentEvents)
      .mockImplementationOnce(async (_taskId, _after, onEvent) => {
        onEvent({ id: 1, tenantId: 1, taskId: 9, sequence: 4,
          eventType: 'TOOL_SUCCEEDED', payload: { tool: 'queryLogs' }, createdAt: '' })
        throw new Error('connection lost')
      })
      .mockImplementation(() => new Promise(() => {}))

    const wrapper = mount(AgentTimeline, { props: { taskId: 9 } })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(750)

    expect(streamAgentEvents).toHaveBeenNthCalledWith(
      2, 9, 4, expect.any(Function), expect.any(AbortSignal))
    expect(wrapper.text()).toContain('TOOL_SUCCEEDED')
    wrapper.unmount()
  })

  it('does not render hidden reasoning fields', async () => {
    vi.mocked(streamAgentEvents).mockImplementation((_taskId, _after, onEvent) => {
      onEvent({ id: 1, tenantId: 1, taskId: 9, sequence: 1,
        eventType: 'NODE_COMPLETED', payload: {
          result: 'safe', nested: { reasoning: 'hidden-secret', apiToken: 'token-value' },
        }, createdAt: '' })
      return new Promise(() => {})
    })
    const wrapper = mount(AgentTimeline, { props: { taskId: 9 } })
    await flushPromises()
    expect(wrapper.text()).toContain('safe')
    expect(wrapper.text()).not.toContain('hidden-secret')
    expect(wrapper.text()).not.toContain('token-value')
    expect(wrapper.text()).toContain('[REDACTED]')
    wrapper.unmount()
  })

  it('keeps reconnecting while a task waits for approval', async () => {
    vi.mocked(getAgentTask).mockResolvedValue({
      id: 9, ticketId: 3, status: 'WAITING_APPROVAL', maxSteps: 12,
      timeoutSeconds: 180, maxTokens: 20000, stepsUsed: 5,
      tokensUsed: 200, errorSummary: null, createdAt: '', startedAt: '', finishedAt: null,
    })
    vi.mocked(streamAgentEvents)
      .mockImplementationOnce(async (_taskId, _after, onEvent) => {
        onEvent({ id: 2, tenantId: 1, taskId: 9, sequence: 7,
          eventType: 'TASK_COMPLETED', payload: { status: 'WAITING_APPROVAL' }, createdAt: '' })
      })
      .mockImplementation(() => new Promise(() => {}))

    const wrapper = mount(AgentTimeline, { props: { taskId: 9 } })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(750)

    expect(streamAgentEvents).toHaveBeenNthCalledWith(
      2, 9, 7, expect.any(Function), expect.any(AbortSignal))
    wrapper.unmount()
  })
})
