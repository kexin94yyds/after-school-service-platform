<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import {
  leaveCorrectionApi,
  type GuardianLeaveSession,
  type LeaveRequest,
} from '@/api/leaveCorrections'
import type {
  Enrollment,
  GuardianOffering,
  GuardianStudent,
} from '@/api/types'
import RoleDashboard from '@/components/RoleDashboard.vue'
import type {
  DashboardMetric,
  DashboardTask,
} from '@/components/RoleDashboard.vue'

const students = ref<GuardianStudent[]>([])
const enrollments = ref<Enrollment[]>([])
const offerings = ref<GuardianOffering[]>([])
const leaveRequests = ref<LeaveRequest[]>([])
const leaveSessions = ref<GuardianLeaveSession[]>([])
const loading = ref(false)
const error = ref('')

const metrics = computed<DashboardMetric[]>(() => [
  {
    label: '已绑定学生',
    value: students.value.length,
    hint: '服务端监护关系',
  },
  {
    label: '可选开班',
    value: offerings.value.filter((item) => item.canEnroll).length,
    hint: '通过服务端报名校验',
  },
  {
    label: '有效报名',
    value: enrollments.value.filter((item) => item.status === 'ENROLLED').length,
    hint: '当前家长授权范围',
    featured: true,
  },
  {
    label: '已取消报名',
    value: enrollments.value.filter((item) => item.status === 'CANCELED').length,
    hint: '保留历史记录',
  },
])

const tasks = computed<DashboardTask[]>(() => [
  {
    title: '待处理请假',
    description: '已提交、可继续查看或在上课前撤回的请假。',
    value: leaveRequests.value.filter((item) => item.status === 'PENDING')
      .length,
    to: '/parent/children',
    tone: 'warning',
  },
  {
    title: '已批准请假',
    description: '已通过审核、将预标记为请假的课次。',
    value: leaveRequests.value.filter((item) => item.status === 'APPROVED')
      .length,
    to: '/parent/children',
  },
  {
    title: '未来可请假课次',
    description: '绑定学生尚未提交有效请假的未来课次。',
    value: leaveSessions.value.filter(
      (item) =>
        item.status === 'SCHEDULED' && item.activeLeaveRequestId === null,
    ).length,
    to: '/parent/children',
  },
])

const shortcuts = [
  {
    title: '子女教务信息',
    description: '查看已绑定子女的课表、请假、考勤和成绩。',
    to: '/parent/children',
    action: '查看记录',
  },
  {
    title: '课后课程评价',
    description: '对符合条件的已完成课程提交服务反馈。',
    to: '/parent/evaluations',
    action: '去评价',
  },
]

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  students.value = []
  enrollments.value = []
  offerings.value = []
  leaveRequests.value = []
  leaveSessions.value = []

  const failures: string[] = []
  const [studentResult, enrollmentResult, leaveResult] =
    await Promise.allSettled([
      enrollmentApi.getGuardianStudents(),
      enrollmentApi.getEnrollments(),
      leaveCorrectionApi.getLeaveRequests(),
    ])

  if (studentResult.status === 'fulfilled') {
    students.value = studentResult.value
    const [offeringResults, sessionResults] = await Promise.all([
      Promise.allSettled(
        studentResult.value.map((student) =>
          enrollmentApi.getStudentOfferings(student.id),
        ),
      ),
      Promise.allSettled(
        studentResult.value.map((student) =>
          leaveCorrectionApi.getGuardianSessions(student.id),
        ),
      ),
    ])
    offerings.value = offeringResults.flatMap((result) =>
      result.status === 'fulfilled' ? result.value : [],
    )
    leaveSessions.value = sessionResults.flatMap((result) =>
      result.status === 'fulfilled' ? result.value : [],
    )

    const offeringFailures = offeringResults.filter(
      (result) => result.status === 'rejected',
    ).length
    if (offeringFailures) {
      failures.push(`${offeringFailures} 名学生的可选开班未能加载`)
    }
    const sessionFailures = sessionResults.filter(
      (result) => result.status === 'rejected',
    ).length
    if (sessionFailures) {
      failures.push(`${sessionFailures} 名学生的未来课次未能加载`)
    }
  } else {
    failures.push(
      `学生：${getErrorMessage(studentResult.reason, '加载失败')}`,
    )
  }

  if (enrollmentResult.status === 'fulfilled') {
    enrollments.value = enrollmentResult.value
  } else {
    failures.push(
      `报名：${getErrorMessage(enrollmentResult.reason, '加载失败')}`,
    )
  }

  if (leaveResult.status === 'fulfilled') {
    leaveRequests.value = leaveResult.value
  } else {
    failures.push(
      `请假：${getErrorMessage(leaveResult.reason, '加载失败')}`,
    )
  }

  error.value = failures.length
    ? `部分家长服务数据未能加载：${failures.join('；')}。`
    : ''
  loading.value = false
}

onMounted(load)
</script>

<template>
  <RoleDashboard
    kicker="家长服务"
    title="子女课后服务概览"
    description="查看已绑定子女的教务进度，并在课程结束后提供课程与教师评价。"
    :metrics="metrics"
    :tasks="tasks"
    :shortcuts="shortcuts"
    :loading="loading"
    :error="error"
    @retry="load"
  />
</template>
