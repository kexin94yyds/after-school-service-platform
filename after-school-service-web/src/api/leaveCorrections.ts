import { http } from './http'

export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type CorrectionStatus = 'PENDING' | 'REJECTED' | 'CANCELED' | 'APPLIED'
export type ReviewDecision = 'APPROVED' | 'REJECTED'
export type AttendanceStatus = 'PRESENT' | 'LATE' | 'LEAVE' | 'ABSENT'
export type SessionStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELED'

export interface GuardianStudentSummary {
  id: number
  schoolId: number
  schoolName: string
  className: string
  grade: number
  studentNo: string
  fullName: string
}

export interface GuardianLeaveSession {
  id: number
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  courseName: string
  sessionDate: string
  startTime: string
  endTime: string
  classroom: string
  status: SessionStatus
  activeLeaveRequestId: number | null
  activeLeaveStatus: Extract<LeaveStatus, 'PENDING' | 'APPROVED'> | null
  activeLeaveReason: string | null
}

export interface LeaveRequest {
  id: number
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  courseName: string
  sessionId: number
  sessionDate: string
  startTime: string
  endTime: string
  studentId: number
  studentNo: string
  studentName: string
  guardianId: number
  guardianName: string
  reason: string
  status: LeaveStatus
  submittedBy: number
  submittedByName: string
  submittedAt: string
  reviewedBy: number | null
  reviewedByName: string | null
  reviewedAt: string | null
  reviewRemark: string | null
  withdrawnBy: number | null
  withdrawnByName: string | null
  withdrawnAt: string | null
}

export interface OfferingSummary {
  id: number
  schoolId: number
  offeringCode: string
  courseName: string
  teacherName: string
  classroom: string
  startTime: string
  weekDay: number
  status: string
}

export interface SessionSummary {
  id: number
  offeringId: number
  courseName: string
  offeringCode: string
  sessionDate: string
  startTime: string
  endTime: string
  status: SessionStatus
}

export interface AttendanceTarget {
  id: number | null
  studentId: number
  studentName: string
  studentNo: string
  className: string
  status: AttendanceStatus | null
  remark: string | null
  recordedAt: string | null
  recordedByName: string | null
}

export interface AttendanceCorrection {
  id: number
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  courseName: string
  sessionId: number
  sessionDate: string
  startTime: string
  attendanceId: number
  studentId: number
  studentNo: string
  studentName: string
  requestedStatus: AttendanceStatus
  requestedRemark: string | null
  reason: string
  status: CorrectionStatus
  requestedBy: number
  requestedByName: string
  requestedAt: string
  reviewedBy: number | null
  reviewedByName: string | null
  reviewedAt: string | null
  reviewRemark: string | null
  appliedAt: string | null
  canceledBy: number | null
  canceledByName: string | null
  canceledAt: string | null
  currentAttendanceStatus: AttendanceStatus
  currentAttendanceRemark: string | null
}

export interface AttendanceRevision {
  id: number
  schoolId: number
  offeringId: number
  sessionId: number
  attendanceId: number
  correctionRequestId: number
  studentId: number
  studentNo: string
  studentName: string
  oldStatus: AttendanceStatus
  newStatus: AttendanceStatus
  oldRemark: string | null
  newRemark: string | null
  oldRecordedBy: number
  oldRecordedByName: string
  oldRecordedAt: string
  changedBy: number
  changedByName: string
  changedAt: string
}

interface WorkflowFilters<TStatus extends string> {
  schoolId?: number
  offeringId?: number
  sessionId?: number
  status?: TStatus
}

export const leaveCorrectionApi = {
  async getGuardianStudents(): Promise<GuardianStudentSummary[]> {
    const response = await http.get<GuardianStudentSummary[]>('/guardian/students')
    return response.data
  },
  async getGuardianSessions(studentId: number): Promise<GuardianLeaveSession[]> {
    const response = await http.get<GuardianLeaveSession[]>(
      `/guardian/students/${studentId}/sessions`,
    )
    return response.data
  },
  async getLeaveRequests(
    filters: WorkflowFilters<LeaveStatus> = {},
  ): Promise<LeaveRequest[]> {
    const response = await http.get<LeaveRequest[]>('/leave-requests', {
      params: filters,
    })
    return response.data
  },
  async submitLeave(
    sessionId: number,
    studentId: number,
    reason: string,
  ): Promise<LeaveRequest> {
    const response = await http.post<LeaveRequest>('/leave-requests', {
      sessionId,
      studentId,
      reason,
    })
    return response.data
  },
  async withdrawLeave(id: number): Promise<LeaveRequest> {
    const response = await http.post<LeaveRequest>(
      `/leave-requests/${id}/withdraw`,
    )
    return response.data
  },
  async reviewLeave(
    id: number,
    decision: ReviewDecision,
    remark: string | null,
  ): Promise<LeaveRequest> {
    const response = await http.put<LeaveRequest>(
      `/leave-requests/${id}/review`,
      { decision, remark },
    )
    return response.data
  },
  async getOfferings(): Promise<OfferingSummary[]> {
    const response = await http.get<OfferingSummary[]>('/offerings')
    return response.data
  },
  async getSessions(offeringId: number): Promise<SessionSummary[]> {
    const response = await http.get<SessionSummary[]>(
      `/offerings/${offeringId}/sessions`,
    )
    return response.data
  },
  async getAttendance(sessionId: number): Promise<AttendanceTarget[]> {
    const response = await http.get<AttendanceTarget[]>(
      `/sessions/${sessionId}/attendance`,
    )
    return response.data
  },
  async getCorrections(
    filters: WorkflowFilters<CorrectionStatus> = {},
  ): Promise<AttendanceCorrection[]> {
    const response = await http.get<AttendanceCorrection[]>(
      '/attendance-corrections',
      { params: filters },
    )
    return response.data
  },
  async requestCorrection(input: {
    sessionId: number
    studentId: number
    requestedStatus: AttendanceStatus
    requestedRemark: string | null
    reason: string
  }): Promise<AttendanceCorrection> {
    const response = await http.post<AttendanceCorrection>(
      '/attendance-corrections',
      input,
    )
    return response.data
  },
  async cancelCorrection(id: number): Promise<AttendanceCorrection> {
    const response = await http.post<AttendanceCorrection>(
      `/attendance-corrections/${id}/cancel`,
    )
    return response.data
  },
  async reviewCorrection(
    id: number,
    decision: ReviewDecision,
    remark: string | null,
  ): Promise<AttendanceCorrection> {
    const response = await http.put<AttendanceCorrection>(
      `/attendance-corrections/${id}/review`,
      { decision, remark },
    )
    return response.data
  },
  async getRevisions(attendanceId: number): Promise<AttendanceRevision[]> {
    const response = await http.get<AttendanceRevision[]>(
      `/attendance/${attendanceId}/revisions`,
    )
    return response.data
  },
}
