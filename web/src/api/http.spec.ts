import MockAdapter from 'axios-mock-adapter'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  api,
  AUTH_UNAUTHORIZED_EVENT,
  TOKEN_STORAGE_KEY,
} from './http'

describe('HTTP authentication boundary', () => {
  let mock: MockAdapter

  beforeEach(() => {
    localStorage.clear()
    mock = new MockAdapter(api)
  })

  afterEach(() => {
    mock.restore()
  })

  it('attaches the persisted bearer token', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'signed-jwt')
    mock.onGet('/protected').reply((config) => [
      200,
      { authorization: config.headers?.get('Authorization') },
    ])

    const response = await api.get<{ authorization: string }>('/protected')

    expect(response.data.authorization).toBe('Bearer signed-jwt')
  })

  it('clears the token and notifies the app after a 401', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'expired-jwt')
    const unauthorized = vi.fn()
    window.addEventListener(AUTH_UNAUTHORIZED_EVENT, unauthorized, { once: true })
    mock.onGet('/protected').reply(401)

    await expect(api.get('/protected')).rejects.toBeDefined()

    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(unauthorized).toHaveBeenCalledOnce()
  })
})
