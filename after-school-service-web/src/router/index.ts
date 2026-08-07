import { createRouter, createWebHistory } from 'vue-router'
import type { RouteLocationNormalized, RouteRecordRaw } from 'vue-router'

import type { RoleCode } from '@/api/types'
import RoleLayout from '@/layouts/RoleLayout.vue'
import { pinia } from '@/stores'
import { useSessionStore } from '@/stores/session'

declare module 'vue-router' {
  interface RouteMeta {
    title: string
    roles?: RoleCode[]
    public?: boolean
  }
}

export const roleHomePaths: Record<RoleCode, string> = {
  REGULATOR: '/regulator',
  SCHOOL_ADMIN: '/school',
  TEACHER: '/teacher',
  GUARDIAN: '/parent',
}

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/regulator',
    component: RoleLayout,
    meta: { title: '监管端', roles: ['REGULATOR'] },
    children: [
      {
        path: '',
        name: 'regulator-home',
        component: () => import('@/views/regulator/RegulatorHomeView.vue'),
        meta: { title: '监管总览', roles: ['REGULATOR'] },
      },
      {
        path: 'schools',
        name: 'regulator-schools',
        component: () => import('@/views/regulator/SchoolDirectoryView.vue'),
        meta: { title: '学校管理', roles: ['REGULATOR'] },
      },
      {
        path: 'academic',
        name: 'regulator-academic',
        component: () =>
          import('@/views/regulator/AcademicGovernanceView.vue'),
        meta: { title: '学期与计划备案', roles: ['REGULATOR'] },
      },
      {
        path: 'supervision',
        name: 'regulator-supervision',
        component: () =>
          import('@/views/regulator/SupervisionAlertsView.vue'),
        meta: { title: '监管预警', roles: ['REGULATOR'] },
      },
      {
        path: 'reports',
        name: 'regulator-reports',
        component: () => import('@/views/regulator/ReportOverviewView.vue'),
        meta: { title: '监管统计', roles: ['REGULATOR'] },
      },
      {
        path: 'analysis',
        name: 'regulator-analysis',
        component: () =>
          import('@/views/regulator/ComprehensiveAnalysisView.vue'),
        meta: { title: '综合分析', roles: ['REGULATOR'] },
      },
      {
        path: 'audit',
        name: 'regulator-audit',
        component: () => import('@/views/regulator/OperationAuditView.vue'),
        meta: { title: '操作审计', roles: ['REGULATOR'] },
      },
    ],
  },
  {
    path: '/school',
    component: RoleLayout,
    meta: { title: '学校管理端', roles: ['SCHOOL_ADMIN'] },
    children: [
      {
        path: '',
        name: 'school-home',
        component: () => import('@/views/school/SchoolHomeView.vue'),
        meta: { title: '学校工作台', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'organization',
        name: 'school-organization',
        component: () => import('@/views/school/OrganizationPeopleView.vue'),
        meta: { title: '组织与人员', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'courses',
        name: 'school-courses',
        component: () => import('@/views/school/CourseOfferingView.vue'),
        meta: { title: '课程与开班', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'academic',
        name: 'school-academic',
        component: () => import('@/views/school/AcademicResourcesView.vue'),
        meta: { title: '学期资源', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'enrollments',
        name: 'school-enrollments',
        component: () => import('@/views/school/EnrollmentManagementView.vue'),
        meta: { title: '报名管理', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'teaching',
        name: 'school-teaching',
        component: () => import('@/views/teacher/TeacherSessionsView.vue'),
        meta: { title: '授课与考勤', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'schedule-adjustments',
        name: 'school-schedule-adjustments',
        component: () =>
          import('@/views/school/ScheduleAdjustmentsView.vue'),
        meta: { title: '调课管理', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'leave-corrections',
        name: 'school-leave-corrections',
        component: () =>
          import('@/views/school/SchoolLeaveCorrectionView.vue'),
        meta: { title: '请假与纠错审批', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'rectifications',
        name: 'school-rectifications',
        component: () =>
          import('@/views/school/RectificationManagementView.vue'),
        meta: { title: '整改处理', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'reports',
        name: 'school-reports',
        component: () => import('@/views/regulator/ReportOverviewView.vue'),
        props: { scope: 'school' },
        meta: { title: '本校统计', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'audit',
        name: 'school-audit',
        component: () => import('@/views/school/OperationAuditView.vue'),
        meta: { title: '操作审计', roles: ['SCHOOL_ADMIN'] },
      },
    ],
  },
  {
    path: '/teacher',
    component: RoleLayout,
    meta: { title: '教师端', roles: ['TEACHER'] },
    children: [
      {
        path: '',
        name: 'teacher-home',
        component: () => import('@/views/teacher/TeacherHomeView.vue'),
        meta: { title: '教师工作台', roles: ['TEACHER'] },
      },
      {
        path: 'sessions',
        name: 'teacher-sessions',
        component: () => import('@/views/teacher/TeacherSessionsView.vue'),
        meta: { title: '课次与考勤', roles: ['TEACHER'] },
      },
      {
        path: 'leave-corrections',
        name: 'teacher-leave-corrections',
        component: () =>
          import('@/views/teacher/TeacherLeaveCorrectionView.vue'),
        meta: { title: '请假与纠错', roles: ['TEACHER'] },
      },
    ],
  },
  {
    path: '/parent',
    component: RoleLayout,
    meta: { title: '家长端', roles: ['GUARDIAN'] },
    children: [
      {
        path: '',
        name: 'parent-home',
        component: () => import('@/views/parent/ParentHomeView.vue'),
        meta: { title: '家长服务', roles: ['GUARDIAN'] },
      },
      {
        path: 'enrollments',
        name: 'parent-enrollments',
        component: () => import('@/views/parent/GuardianEnrollmentView.vue'),
        meta: { title: '学生选课', roles: ['GUARDIAN'] },
      },
      {
        path: 'leaves',
        name: 'parent-leaves',
        component: () => import('@/views/parent/GuardianLeaveView.vue'),
        meta: { title: '课次请假', roles: ['GUARDIAN'] },
      },
      {
        path: 'evaluations',
        name: 'parent-evaluations',
        component: () => import('@/views/parent/GuardianEvaluationView.vue'),
        meta: { title: '课程评价', roles: ['GUARDIAN'] },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

export function canAccessRoute(
  route: RouteLocationNormalized,
  role: RoleCode,
): boolean {
  const allowedRoles = route.meta.roles
  return !allowedRoles || allowedRoles.includes(role)
}

router.beforeEach(async (to) => {
  const session = useSessionStore(pinia)
  try {
    await session.restore()
  } catch {
    if (!to.meta.public) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  if (to.meta.public) {
    return session.role ? roleHomePaths[session.role] : true
  }

  if (!session.user || !session.role) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (!canAccessRoute(to, session.role)) {
    return roleHomePaths[session.role]
  }
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title} | 课后服务教务监管平台`
})

export default router
