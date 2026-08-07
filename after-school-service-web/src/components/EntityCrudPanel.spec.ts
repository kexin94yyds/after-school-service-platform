// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus/es/components/message-box/style/css', () => ({}))

import EntityCrudPanel from './EntityCrudPanel.vue'

const { routeGuards } = vi.hoisted(() => ({
  routeGuards: [] as Array<() => unknown>,
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    onBeforeRouteLeave: (guard: () => unknown) => routeGuards.push(guard),
  }
})

const DialogStub = defineComponent({
  props: {
    modelValue: Boolean,
    beforeClose: Function,
    closeOnPressEscape: Boolean,
    showClose: Boolean,
  },
  template: `
    <section v-if="modelValue" class="test-dialog">
      <slot />
      <slot name="footer" />
    </section>
  `,
})

const FormStub = defineComponent({
  props: {
    disabled: Boolean,
  },
  template: '<form :data-disabled="disabled ? \'true\' : \'false\'"><slot /></form>',
})

const FormItemStub = defineComponent({
  props: {
    label: String,
  },
  template: '<label><span>{{ label }}</span><slot /></label>',
})

const InputStub = defineComponent({
  props: {
    disabled: Boolean,
    modelValue: {
      type: [String, Number],
      default: '',
    },
  },
  emits: ['update:modelValue'],
  template: `
    <input
      :value="modelValue"
      :disabled="disabled"
      @input="$emit('update:modelValue', $event.target.value)"
    />
  `,
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
      @click="$emit('click')"
    >
      <slot />
    </button>
  `,
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

function mountPanel(save = vi.fn().mockResolvedValue(undefined)) {
  return mount(EntityCrudPanel, {
    props: {
      title: '教师',
      description: '维护教师档案',
      rows: [],
      columns: [{ key: 'fullName', label: '姓名' }],
      fields: [
        {
          key: 'fullName',
          label: '姓名',
          kind: 'text',
          required: true,
          placeholder: '姓名',
        },
      ],
      loading: false,
      error: '',
      save,
    },
    global: {
      directives: {
        loading: () => undefined,
      },
      stubs: {
        ElAlert: true,
        ElButton: ButtonStub,
        ElDatePicker: true,
        ElDialog: DialogStub,
        ElForm: FormStub,
        ElFormItem: FormItemStub,
        ElInput: InputStub,
        ElInputNumber: true,
        ElOption: true,
        ElPagination: true,
        ElSelect: true,
        ElSwitch: true,
        ElTable: true,
        ElTableColumn: true,
        ElTag: true,
        ElTimePicker: true,
      },
    },
  })
}

function buttonByText(
  wrapper: ReturnType<typeof mountPanel>,
  text: string,
) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text() === text)
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

describe('EntityCrudPanel long form protection', () => {
  beforeEach(() => {
    routeGuards.length = 0
    vi.restoreAllMocks()
    vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  })

  it('keeps dirty values when discard is canceled and closes after confirmation', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const wrapper = mountPanel()

    await buttonByText(wrapper, '新增').trigger('click')
    await wrapper.find('input[placeholder="姓名"]').setValue('张老师')

    confirm.mockRejectedValueOnce(new Error('cancel'))
    await buttonByText(wrapper, '取消').trigger('click')
    await flushPromises()
    expect(wrapper.find('.test-dialog').exists()).toBe(true)

    confirm.mockResolvedValueOnce('confirm' as never)
    await buttonByText(wrapper, '取消').trigger('click')
    await flushPromises()
    expect(wrapper.find('.test-dialog').exists()).toBe(false)
    wrapper.unmount()
  })

  it('protects a dirty form from route and browser navigation', async () => {
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValue(new Error('cancel'))
    const wrapper = mountPanel()

    await buttonByText(wrapper, '新增').trigger('click')
    await wrapper.find('input[placeholder="姓名"]').setValue('张老师')

    const beforeUnload = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(beforeUnload)
    expect(beforeUnload.defaultPrevented).toBe(true)

    const routeGuard = routeGuards.at(-1)
    expect(routeGuard).toBeDefined()
    await expect(routeGuard?.()).resolves.toBe(false)
    expect(confirm).toHaveBeenCalledOnce()
    expect(wrapper.find('.test-dialog').exists()).toBe(true)
    wrapper.unmount()
  })

  it('disables close controls and rejects close callbacks while saving', async () => {
    const saveRequest = deferred<void>()
    const save = vi.fn(() => saveRequest.promise)
    const wrapper = mountPanel(save)

    await buttonByText(wrapper, '新增').trigger('click')
    await wrapper.find('input[placeholder="姓名"]').setValue('张老师')
    await buttonByText(wrapper, '保存').trigger('click')
    await nextTick()

    const dialog = wrapper.findComponent(DialogStub)
    expect(dialog.props('showClose')).toBe(false)
    expect(dialog.props('closeOnPressEscape')).toBe(false)
    expect(buttonByText(wrapper, '取消').attributes('disabled')).toBeDefined()
    expect(wrapper.find('form').attributes('data-disabled')).toBe('true')
    expect(wrapper.find('input[placeholder="姓名"]').attributes('disabled')).toBeDefined()

    const done = vi.fn()
    await dialog.props('beforeClose')(done)
    expect(done).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('正在保存，请稍候。')

    saveRequest.resolve()
    await flushPromises()
    expect(save).toHaveBeenCalledWith({ fullName: '张老师' }, null)
    expect(wrapper.find('.test-dialog').exists()).toBe(false)
    wrapper.unmount()
  })
})
