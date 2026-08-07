import { beforeEach, describe, expect, it, vi } from 'vitest'

import { http } from './http'
import { leaveCorrectionApi } from './leaveCorrections'

describe('leave and attendance correction API contracts', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('uses the guardian-scoped session and leave lifecycle endpoints', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 9 } })

    await leaveCorrectionApi.getGuardianSessions(7)
    await leaveCorrectionApi.submitLeave(31, 7, '发烧就医')
    await leaveCorrectionApi.withdrawLeave(9)

    expect(get).toHaveBeenCalledWith('/guardian/students/7/sessions')
    expect(post).toHaveBeenCalledWith('/leave-requests', {
      sessionId: 31,
      studentId: 7,
      reason: '发烧就医',
    })
    expect(post).toHaveBeenCalledWith('/leave-requests/9/withdraw')
  })

  it('preserves server-side leave filters and review decisions', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })
    const put = vi.spyOn(http, 'put').mockResolvedValue({ data: { id: 9 } })

    await leaveCorrectionApi.getLeaveRequests({
      offeringId: 12,
      status: 'PENDING',
    })
    await leaveCorrectionApi.reviewLeave(9, 'APPROVED', '材料已核验')

    expect(get).toHaveBeenCalledWith('/leave-requests', {
      params: { offeringId: 12, status: 'PENDING' },
    })
    expect(put).toHaveBeenCalledWith('/leave-requests/9/review', {
      decision: 'APPROVED',
      remark: '材料已核验',
    })
  })

  it('uses the correction request, approval, cancellation, and revision paths', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(http, 'post').mockResolvedValue({ data: { id: 18 } })
    const put = vi.spyOn(http, 'put').mockResolvedValue({ data: { id: 18 } })

    await leaveCorrectionApi.requestCorrection({
      sessionId: 31,
      studentId: 7,
      requestedStatus: 'LEAVE',
      requestedRemark: '已核验病假',
      reason: '家长补交材料',
    })
    await leaveCorrectionApi.reviewCorrection(18, 'APPROVED', '同意修订')
    await leaveCorrectionApi.cancelCorrection(18)
    await leaveCorrectionApi.getRevisions(22)

    expect(post).toHaveBeenCalledWith('/attendance-corrections', {
      sessionId: 31,
      studentId: 7,
      requestedStatus: 'LEAVE',
      requestedRemark: '已核验病假',
      reason: '家长补交材料',
    })
    expect(put).toHaveBeenCalledWith('/attendance-corrections/18/review', {
      decision: 'APPROVED',
      remark: '同意修订',
    })
    expect(post).toHaveBeenCalledWith('/attendance-corrections/18/cancel')
    expect(get).toHaveBeenCalledWith('/attendance/22/revisions')
  })
})
