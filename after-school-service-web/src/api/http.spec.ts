// @vitest-environment happy-dom

import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'

import { http, resetCsrfToken } from './http'

describe('CSRF request flow', () => {
  const originalAdapter = http.defaults.adapter

  afterEach(() => {
    http.defaults.adapter = originalAdapter
    resetCsrfToken()
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('sends the raw cookie token instead of a masked JSON token', async () => {
    let sentCsrfHeader: string | null = null
    http.defaults.adapter = async (
      config: InternalAxiosRequestConfig,
    ): Promise<AxiosResponse> => {
      if (config.url === '/public/csrf') {
        document.cookie = 'XSRF-TOKEN=raw-cookie-token; Path=/'
        return {
          data: {
            headerName: 'X-XSRF-TOKEN',
            parameterName: '_csrf',
            token: 'masked-json-token-must-not-be-used',
          },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        }
      }
      sentCsrfHeader = String(config.headers.get('X-XSRF-TOKEN') ?? '')
      return {
        data: {},
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }

    await http.post('/auth/login', {
      username: 'operator',
      password: 'not-a-real-password',
    })

    expect(sentCsrfHeader).toBe('raw-cookie-token')
    expect(sentCsrfHeader).not.toBe('masked-json-token-must-not-be-used')
  })
})
