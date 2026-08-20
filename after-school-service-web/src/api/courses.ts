import { http } from './http'
import type {
  Course,
  CourseInput,
  CourseOffering,
  CourseOfferingInput,
} from './types'

export const courseApi = {
  async getCourses(): Promise<Course[]> {
    const response = await http.get<Course[]>('/courses')
    return response.data
  },
  async createCourse(payload: CourseInput): Promise<Course> {
    const response = await http.post<Course>('/courses', payload)
    return response.data
  },
  async updateCourse(
    payload: CourseInput & { id: number },
  ): Promise<Course> {
    const { id, ...body } = payload
    const response = await http.put<Course>(`/courses/${id}`, body)
    return response.data
  },
  async downloadCourses(): Promise<void> {
    await download('/courses.xlsx', 'courses.xlsx')
  },
  async downloadImportTemplate(): Promise<void> {
    await download('/courses/import-template.xlsx', 'course-import-template.xlsx')
  },
  async importCourses(file: File): Promise<{
    createdCount: number
    updatedCount: number
    processedCount: number
  }> {
    const body = new FormData()
    body.append('file', file)
    const response = await http.post('/courses/import', body)
    return response.data
  },
  async getOfferings(): Promise<CourseOffering[]> {
    const response = await http.get<CourseOffering[]>('/offerings')
    return response.data
  },
  async createOffering(
    payload: CourseOfferingInput,
  ): Promise<CourseOffering> {
    const response = await http.post<CourseOffering>('/offerings', payload)
    return response.data
  },
  async updateOffering(
    payload: CourseOfferingInput & { id: number },
  ): Promise<CourseOffering> {
    const { id, ...body } = payload
    const response = await http.put<CourseOffering>(`/offerings/${id}`, body)
    return response.data
  },
}

async function download(path: string, fallback: string): Promise<void> {
  const response = await http.get<Blob>(path, { responseType: 'blob' })
  const blob = response.data instanceof Blob ? response.data : new Blob([response.data])
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fallback
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
