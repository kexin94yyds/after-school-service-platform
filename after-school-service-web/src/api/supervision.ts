import { http } from './http'
import { compactQuery } from './v7-query'

export type AlertType =
  | 'OVERDUE_ATTENDANCE'
  | 'OFFERING_NO_SESSIONS'
  | 'LOW_ATTENDANCE'

export type AlertSeverity = 'LOW' | 'MEDIUM' | 'HIGH'

export type AlertStatus =
  | 'OPEN'
  | 'ACKNOWLEDGED'
  | 'RECTIFYING'
  | 'WAITING_VERIFY'
  | 'CLOSED'
  | 'RETURNED'

export interface SupervisionAlert {
  id: number
  schoolId: number
  schoolCode: string
  schoolName: string
  offeringId: number
  offeringCode: string
  termId?: number | null
  termName: string
  courseName: string
  category: string
  teacherName: string
  sessionId?: number | null
  sessionDate?: string | null
  sessionStartTime?: string | null
  sessionEndTime?: string | null
  alertType: AlertType
  severity: AlertSeverity
  status: AlertStatus
  title: string
  description: string
  metricValue?: number | null
  thresholdValue?: number | null
  rectificationDeadline: string
  detectedAt: string
  closedAt?: string | null
  updatedAt: string
  overdue: boolean | number
}

export interface SupervisionAction {
  id: number
  alertId: number
  fromStatus?: AlertStatus | null
  toStatus: AlertStatus
  actionType: string
  comment: string
  actorId: number
  actorName: string
  actorRole: 'REGULATOR' | 'SCHOOL_ADMIN'
  actedAt: string
}

export interface SupervisionFilters {
  schoolId?: number | null
  type?: AlertType | ''
  status?: AlertStatus | ''
  detectedFrom?: string
  detectedTo?: string
}

export interface ScanRequest {
  schoolId?: number | null
  termId?: number | null
  lowAttendanceThreshold?: number
  deadlineDays?: number
}

export interface ScanResult {
  candidateCount: number
  createdCount: number
  deduplicatedCount: number
  createdByType: Record<AlertType, number>
  scannedAt: string
  lowAttendanceThreshold: number
}

export interface TransitionRequest {
  targetStatus: AlertStatus
  comment: string
}

export const supervisionApi = {
  async list(filters: SupervisionFilters = {}): Promise<SupervisionAlert[]> {
    const response = await http.get<SupervisionAlert[]>(
      '/supervision/alerts',
      { params: compactQuery(filters) },
    )
    return response.data
  },

  async scan(payload: ScanRequest): Promise<ScanResult> {
    const response = await http.post<ScanResult>(
      '/supervision/alerts/scan',
      payload,
    )
    return response.data
  },

  async transition(
    alertId: number,
    payload: TransitionRequest,
  ): Promise<SupervisionAlert> {
    const response = await http.post<SupervisionAlert>(
      `/supervision/alerts/${alertId}/transition`,
      payload,
    )
    return response.data
  },

  async history(alertId: number): Promise<SupervisionAction[]> {
    const response = await http.get<SupervisionAction[]>(
      `/supervision/alerts/${alertId}/history`,
    )
    return response.data
  },
}
