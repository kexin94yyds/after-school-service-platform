import { beforeEach, describe, expect, it, vi } from 'vitest'

import { changePassword } from './auth'
import { http } from './http'
import { organizationApi } from './organization'
import type { SchoolAdminInput } from './types'

describe('account security API contracts', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('posts the current and new password to the authenticated endpoint', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: undefined })

    await changePassword('current-secret', 'new-secret-1234')

    expect(post).toHaveBeenCalledWith('/auth/change-password', {
      currentPassword: 'current-secret',
      newPassword: 'new-secret-1234',
    })
  })

  it('uses the selected school when listing and creating administrators', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { id: 17 },
    })
    const payload: SchoolAdminInput = {
      username: 'school_operator',
      displayName: '学校管理员',
      mobile: '13800000000',
      password: 'initial-pass-123',
      enabled: true,
    }

    await organizationApi.getSchoolAdmins(3)
    await organizationApi.createSchoolAdmin(3, payload)

    expect(get).toHaveBeenCalledWith('/schools/3/admins')
    expect(post).toHaveBeenCalledWith('/schools/3/admins', payload)
  })

  it('targets the administrator id and permits an empty update password', async () => {
    const put = vi.spyOn(http, 'put').mockResolvedValue({
      data: { id: 17 },
    })
    const payload: SchoolAdminInput = {
      username: 'school_operator',
      displayName: '学校管理员',
      mobile: null,
      password: null,
      enabled: false,
    }

    await organizationApi.updateSchoolAdmin(3, { id: 17, ...payload })

    expect(put).toHaveBeenCalledWith('/schools/3/admins/17', payload)
  })
})
