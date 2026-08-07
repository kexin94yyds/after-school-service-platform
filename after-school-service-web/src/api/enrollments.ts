import { http } from './http'
import type {
  Enrollment,
  GuardianAttendance,
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
}
