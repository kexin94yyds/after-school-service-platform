const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
  timeZone: 'Asia/Shanghai',
})

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'Asia/Shanghai',
})

export function combineDateAndTime(
  dateValue: string | null | undefined,
  timeValue: string | null | undefined,
): Date {
  const datePart = dateValue?.match(/^\d{4}-\d{2}-\d{2}/)?.[0]
  const timePart = timeValue?.match(/^\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?/)?.[0]
  if (!datePart || !timePart) return new Date(Number.NaN)
  return new Date(`${datePart}T${timePart}+08:00`)
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTimeFormatter.format(date)
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '-'
  const normalized = /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? `${value}T00:00:00`
    : value
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? value : dateFormatter.format(date)
}

export function formatTime(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '-'
}

export function formatPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '-'
  return `${value.toFixed(1)}%`
}

export function weekdayLabel(value: number): string {
  return (
    ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'][
      value - 1
    ] ?? `星期${value}`
  )
}

const statusLabels: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  CLOSED: '已截止',
  FINISHED: '已结束',
  CANCELED: '已取消',
  ENROLLED: '已报名',
  SCHEDULED: '待上课',
  COMPLETED: '已完成',
  PRESENT: '出勤',
  LATE: '迟到',
  LEAVE: '请假',
  ABSENT: '缺勤',
  SUBMITTED: '已提交',
  FILED: '已备案',
  RETURNED: '已退回',
  ARCHIVED: '已归档',
  PENDING: '待处理',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  APPLIED: '已生效',
  REVERTED: '已回退',
  OPEN: '待响应',
  ACKNOWLEDGED: '已确认',
  RECTIFYING: '整改中',
  WAITING_VERIFY: '待复核',
  CRITICAL: '紧急',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  TEACHING_DAY: '教学日',
  MAKEUP_DAY: '补课日',
  HOLIDAY: '节假日',
  SUSPENDED: '临时停课',
  OVERDUE_ATTENDANCE: '考勤逾期',
  OFFERING_NO_SESSIONS: '未生成课次',
  LOW_ATTENDANCE: '低出勤率',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  MALE: '男',
  FEMALE: '女',
  OTHER: '其他',
}

export function statusLabel(value: unknown): string {
  if (typeof value !== 'string') return value == null ? '-' : String(value)
  return statusLabels[value] ?? value
}

export type StatusTagType =
  | 'primary'
  | 'success'
  | 'warning'
  | 'danger'
  | 'info'

const statusTagTypes: Record<string, StatusTagType> = {
  ACTIVE: 'success',
  APPROVED: 'success',
  APPLIED: 'success',
  COMPLETED: 'success',
  FILED: 'success',
  FINISHED: 'success',
  PRESENT: 'success',
  PUBLISHED: 'primary',
  ENROLLED: 'primary',
  ACKNOWLEDGED: 'primary',
  RECTIFYING: 'primary',
  DRAFT: 'info',
  INACTIVE: 'info',
  CLOSED: 'info',
  ARCHIVED: 'info',
  SCHEDULED: 'info',
  PENDING: 'warning',
  SUBMITTED: 'warning',
  WAITING_VERIFY: 'warning',
  LATE: 'warning',
  LEAVE: 'warning',
  MEDIUM: 'warning',
  HIGH: 'danger',
  CRITICAL: 'danger',
  ABSENT: 'danger',
  CANCELED: 'danger',
  WITHDRAWN: 'info',
  REVERTED: 'info',
  REJECTED: 'danger',
  RETURNED: 'danger',
  OPEN: 'danger',
  RUNNING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
}

export function statusTagType(value: unknown): StatusTagType {
  return typeof value === 'string' ? (statusTagTypes[value] ?? 'info') : 'info'
}

export function nullableText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}

const reportLabels: Record<string, string> = {
  schoolCount: '接入学校',
  courseCount: '课程总数',
  offeringCount: '开班总数',
  enrollmentCount: '有效报名',
  studentCount: '覆盖学生',
  teacherCount: '参与教师',
  sessionCount: '累计课次',
  attendanceRate: '综合出勤率',
  satisfactionRate: '课程满意度',
}

export function reportMetricLabel(key: string): string {
  return reportLabels[key] ?? key
}
