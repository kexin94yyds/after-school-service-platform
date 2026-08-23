import { http } from './http'

export interface StudentGrade {
  id: number
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  courseName: string
  category: string
  term: string
  studentId: number
  studentNo: string
  studentName: string
  className: string
  gradeLevel: number
  teacherId: number
  teacherName: string
  score: number
  learningEvaluation: string | null
  recordedAt: string
  updatedAt: string
  updatedByName: string
}

export interface GradeRosterItem {
  studentId: number
  studentNo: string
  studentName: string
  className: string
  gradeId: number | null
  score: number | null
  learningEvaluation: string | null
  updatedAt: string | null
}

export interface GradeSummary {
  offeringId: number
  offeringCode: string
  courseName: string
  teacherName: string
  gradedCount: number
  averageScore: number | null
  excellentCount: number
  goodCount: number
  passCount: number
  needsSupportCount: number
}

export interface GradeRevision {
  id: number
  oldScore: number
  newScore: number
  oldLearningEvaluation: string | null
  newLearningEvaluation: string | null
  changedByName: string
  changedAt: string
}

export const gradeApi = {
  async getGrades(offeringId?: number): Promise<StudentGrade[]> {
    const response = await http.get<StudentGrade[]>('/grades', {
      params: offeringId ? { offeringId } : {},
    })
    return response.data
  },
  async getRoster(offeringId: number): Promise<GradeRosterItem[]> {
    const response = await http.get<GradeRosterItem[]>('/grades/roster', {
      params: { offeringId },
    })
    return response.data
  },
  async save(input: {
    offeringId: number
    studentId: number
    score: number
    learningEvaluation: string | null
  }): Promise<StudentGrade> {
    const response = await http.put<StudentGrade>('/grades', input)
    return response.data
  },
  async getSummary(): Promise<GradeSummary[]> {
    const response = await http.get<GradeSummary[]>('/grades/summary')
    return response.data
  },
  async getRevisions(gradeId: number): Promise<GradeRevision[]> {
    const response = await http.get<GradeRevision[]>(`/grades/${gradeId}/revisions`)
    return response.data
  },
  async downloadGrades(): Promise<void> {
    const response = await http.get<Blob>('/grades/export.xlsx', { responseType: 'blob' })
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url; anchor.download = 'student-grades.xlsx'
    document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url)
  },
}
