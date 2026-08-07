import { beforeEach, describe, expect, it, vi } from 'vitest'

import { courseApi } from './courses'
import { enrollmentApi } from './enrollments'
import { http } from './http'
import { organizationApi } from './organization'

describe('resource update paths', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('targets the school resource id and omits id from its request body', async () => {
    const put = vi.spyOn(http, 'put').mockResolvedValue({
      data: { id: 12 },
    })

    await organizationApi.updateSchool({
      id: 12,
      schoolCode: 'SCH-12',
      schoolName: '晨光学校',
      districtCode: 'D-01',
      address: null,
      contactPhone: null,
      status: 'ACTIVE',
    })

    expect(put).toHaveBeenCalledWith('/schools/12', {
      schoolCode: 'SCH-12',
      schoolName: '晨光学校',
      districtCode: 'D-01',
      address: null,
      contactPhone: null,
      status: 'ACTIVE',
    })
  })

  it('targets the course resource id and omits id from its request body', async () => {
    const put = vi.spyOn(http, 'put').mockResolvedValue({
      data: { id: 5 },
    })

    await courseApi.updateCourse({
      id: 5,
      schoolId: 2,
      courseCode: 'ART-01',
      courseName: '创意美术',
      category: '艺术',
      description: null,
      targetGradeMin: 1,
      targetGradeMax: 3,
      defaultCapacity: 24,
      status: 'ACTIVE',
    })

    expect(put).toHaveBeenCalledWith('/courses/5', {
      schoolId: 2,
      courseCode: 'ART-01',
      courseName: '创意美术',
      category: '艺术',
      description: null,
      targetGradeMin: 1,
      targetGradeMax: 3,
      defaultCapacity: 24,
      status: 'ACTIVE',
    })
  })

  it('targets the enrollment resource id when cancelling', async () => {
    const remove = vi
      .spyOn(http, 'delete')
      .mockResolvedValue({ data: undefined })

    await enrollmentApi.cancel(23)

    expect(remove).toHaveBeenCalledWith('/enrollments/23')
  })
})
