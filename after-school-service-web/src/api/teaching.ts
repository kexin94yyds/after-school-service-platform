import { http } from './http'
import type {
  AttendanceRecord,
  AttendanceUpdateRecord,
  LessonSession,
  SessionStatus,
} from './types'

export const teachingApi = {
  async getSessions(offeringId: number): Promise<LessonSession[]> {
    const response = await http.get<LessonSession[]>(
      `/offerings/${offeringId}/sessions`,
    )
    return response.data
  },
  async generateSessions(offeringId: number): Promise<LessonSession[]> {
    const response = await http.post<LessonSession[]>(
      `/offerings/${offeringId}/sessions/generate`,
    )
    return response.data
  },
  async updateSession(
    sessionId: number,
    status: SessionStatus,
    notes: string | null,
  ): Promise<LessonSession> {
    const response = await http.put<LessonSession>(
      `/sessions/${sessionId}`,
      { status, notes },
    )
    return response.data
  },
  async getAttendance(sessionId: number): Promise<AttendanceRecord[]> {
    const response = await http.get<AttendanceRecord[]>(
      `/sessions/${sessionId}/attendance`,
    )
    return response.data
  },
  async updateAttendance(
    sessionId: number,
    records: AttendanceUpdateRecord[],
  ): Promise<AttendanceRecord[]> {
    const response = await http.put<AttendanceRecord[]>(
      `/sessions/${sessionId}/attendance`,
      { records },
    )
    return response.data
  },
}
