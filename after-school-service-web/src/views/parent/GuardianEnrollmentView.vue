<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, ref, watch } from 'vue'

import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import type {
  Enrollment,
  EnrollmentAction,
  GuardianAttendance,
  GuardianMonthlyAttendance,
  GuardianOffering,
  GuardianStudent,
} from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import {
  formatDate,
  formatDateTime,
  formatTime,
  statusLabel,
  weekdayLabel,
} from '@/utils/format'

const students = ref<GuardianStudent[]>([])
const selectedStudentId = ref<number | null>(null)
const offerings = ref<GuardianOffering[]>([])
const enrollments = ref<Enrollment[]>([])
const attendance = ref<GuardianAttendance[]>([])
const monthlyAttendance = ref<GuardianMonthlyAttendance | null>(null)
const attendanceMonth = ref(new Date().toISOString().slice(0, 7))
const loading = ref(false)
const studentDataLoading = ref(false)
const loadedStudentDataId = ref<number | null>(null)
const error = ref('')
const actingOfferingId = ref<number | null>(null)
const cancelingEnrollmentId = ref<number | null>(null)
const switchDialogVisible = ref(false)
const switchingEnrollment = ref<Enrollment | null>(null)
const switchTargetId = ref<number | null>(null)
const switching = ref(false)
const actionDialogVisible = ref(false)
const enrollmentActions = ref<EnrollmentAction[]>([])
const actionLoading = ref(false)
type OfferingFilter = 'ALL' | 'AVAILABLE' | 'ENROLLED'
type RecordTab = 'ENROLLMENTS' | 'ATTENDANCE' | 'MONTHLY'
const offeringFilter = ref<OfferingFilter>('ALL')
const recordTab = ref<RecordTab>('ENROLLMENTS')
let studentDataRequestId = 0
let baseRequestId = 0

const selectedStudent = computed(() =>
  students.value.find((item) => item.id === selectedStudentId.value),
)

const activeEnrollmentByOffering = computed(
  () =>
    new Map(
      enrollments.value
        .filter(
          (item) =>
            item.studentId === selectedStudentId.value &&
            item.status === 'ENROLLED',
        )
        .map((item) => [item.offeringId, item]),
    ),
)

const studentEnrollments = computed(() =>
  enrollments.value.filter(
    (item) => item.studentId === selectedStudentId.value,
  ),
)

const availableOfferingCount = computed(
  () => offerings.value.filter((offering) => canEnroll(offering)).length,
)

const enrolledOfferingCount = computed(
  () =>
    offerings.value.filter((offering) =>
      activeEnrollmentByOffering.value.has(offering.id),
    ).length,
)

const filteredOfferings = computed(() => {
  if (offeringFilter.value === 'AVAILABLE') {
    return offerings.value.filter((offering) => canEnroll(offering))
  }
  if (offeringFilter.value === 'ENROLLED') {
    return offerings.value.filter((offering) =>
      activeEnrollmentByOffering.value.has(offering.id),
    )
  }
  return offerings.value
})

function remainingCapacity(offering: GuardianOffering): number {
  return Math.max(0, offering.capacity - offering.enrolledCount)
}

function canEnroll(offering: GuardianOffering): boolean {
  return offering.canEnroll
}

function capacityPercent(offering: GuardianOffering): number {
  if (offering.capacity <= 0) return 100
  return Math.min(
    100,
    Math.round((offering.enrolledCount / offering.capacity) * 100),
  )
}

function capacityTone(offering: GuardianOffering): string {
  const remaining = remainingCapacity(offering)
  if (remaining === 0) return 'is-full'
  if (remaining <= Math.max(2, Math.ceil(offering.capacity * 0.15))) {
    return 'is-tight'
  }
  return 'is-open'
}

function categoryTone(category?: string | null): string {
  const value = category || ''
  if (/科技|科学|编程|信息/.test(value)) return 'is-science'
  if (/艺术|美术|音乐|舞蹈|戏剧/.test(value)) return 'is-art'
  if (/体育|运动|球|体能/.test(value)) return 'is-sport'
  return 'is-general'
}

function categoryMark(category?: string | null): string {
  return (category || '课后').slice(0, 2)
}

async function loadBase(): Promise<void> {
  const requestId = ++baseRequestId
  loading.value = true
  error.value = ''
  try {
    const [studentItems, enrollmentItems] = await Promise.all([
      enrollmentApi.getGuardianStudents(),
      enrollmentApi.getEnrollments(),
    ])
    if (requestId !== baseRequestId) return
    students.value = studentItems
    enrollments.value = enrollmentItems
    if (
      selectedStudentId.value === null ||
      !studentItems.some((item) => item.id === selectedStudentId.value)
    ) {
      selectedStudentId.value = studentItems[0]?.id ?? null
    }
  } catch (loadError) {
    if (requestId === baseRequestId) {
      error.value = getErrorMessage(loadError, '学生与报名信息加载失败。')
    }
  } finally {
    if (requestId === baseRequestId) loading.value = false
  }
}

async function loadStudentData(studentId: number | null): Promise<void> {
  if (studentId !== selectedStudentId.value) return
  const requestId = ++studentDataRequestId
  offerings.value = []
  attendance.value = []
  loadedStudentDataId.value = null
  if (studentId === null) {
    studentDataLoading.value = false
    return
  }
  studentDataLoading.value = true
  error.value = ''
  try {
    const [offeringItems, attendanceItems, monthly] = await Promise.all([
      enrollmentApi.getStudentOfferings(studentId),
      enrollmentApi.getStudentAttendance(studentId),
      enrollmentApi.getStudentMonthlyAttendance(studentId, attendanceMonth.value),
    ])
    if (
      requestId !== studentDataRequestId ||
      selectedStudentId.value !== studentId
    ) {
      return
    }
    offerings.value = offeringItems
    attendance.value = attendanceItems
    monthlyAttendance.value = monthly
    loadedStudentDataId.value = studentId
  } catch (loadError) {
    if (
      requestId === studentDataRequestId &&
      selectedStudentId.value === studentId
    ) {
      error.value = getErrorMessage(
        loadError,
        '学生课程与出勤信息加载失败。',
      )
    }
  } finally {
    if (
      requestId === studentDataRequestId &&
      selectedStudentId.value === studentId
    ) {
      studentDataLoading.value = false
    }
  }
}

async function loadMonthlyAttendance(): Promise<void> {
  if (!selectedStudentId.value) return
  try {
    monthlyAttendance.value = await enrollmentApi.getStudentMonthlyAttendance(
      selectedStudentId.value,
      attendanceMonth.value,
    )
  } catch (loadError) {
    ElMessage.error(getErrorMessage(loadError, '月度考勤加载失败。'))
  }
}

async function refresh(): Promise<void> {
  const previousStudentId = selectedStudentId.value
  await loadBase()
  if (selectedStudentId.value === previousStudentId) {
    await loadStudentData(selectedStudentId.value)
  }
}

async function enroll(offering: GuardianOffering): Promise<void> {
  const studentId = selectedStudentId.value
  if (
    studentId === null ||
    loadedStudentDataId.value !== studentId ||
    !offerings.value.some((item) => item.id === offering.id)
  ) {
    ElMessage.warning('学生或课程信息已变化，请重新确认后报名。')
    return
  }
  if (!canEnroll(offering)) return
  actingOfferingId.value = offering.id
  try {
    await enrollmentApi.enroll(studentId, offering.id)
    ElMessage.success('报名成功')
    await loadBase()
    if (selectedStudentId.value === studentId) {
      await loadStudentData(studentId)
    }
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '报名失败，请重试。'))
  } finally {
    actingOfferingId.value = null
  }
}

async function cancelEnrollment(enrollment: Enrollment): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消${enrollment.courseName ? `“${enrollment.courseName}”` : '该课程'}的报名吗？`,
      '取消报名',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '保留报名',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  cancelingEnrollmentId.value = enrollment.id
  try {
    await enrollmentApi.cancel(enrollment.id)
    ElMessage.success('报名已取消')
    await Promise.all([
      loadBase(),
      loadStudentData(selectedStudentId.value),
    ])
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '取消报名失败。'))
  } finally {
    cancelingEnrollmentId.value = null
  }
}

function openSwitch(enrollment: Enrollment): void {
  switchingEnrollment.value = enrollment
  switchTargetId.value = null
  switchDialogVisible.value = true
}

async function confirmSwitch(): Promise<void> {
  if (!switchingEnrollment.value || !switchTargetId.value) return
  switching.value = true
  try {
    await enrollmentApi.switchEnrollment(
      switchingEnrollment.value.id,
      switchTargetId.value,
    )
    ElMessage.success('改选成功，原报名和新报名已原子更新')
    switchDialogVisible.value = false
    await refresh()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '改选失败。'))
  } finally {
    switching.value = false
  }
}

async function showEnrollmentActions(enrollment: Enrollment): Promise<void> {
  actionDialogVisible.value = true
  actionLoading.value = true
  try {
    enrollmentActions.value = await enrollmentApi.getActions(enrollment.id)
  } catch (loadError) {
    enrollmentActions.value = []
    ElMessage.error(getErrorMessage(loadError, '报名变更历史加载失败。'))
  } finally {
    actionLoading.value = false
  }
}

watch(selectedStudentId, (studentId) => {
  offeringFilter.value = 'ALL'
  void loadStudentData(studentId)
})

onMounted(refresh)
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="家长选课"
      title="为学生选择课后课程"
      description="可选范围由监护关系、学生年级、报名时段、容量和冲突规则共同决定，最终结果以服务端确认为准。"
    >
      <template #actions>
        <el-button :loading="loading || studentDataLoading" @click="refresh">
          刷新
        </el-button>
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

    <section class="student-selector-panel enrollment-student-panel">
      <div class="student-profile">
        <span class="student-avatar" aria-hidden="true">
          {{ selectedStudent?.fullName.slice(0, 1) || '生' }}
        </span>
        <div>
          <h2>{{ selectedStudent?.fullName || '选择学生' }}</h2>
          <p v-if="selectedStudent">
            {{ selectedStudent.grade ? `${selectedStudent.grade} 年级` : '在校学生' }}
            <span v-if="selectedStudent.className"> · {{ selectedStudent.className }}</span>
          </p>
          <p v-else>这里只显示当前账号已绑定的学生。</p>
        </div>
      </div>
      <div class="student-selection-actions">
        <div v-if="selectedStudent" class="student-course-summary" aria-label="选课概览">
          <span><strong>{{ availableOfferingCount }}</strong> 可报名</span>
          <span><strong>{{ enrolledOfferingCount }}</strong> 已报名</span>
        </div>
        <el-select
          v-model="selectedStudentId"
          :loading="loading"
          placeholder="请选择学生"
          class="student-selector"
          aria-label="选择学生"
        >
          <el-option
            v-for="student in students"
            :key="student.id"
            :label="`${student.fullName}（${student.className || student.studentNo}）`"
            :value="student.id"
          />
        </el-select>
      </div>
    </section>

    <section v-if="selectedStudent" class="selection-section">
      <div class="course-section-heading">
        <div class="section-heading">
          <h2>发现课程</h2>
          <p>按报名状态快速筛选，为 {{ selectedStudent.fullName }} 选择合适的课后课程。</p>
        </div>
        <div class="course-filters" role="group" aria-label="筛选课程">
          <button
            type="button"
            :class="{ active: offeringFilter === 'ALL' }"
            :aria-pressed="offeringFilter === 'ALL'"
            @click="offeringFilter = 'ALL'"
          >
            全部 <span>{{ offerings.length }}</span>
          </button>
          <button
            type="button"
            :class="{ active: offeringFilter === 'AVAILABLE' }"
            :aria-pressed="offeringFilter === 'AVAILABLE'"
            @click="offeringFilter = 'AVAILABLE'"
          >
            可报名 <span>{{ availableOfferingCount }}</span>
          </button>
          <button
            type="button"
            :class="{ active: offeringFilter === 'ENROLLED' }"
            :aria-pressed="offeringFilter === 'ENROLLED'"
            @click="offeringFilter = 'ENROLLED'"
          >
            已报名 <span>{{ enrolledOfferingCount }}</span>
          </button>
        </div>
      </div>

      <div v-loading="studentDataLoading" class="offering-grid">
        <article
          v-for="offering in filteredOfferings"
          :key="offering.id"
          class="offering-card"
          :class="[
            categoryTone(offering.category),
            { 'is-enrolled': activeEnrollmentByOffering.has(offering.id) },
          ]"
        >
          <header class="offering-card-hero">
            <span class="category-mark" aria-hidden="true">
              {{ categoryMark(offering.category) }}
            </span>
            <div class="offering-title">
              <span>{{ offering.category || '课后课程' }}</span>
              <h3>{{ offering.courseName || offering.offeringCode }}</h3>
            </div>
            <el-tag v-if="activeEnrollmentByOffering.has(offering.id)" type="success" effect="dark">
              已报名
            </el-tag>
            <el-tag v-else effect="plain">{{ statusLabel(offering.status) }}</el-tag>
          </header>

          <div class="offering-quick-meta">
            <span>
              <small>上课时间</small>
              <strong>
                {{ weekdayLabel(offering.weekDay) }}
                {{ formatTime(offering.startTime) }}–{{ formatTime(offering.endTime) }}
              </strong>
            </span>
            <span>
              <small>上课教室</small>
              <strong>{{ offering.classroom }}</strong>
            </span>
          </div>

          <div class="capacity-block" :class="capacityTone(offering)">
            <div>
              <span>剩余 {{ remainingCapacity(offering) }} 个名额</span>
              <strong>{{ offering.enrolledCount }} / {{ offering.capacity }}</strong>
            </div>
            <span class="capacity-track" aria-hidden="true">
              <i :style="{ width: `${capacityPercent(offering)}%` }"></i>
            </span>
          </div>

          <dl>
            <div>
              <dt>课程周期</dt>
              <dd>{{ formatDate(offering.startDate) }} 至 {{ formatDate(offering.endDate) }}</dd>
            </div>
            <div>
              <dt>报名截止</dt>
              <dd>{{ formatDateTime(offering.enrollmentEnd) }}</dd>
            </div>
            <div class="eligibility-row">
              <dt>报名校验</dt>
              <dd>{{ offering.eligibilityMessage }}</dd>
            </div>
          </dl>
          <el-button
            type="primary"
            :disabled="!canEnroll(offering)"
            :loading="actingOfferingId === offering.id"
            @click="enroll(offering)"
          >
            {{
              activeEnrollmentByOffering.has(offering.id)
                ? '已报名'
                : offering.canEnroll
                  ? '立即报名'
                  : offering.eligibilityMessage
            }}
          </el-button>
        </article>
        <div
          v-if="!studentDataLoading && filteredOfferings.length === 0"
          class="empty-state large"
        >
          <strong>{{ offerings.length === 0 ? '暂无课程开班' : '当前筛选下没有课程' }}</strong>
          <span>
            {{ offerings.length === 0 ? '当前学生所在学校暂时没有已发布课程。' : '可以切换到“全部”继续浏览。' }}
          </span>
        </div>
      </div>
    </section>

    <section v-if="selectedStudent" class="entity-panel enrollment-record-panel">
      <div class="record-panel-heading">
        <div>
          <h2>我的选课</h2>
          <p>报名历史与课程出勤集中在这里查看。</p>
        </div>
        <div class="record-tabs" role="tablist" aria-label="选课记录类型">
          <button
            type="button"
            role="tab"
            :aria-selected="recordTab === 'ENROLLMENTS'"
            :class="{ active: recordTab === 'ENROLLMENTS' }"
            @click="recordTab = 'ENROLLMENTS'"
          >
            报名记录 <span>{{ studentEnrollments.length }}</span>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="recordTab === 'ATTENDANCE'"
            :class="{ active: recordTab === 'ATTENDANCE' }"
            @click="recordTab = 'ATTENDANCE'"
          >
            出勤记录 <span>{{ attendance.length }}</span>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="recordTab === 'MONTHLY'"
            :class="{ active: recordTab === 'MONTHLY' }"
            @click="recordTab = 'MONTHLY'"
          >
            月度考勤
          </button>
        </div>
      </div>
      <section v-if="recordTab === 'MONTHLY'" class="monthly-attendance">
        <div class="list-toolbar">
          <el-date-picker
            v-model="attendanceMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择月份"
            @change="loadMonthlyAttendance"
          />
          <el-button @click="loadMonthlyAttendance">刷新月报</el-button>
        </div>
        <div class="report-metrics">
          <article><span>应记课次</span><strong>{{ monthlyAttendance?.summary.totalCount || 0 }}</strong></article>
          <article><span>正常/迟到</span><strong>{{ (monthlyAttendance?.summary.presentCount || 0) + (monthlyAttendance?.summary.lateCount || 0) }}</strong></article>
          <article><span>请假</span><strong>{{ monthlyAttendance?.summary.leaveCount || 0 }}</strong></article>
          <article><span>缺勤</span><strong>{{ monthlyAttendance?.summary.absentCount || 0 }}</strong></article>
          <article><span>出勤率</span><strong>{{ monthlyAttendance?.summary.attendanceRate || 0 }}%</strong></article>
        </div>
      </section>
      <el-table
        v-if="recordTab === 'ATTENDANCE'"
        v-loading="studentDataLoading"
        :data="attendance"
        row-key="id"
        table-layout="auto"
      >
        <el-table-column prop="courseName" label="课程" min-width="150">
          <template #default="{ row }">
            {{ row.courseName || row.offeringCode }}
          </template>
        </el-table-column>
        <el-table-column prop="teacherName" label="教师" min-width="110" />
        <el-table-column prop="sessionDate" label="课次时间" min-width="190">
          <template #default="{ row }">
            {{ formatDate(row.sessionDate) }}
            {{ formatTime(row.startTime) }}-{{ formatTime(row.endTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="出勤" min-width="100">
          <template #default="{ row }">
            <el-tag effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160">
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>暂无出勤记录</strong>
            <span>教师完成课次考勤后，记录会显示在这里。</span>
          </div>
        </template>
      </el-table>
      <el-table
        v-else-if="recordTab === 'ENROLLMENTS'"
        :data="studentEnrollments"
        row-key="id"
        table-layout="auto"
      >
        <el-table-column prop="courseName" label="课程" min-width="150">
          <template #default="{ row }">
            {{ row.courseName || row.offeringCode || row.offeringId }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enrolledAt" label="报名时间" min-width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.enrolledAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="240" align="right">
          <template #default="{ row }">
            <el-button text @click="showEnrollmentActions(row)">变更历史</el-button>
            <el-button
              v-if="row.status === 'ENROLLED'"
              text
              type="primary"
              @click="openSwitch(row)"
            >
              改选
            </el-button>
            <el-button
              v-if="row.status === 'ENROLLED'"
              text
              type="danger"
              :loading="cancelingEnrollmentId === row.id"
              @click="cancelEnrollment(row)"
            >
              取消报名
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>暂无报名</strong>
            <span>该学生还没有报名记录。</span>
          </div>
        </template>
      </el-table>
      <el-table
        v-else
        :data="monthlyAttendance?.records || []"
        table-layout="auto"
      >
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="sessionDate" label="日期" min-width="120">
          <template #default="{ row }">{{ formatDate(row.sessionDate) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="出勤" min-width="100">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" />
      </el-table>
    </section>

    <el-dialog v-model="switchDialogVisible" title="改选课程" width="min(520px, calc(100vw - 32px))">
      <p>原课程：{{ switchingEnrollment?.courseName }}</p>
      <el-select v-model="switchTargetId" filterable placeholder="选择新课程" style="width: 100%">
        <el-option
          v-for="offering in offerings.filter((item) => item.id !== switchingEnrollment?.offeringId)"
          :key="offering.id"
          :label="`${offering.courseName}（剩余 ${remainingCapacity(offering)}）`"
          :value="offering.id"
          :disabled="remainingCapacity(offering) <= 0"
        />
      </el-select>
      <template #footer>
        <el-button @click="switchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="switching" :disabled="!switchTargetId" @click="confirmSwitch">
          确认改选
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="actionDialogVisible" title="报名变更历史" width="min(720px, calc(100vw - 32px))">
      <el-table v-loading="actionLoading" :data="enrollmentActions" table-layout="auto">
        <el-table-column prop="actionType" label="动作" min-width="110" />
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="actorName" label="操作人" min-width="110" />
        <el-table-column prop="actedAt" label="时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.actedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <div v-if="!loading && students.length === 0" class="empty-state page-empty">
      <strong>暂无已绑定学生</strong>
      <span>请联系学校管理员核对监护关系。</span>
    </div>
  </section>
</template>

<style scoped>
.enrollment-student-panel {
  border: 1px solid #cbdced;
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(120deg, rgb(227 239 255 / 78%) 0%, rgb(255 255 255 / 94%) 52%, rgb(231 246 238 / 76%) 100%);
  box-shadow: 0 9px 24px rgb(39 77 117 / 6%);
}

.student-profile,
.student-selection-actions,
.student-course-summary,
.course-section-heading,
.course-filters,
.record-panel-heading,
.record-tabs,
.offering-quick-meta,
.capacity-block > div {
  display: flex;
  align-items: center;
}

.student-profile {
  gap: 14px;
}

.student-avatar {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 14px;
  color: #ffffff;
  font-family: "STKaiti", "KaiTi", serif;
  font-size: 20px;
  font-weight: 800;
  background: linear-gradient(145deg, #1f5faf, #367dc7);
  box-shadow: 0 8px 17px rgb(31 95 175 / 20%);
}

.student-selection-actions {
  justify-content: flex-end;
  gap: 18px;
}

.student-course-summary {
  gap: 16px;
  padding-right: 18px;
  border-right: 1px solid var(--line-strong);
}

.student-course-summary span {
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}

.student-course-summary strong {
  margin-right: 4px;
  color: var(--accent);
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}

.course-section-heading {
  justify-content: space-between;
  gap: 20px;
}

.course-section-heading .section-heading {
  margin-bottom: 18px;
}

.course-filters,
.record-tabs {
  gap: 5px;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: rgb(255 255 255 / 82%);
}

.course-filters button,
.record-tabs button {
  padding: 8px 12px;
  border: 0;
  border-radius: 7px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
  background: transparent;
  cursor: pointer;
}

.course-filters button:hover,
.record-tabs button:hover {
  color: var(--accent);
  background: var(--accent-soft);
}

.course-filters button.active,
.record-tabs button.active {
  color: #ffffff;
  background: var(--accent);
  box-shadow: 0 5px 12px rgb(31 95 175 / 18%);
}

.course-filters button span,
.record-tabs button span {
  display: inline-grid;
  min-width: 18px;
  height: 18px;
  margin-left: 4px;
  place-items: center;
  border-radius: 999px;
  font-size: 10px;
  background: rgb(31 95 175 / 10%);
}

.course-filters button.active span,
.record-tabs button.active span {
  background: rgb(255 255 255 / 20%);
}

.offering-card {
  --course-color: #1f5faf;
  --course-soft: #e6f0fd;
  padding: 0;
  overflow: hidden;
  border-color: #cbdced;
  box-shadow: 0 12px 30px rgb(35 74 115 / 7%);
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.offering-card:hover {
  border-color: color-mix(in srgb, var(--course-color) 46%, #ffffff);
  box-shadow: 0 17px 34px rgb(35 74 115 / 11%);
  transform: translateY(-2px);
}

.offering-card.is-art {
  --course-color: #b65379;
  --course-soft: #faeaf0;
}

.offering-card.is-sport {
  --course-color: #28835f;
  --course-soft: #e4f4ec;
}

.offering-card.is-general {
  --course-color: #7b62b6;
  --course-soft: #eeeafa;
}

.offering-card-hero {
  position: relative;
  min-height: 118px;
  padding: 22px;
  overflow: hidden;
  background:
    linear-gradient(112deg, var(--course-soft) 0%, rgb(255 255 255 / 92%) 76%);
}

.offering-card-hero::after {
  position: absolute;
  right: 82px;
  bottom: -42px;
  width: 112px;
  height: 112px;
  border: 20px solid color-mix(in srgb, var(--course-color) 9%, transparent);
  border-radius: 50%;
  content: "";
}

.offering-title {
  position: relative;
  z-index: 1;
  align-self: center;
}

.offering-card .offering-title > span {
  color: var(--course-color);
  letter-spacing: 0.08em;
}

.offering-card h3 {
  font-size: 23px;
}

.category-mark {
  display: grid;
  width: 56px;
  height: 56px;
  flex: 0 0 auto;
  position: relative;
  z-index: 1;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--course-color) 20%, #ffffff);
  border-radius: 17px;
  color: var(--course-color) !important;
  font-size: 14px !important;
  font-weight: 800;
  background: rgb(255 255 255 / 76%);
  box-shadow: 0 8px 18px color-mix(in srgb, var(--course-color) 11%, transparent);
}

.offering-card-hero :deep(.el-tag) {
  position: relative;
  z-index: 1;
}

.offering-quick-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding: 18px 22px 0;
}

.offering-quick-meta > span {
  min-width: 0;
  padding: 12px 13px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #f8fbfe;
}

.offering-quick-meta small {
  display: block;
  color: var(--subtle);
  font-size: 10px;
}

.offering-quick-meta strong {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capacity-block {
  margin: 14px 22px 0;
  padding: 12px 13px;
  border-radius: 10px;
  background: var(--course-soft);
}

.capacity-block > div {
  justify-content: space-between;
  gap: 12px;
  color: var(--muted);
  font-size: 11px;
}

.capacity-block > div strong {
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}

.capacity-track {
  display: block;
  height: 5px;
  margin-top: 9px;
  overflow: hidden;
  border-radius: 999px;
  background: rgb(255 255 255 / 72%);
}

.capacity-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--course-color);
}

.capacity-block.is-tight .capacity-track i {
  background: var(--warning);
}

.capacity-block.is-full .capacity-track i {
  background: var(--danger);
}

.offering-card dl {
  gap: 12px 18px;
  margin: 17px 22px 20px;
}

.offering-card .eligibility-row {
  grid-column: 1 / -1;
  padding-top: 12px;
  border-top: 1px dashed var(--line-strong);
}

.offering-card > .el-button {
  width: auto;
  margin: auto 22px 22px;
}

.offering-card.is-enrolled {
  border-color: color-mix(in srgb, var(--success) 42%, #ffffff);
}

.record-panel-heading {
  justify-content: space-between;
  gap: 20px;
  padding: 21px 24px;
  border-bottom: 1px solid var(--line);
}

.record-panel-heading h2 {
  margin: 0;
  font-size: 18px;
}

.record-panel-heading p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 900px) {
  .enrollment-student-panel,
  .student-selection-actions,
  .course-section-heading,
  .record-panel-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .student-selection-actions {
    width: 100%;
  }

  .student-course-summary {
    justify-content: space-between;
    padding: 0 0 14px;
    border-right: 0;
    border-bottom: 1px solid var(--line-strong);
  }

  .student-selection-actions :deep(.student-selector) {
    width: 100%;
  }

  .course-filters,
  .record-tabs {
    display: grid;
    width: 100%;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .record-tabs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .offering-card-hero {
    min-height: 108px;
    padding: 18px;
  }

  .category-mark {
    width: 48px;
    height: 48px;
    border-radius: 14px;
  }

  .offering-card h3 {
    font-size: 21px;
  }

  .offering-quick-meta {
    grid-template-columns: 1fr;
    padding: 15px 17px 0;
  }

  .capacity-block {
    margin: 12px 17px 0;
  }

  .offering-card dl {
    grid-template-columns: 1fr;
    margin: 16px 17px 18px;
  }

  .offering-card .eligibility-row {
    grid-column: auto;
  }

  .offering-card > .el-button {
    margin: auto 17px 18px;
  }

  .course-filters button,
  .record-tabs button {
    padding: 8px 5px;
  }
}
</style>
