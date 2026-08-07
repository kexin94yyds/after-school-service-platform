import type { AttendanceRecord } from '@/api/types'

export const UNSAVED_ATTENDANCE_MESSAGE =
  '当前课次有未保存的考勤修改。放弃修改并继续吗？'

type AttendanceDraftRecord = Pick<
  AttendanceRecord,
  'studentId' | 'status' | 'remark'
>

/**
 * Builds a stable signature from the fields submitted by the attendance form.
 * Server-only metadata and row ordering do not make an attendance draft dirty.
 */
export function attendanceDraftSignature(
  records: readonly AttendanceDraftRecord[],
): string {
  return JSON.stringify(
    records
      .map((record) => ({
        studentId: record.studentId,
        status: record.status,
        remark: record.remark?.trim() || null,
      }))
      .sort((left, right) => left.studentId - right.studentId),
  )
}

/**
 * A protected page must not retain a guest user just to resolve a local draft.
 * This is especially important after logout has already cleared local identity.
 */
export function shouldConfirmUnsavedAttendanceChange(
  isDirty: boolean,
  isGuest: boolean,
): boolean {
  return isDirty && !isGuest
}

/**
 * Runs a state transition only after the user accepts discarding a dirty draft.
 * A rejected or dismissed confirmation leaves the caller's state untouched.
 */
export async function guardUnsavedAttendanceChange(
  isDirty: boolean,
  confirmDiscard: () => Promise<unknown>,
  continueAction: () => void | Promise<void>,
): Promise<boolean> {
  if (isDirty) {
    try {
      await confirmDiscard()
    } catch {
      return false
    }
  }

  await continueAction()
  return true
}
