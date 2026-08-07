import { describe, expect, it, vi } from 'vitest'

import {
  completeLogout,
  LOGOUT_SERVER_UNCONFIRMED_MESSAGE,
  navigateToLoginSafely,
} from './logoutFlow'

describe('logout flow', () => {
  it('navigates to login without warning after server logout succeeds', async () => {
    const signOut = vi.fn().mockResolvedValue(undefined)
    const navigateToLogin = vi.fn().mockResolvedValue(undefined)
    const isNavigationFailure = vi.fn().mockReturnValue(false)
    const warn = vi.fn()

    await expect(
      completeLogout({
        signOut,
        navigateToLogin,
        isNavigationFailure,
        warn,
      }),
    ).resolves.toBe(true)

    expect(navigateToLogin).toHaveBeenCalledOnce()
    expect(isNavigationFailure).toHaveBeenCalledWith(undefined)
    expect(warn).not.toHaveBeenCalled()
  })

  it('warns accurately and still navigates when server logout fails', async () => {
    const events: string[] = []
    const signOut = vi.fn(async () => {
      events.push('sign-out')
      throw new Error('offline')
    })
    const warn = vi.fn((message: string) => {
      events.push(`warning:${message}`)
    })
    const navigateToLogin = vi.fn(async () => {
      events.push('navigate')
    })
    const isNavigationFailure = vi.fn().mockReturnValue(false)

    await expect(
      completeLogout({
        signOut,
        navigateToLogin,
        isNavigationFailure,
        warn,
      }),
    ).resolves.toBe(true)

    expect(warn).toHaveBeenCalledWith(LOGOUT_SERVER_UNCONFIRMED_MESSAGE)
    expect(navigateToLogin).toHaveBeenCalledOnce()
    expect(events).toEqual([
      'sign-out',
      `warning:${LOGOUT_SERVER_UNCONFIRMED_MESSAGE}`,
      'navigate',
    ])
  })
})

describe('safe login navigation', () => {
  it('reports a resolved router navigation failure without throwing', async () => {
    const failure = { type: 'aborted' }

    await expect(
      navigateToLoginSafely({
        navigateToLogin: vi.fn().mockResolvedValue(failure),
        isNavigationFailure: (result) => result === failure,
      }),
    ).resolves.toBe(false)
  })

  it('reports a rejected router navigation without throwing', async () => {
    await expect(
      navigateToLoginSafely({
        navigateToLogin: vi.fn().mockRejectedValue(new Error('guard failed')),
        isNavigationFailure: vi.fn(),
      }),
    ).resolves.toBe(false)
  })
})
