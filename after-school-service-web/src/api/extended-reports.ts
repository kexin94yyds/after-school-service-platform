import { http } from './http'
import { compactQuery } from './v7-query'

export interface CoursePerformanceFilters {
  schoolId?: number | null
  termId?: number | null
  fromDate?: string
  toDate?: string
  category?: string
  status?: string
}

export interface CoursePerformanceRow {
  schoolId: number
  schoolName: string
  termId?: number | null
  termName: string
  category: string
  courseId: number
  courseName: string
  offeringId: number
  offeringCode: string
  teacherId: number
  teacherName: string
  status: string
  activeEnrollmentCount: number
  sessionCount: number
  completedSessions: number
  completedHours: number
  attendanceRate: number
  evaluationCount: number
  averageRating: number
  satisfactionRate: number
}

function filenameFromDisposition(disposition: unknown): string {
  if (typeof disposition !== 'string') return 'course-performance.csv'
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded)
    } catch {
      return 'course-performance.csv'
    }
  }
  return (
    disposition.match(/filename="?([^";]+)"?/i)?.[1] ??
    'course-performance.csv'
  )
}

export const extendedReportsApi = {
  async getCoursePerformance(
    filters: CoursePerformanceFilters = {},
  ): Promise<CoursePerformanceRow[]> {
    const response = await http.get<CoursePerformanceRow[]>(
      '/reports/course-performance',
      { params: compactQuery(filters) },
    )
    return response.data
  },

  async downloadCoursePerformanceCsv(
    filters: CoursePerformanceFilters = {},
  ): Promise<void> {
    const response = await http.get<Blob>(
      '/reports/course-performance.csv',
      {
        params: compactQuery(filters),
        responseType: 'blob',
      },
    )
    const blob =
      response.data instanceof Blob
        ? response.data
        : new Blob([response.data], { type: 'text/csv;charset=UTF-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filenameFromDisposition(
      response.headers['content-disposition'],
    )
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  },
}
