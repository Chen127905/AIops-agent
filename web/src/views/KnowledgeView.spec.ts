import { flushPromises, mount } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'
import KnowledgeView from './KnowledgeView.vue'

describe('KnowledgeView', () => {
  const mock = new MockAdapter(api)

  beforeEach(() => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = {
      tenantId: 1,
      userId: 1,
      username: 'admin',
      roles: ['ADMIN'],
    }
  })

  afterEach(() => mock.reset())

  it('ingests a document and renders pgvector search evidence', async () => {
    mock.onPost('/api/knowledge/documents').reply(201, { documentId: 7 })
    mock.onGet('/api/knowledge/search').reply(200, [{
      documentId: 7,
      documentVersion: 1,
      chunkIndex: 0,
      source: 'demo://redis-timeout-runbook',
      content: 'Redis connection pool timeout troubleshooting',
      score: 0.91,
      citationId: 'tenant:1:doc:7:v1:chunk:0',
    }])
    const wrapper = mount(KnowledgeView, {
      global: {
        stubs: { ConsoleLayout: { template: '<div><slot /></div>' }, teleport: true },
      },
    })

    await wrapper.findAll('button').find((button) => button.text().includes('新增文档'))!.trigger('click')
    await wrapper.get('[data-test=ingest-form]').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('文档 #7 已切分、向量化并发布')

    await wrapper.get('.search-bar').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('tenant:1:doc:7:v1:chunk:0')
    expect(wrapper.text()).toContain('Redis connection pool timeout troubleshooting')
  })

  it('shows the real HTTP failure instead of claiming pgvector is disabled', async () => {
    mock.onGet('/api/knowledge/search').reply(500, { message: 'embedding provider failed' })
    const wrapper = mount(KnowledgeView, {
      global: {
        stubs: { ConsoleLayout: { template: '<div><slot /></div>' } },
      },
    })

    await wrapper.get('.search-bar').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('embedding provider failed')
    expect(wrapper.text()).not.toContain('请确认 pgvector 已启用')
  })

  it('initializes built-in knowledge from the page without duplicates', async () => {
    mock.onPost('/api/knowledge/bootstrap').reply(200, {
      total: 5,
      published: 5,
      skipped: 0,
    })
    mock.onGet('/api/knowledge/search').reply(200, [])
    const wrapper = mount(KnowledgeView, {
      global: { stubs: { ConsoleLayout: { template: '<div><slot /></div>' } } },
    })

    await wrapper.findAll('button')
      .find((button) => button.text().includes('初始化内置知识'))!
      .trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('初始化完成：新增 5 份')
    expect(mock.history.post[0].url).toBe('/api/knowledge/bootstrap')
  })
})
