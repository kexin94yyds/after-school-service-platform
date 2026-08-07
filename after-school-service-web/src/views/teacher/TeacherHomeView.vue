<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { courseApi } from '@/api/courses'
import { getErrorMessage } from '@/api/http'
import {
  leaveCorrectionApi,
  type AttendanceCorrection,
  type LeaveRequest,
  type SessionSummary,
} from '@/api/leaveCorrections'
import type { CourseOffering } from '@/api/types'
import RoleDashboard from '@/components/RoleDashboard.vue'
import type {
  DashboardMetric,
  DashboardTask,
} from '@/components/RoleDashboard.vue'

const offerings = ref<CourseOffering[]>([])
const leaveRequests = ref<LeaveRequest[]>([])
const corrections = ref<AttendanceCorrection[]>([])
const sessions = ref<SessionSummary[]>([])
const loading = ref(false)
const error = ref('')

const metrics = computed<DashboardMetric[]>(() => [
  {
    label: '我的开班',
    value: offerings.value.length,
    hint: '服务端按任课教师过滤',
  },
  {
    label: '已发布',
    value: offerings.value.filter((item) => item.status === 'PUBLISHED').length,
    hint: '当前可执行开班',
  },
  {
    label: '在班学生',
    value: offerings.value.reduce((sum, item) => sum + item.enrolledCount, 0),
    hint: '各开班报名人数合计',
    featured: true,
  },
  {
    label: '总容量',
    value: offerings.value.reduce((sum, item) => sum + item.capacity, 0),
    hint: '本人开班容量合计',
  },
])

const upcomingSessions = computed(() => {
  const now = new Date()
  const localToday = new Date(
    now.getTime() - now.getTimezoneOffset() * 60_000,
  )
    .toISOString()
    .slice(0, 10)
  return sessions.value.filter(
    (item) =>
      item.status === 'SCHEDULED' && item.sessionDate >= localToday,
  )
})

const tasks = computed<DashboardTask[]>(() => [
  {
    title: '待审核请假',
    description: '本人开班中等待教师处理的课次请假。',
    value: leaveRequests.value.filter((item) => item.status === 'PENDING')
      .length,
    to: '/teacher/leave-corrections?workflow=leave&leaveStatus=PENDING',
    tone: 'warning',
  },
  {
    title: '待审批纠错',
    description: '已发起、正在等待学校审批的考勤纠错。',
    value: corrections.value.filter((item) => item.status === 'PENDING')
      .length,
    to: '/teacher/leave-corrections?workflow=correction&correctionStatus=PENDING',
    tone: 'warning',
  },
  {
    title: '近期待上课',
    description: '从今天起处于待上课状态的本人课次。',
    value: upcomingSessions.value.length,
    to: '/teacher/sessions',
  },
])

const shortcuts = [
  {
    title: '课次与考勤',
    description: '按本人开班生成课次，选择课次后登记学生出勤。',
    to: '/teacher/sessions',
    action: '进入授课台',
  },
  {
    title: '请假与纠错',
    description: '审核本人开班请假，发起并跟进考勤纠错。',
    to: '/teacher/leave-corrections',
    action: '处理待办',
  },
]

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  offerings.value = []
  leaveRequests.value = []
  corrections.value = []
  sessions.value = []

  const failures: string[] = []
  const [offeringResult, leaveResult, correctionResult] =
    await Promise.allSettled([
      courseApi.getOfferings(),
      leaveCorrectionApi.getLeaveRequests({ status: 'PENDING' }),
      leaveCorrectionApi.getCorrections({ status: 'PENDING' }),
    ])

  if (offeringResult.status === 'fulfilled') {
    offerings.value = offeringResult.value
    const sessionResults = await Promise.allSettled(
      offeringResult.value.map((item) =>
        leaveCorrectionApi.getSessions(item.id),
      ),
    )
    sessions.value = sessionResults.flatMap((result) =>
      result.status === 'fulfilled' ? result.value : [],
    )
    const sessionFailures = sessionResults.filter(
      (result) => result.status === 'rejected',
    )
    if (sessionFailures.length) {
      failures.push(
        `${sessionFailures.length} 个开班的课次未能加载`,
      )
    }
  } else {
    failures.push(
      `开班：${getErrorMessage(offeringResult.reason, '加载失败')}`,
    )
  }

  if (leaveResult.status === 'fulfilled') {
    leaveRequests.value = leaveResult.value
  } else {
    failures.push(
      `请假：${getErrorMessage(leaveResult.reason, '加载失败')}`,
    )
  }

  if (correctionResult.status === 'fulfilled') {
    corrections.value = correctionResult.value
  } else {
    failures.push(
      `纠错：${getErrorMessage(correctionResult.reason, '加载失败')}`,
    )
  }

  error.value = failures.length
    ? `部分工作台数据未能加载：${failures.join('；')}。`
    : ''
  loading.value = false
}

onMounted(load)
</script>

<template>
  <RoleDashboard
    kicker="教师授课"
    title="课程与考勤工作台"
    description="聚焦当前教师承担的开班，生成课次并保留每次出勤记录。"
    :metrics="metrics"
    :tasks="tasks"
    :shortcuts="shortcuts"
    :loading="loading"
    :error="error"
    @retry="load"
  />
</template>
