import axios from 'axios'
import type { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

export interface ApiErrorBody {
  code?: string
  message?: string
  fieldErrors?: Record<string, string | string[]>
}

export interface CsrfBootstrap {
  headerName: string
  parameterName: string
}

export class ApiClientError extends Error {
  readonly code: string
  readonly status: number | null
  readonly fieldErrors: Record<string, string | string[]>

  constructor(
    message: string,
    options: {
      code?: string
      status?: number | null
      fieldErrors?: Record<string, string | string[]>
    } = {},
  ) {
    super(message)
    this.name = 'ApiClientError'
    this.code = options.code ?? 'REQUEST_FAILED'
    this.status = options.status ?? null
    this.fieldErrors = options.fieldErrors ?? {}
  }
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

let csrfRequest: Promise<CsrfBootstrap> | null = null
let unauthorizedHandler: (() => void) | null = null

const unsafeMethods = new Set(['post', 'put', 'patch', 'delete'])

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') {
    return null
  }
  const prefix = `${encodeURIComponent(name)}=`
  const entry = document.cookie
    .split(';')
    .map((value) => value.trim())
    .find((value) => value.startsWith(prefix))
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : null
}

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export function resetCsrfToken(): void {
  csrfRequest = null
}

export function ensureCsrfToken(): Promise<CsrfBootstrap> {
  if (!csrfRequest) {
    csrfRequest = http
      .get<CsrfBootstrap>('/public/csrf')
      .then((response) => response.data)
      .catch((error: unknown) => {
        csrfRequest = null
        throw error
      })
  }
  return csrfRequest
}

function isAuthBootstrapRequest(config?: InternalAxiosRequestConfig): boolean {
  const url = config?.url ?? ''
  return url.includes('/auth/me') || url.includes('/auth/login')
}

http.interceptors.request.use(
  async (config: InternalAxiosRequestConfig): Promise<InternalAxiosRequestConfig> => {
    const method = (config.method ?? 'get').toLowerCase()
    if (unsafeMethods.has(method)) {
      const csrf = await ensureCsrfToken()
      const rawCookieToken = readCookie('XSRF-TOKEN')
      if (!rawCookieToken) {
        resetCsrfToken()
        throw new ApiClientError('安全令牌初始化失败，请刷新页面后重试。', {
          code: 'CSRF_BOOTSTRAP_FAILED',
        })
      }
      config.headers.set(csrf.headerName, rawCookieToken)
    }
    return config
  },
)

http.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const axiosError = error as AxiosError<ApiErrorBody>
      if (
        axiosError.response?.status === 401 &&
        !isAuthBootstrapRequest(axiosError.config)
      ) {
        unauthorizedHandler?.()
      }
      if (axiosError.response?.status === 403) {
        resetCsrfToken()
      }
    }
    return Promise.reject(error)
  },
)

export function toApiClientError(
  error: unknown,
  fallbackMessage = '请求未完成，请稍后重试。',
): ApiClientError {
  if (error instanceof ApiClientError) {
    return error
  }
  if (axios.isAxiosError<ApiErrorBody>(error)) {
    const body = error.response?.data
    return new ApiClientError(body?.message || fallbackMessage, {
      code: body?.code,
      status: error.response?.status ?? null,
      fieldErrors: body?.fieldErrors,
    })
  }
  if (error instanceof Error && error.message) {
    return new ApiClientError(error.message)
  }
  return new ApiClientError(fallbackMessage)
}

export function getErrorMessage(
  error: unknown,
  fallbackMessage = '请求未完成，请稍后重试。',
): string {
  return toApiClientError(error, fallbackMessage).message
}
