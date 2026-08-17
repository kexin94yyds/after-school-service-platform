// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import ChangePasswordDialog from './ChangePasswordDialog.vue'

const { changePasswordMock } = vi.hoisted(() => ({
  changePasswordMock: vi.fn(),
}))

vi.mock('@/api/auth', () => ({
  changePassword: changePasswordMock,
}))

const DialogStub = defineComponent({
  props: {
    modelValue: Boolean,
  },
  template: `
    <section v-if="modelValue">
      <slot />
      <slot name="footer" />
    </section>
  `,
})

const FormStub = defineComponent({
  template: '<form><slot /></form>',
})

const FormItemStub = defineComponent({
  props: {
    label: String,
    error: String,
  },
  template: `
    <label>
      <span>{{ label }}</span>
      <slot />
      <span v-if="error" class="test-error">{{ error }}</span>
    </label>
  `,
})

const InputStub = defineComponent({
  props: {
    modelValue: {
      type: String,
      default: '',
    },
  },
  emits: ['update:modelValue'],
  template: `
    <input
      :value="modelValue"
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

function mountDialog() {
  return mount(ChangePasswordDialog, {
    props: {
      modelValue: true,
    },
    global: {
      stubs: {
        ElAlert: true,
        ElButton: ButtonStub,
        ElDialog: DialogStub,
        ElForm: FormStub,
        ElFormItem: FormItemStub,
        ElInput: InputStub,
      },
    },
  })
}

describe('ChangePasswordDialog', () => {
  beforeEach(() => {
    changePasswordMock.mockReset()
    changePasswordMock.mockResolvedValue(undefined)
  })

  it('blocks a new password shorter than twelve characters', async () => {
    const wrapper = mountDialog()
    const inputs = wrapper.findAll('input')

    await inputs[0].setValue('current-secret')
    await inputs[1].setValue('too-short')
    await inputs[2].setValue('too-short')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认修改')
      ?.trigger('click')

    expect(wrapper.text()).toContain('新密码至少需要 12 个字符')
    expect(changePasswordMock).not.toHaveBeenCalled()
  })

  it('submits valid credentials and requests a signed-out transition', async () => {
    const wrapper = mountDialog()
    const inputs = wrapper.findAll('input')

    await inputs[0].setValue('current-secret')
    await inputs[1].setValue('new-secret-1234')
    await inputs[2].setValue('new-secret-1234')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '确认修改')
      ?.trigger('click')
    await flushPromises()

    expect(changePasswordMock).toHaveBeenCalledWith(
      'current-secret',
      'new-secret-1234',
    )
    expect(wrapper.emitted('success')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false])
  })

  it('ignores a second submit while the password change is pending', async () => {
    let resolveChange: (() => void) | undefined
    changePasswordMock.mockImplementation(
      () => new Promise<void>((resolve) => {
        resolveChange = resolve
      }),
    )
    const wrapper = mountDialog()
    const inputs = wrapper.findAll('input')

    await inputs[0].setValue('current-secret')
    await inputs[1].setValue('new-secret-1234')
    await inputs[2].setValue('new-secret-1234')
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')

    expect(changePasswordMock).toHaveBeenCalledTimes(1)
    resolveChange?.()
    await flushPromises()
  })
})
