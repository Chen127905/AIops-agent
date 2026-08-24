import { flushPromises, mount } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'

import { api } from '../api/http'
import TicketDetailView from './TicketDetailView.vue'

describe('TicketDetailView', () => {
  const mock = new MockAdapter(api)

  afterEach(() => mock.reset())

  function routerFor(id: string) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/tickets/:id', component: TicketDetailView }],
    })
    return router.push(`/tickets/${id}`).then(() => router)
  }

  const ticket = (id: string, status = 'OPEN') => ({
    id, tenantId: 1, reporterId: 1, title: 'Redis 请求持续超时',
    description: '订单服务读取缓存持续超时，已经影响下单请求。',
    affectedService: 'order-service', category: 'CACHE', scenarioKey: 'redis-timeout',
    severity: 'HIGH', status, resolutionSummary: null,
    createdAt: '2026-08-24T00:00:00', updatedAt: '2026-08-24T00:00:00',
  })

  it('keeps a Snowflake ticket ID exact while loading the detail page', async () => {
    const id = '2091803106393858049'
    mock.onGet(`/api/tickets/${id}`).reply(200, ticket(id))
    mock.onGet(`/api/tickets/${id}/agent-tasks/latest`).reply(204)
    const router = await routerFor(id)
    const wrapper = mount(TicketDetailView, { global: { plugins: [router], stubs: {
      ConsoleLayout: { template: '<main><slot /></main>' }, AgentTimeline: true, RouterLink: true,
    } } })
    await flushPromises()

    expect(wrapper.text()).toContain('Redis 请求持续超时')
    expect(mock.history.get.map((request) => request.url)).toContain(`/api/tickets/${id}`)
    expect(mock.history.get.map((request) => request.url)).not.toContain('/api/tickets/2091803106393858000')
  })

  it('shows the failure reason instead of claiming the Agent never started', async () => {
    const id = '2091803106393858049'
    mock.onGet(`/api/tickets/${id}`).reply(200, ticket(id, 'FAILED'))
    mock.onGet(`/api/tickets/${id}/agent-tasks/latest`).reply(200, {
      id: 2, ticketId: id, status: 'FAILED', maxSteps: 12, timeoutSeconds: 180,
      maxTokens: 20000, stepsUsed: 1, tokensUsed: 0, errorSummary: 'provider unavailable',
      createdAt: '', startedAt: '', finishedAt: '',
    })
    mock.onGet('/api/agent-tasks/2/result').reply(200, {
      taskId: 2, ticketId: id, status: 'FAILED', category: null, urgency: null,
      rootCause: null, proposedAction: null, diagnosisSummary: null,
      actionArguments: {}, confidence: 0, report: null, citations: [], plannedTools: [],
      evidence: [], observations: [], remediationSteps: [], verificationSteps: [],
      rollbackPlan: null, errorSummary: 'Model provider is not configured: QWEN',
    })
    const router = await routerFor(id)
    const wrapper = mount(TicketDetailView, { global: { plugins: [router], stubs: {
      ConsoleLayout: { template: '<main><slot /></main>' }, AgentTimeline: true, RouterLink: true,
    } } })
    await flushPromises()

    expect(wrapper.text()).toContain('Agent 诊断未完成')
    expect(wrapper.text()).toContain('Model provider is not configured: QWEN')
    expect(wrapper.text()).not.toContain('尚未启动 Agent 诊断')
  })
})
