// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus/es/components/message-box/style/css', () => ({}))

import type {
  Enrollment,
  GuardianOffering,
  GuardianStudent,
} from '@/api/types'

import GuardianEnrollmentView from './GuardianEnrollmentView.vue'

const {
  cancelMock,
  enrollMock,
  getEnrollmentsMock,
  getGuardianStudentsMock,
  getStudentAttendanceMock,
  getStudentMonthlyAttendanceMock,
  getStudentOfferingsMock,
} = vi.hoisted(() => ({
  cancelMock: vi.fn(),
  enrollMock: vi.fn(),
  getEnrollmentsMock: vi.fn(),
  getGuardianStudentsMock: vi.fn(),
  getStudentAttendanceMock: vi.fn(),
  getStudentMonthlyAttendanceMock: vi.fn(),
  getStudentOfferingsMock: vi.fn(),
}))

vi.mock('@/api/enrollments', () => ({
  enrollmentApi: {
    cancel: cancelMock,
    enroll: enrollMock,
    getEnrollments: getEnrollmentsMock,
    getGuardianStudents: getGuardianStudentsMock,
    getStudentAttendance: getStudentAttendanceMock,
    getStudentMonthlyAttendance: getStudentMonthlyAttendanceMock,
    getStudentOfferings: getStudentOfferingsMock,
  },
}))

const PageHeaderStub = defineComponent({
  template: '<header class="test-page-header"><slot name="actions" /></header>',
})

const ButtonStub = defineComponent({
  props: {
    disabled: Boolean,
    loading: Boolean,
  },
  emits: ['click'],
  template: `
    <button
      type="button"
      :disabled="disabled || loading"
      :data-loading="loading ? 'true' : 'false'"
      @click="$emit('click')"
    >
      <slot />
    </button>
  `,
})

const SelectStub = defineComponent({
  props: {
    modelValue: {
      type: [Number, String],
      default: null,
    },
  },
  emits: ['update:modelValue'],
  methods: {
    updateValue(event: Event) {
      this.$emit(
        'update:modelValue',
        Number((event.target as HTMLSelectElement).value),
      )
    },
  },
  template: `
    <select :value="modelValue" @change="updateValue">
      <slot />
    </select>
  `,
})

const OptionStub = defineComponent({
  props: {
    label: String,
    value: [Number, String],
  },
  template: '<option :value="value">{{ label }}</option>',
})

const AlertStub = defineComponent({
  props: {
    title: String,
  },
  template: '<div class="test-alert">{{ title }}</div>',
})

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

function student(id: number, fullName: string): GuardianStudent {
  return {
    id,
    schoolId: 1,
    schoolName: '示范学校',
    classId: id,
    className: `${id}班`,
    grade: id,
    studentNo: `S${id}`,
    fullName,
    status: 'ACTIVE',
    relationship: '子女',
    primaryGuardian: true,
  }
}

function offering(id: number, courseName: string): GuardianOffering {
  return {
    id,
    schoolId: 1,
    offeringCode: `O${id}`,
    term: '2026-FALL',
    courseId: id,
    courseName,
    category: '艺术',
    targetGradeMin: 1,
    targetGradeMax: 6,
    teacherName: '王老师',
    weekDay: 1,
    startTime: '16:00:00',
    endTime: '17:00:00',
    startDate: '2026-09-01',
    endDate: '2026-12-31',
    enrollmentStart: '2026-08-01T08:00:00',
    enrollmentEnd: '2026-08-31T18:00:00',
    capacity: 30,
    enrolledCount: 10,
    classroom: '美术教室',
    status: 'PUBLISHED',
    canEnroll: true,
    eligibilityCode: 'ELIGIBLE',
    eligibilityMessage: '可报名',
    enrollmentStatus: null,
  }
}

function mountView() {
  return mount(GuardianEnrollmentView, {
    global: {
      directives: {
        loading: () => undefined,
      },
      stubs: {
        ElAlert: AlertStub,
        ElButton: ButtonStub,
        ElDialog: true,
        ElDatePicker: true,
        ElOption: OptionStub,
        ElSelect: SelectStub,
        ElTable: true,
        ElTableColumn: true,
        ElTag: true,
        PageHeader: PageHeaderStub,
      },
    },
  })
}

function buttonByText(
  wrapper: ReturnType<typeof mountView>,
  text: string,
) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text().includes(text))
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

describe('GuardianEnrollmentView student context', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    getEnrollmentsMock.mockReset().mockResolvedValue([])
    getGuardianStudentsMock
      .mockReset()
      .mockResolvedValue([
        student(1, '学生 A'),
        student(2, '学生 B'),
      ])
    getStudentAttendanceMock.mockReset()
    getStudentMonthlyAttendanceMock.mockReset().mockResolvedValue({
      studentId: 1,
      month: '2026-08',
      summary: {
        totalCount: 0,
        presentCount: 0,
        lateCount: 0,
        leaveCount: 0,
        absentCount: 0,
        attendanceRate: 0,
      },
      records: [],
    })
    getStudentOfferingsMock.mockReset()
    enrollMock.mockReset().mockResolvedValue({} as Enrollment)
    cancelMock.mockReset().mockResolvedValue(undefined)
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  })

  it('keeps only the latest student response, loading state, and error', async () => {
    const aOfferings = deferred<GuardianOffering[]>()
    const aAttendance = deferred<never[]>()
    const bOfferings = deferred<GuardianOffering[]>()
    const bAttendance = deferred<never[]>()
    getStudentOfferingsMock.mockImplementation((studentId: number) =>
      studentId === 1 ? aOfferings.promise : bOfferings.promise,
    )
    getStudentAttendanceMock.mockImplementation((studentId: number) =>
      studentId === 1 ? aAttendance.promise : bAttendance.promise,
    )

    const wrapper = mountView()
    await flushPromises()
    expect(getStudentOfferingsMock).toHaveBeenCalledWith(1)

    await wrapper.find('select.student-selector').setValue('2')
    await nextTick()
    expect(getStudentOfferingsMock).toHaveBeenCalledWith(2)

    aAttendance.resolve([])
    aOfferings.reject(new Error('学生 A 旧请求失败'))
    await flushPromises()
    expect(wrapper.find('.test-page-header button').attributes('data-loading')).toBe(
      'true',
    )
    expect(wrapper.find('.test-alert').exists()).toBe(false)

    bAttendance.resolve([])
    bOfferings.resolve([offering(202, 'B 的美术课')])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的美术课')
    expect(wrapper.text()).not.toContain('学生 A 旧请求失败')
    expect(wrapper.find('.test-page-header button').attributes('data-loading')).toBe(
      'false',
    )

    await buttonByText(wrapper, '立即报名').trigger('click')
    await flushPromises()
    expect(enrollMock).toHaveBeenCalledWith(2, 202)
    wrapper.unmount()
  })

  it('does not let a late successful response replace the selected student data', async () => {
    const aOfferings = deferred<GuardianOffering[]>()
    const aAttendance = deferred<never[]>()
    const bOfferings = deferred<GuardianOffering[]>()
    const bAttendance = deferred<never[]>()
    getStudentOfferingsMock.mockImplementation((studentId: number) =>
      studentId === 1 ? aOfferings.promise : bOfferings.promise,
    )
    getStudentAttendanceMock.mockImplementation((studentId: number) =>
      studentId === 1 ? aAttendance.promise : bAttendance.promise,
    )

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('select.student-selector').setValue('2')
    await nextTick()

    bAttendance.resolve([])
    bOfferings.resolve([offering(202, 'B 的美术课')])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的美术课')

    aAttendance.resolve([])
    aOfferings.resolve([offering(101, 'A 的书法课')])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的美术课')
    expect(wrapper.text()).not.toContain('A 的书法课')
    wrapper.unmount()
  })

  it('freezes the student id when enrollment starts', async () => {
    const enrollRequest = deferred<Enrollment>()
    enrollMock.mockImplementation(() => enrollRequest.promise)
    getStudentOfferingsMock.mockImplementation((studentId: number) =>
      Promise.resolve([
        offering(
          studentId === 1 ? 101 : 202,
          studentId === 1 ? 'A 的书法课' : 'B 的美术课',
        ),
      ]),
    )
    getStudentAttendanceMock.mockResolvedValue([])

    const wrapper = mountView()
    await flushPromises()
    await buttonByText(wrapper, '立即报名').trigger('click')
    await wrapper.find('select.student-selector').setValue('2')
    await flushPromises()

    expect(enrollMock).toHaveBeenCalledWith(1, 101)
    expect(wrapper.text()).toContain('B 的美术课')

    enrollRequest.resolve({} as Enrollment)
    await flushPromises()
    expect(getStudentOfferingsMock.mock.calls.filter(([id]) => id === 1)).toHaveLength(1)
    expect(wrapper.text()).toContain('B 的美术课')
    wrapper.unmount()
  })

  it('keeps only the newest base student and enrollment response', async () => {
    getStudentOfferingsMock.mockResolvedValue([])
    getStudentAttendanceMock.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()

    const oldStudents = deferred<GuardianStudent[]>()
    const oldEnrollments = deferred<Enrollment[]>()
    const newStudents = deferred<GuardianStudent[]>()
    const newEnrollments = deferred<Enrollment[]>()
    getGuardianStudentsMock
      .mockReset()
      .mockReturnValueOnce(oldStudents.promise)
      .mockReturnValueOnce(newStudents.promise)
    getEnrollmentsMock
      .mockReset()
      .mockReturnValueOnce(oldEnrollments.promise)
      .mockReturnValueOnce(newEnrollments.promise)

    const refreshButton = wrapper.findComponent(ButtonStub)
    refreshButton.vm.$emit('click')
    await nextTick()
    refreshButton.vm.$emit('click')
    await nextTick()

    newStudents.resolve([student(2, '最新学生')])
    newEnrollments.resolve([])
    await flushPromises()
    expect(wrapper.text()).toContain('最新学生')

    oldStudents.resolve([student(1, '过期学生')])
    oldEnrollments.resolve([])
    await flushPromises()
    expect(wrapper.text()).toContain('最新学生')
    expect(wrapper.text()).not.toContain('过期学生')
    wrapper.unmount()
  })
})
