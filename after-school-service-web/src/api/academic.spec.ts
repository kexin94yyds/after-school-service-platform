import { beforeEach, describe, expect, it, vi } from 'vitest'

import { academicApi } from './academic'
import { http } from './http'

describe('academic API paths', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps school and term filters in request parameters', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })

    await academicApi.getServicePlans({ schoolId: 3, termId: 8 })

    expect(get).toHaveBeenCalledWith('/service-plans', {
      params: { schoolId: 3, termId: 8 },
    })
  })

  it('targets the plan transition endpoint with an explicit reason', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { id: 12 },
    })

    await academicApi.transitionServicePlan(12, 'RETURNED', '缺少安全预案')

    expect(post).toHaveBeenCalledWith('/service-plans/12/transitions', {
      targetStatus: 'RETURNED',
      reason: '缺少安全预案',
    })
  })

  it('posts rescheduling to the selected lesson session', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { id: 31 },
    })
    const payload = {
      sessionDate: '2026-09-09',
      startTime: '15:00:00',
      endTime: '16:00:00',
      roomId: 9,
      reason: '校级活动调整',
    }

    await academicApi.reschedule(27, payload)

    expect(post).toHaveBeenCalledWith('/sessions/27/reschedule', payload)
  })

  it('reverts one schedule adjustment through its audit record', async () => {
    const post = vi.spyOn(http, 'post').mockResolvedValue({
      data: { id: 31, status: 'REVERTED' },
    })

    await academicApi.revertScheduleAdjustment(31)

    expect(post).toHaveBeenCalledWith('/schedule-adjustments/31/revert')
  })
})
