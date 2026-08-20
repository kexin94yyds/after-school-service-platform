// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from 'vitest'

import { auditApi } from './audit'
import { evaluationApi } from './evaluations'
import { extendedReportsApi } from './extended-reports'
import { http } from './http'
import { referenceDataApi } from './reference-data'
import { supervisionApi } from './supervision'
import { compactQuery } from './v7-query'

describe('V7 API contracts', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('removes blank optional values without dropping zero or false', () => {
    expect(
      compactQuery({
        schoolId: null,
        category: '   ',
        termId: undefined,
        threshold: 0,
        enabled: false,
        status: 'OPEN',
      }),
    ).toEqual({ threshold: 0, enabled: false, status: 'OPEN' })
  })

  it('uses the supervision scan, list, transition, and history endpoints', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: {} })

    await supervisionApi.list({ schoolId: 3, status: 'OPEN', type: '' })
    await supervisionApi.scan({ schoolId: 3, lowAttendanceThreshold: 0.8 })
    await supervisionApi.scanRuns(100)
    await supervisionApi.transition(19, {
      targetStatus: 'ACKNOWLEDGED',
      comment: '学校已接收',
    })
    await supervisionApi.history(19)

    expect(get).toHaveBeenCalledWith('/supervision/alerts', {
      params: { schoolId: 3, status: 'OPEN' },
    })
    expect(post).toHaveBeenCalledWith('/supervision/alerts/scan', {
      schoolId: 3,
      lowAttendanceThreshold: 0.8,
    })
    expect(get).toHaveBeenCalledWith('/supervision/alerts/scan-runs', {
      params: { limit: 100 },
    })
    expect(post).toHaveBeenCalledWith('/supervision/alerts/19/transition', {
      targetStatus: 'ACKNOWLEDGED',
      comment: '学校已接收',
    })
    expect(get).toHaveBeenCalledWith('/supervision/alerts/19/history')
  })

  it('uses guardian submission and regulator evaluation query endpoints', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: {} })

    await evaluationApi.submit({
      studentId: 7,
      offeringId: 12,
      courseRating: 5,
      teacherRating: 4,
      comment: '课程安排清晰',
    })
    await evaluationApi.getMine()
    await evaluationApi.list({ schoolId: 3, termId: null, category: '艺术' })
    await evaluationApi.summary({ schoolId: 3, submittedFrom: '2026-07-01T00:00:00' })
    await evaluationApi.getGuardianStudents()
    await evaluationApi.getGuardianEnrollments()
    await evaluationApi.getGuardianAttendance(7)

    expect(post).toHaveBeenCalledWith('/evaluations', {
      studentId: 7,
      offeringId: 12,
      courseRating: 5,
      teacherRating: 4,
      comment: '课程安排清晰',
    })
    expect(get).toHaveBeenCalledWith('/evaluations/mine')
    expect(get).toHaveBeenCalledWith('/evaluations', {
      params: { schoolId: 3, category: '艺术' },
    })
    expect(get).toHaveBeenCalledWith('/evaluations/summary', {
      params: { schoolId: 3, submittedFrom: '2026-07-01T00:00:00' },
    })
    expect(get).toHaveBeenCalledWith('/guardian/students')
    expect(get).toHaveBeenCalledWith('/enrollments')
    expect(get).toHaveBeenCalledWith('/guardian/students/7/attendance')
  })

  it('preserves audit filters and loads reference options', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })

    await auditApi.list({
      schoolId: 3,
      actorUserId: 17,
      method: 'PATCH',
      pathPrefix: '/api/sessions',
    })
    await referenceDataApi.getSchools()
    await referenceDataApi.getTerms()

    expect(get).toHaveBeenCalledWith('/audit-logs', {
      params: {
        schoolId: 3,
        actorUserId: 17,
        method: 'PATCH',
        pathPrefix: '/api/sessions',
      },
    })
    expect(get).toHaveBeenCalledWith('/schools')
    expect(get).toHaveBeenCalledWith('/terms')
  })

  it('uses one report filter contract for JSON data and CSV export', async () => {
    const csv = new Blob(['school,course'], { type: 'text/csv' })
    const get = vi.spyOn(http, 'get').mockResolvedValue({
      data: csv,
      headers: { 'content-disposition': 'attachment; filename="performance.csv"' },
    })
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined)
    const createObjectURL = vi
      .spyOn(URL, 'createObjectURL')
      .mockReturnValue('blob:course-performance')
    const revokeObjectURL = vi
      .spyOn(URL, 'revokeObjectURL')
      .mockImplementation(() => undefined)
    const filters = {
      schoolId: 3,
      termId: 8,
      fromDate: '2026-03-01',
      toDate: '2026-07-15',
      category: '体育',
      status: 'FINISHED',
    }

    await extendedReportsApi.getCoursePerformance(filters)
    await extendedReportsApi.downloadCoursePerformanceCsv(filters)

    expect(get).toHaveBeenCalledWith('/reports/course-performance', {
      params: filters,
    })
    expect(get).toHaveBeenCalledWith('/reports/course-performance.csv', {
      params: filters,
      responseType: 'blob',
    })
    expect(createObjectURL).toHaveBeenCalledWith(csv)
    expect(click).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:course-performance')
  })
})
