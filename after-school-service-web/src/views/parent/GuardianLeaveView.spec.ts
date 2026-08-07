// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus/es/components/message-box/style/css', () => ({}))

import type {
  GuardianLeaveSession,
  GuardianStudentSummary,
  LeaveRequest,
} from '@/api/leaveCorrections'

import GuardianLeaveView from './GuardianLeaveView.vue'

const {
  getGuardianSessionsMock,
  getGuardianStudentsMock,
  getLeaveRequestsMock,
  routerReplaceMock,
  submitLeaveMock,
  withdrawLeaveMock,
} = vi.hoisted(() => ({
  getGuardianSessionsMock: vi.fn(),
  getGuardianStudentsMock: vi.fn(),
  getLeaveRequestsMock: vi.fn(),
  routerReplaceMock: vi.fn(),
  submitLeaveMock: vi.fn(),
  withdrawLeaveMock: vi.fn(),
}))

vi.mock('@/api/leaveCorrections', () => ({
  leaveCorrectionApi: {
    getGuardianSessions: getGuardianSessionsMock,
    getGuardianStudents: getGuardianStudentsMock,
    getLeaveRequests: getLeaveRequestsMock,
    submitLeave: submitLeaveMock,
    withdrawLeave: withdrawLeaveMock,
  },
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ query: {} }),
    useRouter: () => ({ replace: routerReplaceMock }),
  }
})

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
      const value = (event.target as HTMLSelectElement).value
      const numericValue = Number(value)
      this.$emit(
        'update:modelValue',
        value === '' ? '' : Number.isNaN(numericValue) ? value : numericValue,
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

const DialogStub = defineComponent({
  props: {
    modelValue: Boolean,
  },
  template: `
    <section v-if="modelValue" class="test-dialog">
      <slot />
      <slot name="footer" />
    </section>
  `,
})

const InputStub = defineComponent({
  props: {
    modelValue: {
      type: String,
      default: '',
    },
    type: String,
  },
  emits: ['update:modelValue'],
  template: `
    <textarea
      v-if="type === 'textarea'"
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)"
    />
    <input
      v-else
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)"
    />
  `,
})

const AlertStub = defineComponent({
  props: {
    title: String,
  },
  template: '<div class="test-alert">{{ title }}</div>',
})

const TableStub = defineComponent({
  props: {
    data: {
      type: Array,
      default: () => [],
    },
  },
  template: '<div class="test-table">{{ data.map((item) => item.id).join(",") }}</div>',
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

function student(id: number, fullName: string): GuardianStudentSummary {
  return {
    id,
    schoolId: 1,
    schoolName: '示范学校',
    className: `${id}班`,
    grade: id,
    studentNo: `S${id}`,
    fullName,
  }
}

function session(id: number, courseName: string): GuardianLeaveSession {
  return {
    id,
    schoolId: 1,
    schoolName: '示范学校',
    offeringId: id,
    offeringCode: `O${id}`,
    courseName,
    sessionDate: '2026-09-01',
    startTime: '16:00:00',
    endTime: '17:00:00',
    classroom: '综合教室',
    status: 'SCHEDULED',
    activeLeaveRequestId: null,
    activeLeaveStatus: null,
    activeLeaveReason: null,
  }
}

function leaveRequest(id: number, status: 'PENDING' | 'APPROVED'): LeaveRequest {
  return {
    id,
    studentId: 1,
    status,
  } as LeaveRequest
}

function mountView() {
  return mount(GuardianLeaveView, {
    global: {
      directives: {
        loading: () => undefined,
      },
      stubs: {
        ElAlert: AlertStub,
        ElButton: ButtonStub,
        ElDialog: DialogStub,
        ElForm: defineComponent({ template: '<form><slot /></form>' }),
        ElFormItem: defineComponent({ template: '<label><slot /></label>' }),
        ElInput: InputStub,
        ElOption: OptionStub,
        ElSelect: SelectStub,
        ElTable: TableStub,
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

describe('GuardianLeaveView student context', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    getGuardianStudentsMock
      .mockReset()
      .mockResolvedValue([
        student(1, '学生 A'),
        student(2, '学生 B'),
      ])
    getGuardianSessionsMock.mockReset()
    getLeaveRequestsMock.mockReset().mockResolvedValue([])
    submitLeaveMock.mockReset().mockResolvedValue({})
    withdrawLeaveMock.mockReset().mockResolvedValue({})
    routerReplaceMock.mockReset().mockResolvedValue(undefined)
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  })

  it('keeps only the latest session response, loading state, and error', async () => {
    const aSessions = deferred<GuardianLeaveSession[]>()
    const bSessions = deferred<GuardianLeaveSession[]>()
    getGuardianSessionsMock.mockImplementation((studentId: number) =>
      studentId === 1 ? aSessions.promise : bSessions.promise,
    )

    const wrapper = mountView()
    await flushPromises()
    expect(getGuardianSessionsMock).toHaveBeenCalledWith(1)

    await wrapper.find('select.student-select').setValue('2')
    await nextTick()
    expect(getGuardianSessionsMock).toHaveBeenCalledWith(2)

    aSessions.reject(new Error('学生 A 旧请求失败'))
    await flushPromises()
    expect(wrapper.find('.test-page-header button').attributes('data-loading')).toBe(
      'true',
    )
    expect(wrapper.find('.test-alert').exists()).toBe(false)

    bSessions.resolve([session(202, 'B 的足球课')])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的足球课')
    expect(wrapper.text()).not.toContain('学生 A 旧请求失败')
    expect(wrapper.find('.test-page-header button').attributes('data-loading')).toBe(
      'false',
    )
    wrapper.unmount()
  })

  it('does not let a late successful response replace the selected student sessions', async () => {
    const aSessions = deferred<GuardianLeaveSession[]>()
    const bSessions = deferred<GuardianLeaveSession[]>()
    getGuardianSessionsMock.mockImplementation((studentId: number) =>
      studentId === 1 ? aSessions.promise : bSessions.promise,
    )

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('select.student-select').setValue('2')
    await nextTick()

    bSessions.resolve([session(202, 'B 的足球课')])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的足球课')

    aSessions.resolve([session(101, 'A 的美术课')])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的足球课')
    expect(wrapper.text()).not.toContain('A 的美术课')
    wrapper.unmount()
  })

  it('rejects a leave submission after its frozen student context changes', async () => {
    getGuardianSessionsMock.mockImplementation((studentId: number) =>
      Promise.resolve([
        session(studentId * 100, studentId === 1 ? 'A 的美术课' : 'B 的足球课'),
      ]),
    )
    const wrapper = mountView()
    await flushPromises()

    await buttonByText(wrapper, '申请请假').trigger('click')
    await wrapper.find('textarea').setValue('家中有事')
    await wrapper.find('select.student-select').setValue('2')
    await nextTick()
    await buttonByText(wrapper, '提交请假申请').trigger('click')
    await flushPromises()

    expect(submitLeaveMock).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith(
      '学生或课次信息已变化，请重新发起请假。',
    )
    expect(wrapper.find('.test-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps only the newest leave-status response', async () => {
    getGuardianSessionsMock.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()

    const pendingRows = deferred<LeaveRequest[]>()
    const approvedRows = deferred<LeaveRequest[]>()
    getLeaveRequestsMock.mockReset()
    getLeaveRequestsMock
      .mockReturnValueOnce(pendingRows.promise)
      .mockReturnValueOnce(approvedRows.promise)

    await wrapper.find('select.status-filter').setValue('PENDING')
    await nextTick()
    await wrapper.find('select.status-filter').setValue('APPROVED')
    await nextTick()

    approvedRows.resolve([leaveRequest(902, 'APPROVED')])
    await flushPromises()
    expect(wrapper.text()).toContain('902')

    pendingRows.resolve([leaveRequest(901, 'PENDING')])
    await flushPromises()
    expect(wrapper.text()).toContain('902')
    expect(wrapper.text()).not.toContain('901')
    wrapper.unmount()
  })
})
