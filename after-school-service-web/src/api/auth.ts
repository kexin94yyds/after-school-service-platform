import { http } from './http'
import { roleCodes } from './types'
import type { RoleCode, SessionUser } from './types'

interface SessionUserResponse {
  id: number
  username: string
  displayName?: string
  role?: RoleCode
  roleCode?: RoleCode
  schoolId?: number | null
  studentId?: number | null
  teacherId?: number | null
  guardianId?: number | null
  capabilities?: Record<string, boolean>
}

function normalizeSessionUser(data: SessionUserResponse): SessionUser {
  const role = data.role ?? data.roleCode
  if (!role || !roleCodes.includes(role)) {
    throw new Error('服务端未返回有效角色。')
  }
  return {
    id: data.id,
    username: data.username,
    displayName: data.displayName || data.username,
    role,
    schoolId: data.schoolId ?? null,
    studentId: data.studentId ?? null,
    teacherId: data.teacherId ?? null,
    guardianId: data.guardianId ?? null,
    capabilities: data.capabilities,
  }
}

export async function fetchCurrentUser(): Promise<SessionUser> {
  const response = await http.get<SessionUserResponse>('/auth/me')
  return normalizeSessionUser(response.data)
}

export async function login(
  username: string,
  password: string,
  expectedRole: RoleCode,
): Promise<void> {
  await http.post('/auth/login', { username, password, expectedRole })
}

export async function logout(): Promise<void> {
  await http.post('/auth/logout')
}

export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  await http.post('/auth/change-password', {
    currentPassword,
    newPassword,
  })
}
