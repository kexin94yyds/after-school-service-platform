<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { computed, onMounted, reactive, ref, watch } from 'vue'

import {
  academicApi,
  type AcademicTerm,
  type PlanStatus,
  type SchoolRoom,
  type ServicePlan,
} from '@/api/academic'
import { courseApi } from '@/api/courses'
import {
  ApiClientError,
  getErrorMessage,
  toApiClientError,
} from '@/api/http'
import { organizationApi } from '@/api/organization'
import type {
  Course,
  CourseInput,
  CourseOffering,
  CourseOfferingInput,
  OfferingStatus,
  Teacher,
} from '@/api/types'
import EntityCrudPanel from '@/components/EntityCrudPanel.vue'
import type {
  EntityColumn,
  EntityField,
  FormValues,
} from '@/components/entity-crud'
import PageHeader from '@/components/PageHeader.vue'
import { useLongFormGuard } from '@/composables/useLongFormGuard'
import { useSessionStore } from '@/stores/session'
import {
  formNullableString,
  formNumber,
  formString,
} from '@/utils/forms'
import {
  formatDate,
  formatTime,
  statusLabel,
  statusTagType,
  weekdayLabel,
} from '@/utils/format'

type ResourceKey = 'courses' | 'offerings'

interface OfferingFormState {
  courseId: number | null
  teacherId: number | null
  offeringCode: string
  termId: number | null
  planId: number | null
  roomId: number | null
  term: string
  classroom: string
  weekDay: number | null
  startTime: string
  endTime: string
  startDate: string
  endDate: string
  enrollmentStart: string
  enrollmentEnd: string
  capacity: number | null
  status: OfferingStatus
}

const session = useSessionStore()
const activeTab = ref<ResourceKey>('courses')
const courses = ref<Course[]>([])
const offerings = ref<CourseOffering[]>([])
const teachers = ref<Teacher[]>([])
const terms = ref<AcademicTerm[]>([])
const servicePlans = ref<ServicePlan[]>([])
const rooms = ref<SchoolRoom[]>([])
const loading = reactive<Record<ResourceKey, boolean>>({
  courses: false,
  offerings: false,
})
const errors = reactive<Record<ResourceKey, string>>({
  courses: '',
  offerings: '',
})
const supportLoading = ref(false)
const supportError = ref('')
const academicLoading = ref(false)
const academicError = ref('')
const offeringQuery = ref('')
const offeringStatusFilter = ref<OfferingStatus | ''>('')
const offeringPage = ref(1)
const offeringPageSize = ref(10)
const offeringDialogVisible = ref(false)
const editingOffering = ref<CourseOffering | null>(null)
const offeringSaving = ref(false)
const offeringDialogError = ref('')
const offeringFieldErrors = ref<Record<string, string>>({})
const importInput = ref<HTMLInputElement>()
const importingCourses = ref(false)
const offeringForm = reactive<OfferingFormState>(emptyOfferingForm())

async function handleCourseImport(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importingCourses.value = true
  try {
    const result = await courseApi.importCourses(file)
    ElMessage.success(
      `课程导入完成：新增 ${result.createdCount} 条，更新 ${result.updatedCount} 条`,
    )
    await loadCourses()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '课程导入失败。'))
  } finally {
    importingCourses.value = false
  }
}
const {
  beforeClose: beforeOfferingDialogClose,
  captureBaseline: captureOfferingBaseline,
  requestClose: requestOfferingDialogClose,
} = useLongFormGuard({
  visible: offeringDialogVisible,
  saving: offeringSaving,
  snapshot: () => ({ ...offeringForm }),
})

const courseColumns: EntityColumn[] = [
  { key: 'courseCode', label: '课程编码', minWidth: 130 },
  { key: 'courseName', label: '课程名称', minWidth: 160 },
  { key: 'category', label: '课程类别', minWidth: 120 },
  {
    key: 'targetGradeMin',
    label: '适用年级',
    minWidth: 110,
    formatter: (value, row) =>
      String(value) + '-' + String((row as Course).targetGradeMax),
  },
  { key: 'defaultCapacity', label: '默认容量', minWidth: 100 },
  { key: 'status', label: '状态', formatter: statusLabel, minWidth: 90 },
]

const courseFields: EntityField[] = [
  { key: 'courseCode', label: '课程编码', kind: 'text', required: true },
  { key: 'courseName', label: '课程名称', kind: 'text', required: true },
  { key: 'category', label: '课程类别', kind: 'text', required: true },
  { key: 'description', label: '课程介绍', kind: 'textarea' },
  { key: 'targetGradeMin', label: '最低年级', kind: 'number', required: true, min: 1, max: 12 },
  { key: 'targetGradeMax', label: '最高年级', kind: 'number', required: true, min: 1, max: 12 },
  { key: 'defaultCapacity', label: '默认容量', kind: 'number', required: true, min: 1 },
  {
    key: 'status',
    label: '课程状态',
    kind: 'select',
    required: true,
    defaultValue: 'DRAFT',
    options: [
      { label: '草稿', value: 'DRAFT' },
      { label: '启用', value: 'ACTIVE' },
      { label: '停用', value: 'INACTIVE' },
    ],
  },
]

const filteredOfferings = computed(() => {
  const keyword = offeringQuery.value.trim().toLocaleLowerCase()
  return offerings.value.filter((offering) => {
    if (
      offeringStatusFilter.value &&
      offering.status !== offeringStatusFilter.value
    ) {
      return false
    }
    if (!keyword) return true
    return [
      offering.offeringCode,
      offering.courseName,
      offering.teacherName,
      offering.termName,
      offering.termCode,
      offering.term,
      offering.planName,
      offering.planCode,
      offering.roomName,
      offering.roomCode,
      offering.classroom,
    ].some((value) => value?.toLocaleLowerCase().includes(keyword))
  })
})

const paginatedOfferings = computed(() => {
  const start = (offeringPage.value - 1) * offeringPageSize.value
  return filteredOfferings.value.slice(
    start,
    start + offeringPageSize.value,
  )
})

const selectedTerm = computed(() =>
  terms.value.find((term) => term.id === offeringForm.termId),
)

const selectedPlan = computed(() =>
  servicePlans.value.find((plan) => plan.id === offeringForm.planId),
)

const selectedRoom = computed(() =>
  rooms.value.find((room) => room.id === offeringForm.roomId),
)

const availablePlans = computed(() => {
  if (offeringForm.termId === null) return []
  const priority: Record<PlanStatus, number> = {
    ACTIVE: 0,
    FILED: 1,
    SUBMITTED: 2,
    DRAFT: 3,
    RETURNED: 4,
    CLOSED: 5,
    ARCHIVED: 6,
  }
  return [...servicePlans.value]
    .filter((plan) => plan.termId === offeringForm.termId)
    .sort((left, right) => priority[left.status] - priority[right.status])
})

const availableStatuses = computed<Array<{
  label: string
  value: OfferingStatus
}>>(() => {
  if (editingOffering.value === null) {
    return [{ value: 'DRAFT', label: statusLabel('DRAFT') }]
  }
  const status = editingOffering.value?.status ?? 'DRAFT'
  const targets: Record<OfferingStatus, OfferingStatus[]> = {
    DRAFT: ['DRAFT', 'PUBLISHED', 'CANCELED'],
    PUBLISHED: ['PUBLISHED', 'CLOSED', 'CANCELED'],
    CLOSED: ['CLOSED', 'FINISHED', 'CANCELED'],
    FINISHED: ['FINISHED'],
    CANCELED: ['CANCELED'],
  }
  return targets[status].map((value) => ({
    value,
    label: statusLabel(value),
  }))
})

const editingLegacyOffering = computed(
  () =>
    editingOffering.value !== null &&
    editingOffering.value.termId === null &&
    editingOffering.value.planId === null &&
    editingOffering.value.roomId === null,
)

function emptyOfferingForm(): OfferingFormState {
  return {
    courseId: null,
    teacherId: null,
    offeringCode: '',
    termId: null,
    planId: null,
    roomId: null,
    term: '',
    classroom: '',
    weekDay: 1,
    startTime: '',
    endTime: '',
    startDate: '',
    endDate: '',
    enrollmentStart: '',
    enrollmentEnd: '',
    capacity: 30,
    status: 'DRAFT',
  }
}

function planStatusLabel(status: PlanStatus): string {
  const labels: Record<PlanStatus, string> = {
    DRAFT: '草稿',
    SUBMITTED: '待确认',
    FILED: '已确认',
    RETURNED: '已退回',
    ACTIVE: '执行中',
    CLOSED: '已关闭',
    ARCHIVED: '已归档',
  }
  return labels[status]
}

function courseOptionLabel(course: Course): string {
  return course.courseName + '（' + course.courseCode + '）'
}

function teacherOptionLabel(teacher: Teacher): string {
  return teacher.fullName + '（' + teacher.teacherNo + '）'
}

function termOptionLabel(term: AcademicTerm): string {
  return term.termName + '（' + statusLabel(term.status) + '）'
}

function planOptionLabel(plan: ServicePlan): string {
  return plan.planName + '（' + planStatusLabel(plan.status) + '）'
}

function roomOptionLabel(room: SchoolRoom): string {
  return room.roomName + '（' + String(room.capacity) + ' 人）'
}

function retainedTermLabel(offering: CourseOffering): string {
  return '保留：' + (offering.termName || offering.termCode || offering.term)
}

function retainedPlanLabel(offering: CourseOffering): string {
  return (
    '保留：' +
    (offering.planName ||
      offering.planCode ||
      '计划 #' + String(offering.planId))
  )
}

function retainedRoomLabel(offering: CourseOffering): string {
  return '保留：' + (offering.roomName || offering.classroom)
}

function planForOffering(offering: CourseOffering): ServicePlan | undefined {
  return servicePlans.value.find((plan) => plan.id === offering.planId)
}

function roomForOffering(offering: CourseOffering): SchoolRoom | undefined {
  return rooms.value.find((room) => room.id === offering.roomId)
}

function planStatusText(offering: CourseOffering): string {
  const plan = planForOffering(offering)
  return plan
    ? planStatusLabel(plan.status)
    : offering.planId === null
      ? '未关联'
      : '状态未加载'
}

function roomCapacityText(offering: CourseOffering): string {
  const room = roomForOffering(offering)
  return room
    ? '容量 ' + String(room.capacity) + ' 人'
    : offering.roomId === null
      ? '旧文本'
      : '容量未加载'
}

function termDisplay(offering: CourseOffering): string {
  return offering.termName || offering.termCode || offering.term
}

function planDisplay(offering: CourseOffering): string {
  return offering.planName || offering.planCode || '未关联计划'
}

function roomDisplay(offering: CourseOffering): string {
  return offering.roomName || offering.classroom
}

function normalizeFieldErrors(
  source: Record<string, string | string[]>,
): Record<string, string> {
  return Object.fromEntries(
    Object.entries(source).map(([key, value]) => [
      key,
      Array.isArray(value) ? value.join('；') : value,
    ]),
  )
}

function clearFieldError(key: string): void {
  if (!offeringFieldErrors.value[key]) return
  const next = { ...offeringFieldErrors.value }
  delete next[key]
  offeringFieldErrors.value = next
}

function schoolId(): number {
  const id = session.user?.schoolId
  if (!id) throw new ApiClientError('当前教务管理员账号未绑定学校。')
  return id
}

async function loadCourses(): Promise<void> {
  loading.courses = true
  errors.courses = ''
  try {
    courses.value = await courseApi.getCourses()
  } catch (error) {
    errors.courses = getErrorMessage(error, '课程列表加载失败。')
  } finally {
    loading.courses = false
  }
}

async function loadOfferings(): Promise<void> {
  loading.offerings = true
  errors.offerings = ''
  try {
    offerings.value = await courseApi.getOfferings()
  } catch (error) {
    errors.offerings = getErrorMessage(error, '开班列表加载失败。')
  } finally {
    loading.offerings = false
  }
}

async function loadTeachers(): Promise<void> {
  supportLoading.value = true
  supportError.value = ''
  try {
    teachers.value = await organizationApi.getTeachers()
  } catch (error) {
    supportError.value = getErrorMessage(error, '教师列表加载失败。')
  } finally {
    supportLoading.value = false
  }
}

async function loadAcademicResources(): Promise<void> {
  academicLoading.value = true
  academicError.value = ''
  const [termResult, planResult, roomResult] = await Promise.allSettled([
    academicApi.getTerms(),
    academicApi.getServicePlans(),
    academicApi.getRooms(),
  ])
  const messages: string[] = []
  if (termResult.status === 'fulfilled') {
    terms.value = termResult.value
  } else {
    terms.value = []
    messages.push(getErrorMessage(termResult.reason, '学期加载失败。'))
  }
  if (planResult.status === 'fulfilled') {
    servicePlans.value = planResult.value
  } else {
    servicePlans.value = []
    messages.push(getErrorMessage(planResult.reason, '服务计划加载失败。'))
  }
  if (roomResult.status === 'fulfilled') {
    rooms.value = roomResult.value
  } else {
    rooms.value = []
    messages.push(getErrorMessage(roomResult.reason, '教室资源加载失败。'))
  }
  academicError.value = messages.join(' ')
  academicLoading.value = false
}

async function reloadOfferingData(): Promise<void> {
  await Promise.all([
    loadOfferings(),
    loadTeachers(),
    loadAcademicResources(),
  ])
}

async function saveCourse(values: FormValues, id: number | null): Promise<void> {
  const minimumGrade = formNumber(values, 'targetGradeMin')
  const maximumGrade = formNumber(values, 'targetGradeMax')
  if (minimumGrade > maximumGrade) {
    throw new ApiClientError('最低年级不能高于最高年级。', {
      fieldErrors: {
        targetGradeMax: '最高年级应不低于最低年级',
      },
    })
  }
  const payload: CourseInput = {
    schoolId: schoolId(),
    courseCode: formString(values, 'courseCode'),
    courseName: formString(values, 'courseName'),
    category: formString(values, 'category'),
    description: formNullableString(values, 'description'),
    targetGradeMin: minimumGrade,
    targetGradeMax: maximumGrade,
    defaultCapacity: formNumber(values, 'defaultCapacity'),
    status: formString(values, 'status') as CourseInput['status'],
  }
  if (id === null) await courseApi.createCourse(payload)
  else await courseApi.updateCourse({ id, ...payload })
  await loadCourses()
}

function openCreateOffering(): void {
  const defaultCourse = courses.value.find((course) => course.status === 'ACTIVE')
  const defaultTeacher = teachers.value.find(
    (teacher) => teacher.status === 'ACTIVE',
  )
  const defaultTerm =
    terms.value.find((term) => term.status === 'ACTIVE') ??
    terms.value.find((term) => term.status === 'DRAFT')
  editingOffering.value = null
  Object.assign(offeringForm, emptyOfferingForm(), {
    courseId: defaultCourse?.id ?? null,
    teacherId: defaultTeacher?.id ?? null,
    capacity: defaultCourse?.defaultCapacity ?? 30,
    termId: defaultTerm?.id ?? null,
    term: defaultTerm?.termCode ?? '',
    startDate: defaultTerm?.startDate ?? '',
    endDate: defaultTerm?.endDate ?? '',
  })
  offeringDialogError.value = ''
  offeringFieldErrors.value = {}
  captureOfferingBaseline()
  offeringDialogVisible.value = true
}

function openEditOffering(offering: CourseOffering): void {
  editingOffering.value = offering
  Object.assign(offeringForm, {
    courseId: offering.courseId,
    teacherId: offering.teacherId,
    offeringCode: offering.offeringCode,
    termId: offering.termId,
    planId: offering.planId,
    roomId: offering.roomId,
    term: offering.term,
    classroom: offering.classroom,
    weekDay: offering.weekDay,
    startTime: offering.startTime,
    endTime: offering.endTime,
    startDate: offering.startDate,
    endDate: offering.endDate,
    enrollmentStart: offering.enrollmentStart,
    enrollmentEnd: offering.enrollmentEnd,
    capacity: offering.capacity,
    status: offering.status,
  } satisfies OfferingFormState)
  offeringDialogError.value = ''
  offeringFieldErrors.value = {}
  captureOfferingBaseline()
  offeringDialogVisible.value = true
}

function changeTerm(termId: number | null): void {
  offeringForm.termId = termId
  offeringForm.planId = null
  offeringForm.roomId = null
  offeringForm.classroom = ''
  const term = terms.value.find((item) => item.id === termId)
  if (term) {
    offeringForm.term = term.termCode
    if (!offeringForm.startDate) offeringForm.startDate = term.startDate
    if (!offeringForm.endDate) offeringForm.endDate = term.endDate
  }
  clearFieldError('termId')
  clearFieldError('planId')
  clearFieldError('roomId')
}

function changePlan(planId: number | null): void {
  offeringForm.planId = planId
  offeringForm.roomId = null
  offeringForm.classroom = ''
  clearFieldError('planId')
  clearFieldError('roomId')
}

function changeRoom(roomId: number | null): void {
  offeringForm.roomId = roomId
  const room = rooms.value.find((item) => item.id === roomId)
  offeringForm.classroom = room?.roomName ?? ''
  clearFieldError('roomId')
}

function changeCourse(courseId: number | null): void {
  offeringForm.courseId = courseId
  if (editingOffering.value === null) {
    const course = courses.value.find((item) => item.id === courseId)
    if (course) offeringForm.capacity = course.defaultCapacity
  }
  clearFieldError('courseId')
}

function isPublishing(): boolean {
  return (
    offeringForm.status === 'PUBLISHED' &&
    editingOffering.value?.status !== 'PUBLISHED'
  )
}

function isLifecycleOnly(): boolean {
  return ['CLOSED', 'FINISHED', 'CANCELED'].includes(offeringForm.status)
}

function validateOffering(): boolean {
  const fieldErrors: Record<string, string> = {}
  if (offeringForm.courseId === null) fieldErrors.courseId = '请选择课程'
  if (offeringForm.teacherId === null) {
    fieldErrors.teacherId = '请选择任课教师'
  }
  if (!offeringForm.offeringCode.trim()) {
    fieldErrors.offeringCode = '请填写开班编码'
  }
  if (offeringForm.weekDay === null) fieldErrors.weekDay = '请选择上课星期'
  if (!offeringForm.startTime) fieldErrors.startTime = '请选择开始时间'
  if (!offeringForm.endTime) fieldErrors.endTime = '请选择结束时间'
  if (!offeringForm.startDate) fieldErrors.startDate = '请选择开课日期'
  if (!offeringForm.endDate) fieldErrors.endDate = '请选择结课日期'
  if (!offeringForm.enrollmentStart) {
    fieldErrors.enrollmentStart = '请选择报名开始时间'
  }
  if (!offeringForm.enrollmentEnd) {
    fieldErrors.enrollmentEnd = '请选择报名截止时间'
  }
  if (offeringForm.capacity === null || offeringForm.capacity <= 0) {
    fieldErrors.capacity = '报名容量必须大于 0'
  }
  if (
    offeringForm.startTime &&
    offeringForm.endTime &&
    offeringForm.startTime >= offeringForm.endTime
  ) {
    fieldErrors.endTime = '结束时间必须晚于开始时间'
  }
  if (
    offeringForm.startDate &&
    offeringForm.endDate &&
    offeringForm.startDate > offeringForm.endDate
  ) {
    fieldErrors.endDate = '结课日期不能早于开课日期'
  }
  if (
    offeringForm.enrollmentStart &&
    offeringForm.enrollmentEnd &&
    offeringForm.enrollmentStart >= offeringForm.enrollmentEnd
  ) {
    fieldErrors.enrollmentEnd = '报名截止时间必须晚于报名开始时间'
  }
  if (
    editingOffering.value &&
    offeringForm.status !== 'CANCELED' &&
    offeringForm.capacity !== null &&
    offeringForm.capacity < editingOffering.value.enrolledCount
  ) {
    fieldErrors.capacity =
      '不能低于当前已报名人数 ' +
      String(editingOffering.value.enrolledCount)
  }

  const term = selectedTerm.value
  const plan = selectedPlan.value
  const room = selectedRoom.value
  const retainingLifecycleTerm =
    isLifecycleOnly() &&
    editingOffering.value?.termId === offeringForm.termId
  const retainingLifecyclePlan =
    isLifecycleOnly() &&
    editingOffering.value?.planId === offeringForm.planId
  const retainingLifecycleRoom =
    isLifecycleOnly() &&
    editingOffering.value?.roomId === offeringForm.roomId
  if (offeringForm.termId !== null) {
    if (!term && !retainingLifecycleTerm) {
      fieldErrors.termId = '关联学期未加载，请刷新标准资源'
    } else if (term) {
      if (
        !isLifecycleOnly() &&
        ['CLOSED', 'ARCHIVED'].includes(term.status)
      ) {
        fieldErrors.termId = '已关闭或归档学期不能新建或调整开班'
      }
      if (
        offeringForm.startDate &&
        offeringForm.endDate &&
        (offeringForm.startDate < term.startDate ||
          offeringForm.endDate > term.endDate)
      ) {
        fieldErrors.startDate = '授课周期必须位于所选学期范围内'
        fieldErrors.endDate = '授课周期必须位于所选学期范围内'
      }
    }
  } else if (!offeringForm.term.trim()) {
    fieldErrors.term = '请选择标准学期或填写旧学期文本'
  }

  if (offeringForm.planId !== null) {
    if (!plan && !retainingLifecyclePlan) {
      fieldErrors.planId = '关联计划未加载，请刷新标准资源'
    } else if (plan && plan.termId !== offeringForm.termId) {
      fieldErrors.planId = '服务计划与所选学期不一致'
    } else if (
      plan &&
      offeringForm.status === 'PUBLISHED' &&
      !['FILED', 'ACTIVE'].includes(plan.status)
    ) {
      fieldErrors.planId = '发布只能使用已确认或执行中的开课计划'
    }
  }

  if (offeringForm.roomId !== null) {
    if (!room && !retainingLifecycleRoom) {
      fieldErrors.roomId = '关联教室未加载，请刷新标准资源'
    } else if (room && !isLifecycleOnly() && room.status !== 'ACTIVE') {
      fieldErrors.roomId = '只能使用启用中的教室'
    } else if (
      room &&
      !isLifecycleOnly() &&
      offeringForm.capacity !== null &&
      room.capacity < offeringForm.capacity
    ) {
      fieldErrors.roomId =
        '教室容量为 ' + String(room.capacity) + ' 人，低于开班容量'
    }
  } else if (!offeringForm.classroom.trim()) {
    if (offeringForm.planId !== null) {
      fieldErrors.roomId = '关联计划后请选择标准教室'
    } else {
      fieldErrors.classroom = '请选择标准教室或填写旧教室文本'
    }
  }

  if (isPublishing()) {
    if (offeringForm.termId === null) {
      fieldErrors.termId = '发布前必须关联标准学期'
    }
    if (offeringForm.planId === null) {
      fieldErrors.planId = '发布前必须选择已确认或执行中的计划'
    }
    if (offeringForm.roomId === null) {
      fieldErrors.roomId = '发布前必须选择启用中的标准教室'
    }
  }

  offeringFieldErrors.value = fieldErrors
  offeringDialogError.value =
    Object.keys(fieldErrors).length > 0 ? '请检查表单中标记的内容。' : ''
  return Object.keys(fieldErrors).length === 0
}

function transitionMessage(): string | null {
  const current = editingOffering.value?.status
  if (offeringForm.status === 'PUBLISHED' && current !== 'PUBLISHED') {
    return '发布后家长可看到该开班，课程、教师、计划与教室资源将进入业务约束。确认发布？'
  }
  if (offeringForm.status === 'CANCELED' && current !== 'CANCELED') {
    return '取消开班会同步取消有效报名和未来待上课课次，确认继续？'
  }
  if (offeringForm.status === 'CLOSED' && current !== 'CLOSED') {
    return '截止后将不再接受新报名，确认关闭报名？'
  }
  if (offeringForm.status === 'FINISHED' && current !== 'FINISHED') {
    return '结束开班前需要完成全部课次，确认继续？'
  }
  return null
}

async function saveOffering(): Promise<void> {
  if (!validateOffering()) return
  const confirmation = transitionMessage()
  if (confirmation) {
    try {
      await ElMessageBox.confirm(confirmation, '确认状态变更', {
        confirmButtonText:
          offeringForm.status === 'PUBLISHED' ? '确认发布' : '确认继续',
        cancelButtonText: '取消',
        type: offeringForm.status === 'CANCELED' ? 'error' : 'warning',
      })
    } catch {
      return
    }
  }

  const term = selectedTerm.value
  const room = selectedRoom.value
  const payload: CourseOfferingInput = {
    schoolId: schoolId(),
    courseId: Number(offeringForm.courseId),
    teacherId: Number(offeringForm.teacherId),
    offeringCode: offeringForm.offeringCode.trim(),
    term: term?.termCode ?? offeringForm.term.trim(),
    termId: offeringForm.termId,
    planId: offeringForm.planId,
    roomId: offeringForm.roomId,
    weekDay: Number(offeringForm.weekDay),
    startTime: offeringForm.startTime,
    endTime: offeringForm.endTime,
    startDate: offeringForm.startDate,
    endDate: offeringForm.endDate,
    enrollmentStart: offeringForm.enrollmentStart,
    enrollmentEnd: offeringForm.enrollmentEnd,
    capacity: Number(offeringForm.capacity),
    classroom: room?.roomName ?? offeringForm.classroom.trim(),
    status: offeringForm.status,
  }

  offeringSaving.value = true
  offeringDialogError.value = ''
  try {
    if (editingOffering.value === null) {
      await courseApi.createOffering(payload)
    } else {
      await courseApi.updateOffering({
        id: editingOffering.value.id,
        ...payload,
      })
    }
    captureOfferingBaseline()
    offeringDialogVisible.value = false
    ElMessage.success(
      editingOffering.value === null ? '开班已创建' : '开班已保存',
    )
    await loadOfferings()
  } catch (error) {
    const apiError = toApiClientError(error, '开班保存失败。')
    offeringDialogError.value = apiError.message
    offeringFieldErrors.value = {
      ...offeringFieldErrors.value,
      ...normalizeFieldErrors(apiError.fieldErrors),
    }
  } finally {
    offeringSaving.value = false
  }
}

watch(
  [offeringQuery, offeringStatusFilter, () => offerings.value.length],
  () => {
    offeringPage.value = 1
  },
)

watch([offeringPageSize, () => filteredOfferings.value.length], () => {
  const maxPage = Math.max(
    1,
    Math.ceil(filteredOfferings.value.length / offeringPageSize.value),
  )
  if (offeringPage.value > maxPage) offeringPage.value = maxPage
})

onMounted(() => {
  void Promise.all([
    loadCourses(),
    loadOfferings(),
    loadTeachers(),
    loadAcademicResources(),
  ])
})
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="课程供给"
      title="课程与开班"
      description="先维护课程目录，再设置任课教师、上课周期、报名窗口和容量。"
    >
      <template #actions>
        <el-button @click="courseApi.downloadImportTemplate()">下载导入模板</el-button>
        <el-button @click="courseApi.downloadCourses()">导出课程 Excel</el-button>
        <el-button
          type="primary"
          :loading="importingCourses"
          @click="importInput?.click()"
        >
          批量导入课程
        </el-button>
        <input
          ref="importInput"
          hidden
          type="file"
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          @change="handleCourseImport"
        />
      </template>
    </PageHeader>
    <el-tabs v-model="activeTab" class="business-tabs">
      <el-tab-pane label="课程目录" name="courses">
        <EntityCrudPanel
          title="课程"
          description="课程定义适用年级、类别和默认容量。"
          :rows="courses"
          :columns="courseColumns"
          :fields="courseFields"
          :loading="loading.courses"
          :error="errors.courses"
          :save="saveCourse"
          @retry="loadCourses"
        />
      </el-tab-pane>
      <el-tab-pane label="开班安排" name="offerings">
        <section class="entity-panel offering-panel">
          <header class="entity-panel-header">
            <div>
              <h2>开班</h2>
              <p>发布前必须关联标准学期、已确认计划和启用教室，服务端会再次强制校验。</p>
            </div>
            <div class="offering-toolbar">
              <el-select
                v-model="offeringStatusFilter"
                clearable
                placeholder="全部状态"
                aria-label="筛选开班状态"
              >
                <el-option label="草稿" value="DRAFT" />
                <el-option label="已发布" value="PUBLISHED" />
                <el-option label="已截止" value="CLOSED" />
                <el-option label="已结束" value="FINISHED" />
                <el-option label="已取消" value="CANCELED" />
              </el-select>
              <el-input
                v-model="offeringQuery"
                clearable
                placeholder="搜索课程、教师或资源"
                aria-label="搜索开班"
              />
              <el-button
                type="primary"
                :disabled="courses.length === 0 || teachers.length === 0"
                @click="openCreateOffering"
              >
                新增开班
              </el-button>
            </div>
          </header>

          <el-alert
            v-if="errors.offerings"
            class="embedded-alert"
            :title="errors.offerings"
            type="error"
            :closable="false"
            show-icon
          >
            <template #default>
              <el-button text type="primary" @click="loadOfferings">
                重新加载
              </el-button>
            </template>
          </el-alert>
          <el-alert
            v-if="supportError || academicError"
            class="embedded-alert"
            :title="supportError || academicError"
            type="warning"
            :closable="false"
            show-icon
          >
            <template #default>
              <span v-if="supportError && academicError">{{ academicError }}</span>
              <el-button text type="primary" @click="reloadOfferingData">
                重新加载关联资源
              </el-button>
            </template>
          </el-alert>

          <el-table
            v-loading="loading.offerings || supportLoading || academicLoading"
            :data="paginatedOfferings"
            row-key="id"
            class="entity-table"
            table-layout="auto"
          >
            <el-table-column label="开班 / 课程" min-width="190">
              <template #default="{ row }">
                <div class="primary-cell">
                  <strong>{{ (row as CourseOffering).courseName }}</strong>
                  <span>{{ (row as CourseOffering).offeringCode }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="学期" min-width="165">
              <template #default="{ row }">
                <div class="resource-cell">
                  <strong>{{ termDisplay(row as CourseOffering) }}</strong>
                  <span
                    :class="{ legacy: (row as CourseOffering).termId === null }"
                  >
                    {{
                      (row as CourseOffering).termId === null
                        ? '旧文本'
                        : (row as CourseOffering).termCode || '标准学期'
                    }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="服务计划" min-width="175">
              <template #default="{ row }">
                <div class="resource-cell">
                  <strong>{{ planDisplay(row as CourseOffering) }}</strong>
                  <el-tag
                    v-if="planForOffering(row as CourseOffering)"
                    effect="plain"
                    size="small"
                    :type="
                      statusTagType(
                        planForOffering(row as CourseOffering)?.status,
                      )
                    "
                  >
                    {{ planStatusText(row as CourseOffering) }}
                  </el-tag>
                  <span v-else>{{ planStatusText(row as CourseOffering) }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="标准教室" min-width="150">
              <template #default="{ row }">
                <div class="resource-cell">
                  <strong>{{ roomDisplay(row as CourseOffering) }}</strong>
                  <span
                    :class="{ legacy: (row as CourseOffering).roomId === null }"
                  >
                    {{ roomCapacityText(row as CourseOffering) }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="教师 / 时间" min-width="180">
              <template #default="{ row }">
                <div class="secondary-cell">
                  <strong>{{ (row as CourseOffering).teacherName }}</strong>
                  <span>
                    {{ weekdayLabel((row as CourseOffering).weekDay) }}
                    {{ formatTime((row as CourseOffering).startTime) }}-{{
                      formatTime((row as CourseOffering).endTime)
                    }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="报名" width="100">
              <template #default="{ row }">
                {{ (row as CourseOffering).enrolledCount }} /
                {{ (row as CourseOffering).capacity }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">
                <el-tag
                  effect="plain"
                  size="small"
                  :type="statusTagType((row as CourseOffering).status)"
                >
                  {{ statusLabel((row as CourseOffering).status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button
                  text
                  type="primary"
                  @click="openEditOffering(row as CourseOffering)"
                >
                  编辑
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <strong>暂无符合条件的开班</strong>
                <span>先确认课程、教师和标准资源，再新增开班安排。</span>
              </div>
            </template>
          </el-table>

          <el-pagination
            v-if="filteredOfferings.length > offeringPageSize"
            v-model:current-page="offeringPage"
            v-model:page-size="offeringPageSize"
            class="entity-pagination"
            layout="total, sizes, prev, pager, next"
            :page-sizes="[10, 20, 50]"
            :total="filteredOfferings.length"
          />
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="offeringDialogVisible"
      :title="editingOffering === null ? '新增开班' : '编辑开班'"
      width="min(920px, calc(100vw - 32px))"
      destroy-on-close
      :close-on-click-modal="false"
      :close-on-press-escape="!offeringSaving"
      :show-close="!offeringSaving"
      :before-close="beforeOfferingDialogClose"
    >
      <el-alert
        v-if="offeringDialogError"
        :title="offeringDialogError"
        type="error"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-alert
        v-if="academicError"
        title="部分标准资源尚未加载。旧记录仍可保留文本，但新发布必须先恢复学期、计划和教室数据。"
        type="warning"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-alert
        v-if="editingLegacyOffering"
        title="这是旧版文本开班，可原样保存；再次发布前需补齐标准学期、已确认计划和启用教室。"
        type="info"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-alert
        v-else-if="editingOffering && editingOffering.status !== 'DRAFT'"
        title="已有报名或课次后，课程、教师与排课资源会被服务端锁定；此时应只推进允许的状态。"
        type="info"
        :closable="false"
        show-icon
        class="dialog-alert"
      />

      <el-form
        label-position="top"
        :disabled="offeringSaving"
        @submit.prevent="saveOffering"
      >
        <section class="resource-chain" aria-labelledby="resource-chain-title">
          <header>
            <div>
              <span>标准资源链</span>
              <strong id="resource-chain-title">学期 → 开课计划 → 教室</strong>
            </div>
            <small>发布时三项必须完整且状态有效</small>
          </header>
          <div class="chain-grid">
            <article
              class="chain-step"
              :class="{ complete: offeringForm.termId !== null }"
            >
              <span class="chain-label">第一步 · 学期</span>
              <el-form-item
                label="统一学期"
                :error="offeringFieldErrors.termId"
              >
                <el-select
                  :model-value="offeringForm.termId"
                  clearable
                  filterable
                  placeholder="选择标准学期"
                  :loading="academicLoading"
                  @update:model-value="changeTerm($event as number | null)"
                >
                  <el-option
                    v-for="term in terms"
                    :key="term.id"
                    :label="termOptionLabel(term)"
                    :value="term.id"
                    :disabled="['CLOSED', 'ARCHIVED'].includes(term.status)"
                  />
                  <el-option
                    v-if="
                      editingOffering?.termId &&
                      !terms.some(
                        (term) => term.id === editingOffering?.termId,
                      )
                    "
                    :label="retainedTermLabel(editingOffering)"
                    :value="editingOffering.termId"
                    disabled
                  />
                </el-select>
              </el-form-item>
              <div v-if="selectedTerm" class="resource-meta">
                <span>
                  {{ formatDate(selectedTerm.startDate) }} —
                  {{ formatDate(selectedTerm.endDate) }}
                </span>
                <el-tag
                  effect="plain"
                  size="small"
                  :type="statusTagType(selectedTerm.status)"
                >
                  {{ statusLabel(selectedTerm.status) }}
                </el-tag>
              </div>
              <div v-else class="legacy-field">
                <span>旧学期文本</span>
                <el-input
                  v-model="offeringForm.term"
                  maxlength="32"
                  placeholder="例如 2026 秋季"
                  :class="{ 'is-error': offeringFieldErrors.term }"
                  @input="clearFieldError('term')"
                />
                <small v-if="offeringFieldErrors.term">
                  {{ offeringFieldErrors.term }}
                </small>
              </div>
            </article>

            <article
              class="chain-step"
              :class="{ complete: offeringForm.planId !== null }"
            >
              <span class="chain-label">第二步 · 计划</span>
              <el-form-item
                label="服务计划"
                :error="offeringFieldErrors.planId"
              >
                <el-select
                  :model-value="offeringForm.planId"
                  clearable
                  filterable
                  placeholder="选择本学期计划"
                  :loading="academicLoading"
                  :disabled="
                    offeringSaving || offeringForm.termId === null
                  "
                  @update:model-value="changePlan($event as number | null)"
                >
                  <el-option
                    v-for="plan in availablePlans"
                    :key="plan.id"
                    :label="planOptionLabel(plan)"
                    :value="plan.id"
                  />
                  <el-option
                    v-if="
                      editingOffering?.planId &&
                      !availablePlans.some(
                        (plan) => plan.id === editingOffering?.planId,
                      )
                    "
                    :label="retainedPlanLabel(editingOffering)"
                    :value="editingOffering.planId"
                    disabled
                  />
                </el-select>
              </el-form-item>
              <div v-if="selectedPlan" class="resource-meta">
                <span>{{ selectedPlan.planCode }}</span>
                <el-tag
                  effect="plain"
                  size="small"
                  :type="statusTagType(selectedPlan.status)"
                >
                  {{ planStatusLabel(selectedPlan.status) }}
                </el-tag>
              </div>
              <p v-else class="chain-hint">
                {{
                  offeringForm.termId === null
                    ? '先选择标准学期'
                    : '草稿可暂不关联；发布只接受已确认或执行中计划'
                }}
              </p>
            </article>

            <article
              class="chain-step"
              :class="{ complete: offeringForm.roomId !== null }"
            >
              <span class="chain-label">第三步 · 教室</span>
              <el-form-item
                label="标准教室"
                :error="offeringFieldErrors.roomId"
              >
                <el-select
                  :model-value="offeringForm.roomId"
                  clearable
                  filterable
                  placeholder="选择标准教室"
                  :loading="academicLoading"
                  :disabled="
                    offeringSaving ||
                    (offeringForm.planId === null &&
                      offeringForm.roomId === null)
                  "
                  @update:model-value="changeRoom($event as number | null)"
                >
                  <el-option
                    v-for="room in rooms"
                    :key="room.id"
                    :label="roomOptionLabel(room)"
                    :value="room.id"
                    :disabled="room.status !== 'ACTIVE'"
                  />
                  <el-option
                    v-if="
                      editingOffering?.roomId &&
                      !rooms.some(
                        (room) => room.id === editingOffering?.roomId,
                      )
                    "
                    :label="retainedRoomLabel(editingOffering)"
                    :value="editingOffering.roomId"
                    disabled
                  />
                </el-select>
              </el-form-item>
              <div v-if="selectedRoom" class="resource-meta">
                <span>
                  {{ selectedRoom.roomCode }} · 容量
                  {{ selectedRoom.capacity }} 人
                </span>
                <el-tag
                  effect="plain"
                  size="small"
                  :type="statusTagType(selectedRoom.status)"
                >
                  {{ statusLabel(selectedRoom.status) }}
                </el-tag>
              </div>
              <div
                v-else-if="offeringForm.planId === null"
                class="legacy-field"
              >
                <span>旧教室文本</span>
                <el-input
                  v-model="offeringForm.classroom"
                  maxlength="64"
                  placeholder="旧记录可填写文本教室"
                  :class="{ 'is-error': offeringFieldErrors.classroom }"
                  @input="clearFieldError('classroom')"
                />
                <small v-if="offeringFieldErrors.classroom">
                  {{ offeringFieldErrors.classroom }}
                </small>
              </div>
              <p v-else class="chain-hint">
                显示容量；最终还会校验时段占用
              </p>
            </article>
          </div>
        </section>

        <div class="offering-form-grid">
          <el-form-item
            label="课程"
            required
            :error="offeringFieldErrors.courseId"
          >
            <el-select
              :model-value="offeringForm.courseId"
              filterable
              placeholder="请选择课程"
              @update:model-value="changeCourse($event as number | null)"
            >
              <el-option
                v-for="course in courses"
                :key="course.id"
                :label="courseOptionLabel(course)"
                :value="course.id"
                :disabled="course.status !== 'ACTIVE'"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            label="任课教师"
            required
            :error="offeringFieldErrors.teacherId"
          >
            <el-select
              v-model="offeringForm.teacherId"
              filterable
              placeholder="请选择任课教师"
              @change="clearFieldError('teacherId')"
            >
              <el-option
                v-for="teacher in teachers"
                :key="teacher.id"
                :label="teacherOptionLabel(teacher)"
                :value="teacher.id"
                :disabled="teacher.status !== 'ACTIVE'"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            label="开班编码"
            required
            :error="offeringFieldErrors.offeringCode"
          >
            <el-input
              v-model="offeringForm.offeringCode"
              maxlength="32"
              placeholder="例如 ROBOT-2026-F01"
              @input="clearFieldError('offeringCode')"
            />
          </el-form-item>
          <el-form-item label="开班状态" required>
            <el-select v-model="offeringForm.status">
              <el-option
                v-for="option in availableStatuses"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            label="上课星期"
            required
            :error="offeringFieldErrors.weekDay"
          >
            <el-select
              v-model="offeringForm.weekDay"
              @change="clearFieldError('weekDay')"
            >
              <el-option
                v-for="day in 7"
                :key="day"
                :label="weekdayLabel(day)"
                :value="day"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            label="报名容量"
            required
            :error="offeringFieldErrors.capacity"
          >
            <el-input-number
              v-model="offeringForm.capacity"
              :min="1"
              :max="500"
              controls-position="right"
              @change="clearFieldError('capacity')"
            />
          </el-form-item>
          <el-form-item
            label="开始时间"
            required
            :error="offeringFieldErrors.startTime"
          >
            <el-time-picker
              v-model="offeringForm.startTime"
              value-format="HH:mm:ss"
              format="HH:mm"
              placeholder="选择开始时间"
              @change="clearFieldError('startTime')"
            />
          </el-form-item>
          <el-form-item
            label="结束时间"
            required
            :error="offeringFieldErrors.endTime"
          >
            <el-time-picker
              v-model="offeringForm.endTime"
              value-format="HH:mm:ss"
              format="HH:mm"
              placeholder="选择结束时间"
              @change="clearFieldError('endTime')"
            />
          </el-form-item>
          <el-form-item
            label="开课日期"
            required
            :error="offeringFieldErrors.startDate"
          >
            <el-date-picker
              v-model="offeringForm.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择开课日期"
              @change="clearFieldError('startDate')"
            />
          </el-form-item>
          <el-form-item
            label="结课日期"
            required
            :error="offeringFieldErrors.endDate"
          >
            <el-date-picker
              v-model="offeringForm.endDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择结课日期"
              @change="clearFieldError('endDate')"
            />
          </el-form-item>
          <el-form-item
            label="报名开始"
            required
            :error="offeringFieldErrors.enrollmentStart"
          >
            <el-date-picker
              v-model="offeringForm.enrollmentStart"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择报名开始时间"
              @change="clearFieldError('enrollmentStart')"
            />
          </el-form-item>
          <el-form-item
            label="报名截止"
            required
            :error="offeringFieldErrors.enrollmentEnd"
          >
            <el-date-picker
              v-model="offeringForm.enrollmentEnd"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择报名截止时间"
              @change="clearFieldError('enrollmentEnd')"
            />
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button
          :disabled="offeringSaving"
          @click="requestOfferingDialogClose"
        >
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="offeringSaving"
          @click="saveOffering"
        >
          {{ offeringForm.status === 'PUBLISHED' ? '保存并发布' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.offering-toolbar {
  display: grid;
  width: min(100%, 640px);
  grid-template-columns: 135px minmax(210px, 1fr) auto;
  gap: 9px;
}

.embedded-alert {
  margin: 16px 20px 0;
}

.primary-cell,
.secondary-cell,
.resource-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
}

.primary-cell strong,
.secondary-cell strong,
.resource-cell strong {
  max-width: 100%;
  overflow: hidden;
  color: var(--ink);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.primary-cell span,
.secondary-cell span,
.resource-cell > span {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.5;
}

.resource-cell > span.legacy {
  color: var(--warning);
}

.resource-chain {
  margin-bottom: 22px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--paper);
}

.resource-chain > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-soft);
}

.resource-chain > header div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.resource-chain > header span {
  color: var(--accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.09em;
}

.resource-chain > header strong {
  font-size: 14px;
}

.resource-chain > header small {
  color: var(--muted);
  font-size: 10px;
}

.chain-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.chain-step {
  position: relative;
  min-width: 0;
  min-height: 178px;
  padding: 17px 18px;
}

.chain-step + .chain-step {
  border-left: 1px solid var(--line);
}

.chain-step + .chain-step::before {
  position: absolute;
  top: 25px;
  left: -6px;
  z-index: 1;
  width: 10px;
  height: 10px;
  border-top: 1px solid var(--line-strong);
  border-right: 1px solid var(--line-strong);
  background: var(--paper);
  content: "";
  transform: rotate(45deg);
}

.chain-step.complete {
  box-shadow: inset 0 3px 0 var(--accent);
}

.chain-label {
  display: block;
  margin-bottom: 13px;
  color: var(--accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.07em;
}

.chain-step :deep(.el-form-item) {
  margin-bottom: 10px;
}

.chain-step :deep(.el-select) {
  width: 100%;
}

.resource-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.5;
}

.legacy-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.legacy-field > span {
  color: var(--warning);
  font-size: 9px;
  font-weight: 700;
}

.legacy-field > small {
  color: var(--danger);
  font-size: 10px;
}

.legacy-field .is-error :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--danger) inset;
}

.chain-hint {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.55;
}

.offering-form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 16px;
}

.offering-form-grid :deep(.el-select),
.offering-form-grid :deep(.el-input-number),
.offering-form-grid :deep(.el-date-editor),
.offering-form-grid :deep(.el-time-picker) {
  width: 100%;
}

@media (max-width: 980px) {
  .entity-panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .offering-toolbar {
    width: 100%;
  }

  .offering-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .offering-toolbar {
    grid-template-columns: 1fr;
  }

  .resource-chain > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .chain-grid {
    grid-template-columns: 1fr;
  }

  .chain-step + .chain-step {
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .chain-step + .chain-step::before {
    top: -6px;
    left: 24px;
    transform: rotate(135deg);
  }

  .offering-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
