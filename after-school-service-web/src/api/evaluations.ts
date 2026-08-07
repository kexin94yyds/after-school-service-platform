import { http } from './http'
import { compactQuery } from './v7-query'

export interface EvaluationSubmission {
  studentId: number
  offeringId: number
  rating: number
  comment: string | null
}

export interface CourseEvaluation {
  id: number
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  termId?: number | null
  termName: string
  courseId: number
  courseName: string
  category: string
  teacherId: number
  teacherName: string
  rating: number
  submittedAt: string
  studentId?: number
  studentNo?: string
  studentName?: string
  guardianId?: number
  guardianName?: string
  comment?: string | null
}

export interface EvaluationFilters {
  schoolId?: number | null
  termId?: number | null
  category?: string
  minRating?: number | null
  maxRating?: number | null
  submittedFrom?: string
  submittedTo?: string
}

export interface EvaluationSummary {
  evaluationCount: number
  averageRating: number
  satisfactionRate: number
  rating1Count: number
  rating2Count: number
  rating3Count: number
  rating4Count: number
  rating5Count: number
}

export interface GuardianOwnEvaluation {
  id: number
  studentId: number
  offeringId: number
  rating: number
  submittedAt: string
}

export interface GuardianStudentForEvaluation {
  id: number
  schoolId: number
  schoolName: string
  className: string
  grade: number
  studentNo: string
  fullName: string
  status: 'ACTIVE' | 'INACTIVE'
}

export interface GuardianEnrollmentForEvaluation {
  id: number
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  term: string
  courseName: string
  teacherName: string
  studentId: number
  studentName: string
  status: 'ENROLLED' | 'CANCELED'
  enrolledAt: string
}

export interface GuardianAttendanceForEvaluation {
  id: number
  studentId: number
  courseName: string
  offeringCode: string
  teacherName: string
  sessionDate: string
  startTime: string
  endTime: string
  status: 'PRESENT' | 'LATE' | 'LEAVE' | 'ABSENT'
  remark: string | null
  recordedAt: string
}

export const evaluationApi = {
  async submit(payload: EvaluationSubmission): Promise<CourseEvaluation> {
    const response = await http.post<CourseEvaluation>('/evaluations', payload)
    return response.data
  },

  async getMine(): Promise<GuardianOwnEvaluation[]> {
    const response = await http.get<GuardianOwnEvaluation[]>('/evaluations/mine')
    return response.data
  },

  async list(filters: EvaluationFilters = {}): Promise<CourseEvaluation[]> {
    const response = await http.get<CourseEvaluation[]>('/evaluations', {
      params: compactQuery(filters),
    })
    return response.data
  },

  async summary(
    filters: Omit<EvaluationFilters, 'minRating' | 'maxRating'> = {},
  ): Promise<EvaluationSummary> {
    const response = await http.get<EvaluationSummary>(
      '/evaluations/summary',
      { params: compactQuery(filters) },
    )
    return response.data
  },

  async getGuardianStudents(): Promise<GuardianStudentForEvaluation[]> {
    const response = await http.get<GuardianStudentForEvaluation[]>(
      '/guardian/students',
    )
    return response.data
  },

  async getGuardianEnrollments(): Promise<
    GuardianEnrollmentForEvaluation[]
  > {
    const response = await http.get<GuardianEnrollmentForEvaluation[]>(
      '/enrollments',
    )
    return response.data
  },

  async getGuardianAttendance(
    studentId: number,
  ): Promise<GuardianAttendanceForEvaluation[]> {
    const response = await http.get<GuardianAttendanceForEvaluation[]>(
      `/guardian/students/${studentId}/attendance`,
    )
    return response.data
  },
}
