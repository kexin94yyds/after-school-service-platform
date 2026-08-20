import { http } from './http'
import type {
  Enrollment,
  EnrollmentAction,
  GuardianAttendance,
  GuardianMonthlyAttendance,
  GuardianOffering,
  GuardianStudent,
} from './types'

export const enrollmentApi = {
  async getEnrollments(): Promise<Enrollment[]> {
    const response = await http.get<Enrollment[]>('/enrollments')
    return response.data
  },
  async enroll(studentId: number, offeringId: number): Promise<Enrollment> {
    const response = await http.post<Enrollment>('/enrollments', {
      studentId,
      offeringId,
    })
    return response.data
  },
  async cancel(id: number): Promise<void> {
    await http.delete(`/enrollments/${id}`)
  },
  async switchEnrollment(id: number, newOfferingId: number): Promise<Enrollment> {
    const response = await http.post<Enrollment>(`/enrollments/${id}/switch`, {
      newOfferingId,
    })
    return response.data
  },
  async getActions(id: number): Promise<EnrollmentAction[]> {
    const response = await http.get<EnrollmentAction[]>(`/enrollments/${id}/actions`)
    return response.data
  },
  async downloadRoster(offeringId: number): Promise<void> {
    const response = await http.get<Blob>('/enrollments/roster.xlsx', {
      params: { offeringId },
      responseType: 'blob',
    })
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'enrollment-roster.xlsx'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  },
  async getGuardianStudents(): Promise<GuardianStudent[]> {
    const response = await http.get<GuardianStudent[]>('/guardian/students')
    return response.data
  },
  async getStudentOfferings(studentId: number): Promise<GuardianOffering[]> {
    const response = await http.get<GuardianOffering[]>(
      `/guardian/students/${studentId}/offerings`,
    )
    return response.data
  },
  async getStudentAttendance(studentId: number): Promise<GuardianAttendance[]> {
    const response = await http.get<GuardianAttendance[]>(
      `/guardian/students/${studentId}/attendance`,
    )
    return response.data
  },
  async getStudentMonthlyAttendance(
    studentId: number,
    month: string,
  ): Promise<GuardianMonthlyAttendance> {
    const response = await http.get<GuardianMonthlyAttendance>(
      `/guardian/students/${studentId}/attendance/monthly`,
      { params: { month } },
    )
    return response.data
  },
}
