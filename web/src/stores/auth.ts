import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { api, TOKEN_STORAGE_KEY } from '../api/http'

export interface LoginCredentials {
  tenantCode: string
  username: string
  password: string
}

interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

export interface AuthenticatedUser {
  tenantId: number
  userId: number
  username: string
  roles: string[]
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_STORAGE_KEY))
  const user = ref<AuthenticatedUser | null>(null)
  const authenticated = computed(() => Boolean(token.value))

  async function login(credentials: LoginCredentials): Promise<void> {
    const response = await api.post<TokenResponse>('/api/auth/login', credentials)
    token.value = response.data.accessToken
    localStorage.setItem(TOKEN_STORAGE_KEY, response.data.accessToken)
  }

  async function loadCurrentUser(): Promise<void> {
    const response = await api.get<AuthenticatedUser>('/api/auth/me')
    user.value = response.data
  }

  function clearSession(): void {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  return {
    token,
    user,
    authenticated,
    login,
    loadCurrentUser,
    clearSession,
  }
})
