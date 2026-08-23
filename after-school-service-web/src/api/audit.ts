import { http } from './http'
import { compactQuery } from './v7-query'

export type AuditMethod = 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface OperationAuditLog {
  id: number
  actorUserId: number
  actorUsername: string
  actorName: string
  actorRole: 'REGULATOR' | 'SCHOOL_ADMIN' | 'TEACHER' | 'GUARDIAN' | 'STUDENT'
  actorSchoolId?: number | null
  actorSchoolName?: string | null
  targetSchoolId?: number | null
  targetSchoolName?: string | null
  schoolId?: number | null
  schoolName?: string | null
  httpMethod: AuditMethod
  requestPath: string
  responseStatus: number
  sourceFingerprint: string
  occurredAt: string
}

export interface AuditFilters {
  schoolId?: number | null
  actorUserId?: number | null
  method?: AuditMethod | ''
  pathPrefix?: string
  occurredFrom?: string
  occurredTo?: string
}

export const auditApi = {
  async list(filters: AuditFilters = {}): Promise<OperationAuditLog[]> {
    const response = await http.get<OperationAuditLog[]>('/audit-logs', {
      params: compactQuery(filters),
    })
    return response.data
  },
}
