<script setup lang="ts">
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { computed, ref } from 'vue'
import { isNavigationFailure, useRoute, useRouter } from 'vue-router'

import type { RoleCode } from '@/api/types'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import { useSessionStore } from '@/stores/session'

import { completeLogout, navigateToLoginSafely } from './logoutFlow'

interface NavigationItem {
  label: string
  to: string
  section?: string
}

interface RoleProfile {
  label: string
  shortLabel: string
  scope: string
  menu: NavigationItem[]
}

const profiles: Record<RoleCode, RoleProfile> = {
  STUDENT: {
    label: '学生',
    shortLabel: '学生',
    scope: '本人教务数据',
    menu: [
      { label: '学生工作台', to: '/student', section: '工作台' },
      { label: '学生选课', to: '/student/enrollments', section: '学习安排' },
      { label: '个人课表', to: '/student/schedule' },
      { label: '请假与考勤', to: '/student/attendance' },
      { label: '成绩与评价', to: '/student/grades' },
    ],
  },
  SCHOOL_ADMIN: {
    label: '教务管理员',
    shortLabel: '教务',
    scope: '当前学校业务数据',
    menu: [
      { label: '教务工作台', to: '/school', section: '工作台' },
      { label: '组织与人员', to: '/school/organization', section: '教务基础' },
      { label: '学期管理', to: '/school/terms' },
      { label: '学期资源', to: '/school/academic' },
      { label: '课程与开班', to: '/school/courses' },
      { label: '报名管理', to: '/school/enrollments', section: '业务执行' },
      { label: '调课管理', to: '/school/schedule-adjustments' },
      { label: '请假与纠错', to: '/school/leave-corrections' },
      { label: '整改处理', to: '/school/rectifications', section: '质量治理' },
      { label: '本校统计', to: '/school/reports' },
      { label: '成绩统计', to: '/school/grades' },
      { label: '操作审计', to: '/school/audit' },
    ],
  },
  TEACHER: {
    label: '任课教师',
    shortLabel: '教师',
    scope: '本人授课数据',
    menu: [
      { label: '教师工作台', to: '/teacher', section: '工作台' },
      { label: '课次与考勤', to: '/teacher/sessions', section: '授课执行' },
      { label: '请假与纠错', to: '/teacher/leave-corrections' },
      { label: '成绩与评价', to: '/teacher/grades' },
    ],
  },
  GUARDIAN: {
    label: '学生家长',
    shortLabel: '家长',
    scope: '已绑定学生数据',
    menu: [
      { label: '家长服务', to: '/parent', section: '工作台' },
      { label: '子女教务信息', to: '/parent/children', section: '学生服务' },
      { label: '课程评价', to: '/parent/evaluations' },
    ],
  },
}

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const loggingOut = ref(false)
const navigatingToLogin = ref(false)
const loginNavigationFailed = ref(false)
const passwordDialogVisible = ref(false)

const profile = computed(() =>
  session.role ? profiles[session.role] : profiles.SCHOOL_ADMIN,
)
const pageTitle = computed(() => route.meta.title)

function moveToLoginSafely(): Promise<boolean> {
  return navigateToLoginSafely({
    navigateToLogin: () => router.replace({ name: 'login' }),
    isNavigationFailure,
  })
}

async function signOut(): Promise<void> {
  loggingOut.value = true
  loginNavigationFailed.value = false
  try {
    const navigationSucceeded = await completeLogout({
      signOut: () => session.signOut(),
      navigateToLogin: () => router.replace({ name: 'login' }),
      isNavigationFailure,
      warn: (message) => ElMessage.warning(message),
    })
    loginNavigationFailed.value = !navigationSucceeded
  } finally {
    loggingOut.value = false
  }
}

async function retryLoginNavigation(): Promise<void> {
  navigatingToLogin.value = true
  loginNavigationFailed.value = false
  try {
    loginNavigationFailed.value = !(await moveToLoginSafely())
  } finally {
    navigatingToLogin.value = false
  }
}

async function finishPasswordChange(): Promise<void> {
  session.expire()
  ElMessage.success('密码已修改，请重新登录。')
  loginNavigationFailed.value = !(await moveToLoginSafely())
}
</script>

<template>
  <div v-if="session.isAuthenticated" class="role-shell">
    <aside class="role-sidebar">
      <RouterLink class="brand" :to="profile.menu[0]?.to ?? '/'">
        <span class="brand-mark">课</span>
        <span class="brand-copy">
          <strong>课后服务平台</strong>
          <small>教务监管 · 家校服务</small>
        </span>
      </RouterLink>

      <nav class="role-menu" aria-label="角色功能导航">
        <template
          v-for="item in profile.menu"
          :key="item.to"
        >
          <span v-if="item.section" class="role-menu-section">
            {{ item.section }}
          </span>
          <RouterLink
            :to="item.to"
            class="role-menu-link"
            exact-active-class="active"
          >
            {{ item.label }}
          </RouterLink>
        </template>
      </nav>

      <div class="scope-note">
        <span>服务端数据范围</span>
        <strong>{{ profile.scope }}</strong>
      </div>
    </aside>

    <main class="role-main">
      <header class="role-topbar">
        <div class="topbar-title">
          <span>{{ profile.shortLabel }}</span>
          <div>
            <small>2025—2026 学年</small>
            <strong>{{ pageTitle }}</strong>
          </div>
        </div>
        <div class="role-identity">
          <span class="identity-avatar">{{ profile.shortLabel }}</span>
          <div class="identity-copy">
            <strong>{{ session.user?.displayName }}</strong>
            <small>{{ profile.label }} · {{ session.user?.username }}</small>
          </div>
          <div class="identity-actions">
            <el-button @click="passwordDialogVisible = true">
              修改密码
            </el-button>
            <el-button :loading="loggingOut" @click="signOut">
              退出登录
            </el-button>
          </div>
        </div>
      </header>

      <div class="role-content">
        <RouterView />
      </div>
    </main>

    <ChangePasswordDialog
      v-model="passwordDialogVisible"
      @success="finishPasswordChange"
    />
  </div>

  <main v-else class="logged-out-page">
    <section
      class="logged-out-card"
      aria-labelledby="logged-out-title"
      aria-live="polite"
    >
      <span class="brand-mark" aria-hidden="true">课</span>
      <span class="page-kicker">安全退出</span>
      <h1 id="logged-out-title">本机已退出</h1>
      <p>
        受保护内容已关闭。{{
          loginNavigationFailed
            ? '登录页暂时未能打开，请重试。'
            : '正在前往登录页。'
        }}
      </p>
      <el-button
        type="primary"
        :loading="loggingOut || navigatingToLogin"
        @click="retryLoginNavigation"
      >
        前往登录页
      </el-button>
    </section>
  </main>
</template>
