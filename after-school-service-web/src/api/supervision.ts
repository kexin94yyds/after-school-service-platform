import { http } from './http'
import { compactQuery } from './v7-query'

export type AlertType =
  | 'OVERDUE_ATTENDANCE'
  | 'OFFERING_NO_SESSIONS'
  | 'LOW_ATTENDANCE'
  | 'OVER_CAPACITY'
  | 'STAFF_SHORTAGE'
  | 'MISSING_ATTENDANCE'
  | 'UNFILED_OFFERING'

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
  actorId: number | null
  actorName: string
  actorRole: 'REGULATOR' | 'SCHOOL_ADMIN' | 'SYSTEM'
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
  scanRunId: string
  triggerSource: ScanTriggerSource
  candidateCount: number
  createdCount: number
  deduplicatedCount: number
  createdByType: Record<AlertType, number>
  scannedAt: string
  lowAttendanceThreshold: number
}

export type ScanTriggerSource = 'MANUAL' | 'SCHEDULED'
export type ScanRunStatus = 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface SupervisionScanRun {
  id: string
  triggerSource: ScanTriggerSource
  status: ScanRunStatus
  startedAt: string
  finishedAt?: string | null
  candidateCount?: number | null
  createdCount?: number | null
  failureSummary?: string | null
  operatorUserId?: number | null
  operatorName?: string | null
}

export interface TransitionRequest {
  targetStatus: AlertStatus
  comment: string
}

export interface RegulatorNotification {
  id: number
  schoolId: number
  schoolName: string
  alertId: number
  notificationType: 'SUPERVISION_ALERT' | 'RECTIFICATION_SUBMITTED'
  title: string
  content: string
  isRead: boolean | number
  readAt: string | null
  createdAt: string
  severity: AlertSeverity
  alertStatus: AlertStatus
}

export interface RectificationNotice {
  id: number
  schoolId: number
  alertId: number
  title: string
  requirements: string
  dueAt: string
  issuedBy: number
  issuedByName: string
  issuedAt: string
  updatedAt: string
}

export interface RectificationMaterial {
  id: number
  schoolId: number
  alertId: number
  noticeId: number
  originalName: string
  contentType: string
  sizeBytes: number
  sha256: string
  uploadedBy: number
  uploadedByName: string
  uploadedAt: string
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

  async scanRuns(limit = 50): Promise<SupervisionScanRun[]> {
    const response = await http.get<SupervisionScanRun[]>(
      '/supervision/alerts/scan-runs',
      { params: { limit } },
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
  async notifications(unreadOnly = false): Promise<RegulatorNotification[]> {
    const response = await http.get<RegulatorNotification[]>(
      '/regulator/notifications',
      { params: { unreadOnly } },
    )
    return response.data
  },
  async markNotificationRead(id: number): Promise<void> {
    await http.post(`/regulator/notifications/${id}/read`)
  },
  async issueNotice(
    alertId: number,
    payload: { title: string; requirements: string; dueAt: string },
  ): Promise<RectificationNotice> {
    const response = await http.post<RectificationNotice>(
      `/rectifications/alerts/${alertId}/notice`,
      payload,
    )
    return response.data
  },
  async notice(alertId: number): Promise<RectificationNotice> {
    const response = await http.get<RectificationNotice>(
      `/rectifications/alerts/${alertId}/notice`,
    )
    return response.data
  },
  async materials(alertId: number): Promise<RectificationMaterial[]> {
    const response = await http.get<RectificationMaterial[]>(
      `/rectifications/alerts/${alertId}/materials`,
    )
    return response.data
  },
  async uploadMaterial(alertId: number, file: File): Promise<RectificationMaterial> {
    const body = new FormData()
    body.append('file', file)
    const response = await http.post<RectificationMaterial>(
      `/rectifications/alerts/${alertId}/materials`,
      body,
    )
    return response.data
  },
  async downloadMaterial(material: RectificationMaterial): Promise<void> {
    const response = await http.get<Blob>(
      `/rectifications/materials/${material.id}/download`,
      { responseType: 'blob' },
    )
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = material.originalName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  },
}
