import { describe, expect, it, vi } from 'vitest'

import type { AttendanceRecord } from '@/api/types'

import {
  attendanceDraftSignature,
  guardUnsavedAttendanceChange,
  shouldConfirmUnsavedAttendanceChange,
} from './attendanceDraft'

function attendanceRecord(
  overrides: Partial<AttendanceRecord> = {},
): AttendanceRecord {
  return {
    studentId: 1,
    studentName: '学生一',
    studentNo: 'S001',
    className: '一班',
    status: 'PRESENT',
    remark: null,
    recordedAt: null,
    recordedByName: null,
    ...overrides,
  }
}

describe('attendance draft signature', () => {
  it('tracks editable attendance values without depending on row order or metadata', () => {
    const baseline = attendanceDraftSignature([
      attendanceRecord({ studentId: 2, status: 'LATE', remark: '校车晚点' }),
      attendanceRecord({ studentId: 1, recordedAt: '2026-07-31T08:00:00Z' }),
    ])
    const equivalent = attendanceDraftSignature([
      attendanceRecord({ studentId: 1, recordedByName: '王老师' }),
      attendanceRecord({ studentId: 2, status: 'LATE', remark: '校车晚点' }),
    ])

    expect(equivalent).toBe(baseline)
    expect(
      attendanceDraftSignature([
        attendanceRecord({ studentId: 1 }),
        attendanceRecord({ studentId: 2, status: 'LATE', remark: '校车晚点 ' }),
      ]),
    ).toBe(baseline)
    expect(
      attendanceDraftSignature([
        attendanceRecord({ studentId: 1 }),
        attendanceRecord({ studentId: 2, status: 'ABSENT', remark: '校车晚点' }),
      ]),
    ).not.toBe(baseline)
  })
})

describe('unsaved attendance guard', () => {
  it('keeps the current selection when discard is canceled', async () => {
    let selectedSessionId = 11
    const confirmDiscard = vi.fn().mockRejectedValue(new Error('cancel'))

    const continued = await guardUnsavedAttendanceChange(
      true,
      confirmDiscard,
      () => {
        selectedSessionId = 12
      },
    )

    expect(continued).toBe(false)
    expect(selectedSessionId).toBe(11)
  })

  it('changes selection only after discard is confirmed', async () => {
    let selectedSessionId = 11
    const confirmDiscard = vi.fn().mockResolvedValue('confirm')

    const continued = await guardUnsavedAttendanceChange(
      true,
      confirmDiscard,
      () => {
        selectedSessionId = 12
      },
    )

    expect(continued).toBe(true)
    expect(confirmDiscard).toHaveBeenCalledOnce()
    expect(selectedSessionId).toBe(12)
  })

  it('continues immediately when the draft is clean', async () => {
    const confirmDiscard = vi.fn()
    const continueAction = vi.fn()

    await guardUnsavedAttendanceChange(
      false,
      confirmDiscard,
      continueAction,
    )

    expect(confirmDiscard).not.toHaveBeenCalled()
    expect(continueAction).toHaveBeenCalledOnce()
  })

  it('allows a guest session to leave despite a dirty protected-page draft', async () => {
    const confirmDiscard = vi.fn()
    const continueAction = vi.fn()

    const continued = await guardUnsavedAttendanceChange(
      shouldConfirmUnsavedAttendanceChange(true, true),
      confirmDiscard,
      continueAction,
    )

    expect(continued).toBe(true)
    expect(confirmDiscard).not.toHaveBeenCalled()
    expect(continueAction).toHaveBeenCalledOnce()
  })
})
