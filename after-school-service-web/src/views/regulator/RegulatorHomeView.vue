<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { academicApi, type ServicePlan } from '@/api/academic'
import { getErrorMessage } from '@/api/http'
import { reportApi } from '@/api/reports'
import {
  supervisionApi,
  type SupervisionAlert,
} from '@/api/supervision'
import type { ReportOverview } from '@/api/types'
import RoleDashboard from '@/components/RoleDashboard.vue'
import type {
  DashboardMetric,
  DashboardTask,
} from '@/components/RoleDashboard.vue'
import { formatPercent } from '@/utils/format'

const overview = ref<ReportOverview | null>(null)
const plans = ref<ServicePlan[]>([])
const alerts = ref<SupervisionAlert[]>([])
const loading = ref(false)
const error = ref('')
let loadVersion = 0

const metrics = computed<DashboardMetric[]>(() => [
  {
    label: '接入学校',
    value: overview.value?.schoolCount ?? null,
    hint: '当前监管数据范围',
  },
  {
    label: '开班总数',
    value: overview.value?.offeringCount ?? null,
    hint: '服务端实时汇总',
  },
  {
    label: '有效报名',
    value: overview.value?.enrollmentCount ?? null,
    hint: '服务端实时汇总',
  },
  {
    label: '综合出勤率',
    value: formatPercent(overview.value?.attendanceRate),
    hint: '按服务端统计口径',
    featured: true,
  },
])

const tasks = computed<DashboardTask[]>(() => [
  {
    title: '待备案服务计划',
    description: '学校已提交，等待监管备案或退回。',
    value: plans.value.filter((item) => item.status === 'SUBMITTED').length,
    to: '/regulator/academic?planStatus=SUBMITTED',
    tone: 'warning',
  },
  {
    title: '待复核整改',
    description: '学校已提交整改材料，等待监管闭环。',
    value: alerts.value.filter((item) => item.status === 'WAITING_VERIFY').length,
    to: '/regulator/supervision?status=WAITING_VERIFY',
    tone: 'warning',
  },
  {
    title: '逾期未闭环预警',
    description: '超过整改期限且尚未关闭的监管事项。',
    value: alerts.value.filter(
      (item) => Boolean(item.overdue) && item.status !== 'CLOSED',
    ).length,
    to: '/regulator/supervision',
    tone: 'danger',
  },
])

const shortcuts = [
  {
    title: '学校管理',
    description: '维护区域接入学校及基础联系信息。',
    to: '/regulator/schools',
    action: '进入学校列表',
  },
  {
    title: '学期与计划备案',
    description: '维护区域学期并审核各校课后服务实施计划。',
    to: '/regulator/academic',
    action: '进入备案',
  },
  {
    title: '预警与整改',
    description: '扫描教学异常，跟踪学校整改并完成监管复核。',
    to: '/regulator/supervision',
    action: '处理预警',
  },
  {
    title: '综合分析',
    description: '按学校、学期和课程查看出勤、满意度与绩效。',
    to: '/regulator/analysis',
    action: '查看分析',
  },
  {
    title: '操作审计',
    description: '追溯关键写操作的人员、对象、时间与结果。',
    to: '/regulator/audit',
    action: '查看审计',
  },
]

async function load(): Promise<void> {
  const requestVersion = ++loadVersion
  loading.value = true
  error.value = ''
  const [overviewResult, planResult, alertResult] = await Promise.allSettled([
    reportApi.getOverview(),
    academicApi.getServicePlans(),
    supervisionApi.list(),
  ])
  if (requestVersion !== loadVersion) return

  const messages: string[] = []
  if (overviewResult.status === 'fulfilled') overview.value = overviewResult.value
  else {
    overview.value = null
    messages.push(getErrorMessage(overviewResult.reason, '区域统计加载失败。'))
  }
  if (planResult.status === 'fulfilled') plans.value = planResult.value
  else {
    plans.value = []
    messages.push(getErrorMessage(planResult.reason, '备案待办加载失败。'))
  }
  if (alertResult.status === 'fulfilled') alerts.value = alertResult.value
  else {
    alerts.value = []
    messages.push(getErrorMessage(alertResult.reason, '监管待办加载失败。'))
  }
  error.value = messages.join(' ')
  loading.value = false
}

onMounted(load)
</script>

<template>
  <RoleDashboard
    kicker="区域监管"
    title="课后服务运行总览"
    description="从学校供给、课程开设、报名承载到教学出勤，查看当前账号授权范围内的真实统计。"
    :metrics="metrics"
    :tasks="tasks"
    :shortcuts="shortcuts"
    :loading="loading"
    :error="error"
    @retry="load"
  />
</template>
