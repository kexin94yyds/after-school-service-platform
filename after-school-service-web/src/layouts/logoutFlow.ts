export const LOGOUT_SERVER_UNCONFIRMED_MESSAGE =
  '本机已退出，但未能确认服务端会话已注销。'

interface LogoutFlowOptions {
  signOut: () => Promise<void>
  navigateToLogin: () => void | Promise<unknown>
  isNavigationFailure: (result: unknown) => boolean
  warn: (message: string) => void
}

interface LoginNavigationOptions {
  navigateToLogin: () => void | Promise<unknown>
  isNavigationFailure: (result: unknown) => boolean
}

/**
 * Vue Router resolves aborted navigation with a failure value, while guard
 * errors reject. Both outcomes keep the current route mounted and therefore
 * need the same logged-out fallback.
 */
export async function navigateToLoginSafely({
  navigateToLogin,
  isNavigationFailure,
}: LoginNavigationOptions): Promise<boolean> {
  try {
    const result = await navigateToLogin()
    return !isNavigationFailure(result)
  } catch {
    return false
  }
}

/**
 * Finishes the local logout flow even when the server cannot confirm logout.
 * The session store owns local identity cleanup; this coordinator owns feedback
 * and the final transition away from the protected layout.
 */
export async function completeLogout({
  signOut,
  navigateToLogin,
  isNavigationFailure,
  warn,
}: LogoutFlowOptions): Promise<boolean> {
  try {
    await signOut()
  } catch {
    warn(LOGOUT_SERVER_UNCONFIRMED_MESSAGE)
  }

  return navigateToLoginSafely({ navigateToLogin, isNavigationFailure })
}
