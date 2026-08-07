import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as authApi from '@/api/auth'
import type { SessionUser } from '@/api/types'

import { useSessionStore } from './session'

vi.mock('@/api/auth', () => ({
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}))

const user: SessionUser = {
  id: 7,
  username: 'teacher01',
  displayName: '王老师',
  role: 'TEACHER',
  schoolId: 2,
}

describe('session store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('restores an authenticated server session', async () => {
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user)
    const store = useSessionStore()

    await expect(store.restore()).resolves.toEqual(user)

    expect(store.user).toEqual(user)
    expect(store.role).toBe('TEACHER')
    expect(store.isAuthenticated).toBe(true)
    expect(store.state).toBe('authenticated')
  })

  it('treats a 401 restore response as a guest session', async () => {
    vi.mocked(authApi.fetchCurrentUser).mockRejectedValue(
      Object.assign(new Error('Unauthorized'), {
        isAxiosError: true,
        response: { status: 401 },
      }),
    )
    const store = useSessionStore()

    await expect(store.restore()).resolves.toBeNull()

    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(store.state).toBe('guest')
  })

  it('logs in before loading the authoritative server identity', async () => {
    vi.mocked(authApi.login).mockResolvedValue()
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user)
    const store = useSessionStore()

    await expect(store.signIn(' teacher01 ', 'secret')).resolves.toEqual(user)

    expect(authApi.login).toHaveBeenCalledWith(' teacher01 ', 'secret')
    expect(authApi.fetchCurrentUser).toHaveBeenCalledOnce()
    expect(store.user).toEqual(user)
  })

  it('clears local identity even when server logout fails', async () => {
    vi.mocked(authApi.fetchCurrentUser).mockResolvedValue(user)
    vi.mocked(authApi.logout).mockRejectedValue(new Error('offline'))
    const store = useSessionStore()
    await store.restore()

    await expect(store.signOut()).rejects.toThrow('offline')

    expect(store.user).toBeNull()
    expect(store.role).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(store.state).toBe('guest')
  })
})
