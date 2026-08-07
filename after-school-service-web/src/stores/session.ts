import axios from 'axios'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/api/auth'
import { resetCsrfToken } from '@/api/http'
import type { RoleCode, SessionUser } from '@/api/types'

type SessionState = 'idle' | 'loading' | 'authenticated' | 'guest'

export const useSessionStore = defineStore('session', () => {
  const user = ref<SessionUser | null>(null)
  const state = ref<SessionState>('idle')
  let restoreRequest: Promise<SessionUser | null> | null = null

  const role = computed<RoleCode | null>(() => user.value?.role ?? null)
  const isAuthenticated = computed(() => user.value !== null)
  const initialized = computed(
    () => state.value === 'authenticated' || state.value === 'guest',
  )

  async function restore(): Promise<SessionUser | null> {
    if (initialized.value) {
      return user.value
    }
    if (!restoreRequest) {
      state.value = 'loading'
      restoreRequest = authApi
        .fetchCurrentUser()
        .then((currentUser) => {
          user.value = currentUser
          state.value = 'authenticated'
          return currentUser
        })
        .catch((error: unknown) => {
          user.value = null
          state.value = 'guest'
          if (axios.isAxiosError(error) && error.response?.status === 401) {
            return null
          }
          throw error
        })
        .finally(() => {
          restoreRequest = null
        })
    }
    return restoreRequest
  }

  async function signIn(username: string, password: string): Promise<SessionUser> {
    state.value = 'loading'
    try {
      await authApi.login(username, password)
      resetCsrfToken()
      const currentUser = await authApi.fetchCurrentUser()
      user.value = currentUser
      state.value = 'authenticated'
      return currentUser
    } catch (error) {
      user.value = null
      state.value = 'guest'
      throw error
    }
  }

  async function signOut(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      resetCsrfToken()
      user.value = null
      state.value = 'guest'
    }
  }

  function expire(): void {
    user.value = null
    state.value = 'guest'
    resetCsrfToken()
  }

  return {
    user,
    role,
    state,
    isAuthenticated,
    initialized,
    restore,
    signIn,
    signOut,
    expire,
  }
})
