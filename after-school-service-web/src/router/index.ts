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
  SCHOOL_ADMIN: '/school',
  TEACHER: '/teacher',
  GUARDIAN: '/parent',
  STUDENT: '/student',
}

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginGatewayView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/login/student',
    name: 'student-login',
    component: () => import('@/views/LoginView.vue'),
    props: {
      expectedRole: 'STUDENT',
      roleTitle: '学生',
      roleDescription: '从选课到成绩，掌握自己的课后学习安排',
    },
    meta: { title: '学生登录', public: true },
  },
  {
    path: '/login/parent',
    name: 'parent-login',
    component: () => import('@/views/LoginView.vue'),
    props: {
      expectedRole: 'GUARDIAN',
      roleTitle: '家长',
      roleDescription: '了解子女课表、考勤、成绩与课程体验',
    },
    meta: { title: '家长登录', public: true },
  },
  {
    path: '/login/teacher',
    name: 'teacher-login',
    component: () => import('@/views/LoginView.vue'),
    props: {
      expectedRole: 'TEACHER',
      roleTitle: '教师',
      roleDescription: '聚焦授课任务、课堂考勤与学习评价',
    },
    meta: { title: '教师登录', public: true },
  },
  {
    path: '/login/admin',
    name: 'admin-login',
    component: () => import('@/views/LoginView.vue'),
    props: {
      expectedRole: 'SCHOOL_ADMIN',
      roleTitle: '教务管理员',
      roleDescription: '统筹课程、开课计划、排课与教务质量',
    },
    meta: { title: '教务管理员登录', public: true },
  },
  {
    path: '/student',
    component: RoleLayout,
    meta: { title: '学生端', roles: ['STUDENT'] },
    children: [
      {
        path: '',
        name: 'student-home',
        component: () => import('@/views/student/StudentHomeView.vue'),
        meta: { title: '学生工作台', roles: ['STUDENT'] },
      },
      {
        path: 'enrollments',
        name: 'student-enrollments',
        component: () => import('@/views/student/StudentEnrollmentView.vue'),
        meta: { title: '学生选课', roles: ['STUDENT'] },
      },
      {
        path: 'schedule',
        name: 'student-schedule',
        component: () => import('@/views/student/StudentScheduleView.vue'),
        meta: { title: '个人课表', roles: ['STUDENT'] },
      },
      {
        path: 'attendance',
        name: 'student-attendance',
        component: () => import('@/views/student/StudentAttendanceView.vue'),
        meta: { title: '请假与考勤', roles: ['STUDENT'] },
      },
      {
        path: 'grades',
        name: 'student-grades',
        component: () => import('@/views/student/StudentGradesView.vue'),
        meta: { title: '成绩与评价', roles: ['STUDENT'] },
      },
    ],
  },
  {
    path: '/school',
    component: RoleLayout,
    meta: { title: '教务管理端', roles: ['SCHOOL_ADMIN'] },
    children: [
      {
        path: '',
        name: 'school-home',
        component: () => import('@/views/school/SchoolHomeView.vue'),
        meta: { title: '教务工作台', roles: ['SCHOOL_ADMIN'] },
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
        path: 'terms',
        name: 'school-terms',
        component: () => import('@/views/school/TermManagementView.vue'),
        meta: { title: '学期管理', roles: ['SCHOOL_ADMIN'] },
      },
      {
        path: 'enrollments',
        name: 'school-enrollments',
        component: () => import('@/views/school/EnrollmentManagementView.vue'),
        meta: { title: '报名管理', roles: ['SCHOOL_ADMIN'] },
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
        path: 'grades',
        name: 'school-grades',
        component: () => import('@/views/school/SchoolGradesView.vue'),
        meta: { title: '成绩统计', roles: ['SCHOOL_ADMIN'] },
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
      {
        path: 'grades',
        name: 'teacher-grades',
        component: () => import('@/views/teacher/TeacherGradesView.vue'),
        meta: { title: '成绩与评价', roles: ['TEACHER'] },
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
        path: 'children',
        name: 'parent-children',
        component: () => import('@/views/parent/ParentChildRecordsView.vue'),
        meta: { title: '子女教务信息', roles: ['GUARDIAN'] },
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
