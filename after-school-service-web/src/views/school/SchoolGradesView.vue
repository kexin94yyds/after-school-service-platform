<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  evaluationApi,
  type CourseEvaluation,
  type EvaluationSummary,
} from '@/api/evaluations'
import { gradeApi, type GradeSummary, type StudentGrade } from '@/api/grades'
import { getErrorMessage } from '@/api/http'
import PageHeader from '@/components/PageHeader.vue'
import ReportBarChart from '@/components/ReportBarChart.vue'

const grades = ref<StudentGrade[]>([])
const gradeSummary = ref<GradeSummary[]>([])
const evaluations = ref<CourseEvaluation[]>([])
const evaluationSummary = ref<EvaluationSummary | null>(null)
const loading = ref(false)
const error = ref('')

const courseLabels = computed(() =>
  gradeSummary.value.map((item) => item.courseName),
)
const averageScores = computed(() =>
  gradeSummary.value.map((item) => Number(item.averageScore || 0)),
)
const ratingLabels = ['课程评分', '教师评分']
const ratingValues = computed(() => [
  Number(evaluationSummary.value?.averageCourseRating || 0),
  Number(evaluationSummary.value?.averageTeacherRating || 0),
])

function formatRating(value: number | undefined): string {
  return Number(value || 0).toFixed(1)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [gradeRows, summaries, evaluationRows, ratingSummary] =
      await Promise.all([
        gradeApi.getGrades(),
        gradeApi.getSummary(),
        evaluationApi.list(),
        evaluationApi.summary(),
      ])
    grades.value = gradeRows
    gradeSummary.value = summaries
    evaluations.value = evaluationRows
    evaluationSummary.value = ratingSummary
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '成绩与家长评价统计加载失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="教务质量"
      title="成绩与学习评价统计"
      description="统一查看学生成绩、课程评分、教师评分和家长意见，为课程调整提供依据。"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" @click="gradeApi.downloadGrades">
          导出成绩 Excel
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
    />

    <section class="quality-metrics" aria-label="家长评价指标">
      <article>
        <span>有效评价</span>
        <strong>{{ evaluationSummary?.evaluationCount ?? 0 }}</strong>
        <small>条家长反馈</small>
      </article>
      <article>
        <span>课程评分</span>
        <strong>{{ formatRating(evaluationSummary?.averageCourseRating) }}</strong>
        <small>满分 5 分</small>
      </article>
      <article>
        <span>教师评分</span>
        <strong>{{ formatRating(evaluationSummary?.averageTeacherRating) }}</strong>
        <small>满分 5 分</small>
      </article>
      <article>
        <span>课程满意率</span>
        <strong>{{ formatRating(evaluationSummary?.satisfactionRate) }}%</strong>
        <small>4–5 分评价占比</small>
      </article>
    </section>

    <div v-if="gradeSummary.length || evaluationSummary" class="report-chart-grid">
      <ReportBarChart
        v-if="gradeSummary.length"
        title="课程平均成绩"
        :labels="courseLabels"
        :values="averageScores"
        value-label="平均分"
      />
      <ReportBarChart
        v-if="evaluationSummary"
        title="家长双维度评分"
        :labels="ratingLabels"
        :values="ratingValues"
        value-label="平均分"
      />
    </div>

    <section class="entity-panel">
      <div class="section-heading">
        <div>
          <span class="section-kicker">课程表现</span>
          <h2>成绩分布</h2>
        </div>
      </div>
      <el-table v-loading="loading" :data="gradeSummary" table-layout="auto">
        <el-table-column prop="courseName" label="课程" />
        <el-table-column prop="teacherName" label="教师" />
        <el-table-column prop="gradedCount" label="已评分" />
        <el-table-column prop="averageScore" label="平均分" />
        <el-table-column prop="excellentCount" label="优秀" />
        <el-table-column prop="goodCount" label="良好" />
        <el-table-column prop="passCount" label="及格" />
        <el-table-column prop="needsSupportCount" label="待提升" />
      </el-table>
    </section>

    <section class="entity-panel">
      <div class="section-heading">
        <div>
          <span class="section-kicker">家长声音</span>
          <h2>课程、教师评分与意见</h2>
        </div>
        <span class="record-count">{{ evaluations.length }} 条</span>
      </div>
      <el-table v-loading="loading" :data="evaluations" table-layout="auto">
        <el-table-column prop="submittedAt" label="提交时间" min-width="150" />
        <el-table-column prop="studentName" label="学生" />
        <el-table-column prop="guardianName" label="家长" />
        <el-table-column prop="courseName" label="课程" />
        <el-table-column prop="teacherName" label="教师" />
        <el-table-column label="课程评分" width="110">
          <template #default="{ row }">{{ row.courseRating }} / 5</template>
        </el-table-column>
        <el-table-column label="教师评分" width="110">
          <template #default="{ row }">{{ row.teacherRating }} / 5</template>
        </el-table-column>
        <el-table-column prop="comment" label="家长意见" min-width="260">
          <template #default="{ row }">{{ row.comment || '未填写' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <section class="entity-panel">
      <div class="section-heading">
        <div>
          <span class="section-kicker">成绩明细</span>
          <h2>学生课程成绩</h2>
        </div>
      </div>
      <el-table v-loading="loading" :data="grades" table-layout="auto">
        <el-table-column prop="studentName" label="学生" />
        <el-table-column prop="className" label="班级" />
        <el-table-column prop="courseName" label="课程" />
        <el-table-column prop="teacherName" label="教师" />
        <el-table-column prop="score" label="成绩" />
        <el-table-column
          prop="learningEvaluation"
          label="学习评价"
          min-width="260"
        />
      </el-table>
    </section>
  </section>
</template>

<style scoped>
.quality-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.quality-metrics article {
  display: grid;
  gap: 6px;
  padding: 20px;
  border: 1px solid #d9e3ef;
  border-radius: var(--radius-lg);
  background: linear-gradient(145deg, #fff, #f4f8fc);
}

.quality-metrics span,
.quality-metrics small,
.record-count {
  color: var(--muted);
}

.quality-metrics strong {
  color: var(--accent-strong);
  font-size: 30px;
  line-height: 1.1;
}

@media (max-width: 900px) {
  .quality-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .quality-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
