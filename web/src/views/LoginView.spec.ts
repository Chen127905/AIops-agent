import { flushPromises, mount } from '@vue/test-utils'
import MockAdapter from 'axios-mock-adapter'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'

import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'
import LoginView from './LoginView.vue'

describe('LoginView', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('stores the bearer token and redirects after login', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: LoginView },
        { path: '/', component: { template: '<div>home</div>' } },
      ],
    })
    await router.push('/login')
    await router.isReady()
    const mock = new MockAdapter(api)
    mock.onPost('/api/auth/login').reply(200, {
      accessToken: 'signed-jwt',
      tokenType: 'Bearer',
      expiresInSeconds: 7200,
    })

    const wrapper = mount(LoginView, {
      global: { plugins: [pinia, router] },
    })
    await wrapper.get('[data-test=tenant-code]').setValue('acme')
    await wrapper.get('[data-test=username]').setValue('alice')
    await wrapper.get('[data-test=password]').setValue('correct-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(useAuthStore().token).toBe('signed-jwt')
    expect(localStorage.getItem('ops-agent-token')).toBe('signed-jwt')
    expect(router.currentRoute.value.fullPath).toBe('/')
    mock.restore()
  })
})
