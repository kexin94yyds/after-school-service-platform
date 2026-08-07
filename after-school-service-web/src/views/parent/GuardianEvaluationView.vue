<script setup lang="ts">
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { evaluationApi } from '@/api/evaluations'
import type {
  GuardianAttendanceForEvaluation,
  GuardianEnrollmentForEvaluation,
  GuardianStudentForEvaluation,
} from '@/api/evaluations'
import { getErrorMessage, toApiClientError } from '@/api/http'
import PageHeader from '@/components/PageHeader.vue'
import {
  combineDateAndTime,
  formatDate,
  formatDateTime,
} from '@/utils/format'

interface EvaluationCandidate {
  enrollment: GuardianEnrollmentForEvaluation
  completedRecords: GuardianAttendanceForEvaluation[]
}

const route = useRoute()
const router = useRouter()
const students = ref<GuardianStudentForEvaluation[]>([])
const enrollments = ref<GuardianEnrollmentForEvaluation[]>([])
const attendance = ref<GuardianAttendanceForEvaluation[]>([])
const selectedStudentId = ref<number | null>(null)
const loading = ref(false)
const recordsLoading = ref(false)
const error = ref('')
const submittedKeys = ref<Set<string>>(new Set())
let recordsRequestId = 0

const dialogVisible = ref(false)
const submitting = ref(false)
const dialogError = ref('')
const selectedCandidate = ref<EvaluationCandidate | null>(null)
const form = reactive({
  rating: 0,
  comment: '',
})

const selectedStudent = computed(() =>
  students.value.find((item) => item.id === selectedStudentId.value),
)

const candidates = computed<EvaluationCandidate[]>(() => {
  if (!selectedStudentId.value) return []
  const recordsByOffering = new Map<string, GuardianAttendanceForEvaluation[]>()
  attendance.value.forEach((record) => {
    const records = recordsByOffering.get(record.offeringCode) ?? []
    records.push(record)
    recordsByOffering.set(record.offeringCode, records)
  })
  return enrollments.value
    .filter(
      (item) =>
        item.studentId === selectedStudentId.value &&
        item.status === 'ENROLLED' &&
        (recordsByOffering.get(item.offeringCode) ?? []).some(
          (record) =>
            combineDateAndTime(
              record.sessionDate,
              record.startTime,
            ).getTime() >=
            new Date(item.enrolledAt).getTime(),
        ),
    )
    .map((enrollment) => ({
      enrollment,
      completedRecords: (recordsByOffering.get(enrollment.offeringCode) ?? []).filter(
        (record) =>
          combineDateAndTime(
            record.sessionDate,
            record.startTime,
          ).getTime() >=
          new Date(enrollment.enrolledAt).getTime(),
      ),
    }))
})

function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

function queryStudentId(): number | null {
  const parsed = Number(firstQueryValue(route.query.studentId))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function evaluationKey(studentId: number, offeringId: number): string {
  return `${studentId}:${offeringId}`
}

function isSubmitted(candidate: EvaluationCandidate): boolean {
  return submittedKeys.value.has(
    evaluationKey(candidate.enrollment.studentId, candidate.enrollment.offeringId),
  )
}

function rememberSubmitted(candidate: EvaluationCandidate): void {
  const next = new Set(submittedKeys.value)
  next.add(
    evaluationKey(candidate.enrollment.studentId, candidate.enrollment.offeringId),
  )
  submittedKeys.value = next
}

async function saveStudentToUrl(): Promise<void> {
  const query: Record<string, string> = {}
  if (selectedStudentId.value) query.studentId = String(selectedStudentId.value)
  await router.replace({ query })
}

async function loadBase(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [studentItems, enrollmentItems, ownEvaluations] = await Promise.all([
      evaluationApi.getGuardianStudents(),
      evaluationApi.getGuardianEnrollments(),
      evaluationApi.getMine(),
    ])
    students.value = studentItems.filter((item) => item.status === 'ACTIVE')
    enrollments.value = enrollmentItems
    submittedKeys.value = new Set(
      ownEvaluations.map((item) =>
        evaluationKey(item.studentId, item.offeringId),
      ),
    )
    const requestedStudentId = queryStudentId()
    selectedStudentId.value = students.value.some(
      (item) => item.id === requestedStudentId,
    )
      ? requestedStudentId
      : (students.value[0]?.id ?? null)
    await saveStudentToUrl()
  } catch (loadError) {
    students.value = []
    enrollments.value = []
    submittedKeys.value = new Set()
    error.value = getErrorMessage(loadError, '学生与课程报名信息加载失败。')
  } finally {
    loading.value = false
  }
}

async function loadCompletedRecords(studentId: number | null): Promise<void> {
  const requestId = ++recordsRequestId
  attendance.value = []
  if (!studentId) {
    recordsLoading.value = false
    return
  }
  recordsLoading.value = true
  error.value = ''
  try {
    const records = await evaluationApi.getGuardianAttendance(studentId)
    if (requestId === recordsRequestId) attendance.value = records
  } catch (loadError) {
    if (requestId === recordsRequestId) {
      error.value = getErrorMessage(loadError, '课程完成记录加载失败。')
    }
  } finally {
    if (requestId === recordsRequestId) recordsLoading.value = false
  }
}

async function changeStudent(): Promise<void> {
  await saveStudentToUrl()
  await loadCompletedRecords(selectedStudentId.value)
}

async function refresh(): Promise<void> {
  await loadBase()
  await loadCompletedRecords(selectedStudentId.value)
}

function openEvaluation(candidate: EvaluationCandidate): void {
  if (isSubmitted(candidate)) return
  selectedCandidate.value = candidate
  form.rating = 0
  form.comment = ''
  dialogError.value = ''
  dialogVisible.value = true
}

async function submitEvaluation(): Promise<void> {
  const candidate = selectedCandidate.value
  if (!candidate) return
  if (form.rating < 1 || form.rating > 5) {
    dialogError.value = '请选择 1 到 5 星评分。'
    return
  }
  const comment = form.comment.trim()
  if (comment.length > 1000) {
    dialogError.value = '评价内容不能超过 1000 个字。'
    return
  }
  submitting.value = true
  dialogError.value = ''
  try {
    await evaluationApi.submit({
      studentId: candidate.enrollment.studentId,
      offeringId: candidate.enrollment.offeringId,
      rating: form.rating,
      comment: comment || null,
    })
    rememberSubmitted(candidate)
    dialogVisible.value = false
    ElMessage.success('课程评价已提交')
  } catch (submitError) {
    const apiError = toApiClientError(submitError, '课程评价提交失败。')
    if (apiError.code === 'EVALUATION_ALREADY_SUBMITTED') {
      rememberSubmitted(candidate)
      dialogVisible.value = false
      ElMessage.info('该学生已经评价过此课程')
    } else {
      dialogError.value = apiError.message
    }
  } finally {
    submitting.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <section class="page-stack evaluation-page">
    <PageHeader
      kicker="服务反馈"
      title="课后课程评价"
      description="对已有效报名且已有完成记录的课程提交一次评价，评价资格和重复提交由服务端最终校验。"
    >
      <template #actions>
        <el-button :loading="loading || recordsLoading" @click="refresh">刷新课程</el-button>
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

    <section class="student-panel">
      <div>
        <h2>选择学生</h2>
        <p>课程范围按当前账号绑定的学生和有效报名自动限定。</p>
      </div>
      <el-select
        v-model="selectedStudentId"
        class="student-select"
        :loading="loading"
        placeholder="请选择学生"
        @change="changeStudent"
      >
        <el-option
          v-for="student in students"
          :key="student.id"
          :label="`${student.fullName}（${student.className || student.studentNo}）`"
          :value="student.id"
        />
      </el-select>
    </section>

    <el-skeleton v-if="loading || recordsLoading" :rows="7" animated />

    <section v-else-if="selectedStudent" class="course-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">可评价课程</span>
          <h2>{{ selectedStudent.fullName }}的课程</h2>
        </div>
        <p>共 {{ candidates.length }} 门课程具备可识别的完成记录</p>
      </div>

      <div v-if="candidates.length" class="evaluation-grid">
        <article
          v-for="candidate in candidates"
          :key="candidate.enrollment.id"
          class="evaluation-card"
        >
          <header>
            <div>
              <span>{{ candidate.enrollment.term }}</span>
              <h3>{{ candidate.enrollment.courseName }}</h3>
            </div>
            <el-tag :type="isSubmitted(candidate) ? 'success' : 'info'" effect="plain">
              {{ isSubmitted(candidate) ? '已提交' : '待评价' }}
            </el-tag>
          </header>
          <dl>
            <div>
              <dt>授课教师</dt>
              <dd>{{ candidate.enrollment.teacherName }}</dd>
            </div>
            <div>
              <dt>开课编号</dt>
              <dd>{{ candidate.enrollment.offeringCode }}</dd>
            </div>
            <div>
              <dt>有效报名</dt>
              <dd>{{ formatDateTime(candidate.enrollment.enrolledAt) }}</dd>
            </div>
            <div>
              <dt>完成记录</dt>
              <dd>{{ candidate.completedRecords.length }} 次</dd>
            </div>
            <div>
              <dt>最近记录</dt>
              <dd>{{ formatDate(candidate.completedRecords[0]?.sessionDate) }}</dd>
            </div>
          </dl>
          <el-button
            type="primary"
            :disabled="isSubmitted(candidate)"
            @click="openEvaluation(candidate)"
          >
            {{ isSubmitted(candidate) ? '评价已提交' : '评价课程' }}
          </el-button>
        </article>
      </div>

      <div v-else class="empty-state">
        <strong>暂无可评价课程</strong>
        <span>已报名课程在产生完成记录后会显示在这里。</span>
      </div>
    </section>

    <div v-else-if="!error" class="empty-state standalone-empty">
      <strong>当前账号未绑定有效学生</strong>
      <span>请联系学校核对监护关系和学生状态。</span>
    </div>

    <el-dialog v-model="dialogVisible" title="提交课程评价" width="min(560px, 92vw)">
      <div v-if="selectedCandidate" class="dialog-course">
        <span>{{ selectedCandidate.enrollment.offeringCode }}</span>
        <strong>{{ selectedCandidate.enrollment.courseName }}</strong>
        <small>{{ selectedCandidate.enrollment.teacherName }}老师</small>
      </div>
      <el-alert
        v-if="dialogError"
        class="dialog-alert"
        :title="dialogError"
        type="error"
        show-icon
        :closable="false"
      />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="课程评分" required>
          <el-rate
            v-model="form.rating"
            size="large"
            show-text
            :texts="['很不满意', '不满意', '一般', '满意', '很满意']"
            aria-label="课程评分"
          />
        </el-form-item>
        <el-form-item label="评价内容">
          <el-input
            v-model="form.comment"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="可选，请填写具体体验或改进建议"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEvaluation">
          确认提交
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.evaluation-page {
  gap: 18px;
}

.student-panel,
.course-section,
.standalone-empty {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
}

.student-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 20px;
}

.student-panel h2,
.section-heading h2,
.evaluation-card h3 {
  margin: 0;
  color: var(--ink);
}

.student-panel h2,
.section-heading h2 {
  font-size: 18px;
}

.student-panel p,
.section-heading p {
  margin: 5px 0 0;
  color: var(--muted);
}

.student-select {
  width: min(360px, 100%);
}

.course-section {
  padding: 20px;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.section-kicker {
  display: block;
  margin-bottom: 5px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.evaluation-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.evaluation-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: #fbfcfb;
}

.evaluation-card header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.evaluation-card header span {
  color: var(--muted);
  font-size: 12px;
}

.evaluation-card h3 {
  margin-top: 4px;
  font-size: 18px;
}

.evaluation-card dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 0;
}

.evaluation-card dt {
  color: var(--muted);
  font-size: 12px;
}

.evaluation-card dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-size: 13px;
}

.evaluation-card .el-button {
  width: 100%;
  margin-top: auto;
}

.empty-state {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--muted);
}

.empty-state strong {
  color: var(--ink);
  font-size: 17px;
}

.standalone-empty {
  padding: 20px;
}

.dialog-course {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 18px;
  padding: 14px 16px;
  border-radius: var(--radius-md);
  background: #f2f7f4;
}

.dialog-course span,
.dialog-course small {
  color: var(--muted);
}

.dialog-course strong {
  color: var(--ink);
  font-size: 17px;
}

.dialog-alert {
  margin-bottom: 16px;
}

@media (max-width: 1080px) {
  .evaluation-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .student-panel,
  .section-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .student-select,
  .evaluation-grid {
    width: 100%;
  }

  .evaluation-grid {
    grid-template-columns: 1fr;
  }
}
</style>
