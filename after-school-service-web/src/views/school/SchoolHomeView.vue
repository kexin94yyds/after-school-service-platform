<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { academicApi, type ServicePlan } from '@/api/academic'
import { courseApi } from '@/api/courses'
import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import {
  leaveCorrectionApi,
  type AttendanceCorrection,
  type LeaveRequest,
} from '@/api/leaveCorrections'
import { organizationApi } from '@/api/organization'
import {
  supervisionApi,
  type SupervisionAlert,
} from '@/api/supervision'
import RoleDashboard from '@/components/RoleDashboard.vue'
import type {
  DashboardMetric,
  DashboardTask,
} from '@/components/RoleDashboard.vue'

const loading = ref(false)
const error = ref('')
const classCount = ref<number | null>(null)
const teacherCount = ref<number | null>(null)
const studentCount = ref<number | null>(null)
const courseCount = ref<number | null>(null)
const offeringCount = ref<number | null>(null)
const enrollmentCount = ref<number | null>(null)
const plans = ref<ServicePlan[]>([])
const leaves = ref<LeaveRequest[]>([])
const corrections = ref<AttendanceCorrection[]>([])
const alerts = ref<SupervisionAlert[]>([])
let loadVersion = 0

const metrics = computed<DashboardMetric[]>(() => [
  {
    label: '班级',
    value: classCount.value,
    hint: '已纳入课后服务',
  },
  {
    label: '教师与学生',
    value:
      teacherCount.value === null || studentCount.value === null
        ? null
        : `${teacherCount.value} / ${studentCount.value}`,
    hint: '教师 / 学生',
  },
  {
    label: '课程与开班',
    value:
      courseCount.value === null || offeringCount.value === null
        ? null
        : `${courseCount.value} / ${offeringCount.value}`,
    hint: '课程 / 开班',
  },
  {
    label: '有效报名',
    value: enrollmentCount.value,
    hint: '当前在班学生记录',
    featured: true,
  },
])

const tasks = computed<DashboardTask[]>(() => [
  {
    title: '退回待修改计划',
    description: '监管已退回，修改后可重新提交备案。',
    value: plans.value.filter((item) => item.status === 'RETURNED').length,
    to: '/school/academic?tab=plans',
    tone: 'warning',
  },
  {
    title: '待审核请假',
    description: '家长已提交，等待本校教务或任课教师处理。',
    value: leaves.value.filter((item) => item.status === 'PENDING').length,
    to: '/school/leave-corrections?workflow=leave&leaveStatus=PENDING',
    tone: 'warning',
  },
  {
    title: '待审批考勤纠错',
    description: '教师已发起，审批后将生成不可变修订记录。',
    value: corrections.value.filter((item) => item.status === 'PENDING').length,
    to: '/school/leave-corrections?workflow=correction&correctionStatus=PENDING',
    tone: 'warning',
  },
  {
    title: '待响应整改',
    description: '新预警或监管退回事项，需要学校继续闭环。',
    value: alerts.value.filter((item) =>
      ['OPEN', 'RETURNED'].includes(item.status),
    ).length,
    to: '/school/rectifications',
    tone: 'danger',
  },
])

const shortcuts = [
  {
    title: '组织与人员',
    description: '维护班级、教师、学生和家长基础档案。',
    to: '/school/organization',
    action: '管理档案',
  },
  {
    title: '学期资源',
    description: '编制服务计划，维护教室、校历与标准化学期资源。',
    to: '/school/academic',
    action: '管理资源',
  },
  {
    title: '课程与开班',
    description: '设置课程、教师、时段、教室和报名容量。',
    to: '/school/courses',
    action: '管理开班',
  },
  {
    title: '报名管理',
    description: '查看报名学生、监护人和取消操作记录。',
    to: '/school/enrollments',
    action: '查看报名',
  },
  {
    title: '授课与考勤',
    description: '生成课程课次，核对全校开班的学生出勤记录。',
    to: '/school/teaching',
    action: '进入授课台',
  },
  {
    title: '请假与纠错',
    description: '审核学生请假和已完成课次的考勤纠错申请。',
    to: '/school/leave-corrections',
    action: '处理审批',
  },
  {
    title: '整改处理',
    description: '接收监管预警、提交整改材料并跟踪复核结果。',
    to: '/school/rectifications',
    action: '处理整改',
  },
  {
    title: '本校统计',
    description: '查看课程供给、报名、出勤和教师完成课时。',
    to: '/school/reports',
    action: '查看统计',
  },
  {
    title: '操作审计',
    description: '追溯本校关键业务操作及其执行结果。',
    to: '/school/audit',
    action: '查看审计',
  },
]

async function load(): Promise<void> {
  const requestVersion = ++loadVersion
  loading.value = true
  error.value = ''
  const [
    classResult,
    teacherResult,
    studentResult,
    courseResult,
    offeringResult,
    enrollmentResult,
    planResult,
    leaveResult,
    correctionResult,
    alertResult,
  ] = await Promise.allSettled([
    organizationApi.getClasses(),
    organizationApi.getTeachers(),
    organizationApi.getStudents(),
    courseApi.getCourses(),
    courseApi.getOfferings(),
    enrollmentApi.getEnrollments(),
    academicApi.getServicePlans(),
    leaveCorrectionApi.getLeaveRequests(),
    leaveCorrectionApi.getCorrections(),
    supervisionApi.list(),
  ])
  if (requestVersion !== loadVersion) return

  const messages: string[] = []
  if (classResult.status === 'fulfilled') classCount.value = classResult.value.length
  else {
    classCount.value = null
    messages.push(getErrorMessage(classResult.reason, '班级数据加载失败。'))
  }
  if (teacherResult.status === 'fulfilled') teacherCount.value = teacherResult.value.length
  else {
    teacherCount.value = null
    messages.push(getErrorMessage(teacherResult.reason, '教师数据加载失败。'))
  }
  if (studentResult.status === 'fulfilled') studentCount.value = studentResult.value.length
  else {
    studentCount.value = null
    messages.push(getErrorMessage(studentResult.reason, '学生数据加载失败。'))
  }
  if (courseResult.status === 'fulfilled') courseCount.value = courseResult.value.length
  else {
    courseCount.value = null
    messages.push(getErrorMessage(courseResult.reason, '课程数据加载失败。'))
  }
  if (offeringResult.status === 'fulfilled') offeringCount.value = offeringResult.value.length
  else {
    offeringCount.value = null
    messages.push(getErrorMessage(offeringResult.reason, '开班数据加载失败。'))
  }
  if (enrollmentResult.status === 'fulfilled') {
    enrollmentCount.value = enrollmentResult.value.filter(
      (item) => item.status === 'ENROLLED',
    ).length
  } else {
    enrollmentCount.value = null
    messages.push(getErrorMessage(enrollmentResult.reason, '报名数据加载失败。'))
  }
  if (planResult.status === 'fulfilled') plans.value = planResult.value
  else {
    plans.value = []
    messages.push(getErrorMessage(planResult.reason, '服务计划待办加载失败。'))
  }
  if (leaveResult.status === 'fulfilled') leaves.value = leaveResult.value
  else {
    leaves.value = []
    messages.push(getErrorMessage(leaveResult.reason, '请假待办加载失败。'))
  }
  if (correctionResult.status === 'fulfilled') corrections.value = correctionResult.value
  else {
    corrections.value = []
    messages.push(getErrorMessage(correctionResult.reason, '纠错待办加载失败。'))
  }
  if (alertResult.status === 'fulfilled') alerts.value = alertResult.value
  else {
    alerts.value = []
    messages.push(getErrorMessage(alertResult.reason, '整改待办加载失败。'))
  }
  error.value = messages.join(' ')
  loading.value = false
}

onMounted(load)
</script>

<template>
  <RoleDashboard
    kicker="学校教务"
    title="学校课后服务工作台"
    description="围绕本校组织、课程开设和报名执行，形成可追踪的教务操作链路。"
    :metrics="metrics"
    :tasks="tasks"
    :shortcuts="shortcuts"
    :loading="loading"
    :error="error"
    @retry="load"
  />
</template>
