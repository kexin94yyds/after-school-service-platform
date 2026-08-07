// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SessionUser } from '@/api/types'
import { useSessionStore } from '@/stores/session'

import RoleLayout from './RoleLayout.vue'
import { LOGOUT_SERVER_UNCONFIRMED_MESSAGE } from './logoutFlow'

const {
  logoutMock,
  messageSuccessMock,
  messageWarningMock,
  navigationFailure,
  routerReplaceMock,
} = vi.hoisted(() => ({
  logoutMock: vi.fn(),
  messageSuccessMock: vi.fn(),
  messageWarningMock: vi.fn(),
  navigationFailure: { type: 'aborted' },
  routerReplaceMock: vi.fn(),
}))

vi.mock('@/api/auth', () => ({
  changePassword: vi.fn(),
  fetchCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: logoutMock,
}))

vi.mock('@/components/ChangePasswordDialog.vue', () => ({
  default: {
    name: 'ChangePasswordDialog',
    template: '<div />',
  },
}))

vi.mock('element-plus', () => {
  return {
    ElButton: {
      name: 'ElButton',
      props: {
        loading: Boolean,
      },
      emits: ['click'],
      template:
        '<button type="button" :disabled="loading" @click="$emit(\'click\')"><slot /></button>',
    },
    ElMessage: {
      success: messageSuccessMock,
      warning: messageWarningMock,
    },
  }
})

vi.mock('element-plus/es/components/message/style/css', () => ({}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    isNavigationFailure: (result: unknown) => result === navigationFailure,
    useRoute: () => ({ meta: { title: '课次与考勤' } }),
    useRouter: () => ({ replace: routerReplaceMock }),
  }
})

const user: SessionUser = {
  id: 7,
  username: 'teacher01',
  displayName: '王老师',
  role: 'TEACHER',
  schoolId: 2,
}

const ButtonStub = defineComponent({
  props: {
    loading: Boolean,
  },
  emits: ['click'],
  template:
    '<button type="button" :disabled="loading" @click="$emit(\'click\')"><slot /></button>',
})

const RouterLinkStub = defineComponent({
  template: '<a><slot /></a>',
})

const RouterViewStub = defineComponent({
  template: '<div data-testid="protected-content">受保护课次内容</div>',
})

function mountLayout() {
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  session.user = user
  session.state = 'authenticated'

  const wrapper = mount(RoleLayout, {
    global: {
      plugins: [pinia],
      stubs: {
        ChangePasswordDialog: true,
        ElButton: ButtonStub,
        RouterLink: RouterLinkStub,
        RouterView: RouterViewStub,
      },
    },
  })

  return { session, wrapper }
}

function findButtonByText(
  wrapper: ReturnType<typeof mountLayout>['wrapper'],
  text: string,
) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text() === text)
  if (!button) throw new Error('Button not found: ' + text)
  return button
}

describe('RoleLayout logged-out safety state', () => {
  beforeEach(() => {
    logoutMock.mockReset()
    logoutMock.mockResolvedValue(undefined)
    messageSuccessMock.mockReset()
    messageWarningMock.mockReset()
    routerReplaceMock.mockReset()
    routerReplaceMock.mockResolvedValue(undefined)
  })

  it('unmounts protected content as soon as the local session is cleared', async () => {
    const { session, wrapper } = mountLayout()
    expect(wrapper.find('[data-testid="protected-content"]').exists()).toBe(true)

    session.expire()
    await nextTick()

    expect(wrapper.find('[data-testid="protected-content"]').exists()).toBe(false)
    expect(wrapper.find('.role-shell').exists()).toBe(false)
    expect(wrapper.find('.logged-out-page').exists()).toBe(true)
    expect(wrapper.text()).toContain('受保护内容已关闭')
    expect(wrapper.text()).toContain('前往登录页')
  })

  it('keeps an actionable fallback when logout and route navigation both fail', async () => {
    logoutMock.mockRejectedValue(new Error('offline'))
    routerReplaceMock.mockResolvedValue(navigationFailure)
    const { wrapper } = mountLayout()

    await findButtonByText(wrapper, '退出登录').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="protected-content"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录页暂时未能打开，请重试')
    expect(messageWarningMock).toHaveBeenCalledWith(
      LOGOUT_SERVER_UNCONFIRMED_MESSAGE,
    )
    expect(routerReplaceMock).toHaveBeenCalledWith({ name: 'login' })

    routerReplaceMock.mockRejectedValueOnce(new Error('guard failed'))
    await findButtonByText(wrapper, '前往登录页').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('登录页暂时未能打开，请重试')
    expect(routerReplaceMock).toHaveBeenCalledTimes(2)
  })
})
