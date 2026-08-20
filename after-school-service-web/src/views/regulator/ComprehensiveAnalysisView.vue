<script setup lang="ts">
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { evaluationApi } from '@/api/evaluations'
import type { CourseEvaluation, EvaluationSummary } from '@/api/evaluations'
import { extendedReportsApi } from '@/api/extended-reports'
import type {
  CoursePerformanceFilters,
  CoursePerformanceRow,
  RectificationReportRow,
} from '@/api/extended-reports'
import { getErrorMessage } from '@/api/http'
import { referenceDataApi } from '@/api/reference-data'
import type { AcademicTermOption, SchoolOption } from '@/api/reference-data'
import PageHeader from '@/components/PageHeader.vue'
import ReportBarChart from '@/components/ReportBarChart.vue'
import {
  formatDateTime,
  formatPercent,
  statusLabel,
  statusTagType,
} from '@/utils/format'

type OfferingStatus = 'DRAFT' | 'PUBLISHED' | 'CLOSED' | 'FINISHED' | 'CANCELED'

const route = useRoute()
const router = useRouter()
const schools = ref<SchoolOption[]>([])
const terms = ref<AcademicTermOption[]>([])
const rows = ref<CoursePerformanceRow[]>([])
const evaluations = ref<CourseEvaluation[]>([])
const rectifications = ref<RectificationReportRow[]>([])
const evaluationSummary = ref<EvaluationSummary | null>(null)
const loading = ref(false)
const referenceLoading = ref(false)
const exporting = ref(false)
const error = ref('')
const referenceError = ref('')
let dataLoadVersion = 0

const filters = reactive<{
  schoolId: number | null
  termId: number | null
  category: string
  status: OfferingStatus | ''
  dateRange: [string, string] | null
}>({
  schoolId: null,
  termId: null,
  category: '',
  status: '',
  dateRange: null,
})

const statuses: OfferingStatus[] = [
  'DRAFT',
  'PUBLISHED',
  'CLOSED',
  'FINISHED',
  'CANCELED',
]

const displayedError = computed(() =>
  [referenceError.value, error.value].filter(Boolean).join(' '),
)

const metrics = computed(() => ({
  schools: new Set(rows.value.map((item) => item.schoolId)).size,
  offerings: rows.value.length,
  enrollments: rows.value.reduce(
    (total, item) => total + Number(item.activeEnrollmentCount || 0),
    0,
  ),
  completedSessions: rows.value.reduce(
    (total, item) => total + Number(item.completedSessions || 0),
    0,
  ),
  averageRating: Number(evaluationSummary.value?.averageRating || 0),
  satisfactionRate: Number(evaluationSummary.value?.satisfactionRate || 0),
}))

const enrollmentSeries = computed(() => {
  const ranked = [...rows.value]
    .sort(
      (left, right) =>
        Number(right.activeEnrollmentCount) - Number(left.activeEnrollmentCount),
    )
    .slice(0, 10)
  return ranked.length
    ? {
        labels: ranked.map((item) => item.courseName),
        values: ranked.map((item) => Number(item.activeEnrollmentCount)),
      }
    : null
})

const satisfactionSeries = computed(() => {
  const ranked = rows.value
    .filter((item) => Number(item.evaluationCount) > 0)
    .sort(
      (left, right) =>
        Number(right.satisfactionRate) - Number(left.satisfactionRate),
    )
    .slice(0, 10)
  return ranked.length
    ? {
        labels: ranked.map((item) => item.courseName),
        values: ranked.map((item) => Number(item.satisfactionRate)),
      }
    : null
})

function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

function positiveNumber(value: unknown): number | null {
  const parsed = Number(firstQueryValue(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function isOfferingStatus(value: string | undefined): value is OfferingStatus {
  return statuses.includes(value as OfferingStatus)
}

function hydrateFilters(): void {
  const status = firstQueryValue(route.query.status)
  const from = firstQueryValue(route.query.from)
  const to = firstQueryValue(route.query.to)
  filters.schoolId = positiveNumber(route.query.schoolId)
  filters.termId = positiveNumber(route.query.termId)
  filters.category = firstQueryValue(route.query.category) ?? ''
  filters.status = isOfferingStatus(status) ? status : ''
  filters.dateRange = from && to ? [from, to] : null
}

async function saveFiltersToUrl(): Promise<void> {
  const query: Record<string, string> = {}
  if (filters.schoolId) query.schoolId = String(filters.schoolId)
  if (filters.termId) query.termId = String(filters.termId)
  if (filters.category.trim()) query.category = filters.category.trim()
  if (filters.status) query.status = filters.status
  if (filters.dateRange) {
    query.from = filters.dateRange[0]
    query.to = filters.dateRange[1]
  }
  await router.replace({ query })
}

function reportFilters(): CoursePerformanceFilters {
  return {
    schoolId: filters.schoolId,
    termId: filters.termId,
    category: filters.category.trim(),
    status: filters.status,
    fromDate: filters.dateRange?.[0],
    toDate: filters.dateRange?.[1],
  }
}

function evaluationFilters() {
  return {
    schoolId: filters.schoolId,
    termId: filters.termId,
    category: filters.category.trim(),
    submittedFrom: filters.dateRange?.[0]
      ? `${filters.dateRange[0]}T00:00:00`
      : undefined,
    submittedTo: filters.dateRange?.[1]
      ? `${filters.dateRange[1]}T23:59:59`
      : undefined,
  }
}

async function loadReferences(): Promise<void> {
  referenceLoading.value = true
  referenceError.value = ''
  try {
    const [schoolItems, termItems] = await Promise.all([
      referenceDataApi.getSchools(),
      referenceDataApi.getTerms(),
    ])
    schools.value = schoolItems
    terms.value = termItems
  } catch (loadError) {
    referenceError.value = getErrorMessage(loadError, '学校与学期选项加载失败。')
  } finally {
    referenceLoading.value = false
  }
}

async function loadData(options: { saveQuery?: boolean } = {}): Promise<void> {
  const requestVersion = ++dataLoadVersion
  const performanceQuery = reportFilters()
  const evaluationQuery = evaluationFilters()
  loading.value = true
  error.value = ''
  try {
    if (options.saveQuery) await saveFiltersToUrl()
    const [performanceResult, summaryResult, evaluationResult, rectificationResult] =
      await Promise.allSettled([
      extendedReportsApi.getCoursePerformance(performanceQuery),
      evaluationApi.summary(evaluationQuery),
      evaluationApi.list(evaluationQuery),
      extendedReportsApi.getRectifications({
        schoolId: filters.schoolId,
        detectedFrom: filters.dateRange?.[0],
        detectedTo: filters.dateRange?.[1],
      }),
    ])
    if (requestVersion !== dataLoadVersion) return

    const messages: string[] = []
    if (performanceResult.status === 'fulfilled') {
      rows.value = performanceResult.value
    } else {
      rows.value = []
      messages.push(
        getErrorMessage(performanceResult.reason, '课程绩效数据加载失败。'),
      )
    }
    if (summaryResult.status === 'fulfilled') {
      evaluationSummary.value = summaryResult.value
    } else {
      evaluationSummary.value = null
      messages.push(
        getErrorMessage(summaryResult.reason, '评价汇总数据加载失败。'),
      )
    }
    if (evaluationResult.status === 'fulfilled') {
      evaluations.value = evaluationResult.value
    } else {
      evaluations.value = []
      messages.push(
        getErrorMessage(evaluationResult.reason, '评价明细加载失败。'),
      )
    }
    if (rectificationResult.status === 'fulfilled') {
      rectifications.value = rectificationResult.value
    } else {
      rectifications.value = []
      messages.push(
        getErrorMessage(rectificationResult.reason, '违规整改统计加载失败。'),
      )
    }
    error.value = messages.join(' ')
  } catch (loadError) {
    if (requestVersion === dataLoadVersion) {
      error.value = getErrorMessage(loadError, '综合分析数据加载失败。')
    }
  } finally {
    if (requestVersion === dataLoadVersion) loading.value = false
  }
}

async function resetFilters(): Promise<void> {
  filters.schoolId = null
  filters.termId = null
  filters.category = ''
  filters.status = ''
  filters.dateRange = null
  await loadData({ saveQuery: true })
}

async function exportCsv(): Promise<void> {
  exporting.value = true
  try {
    await extendedReportsApi.downloadCoursePerformanceCsv(reportFilters())
    ElMessage.success('课程绩效 CSV 已开始下载')
  } catch (downloadError) {
    ElMessage.error(getErrorMessage(downloadError, 'CSV 导出失败。'))
  } finally {
    exporting.value = false
  }
}

async function exportXlsx(): Promise<void> {
  exporting.value = true
  try {
    await extendedReportsApi.downloadCoursePerformanceXlsx(reportFilters())
    ElMessage.success('课程绩效 Excel 已开始下载')
  } catch (downloadError) {
    ElMessage.error(getErrorMessage(downloadError, 'Excel 导出失败。'))
  } finally {
    exporting.value = false
  }
}

async function exportRectifications(): Promise<void> {
  exporting.value = true
  try {
    await extendedReportsApi.downloadRectificationsXlsx({
      schoolId: filters.schoolId,
      detectedFrom: filters.dateRange?.[0],
      detectedTo: filters.dateRange?.[1],
    })
    ElMessage.success('违规整改 Excel 已开始下载')
  } catch (downloadError) {
    ElMessage.error(getErrorMessage(downloadError, '违规整改 Excel 导出失败。'))
  } finally {
    exporting.value = false
  }
}

function ratingText(value: number): string {
  return `${Number(value || 0).toFixed(1)} 分`
}

onMounted(async () => {
  hydrateFilters()
  await Promise.all([loadReferences(), loadData()])
})
</script>

<template>
  <section class="page-stack analysis-page">
    <PageHeader
      kicker="监管分析"
      title="课后服务综合分析"
      description="联动课程供给、报名、课次、出勤与匿名评价指标，并按当前筛选口径导出课程绩效明细。"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadData()">刷新分析</el-button>
        <el-button :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        <el-button type="primary" :loading="exporting" @click="exportXlsx">导出课程 Excel</el-button>
        <el-button type="success" :loading="exporting" @click="exportRectifications">导出整改 Excel</el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="displayedError"
      class="page-alert"
      :title="displayedError"
      type="error"
      show-icon
      :closable="false"
    />

    <section class="analysis-panel filter-panel">
      <el-form class="analysis-filters" label-position="top" @submit.prevent>
        <el-form-item label="学校范围">
          <el-select
            v-model="filters.schoolId"
            :loading="referenceLoading"
            clearable
            placeholder="全部学校"
          >
            <el-option
              v-for="school in schools"
              :key="school.id"
              :label="school.schoolName"
              :value="school.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="学期范围">
          <el-select
            v-model="filters.termId"
            :loading="referenceLoading"
            clearable
            placeholder="全部学期"
          >
            <el-option
              v-for="term in terms"
              :key="term.id"
              :label="term.termName"
              :value="term.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="课程类别">
          <el-input v-model="filters.category" clearable placeholder="全部类别" />
        </el-form-item>
        <el-form-item label="开课状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态">
            <el-option
              v-for="status in statuses"
              :key="status"
              :label="statusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="统计日期">
          <el-date-picker
            v-model="filters.dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </el-form-item>
        <div class="filter-actions">
          <el-button @click="resetFilters">重置</el-button>
          <el-button type="primary" :loading="loading" @click="loadData({ saveQuery: true })">
            应用筛选
          </el-button>
        </div>
      </el-form>
      <p class="filter-note">
        开课状态仅作用于课程绩效明细；评价指标按学校、学期、类别和日期统计。
      </p>
    </section>

    <el-skeleton v-if="loading && rows.length === 0" :rows="8" animated />

    <template v-else>
      <section class="analysis-metrics" aria-label="综合分析核心指标">
        <article>
          <span>覆盖学校</span>
          <strong>{{ metrics.schools }}</strong>
        </article>
        <article>
          <span>课程开班</span>
          <strong>{{ metrics.offerings }}</strong>
        </article>
        <article>
          <span>有效报名</span>
          <strong>{{ metrics.enrollments }}</strong>
        </article>
        <article>
          <span>完成课次</span>
          <strong>{{ metrics.completedSessions }}</strong>
        </article>
        <article>
          <span>评价均分</span>
          <strong>{{ ratingText(metrics.averageRating) }}</strong>
        </article>
        <article>
          <span>满意度</span>
          <strong>{{ formatPercent(metrics.satisfactionRate) }}</strong>
        </article>
      </section>

      <section v-if="enrollmentSeries || satisfactionSeries" class="chart-grid">
        <ReportBarChart
          v-if="enrollmentSeries"
          title="有效报名较多的课程"
          :labels="enrollmentSeries.labels"
          :values="enrollmentSeries.values"
          value-label="报名数"
        />
        <ReportBarChart
          v-if="satisfactionSeries"
          title="有评价课程满意度"
          :labels="satisfactionSeries.labels"
          :values="satisfactionSeries.values"
          value-label="满意度(%)"
        />
      </section>

      <section v-if="rows.length" class="analysis-panel">
        <div class="section-heading">
          <div>
            <span>课程绩效</span>
            <h2>课程运行明细</h2>
          </div>
          <p>{{ rows.length }} 条开班记录，导出文件沿用同一筛选口径</p>
        </div>
        <el-table v-loading="loading" :data="rows" row-key="offeringId" table-layout="auto">
          <el-table-column prop="schoolName" label="学校" min-width="170" />
          <el-table-column label="课程与开班" min-width="220">
            <template #default="{ row }">
              <div class="course-cell">
                <strong>{{ row.courseName }}</strong>
                <span>{{ row.offeringCode }} · {{ row.termName }}</span>
                <small>{{ row.category }} / {{ row.teacherName }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" effect="plain">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="activeEnrollmentCount" label="有效报名" min-width="95" />
          <el-table-column label="完成课次" min-width="105">
            <template #default="{ row }">
              {{ row.completedSessions }} / {{ row.sessionCount }}
            </template>
          </el-table-column>
          <el-table-column label="完成课时" min-width="95">
            <template #default="{ row }">{{ row.completedHours }}</template>
          </el-table-column>
          <el-table-column label="出勤率" min-width="100">
            <template #default="{ row }">{{ formatPercent(Number(row.attendanceRate)) }}</template>
          </el-table-column>
          <el-table-column label="评价" min-width="125">
            <template #default="{ row }">
              <div class="rating-cell">
                <strong>{{ ratingText(Number(row.averageRating)) }}</strong>
                <span>{{ row.evaluationCount }} 份</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="满意度" min-width="100">
            <template #default="{ row }">{{ formatPercent(Number(row.satisfactionRate)) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="evaluations.length" class="analysis-panel">
        <div class="section-heading">
          <div>
            <span>匿名评价</span>
            <h2>近期课程评价</h2>
          </div>
          <p>监管视图不展示学生、家长和评价正文</p>
        </div>
        <el-table :data="evaluations" row-key="id" table-layout="auto">
          <el-table-column prop="schoolName" label="学校" min-width="170" />
          <el-table-column label="课程" min-width="210">
            <template #default="{ row }">
              <div class="course-cell">
                <strong>{{ row.courseName }}</strong>
                <span>{{ row.offeringCode }} · {{ row.termName }}</span>
                <small>{{ row.category }} / {{ row.teacherName }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="课程评分" min-width="150">
            <template #default="{ row }">
              <el-rate :model-value="row.courseRating" disabled show-score score-template="{value} 分" />
            </template>
          </el-table-column>
          <el-table-column label="教师评分" min-width="150">
            <template #default="{ row }">
              <el-rate :model-value="row.teacherRating" disabled show-score score-template="{value} 分" />
            </template>
          </el-table-column>
          <el-table-column label="提交时间" min-width="170">
            <template #default="{ row }">{{ formatDateTime(row.submittedAt) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="rectifications.length" class="analysis-panel">
        <div class="section-heading">
          <div>
            <span>违规整改</span>
            <h2>预警与整改多维统计</h2>
          </div>
          <p>按学校、类型、等级和状态汇总事项数、逾期数与平均闭环时长</p>
        </div>
        <el-table :data="rectifications" table-layout="auto">
          <el-table-column prop="schoolName" label="学校" min-width="170" />
          <el-table-column prop="alertType" label="违规类型" min-width="150" />
          <el-table-column prop="severity" label="等级" min-width="90" />
          <el-table-column prop="status" label="状态" min-width="120" />
          <el-table-column prop="alertCount" label="事项数" min-width="90" />
          <el-table-column prop="overdueCount" label="逾期数" min-width="90" />
          <el-table-column prop="averageCloseHours" label="平均闭环小时" min-width="130" />
        </el-table>
      </section>

      <div v-if="!rows.length && !evaluations.length && !rectifications.length && !displayedError" class="empty-state">
        <strong>当前条件下暂无分析数据</strong>
        <span>可放宽学校、学期、类别、状态或日期范围后重新查询。</span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.analysis-page {
  gap: 18px;
}

.analysis-panel,
.analysis-metrics article,
.empty-state {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
}

.analysis-panel {
  padding: 18px;
  overflow: hidden;
}

.analysis-filters {
  display: grid;
  grid-template-columns: repeat(5, minmax(150px, 1fr)) auto;
  gap: 12px;
  align-items: end;
}

.analysis-filters :deep(.el-form-item) {
  margin-bottom: 0;
}

.analysis-filters :deep(.el-select),
.analysis-filters :deep(.el-date-editor) {
  width: 100%;
}

.filter-actions {
  display: flex;
  gap: 8px;
  padding-bottom: 1px;
}

.filter-note {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.analysis-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.analysis-metrics article {
  padding: 16px;
}

.analysis-metrics span {
  color: var(--muted);
  font-size: 13px;
}

.analysis-metrics strong {
  display: block;
  margin-top: 7px;
  color: var(--ink);
  font-size: 23px;
  line-height: 1.1;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-heading span {
  display: block;
  margin-bottom: 4px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.section-heading h2 {
  margin: 0;
  color: var(--ink);
  font-size: 18px;
}

.section-heading p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.course-cell,
.rating-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.course-cell span,
.course-cell small,
.rating-cell span {
  color: var(--muted);
  line-height: 1.3;
}

.empty-state {
  display: flex;
  min-height: 230px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px;
  color: var(--muted);
}

.empty-state strong {
  color: var(--ink);
  font-size: 17px;
}

@media (max-width: 1240px) {
  .analysis-filters {
    grid-template-columns: repeat(3, minmax(160px, 1fr));
  }

  .analysis-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .analysis-filters,
  .analysis-metrics,
  .chart-grid {
    grid-template-columns: 1fr;
  }

  .filter-actions {
    justify-content: flex-end;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
