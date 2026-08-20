export const roleCodes = [
  'REGULATOR',
  'SCHOOL_ADMIN',
  'TEACHER',
  'GUARDIAN',
] as const

export type RoleCode = (typeof roleCodes)[number]

export interface SessionUser {
  id: number
  username: string
  displayName: string
  role: RoleCode
  schoolId: number | null
  teacherId?: number | null
  guardianId?: number | null
  capabilities?: Record<string, boolean>
}

export interface ApiEntity {
  id: number
  createdAt?: string
  updatedAt?: string
}

export type ActiveStatus = 'ACTIVE' | 'INACTIVE'
export type CourseStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE'
export type OfferingStatus =
  | 'DRAFT'
  | 'PUBLISHED'
  | 'CLOSED'
  | 'FINISHED'
  | 'CANCELED'
export type EnrollmentStatus = 'ENROLLED' | 'CANCELED'
export type SessionStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELED'
export type AttendanceStatus = 'PRESENT' | 'LATE' | 'LEAVE' | 'ABSENT'
export type Gender = 'MALE' | 'FEMALE' | 'OTHER'

export interface School extends ApiEntity {
  schoolCode: string
  schoolName: string
  districtCode: string
  address: string | null
  contactPhone: string | null
  status: ActiveStatus
}

export type SchoolInput = Omit<School, keyof ApiEntity>

export interface SchoolAdminAccount extends ApiEntity {
  schoolId: number
  username: string
  displayName: string
  mobile: string | null
  enabled: boolean
  lastLoginAt?: string | null
}

export interface SchoolAdminInput {
  username: string
  displayName: string
  mobile: string | null
  password: string | null
  enabled: boolean
}

export interface RegulatorAccount extends ApiEntity {
  username: string
  displayName: string
  mobile: string | null
  enabled: boolean
  schoolIds: number[] | string
  schoolNames: string | null
  lastLoginAt?: string | null
}

export interface RegulatorInput {
  username: string
  displayName: string
  mobile: string | null
  password: string | null
  enabled: boolean
  schoolIds: number[]
}

export interface SchoolClass extends ApiEntity {
  schoolId: number
  schoolName?: string
  className: string
  grade: number
  schoolYear: string
  status: ActiveStatus
}

export type SchoolClassInput = Omit<SchoolClass, keyof ApiEntity | 'schoolName'>

export interface Teacher extends ApiEntity {
  schoolId: number
  schoolName?: string
  userId: number
  username: string
  teacherNo: string
  fullName: string
  phone: string | null
  title: string | null
  status: ActiveStatus
  lastLoginAt?: string | null
}

export interface TeacherInput {
  schoolId: number
  teacherNo: string
  fullName: string
  username: string
  password: string | null
  phone: string | null
  title: string | null
  status: ActiveStatus
}

export interface Student extends ApiEntity {
  schoolId: number
  schoolName?: string
  classId: number
  className?: string
  grade?: number
  studentNo: string
  fullName: string
  gender: Gender | null
  dateOfBirth: string | null
  status: ActiveStatus
}

export type StudentInput = Omit<
  Student,
  keyof ApiEntity | 'schoolName' | 'className' | 'grade'
>

export interface Guardian extends ApiEntity {
  schoolId: number
  schoolName?: string
  userId: number
  username: string
  fullName: string
  mobile: string
  status: ActiveStatus
  childNames: string | null
  studentIds: number[]
  relationship: string
  primary: boolean
}

export interface GuardianInput {
  schoolId: number
  fullName: string
  mobile: string
  username: string
  password: string | null
  studentIds: number[]
  relationship: string
  primary: boolean
  status: ActiveStatus
}

export interface Course extends ApiEntity {
  schoolId: number
  schoolName?: string
  courseCode: string
  courseName: string
  category: string
  description: string | null
  targetGradeMin: number
  targetGradeMax: number
  defaultCapacity: number
  status: CourseStatus
}

export type CourseInput = Omit<Course, keyof ApiEntity | 'schoolName'>

export interface CourseOffering extends ApiEntity {
  schoolId: number
  schoolName: string
  courseId: number
  courseCode: string
  courseName: string
  category: string
  targetGradeMin: number
  targetGradeMax: number
  teacherId: number
  teacherName: string
  offeringCode: string
  term: string
  termId: number | null
  termCode: string | null
  termName: string | null
  planId: number | null
  planCode: string | null
  planName: string | null
  weekDay: number
  startTime: string
  endTime: string
  startDate: string
  endDate: string
  enrollmentStart: string
  enrollmentEnd: string
  capacity: number
  enrolledCount: number
  classroom: string
  roomId: number | null
  roomCode: string | null
  roomName: string | null
  status: OfferingStatus
  version: number
}

export interface GuardianOffering extends ApiEntity {
  schoolId: number
  offeringCode: string
  term: string
  courseId: number
  courseName: string
  category: string
  targetGradeMin: number
  targetGradeMax: number
  teacherName: string
  weekDay: number
  startTime: string
  endTime: string
  startDate: string
  endDate: string
  enrollmentStart: string
  enrollmentEnd: string
  capacity: number
  enrolledCount: number
  classroom: string
  status: OfferingStatus
  canEnroll: boolean
  eligibilityCode: string
  eligibilityMessage: string
  enrollmentStatus: EnrollmentStatus | null
}

export interface CourseOfferingInput {
  schoolId: number
  courseId: number
  teacherId: number
  offeringCode: string
  term: string
  weekDay: number
  startTime: string
  endTime: string
  startDate: string
  endDate: string
  enrollmentStart: string
  enrollmentEnd: string
  capacity: number
  classroom: string
  status: OfferingStatus
  termId?: number | null
  planId?: number | null
  roomId?: number | null
}

export interface Enrollment extends ApiEntity {
  schoolId: number
  schoolName: string
  offeringId: number
  offeringCode: string
  term: string
  courseName: string
  teacherId: number
  teacherName: string
  weekDay: number
  startTime: string
  endTime: string
  studentId: number
  studentName: string
  studentNo: string
  guardianId: number
  guardianName: string
  status: EnrollmentStatus
  enrolledAt: string
  canceledAt: string | null
  canceledBy: number | null
  canceledByName: string | null
}

export interface EnrollmentAction extends ApiEntity {
  schoolId: number
  enrollmentId: number
  offeringId: number
  offeringCode: string
  courseName: string
  studentId: number
  studentName: string
  actionType: 'ENROLL' | 'CANCEL' | 'REACTIVATE' | 'SWITCH_OUT' | 'SWITCH_IN'
  relatedEnrollmentId: number | null
  actorUserId: number
  actorName: string
  actorRole: 'SCHOOL_ADMIN' | 'GUARDIAN'
  actedAt: string
}

export interface GuardianStudent extends ApiEntity {
  schoolId: number
  schoolName: string
  classId: number
  className: string
  grade: number
  studentNo: string
  fullName: string
  status: ActiveStatus
  relationship: string
  primaryGuardian: boolean
}

export interface LessonSession extends ApiEntity {
  schoolId: number
  offeringId: number
  courseName: string
  offeringCode: string
  teacherName: string
  sessionDate: string
  startTime: string
  endTime: string
  classroom: string
  status: SessionStatus
  notes: string | null
  recordedCount: number
  attendedCount: number
}

export interface AttendanceRecord {
  id?: number | null
  sessionId?: number
  studentId: number
  studentName: string
  studentNo: string
  className: string
  status: AttendanceStatus | null
  recordedStatus?: AttendanceStatus | null
  remark: string | null
  recordedAt: string | null
  recordedByName: string | null
  leaveRequestId?: number | null
  leaveReason?: string | null
  leaveApprovedAt?: string | null
}

export interface AttendanceUpdateRecord {
  studentId: number
  status: AttendanceStatus
  remark: string | null
}

export interface GuardianAttendance extends ApiEntity {
  studentId: number
  courseName: string
  offeringCode: string
  teacherName: string
  sessionDate: string
  startTime: string
  endTime: string
  status: AttendanceStatus
  remark: string | null
  recordedAt: string
}

export interface GuardianMonthlyAttendance {
  studentId: number
  month: string
  summary: {
    totalCount: number
    presentCount: number
    lateCount: number
    leaveCount: number
    absentCount: number
    attendanceRate: number
  }
  records: GuardianAttendance[]
}

export interface ReportSchoolSummary {
  schoolId: number
  schoolName: string
  studentCount: number
  teacherCount: number
  offeringCount: number
  enrollmentCount: number
  capacity: number
}

export interface ReportCategorySummary {
  category: string
  courseCount: number
  offeringCount: number
  enrollmentCount: number
}

export interface ReportAttendanceSummary {
  status: AttendanceStatus
  count: number
  percentage: number
}

export interface ReportTeacherHours {
  teacherId: number
  teacherNo: string
  teacherName: string
  schoolName: string
  offeringCount: number
  completedSessions: number
  completedHours: number
}

export interface ReportSatisfactionSummary {
  evaluationCount: number
  averageRating: number
  satisfactionRate: number
}

export interface ReportOverview {
  schoolCount: number
  studentCount: number
  teacherCount: number
  courseCount: number
  offeringCount: number
  enrollmentCount: number
  sessionCount: number
  attendanceRate: number
  schools: ReportSchoolSummary[]
  categories: ReportCategorySummary[]
  attendance: ReportAttendanceSummary[]
  teacherHours: ReportTeacherHours[]
  satisfaction: ReportSatisfactionSummary
}
