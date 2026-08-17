import { beforeEach, describe, expect, it } from 'vitest'

import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'
import router from './index'

describe('authentication route guard', () => {
  beforeEach(async () => {
    useAuthStore(pinia).clearSession()
    await router.replace('/login')
  })

  it('redirects anonymous users to login and preserves their destination', async () => {
    await router.push('/')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/')
  })

  it('allows authenticated users to enter the console', async () => {
    const auth = useAuthStore(pinia)
    auth.token = 'signed-jwt'

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('home')
  })
})
