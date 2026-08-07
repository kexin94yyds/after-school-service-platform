<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { getErrorMessage } from '@/api/http'
import { reportApi } from '@/api/reports'
import type { ReportOverview } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import ReportBarChart from '@/components/ReportBarChart.vue'
import {
  formatPercent,
  reportMetricLabel,
  statusLabel,
} from '@/utils/format'

interface MetricEntry {
  key:
    | keyof Pick<
        ReportOverview,
        | 'schoolCount'
        | 'studentCount'
        | 'teacherCount'
        | 'courseCount'
        | 'offeringCount'
        | 'enrollmentCount'
        | 'sessionCount'
        | 'attendanceRate'
      >
    | 'satisfactionRate'
  label: string
  value: string | number
}

const props = withDefaults(
  defineProps<{
    scope?: 'regulator' | 'school'
  }>(),
  {
    scope: 'regulator',
  },
)

const overview = ref<ReportOverview | null>(null)
const loading = ref(false)
const error = ref('')

const metricKeys = computed<MetricEntry['key'][]>(() =>
  props.scope === 'school'
    ? [
        'studentCount',
        'teacherCount',
        'courseCount',
        'offeringCount',
        'enrollmentCount',
        'sessionCount',
        'attendanceRate',
        'satisfactionRate',
      ]
    : [
        'schoolCount',
        'studentCount',
        'teacherCount',
        'offeringCount',
        'enrollmentCount',
        'sessionCount',
        'attendanceRate',
        'satisfactionRate',
      ],
)

const metrics = computed<MetricEntry[]>(() => {
  const current = overview.value
  if (!current) return []
  return metricKeys.value.map((key) => ({
    key,
    label: reportMetricLabel(key),
    value:
      key === 'attendanceRate'
        ? formatPercent(current.attendanceRate)
        : key === 'satisfactionRate'
          ? formatPercent(current.satisfaction.satisfactionRate)
          : current[key] ?? 0,
  }))
})

const schoolSeries = computed(() => {
  if (props.scope === 'school') return null
  if (!overview.value?.schools.length) return null
  return {
    labels: overview.value.schools.map((item) => item.schoolName),
    values: overview.value.schools.map((item) => item.enrollmentCount),
  }
})

const categorySeries = computed(() => {
  if (!overview.value?.categories.length) return null
  return {
    labels: overview.value.categories.map((item) => item.category),
    values: overview.value.categories.map((item) => item.enrollmentCount),
  }
})

const attendanceSeries = computed(() => {
  if (!overview.value?.attendance.length) return null
  return {
    labels: overview.value.attendance.map((item) => statusLabel(item.status)),
    values: overview.value.attendance.map((item) => item.count),
  }
})

const hasDetails = computed(
  () =>
    Boolean(overview.value?.schools.length) ||
    Boolean(overview.value?.categories.length) ||
    Boolean(overview.value?.attendance.length) ||
    Boolean(overview.value?.teacherHours.length),
)
const pageCopy = computed(() =>
  props.scope === 'school'
    ? {
        kicker: '本校统计',
        title: '学校服务统计',
        description:
          '统计由服务端按当前学校会话自动限定，呈现本校课程供给、报名、出勤和教师课时。',
      }
    : {
        kicker: '监管统计',
        title: '区域服务统计',
        description:
          '统计口径和数据范围均由服务端统一计算，页面只呈现当前监管账号可访问的结果。',
      },
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    overview.value = await reportApi.getOverview()
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '监管统计加载失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <PageHeader
      :kicker="pageCopy.kicker"
      :title="pageCopy.title"
      :description="pageCopy.description"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新统计</el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="error"
      class="page-alert"
      :title="error"
      type="error"
      show-icon
      :closable="false"
    />

    <div v-loading="loading" class="report-content">
      <section v-if="metrics.length" class="report-metrics">
        <article v-for="metric in metrics" :key="metric.key">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
        </article>
      </section>

      <div
        v-if="schoolSeries || categorySeries || attendanceSeries"
        class="report-chart-grid"
      >
        <ReportBarChart
          v-if="schoolSeries"
          title="学校有效报名"
          :labels="schoolSeries.labels"
          :values="schoolSeries.values"
          value-label="报名数"
        />
        <ReportBarChart
          v-if="categorySeries"
          title="课程类别报名"
          :labels="categorySeries.labels"
          :values="categorySeries.values"
          value-label="报名数"
        />
        <ReportBarChart
          v-if="attendanceSeries"
          title="出勤状态分布"
          :labels="attendanceSeries.labels"
          :values="attendanceSeries.values"
          value-label="记录数"
        />
      </div>

      <section
        v-if="props.scope === 'regulator' && overview?.schools.length"
        class="entity-panel"
      >
        <div class="section-heading">
          <h2>学校服务承载</h2>
          <p>按学校汇总学生、教师、开班、容量和有效报名。</p>
        </div>
        <el-table :data="overview.schools" table-layout="auto">
          <el-table-column prop="schoolName" label="学校" min-width="180" />
          <el-table-column prop="studentCount" label="学生" min-width="90" />
          <el-table-column prop="teacherCount" label="教师" min-width="90" />
          <el-table-column prop="offeringCount" label="开班" min-width="90" />
          <el-table-column prop="capacity" label="总容量" min-width="100" />
          <el-table-column
            prop="enrollmentCount"
            label="有效报名"
            min-width="100"
          />
        </el-table>
      </section>

      <section v-if="overview?.categories.length" class="entity-panel">
        <div class="section-heading">
          <h2>课程类别供给</h2>
          <p>比较各课程类别的课程、开班和报名规模。</p>
        </div>
        <el-table :data="overview.categories" table-layout="auto">
          <el-table-column prop="category" label="课程类别" min-width="180" />
          <el-table-column prop="courseCount" label="课程" min-width="100" />
          <el-table-column prop="offeringCount" label="开班" min-width="100" />
          <el-table-column
            prop="enrollmentCount"
            label="有效报名"
            min-width="110"
          />
        </el-table>
      </section>

      <section v-if="overview?.attendance.length" class="entity-panel">
        <div class="section-heading">
          <h2>出勤结构</h2>
          <p>按教师已记录的出勤状态统计数量与占比。</p>
        </div>
        <el-table :data="overview.attendance" table-layout="auto">
          <el-table-column prop="status" label="状态" min-width="120">
            <template #default="{ row }">
              <el-tag effect="plain">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="记录数" min-width="110" />
          <el-table-column prop="percentage" label="占比" min-width="110">
            <template #default="{ row }">
              {{ formatPercent(row.percentage) }}
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="overview?.teacherHours.length" class="entity-panel">
        <div class="section-heading">
          <h2>教师完成课时</h2>
          <p>只统计状态为已完成的课次及其实际时长。</p>
        </div>
        <el-table :data="overview.teacherHours" table-layout="auto">
          <el-table-column prop="teacherName" label="教师" min-width="120" />
          <el-table-column prop="teacherNo" label="工号" min-width="120" />
          <el-table-column
            v-if="props.scope === 'regulator'"
            prop="schoolName"
            label="学校"
            min-width="180"
          />
          <el-table-column prop="offeringCount" label="开班" min-width="90" />
          <el-table-column
            prop="completedSessions"
            label="完成课次"
            min-width="100"
          />
          <el-table-column
            prop="completedHours"
            label="完成课时"
            min-width="100"
          />
        </el-table>
      </section>

      <div
        v-if="!loading && metrics.length === 0 && !hasDetails"
        class="empty-state page-empty"
      >
        <strong>暂无统计数据</strong>
        <span>服务端当前没有返回可展示的监管统计。</span>
      </div>
    </div>
  </section>
</template>
