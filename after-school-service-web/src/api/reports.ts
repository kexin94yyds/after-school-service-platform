import { http } from './http'
import type { ReportOverview } from './types'

export const reportApi = {
  async getOverview(): Promise<ReportOverview> {
    const response = await http.get<ReportOverview>('/reports/overview')
    return response.data
  },
}
