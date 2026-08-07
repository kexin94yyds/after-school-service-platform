import { http } from './http'

export interface SchoolOption {
  id: number
  schoolCode: string
  schoolName: string
  status: 'ACTIVE' | 'INACTIVE'
}

export interface AcademicTermOption {
  id: number
  termCode: string
  termName: string
  startDate: string
  endDate: string
  status: 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'ARCHIVED'
}

export const referenceDataApi = {
  async getSchools(): Promise<SchoolOption[]> {
    const response = await http.get<SchoolOption[]>('/schools')
    return response.data
  },

  async getTerms(): Promise<AcademicTermOption[]> {
    const response = await http.get<AcademicTermOption[]>('/terms')
    return response.data
  },
}
