<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/http'
import {
  leaveCorrectionApi,
  type GuardianLeaveSession,
  type GuardianStudentSummary,
  type LeaveRequest,
  type LeaveStatus,
} from '@/api/leaveCorrections'
import PageHeader from '@/components/PageHeader.vue'
import { useLongFormGuard } from '@/composables/useLongFormGuard'
import { formatDate, formatDateTime, formatTime } from '@/utils/format'

type WorkflowTagType = 'success' | 'warning' | 'danger' | 'info'

const route = useRoute()
const router = useRouter()
const students = ref<GuardianStudentSummary[]>([])
const selectedStudentId = ref<number | null>(null)
const sessions = ref<GuardianLeaveSession[]>([])
const requests = ref<LeaveRequest[]>([])
const statusFilter = ref<LeaveStatus | ''>('')
const loading = ref(false)
const sessionLoading = ref(false)
const loadedSessionStudentId = ref<number | null>(null)
const requestLoading = ref(false)
const submitting = ref(false)
const withdrawingId = ref<number | null>(null)
const error = ref('')
const leaveDialogVisible = ref(false)
const selectedSession = ref<GuardianLeaveSession | null>(null)
const leaveStudentId = ref<number | null>(null)
const leaveReason = ref('')
const {
  beforeClose: beforeLeaveDialogClose,
  captureBaseline: captureLeaveBaseline,
  requestClose: requestLeaveDialogClose,
} = useLongFormGuard({
  visible: leaveDialogVisible,
  saving: submitting,
  snapshot: () => ({ reason: leaveReason.value }),
})
let initialized = false
let sessionRequestId = 0
let leaveRequestsRequestId = 0

const selectedStudent = computed(() =>
  students.value.find((item) => item.id === selectedStudentId.value),
)
const studentRequests = computed(() =>
  requests.value.filter((item) => item.studentId === selectedStudentId.value),
)
const pendingCount = computed(
  () => sessions.value.filter((item) => item.activeLeaveStatus === 'PENDING').length,
)
const approvedCount = computed(
  () => sessions.value.filter((item) => item.activeLeaveStatus === 'APPROVED').length,
)

function firstQueryValue(value: unknown): string | null {
  if (typeof value === 'string') return value
  return Array.isArray(value) && typeof value[0] === 'string' ? value[0] : null
}

function queryNumber(value: unknown): number | null {
  const parsed = Number(firstQueryValue(value))
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function parseLeaveStatus(value: unknown): LeaveStatus | '' {
  const parsed = firstQueryValue(value)
  return parsed && ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].includes(parsed)
    ? (parsed as LeaveStatus)
    : ''
}

function syncQuery(): void {
  void router.replace({
    query: {
      ...route.query,
      student: selectedStudentId.value?.toString(),
      leaveStatus: statusFilter.value || undefined,
    },
  })
}

function leaveStatusLabel(status: LeaveStatus | null): string {
  if (!status) return '未申请'
  const labels: Record<LeaveStatus, string> = {
    PENDING: '待审核',
    APPROVED: '已批准',
    REJECTED: '已驳回',
    WITHDRAWN: '已撤回',
  }
  return labels[status]
}

function leaveStatusType(status: LeaveStatus | null): WorkflowTagType {
  if (!status) return 'info'
  const types: Record<LeaveStatus, WorkflowTagType> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    WITHDRAWN: 'info',
  }
  return types[status]
}

async function loadStudents(): Promise<boolean> {
  loading.value = true
  error.value = ''
  try {
    students.value = await leaveCorrectionApi.getGuardianStudents()
    const requestedStudentId = queryNumber(route.query.student)
    selectedStudentId.value =
      students.value.find((item) => item.id === requestedStudentId)?.id ??
      students.value[0]?.id ??
      null
    return true
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '学生信息加载失败。')
    return false
  } finally {
    loading.value = false
  }
}

async function loadSessions(studentId: number | null): Promise<void> {
  if (studentId !== selectedStudentId.value) return
  const requestId = ++sessionRequestId
  sessions.value = []
  loadedSessionStudentId.value = null
  if (studentId === null) {
    sessionLoading.value = false
    return
  }
  sessionLoading.value = true
  error.value = ''
  try {
    const rows = await leaveCorrectionApi.getGuardianSessions(studentId)
    if (
      requestId !== sessionRequestId ||
      selectedStudentId.value !== studentId
    ) {
      return
    }
    sessions.value = rows
    loadedSessionStudentId.value = studentId
  } catch (loadError) {
    if (
      requestId === sessionRequestId &&
      selectedStudentId.value === studentId
    ) {
      error.value = getErrorMessage(loadError, '可请假课次加载失败。')
    }
  } finally {
    if (
      requestId === sessionRequestId &&
      selectedStudentId.value === studentId
    ) {
      sessionLoading.value = false
    }
  }
}

async function loadRequests(): Promise<void> {
  const requestId = ++leaveRequestsRequestId
  const requestedStatus = statusFilter.value
  requestLoading.value = true
  error.value = ''
  try {
    const rows = await leaveCorrectionApi.getLeaveRequests({
      status: requestedStatus || undefined,
    })
    if (
      requestId !== leaveRequestsRequestId ||
      statusFilter.value !== requestedStatus
    ) {
      return
    }
    requests.value = rows
  } catch (loadError) {
    if (
      requestId === leaveRequestsRequestId &&
      statusFilter.value === requestedStatus
    ) {
      error.value = getErrorMessage(loadError, '请假记录加载失败。')
    }
  } finally {
    if (
      requestId === leaveRequestsRequestId &&
      statusFilter.value === requestedStatus
    ) {
      requestLoading.value = false
    }
  }
}

async function refresh(): Promise<void> {
  await Promise.all([
    loadSessions(selectedStudentId.value),
    loadRequests(),
  ])
}

async function reloadAll(): Promise<void> {
  if (await loadStudents()) {
    await refresh()
  }
}

function openLeaveDialog(session: GuardianLeaveSession): void {
  const studentId = selectedStudentId.value
  const currentSession = sessions.value.find((item) => item.id === session.id)
  if (
    studentId === null ||
    loadedSessionStudentId.value !== studentId ||
    !currentSession ||
    currentSession.activeLeaveRequestId !== null
  ) {
    ElMessage.warning('学生或课次信息已变化，请刷新后重试。')
    return
  }
  leaveStudentId.value = studentId
  selectedSession.value = currentSession
  leaveReason.value = ''
  captureLeaveBaseline()
  leaveDialogVisible.value = true
}

async function submitLeave(): Promise<void> {
  const studentId = leaveStudentId.value
  const session = selectedSession.value
  if (
    studentId === null ||
    !session ||
    selectedStudentId.value !== studentId ||
    loadedSessionStudentId.value !== studentId ||
    !sessions.value.some(
      (item) =>
        item.id === session.id && item.activeLeaveRequestId === null,
    )
  ) {
    ElMessage.warning('学生或课次信息已变化，请重新发起请假。')
    leaveReason.value = ''
    captureLeaveBaseline()
    leaveDialogVisible.value = false
    selectedSession.value = null
    leaveStudentId.value = null
    return
  }
  const reason = leaveReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写请假原因。')
    return
  }
  submitting.value = true
  try {
    await leaveCorrectionApi.submitLeave(
      session.id,
      studentId,
      reason,
    )
    captureLeaveBaseline()
    leaveDialogVisible.value = false
    selectedSession.value = null
    leaveStudentId.value = null
    ElMessage.success('请假申请已提交')
    await refresh()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '请假申请提交失败。'))
  } finally {
    submitting.value = false
  }
}

async function withdrawLeave(id: number): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '撤回后，这条申请将不再进入教师审核。',
      '撤回请假申请',
      {
        confirmButtonText: '确认撤回',
        cancelButtonText: '保留申请',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  withdrawingId.value = id
  try {
    await leaveCorrectionApi.withdrawLeave(id)
    ElMessage.success('请假申请已撤回')
    await refresh()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '撤回请假申请失败。'))
  } finally {
    withdrawingId.value = null
  }
}

watch(selectedStudentId, (studentId) => {
  if (!initialized) return
  syncQuery()
  void loadSessions(studentId)
})

watch(statusFilter, () => {
  if (!initialized) return
  syncQuery()
  void loadRequests()
})

onMounted(async () => {
  statusFilter.value = parseLeaveStatus(route.query.leaveStatus)
  const studentsLoaded = await loadStudents()
  initialized = true
  syncQuery()
  if (studentsLoaded) await refresh()
})
</script>

<template>
  <section class="page-stack leave-page">
    <PageHeader
      kicker="课次请假"
      title="请假从一节课次开始"
      description="只能为已绑定且本课次有效报名的学生提交。开课前可撤回待审申请，审核通过后将在考勤名单中预标请假。"
    >
      <template #actions>
        <el-button
          :loading="loading || sessionLoading || requestLoading"
          @click="reloadAll"
        >
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

    <section class="leave-context-panel">
      <div>
        <span class="context-label">当前学生</span>
        <h2>{{ selectedStudent?.fullName || '请选择学生' }}</h2>
        <p v-if="selectedStudent">
          {{ selectedStudent.schoolName }} · {{ selectedStudent.className }} ·
          {{ selectedStudent.studentNo }}
        </p>
        <p v-else>当前账号暂无已绑定学生。</p>
      </div>
      <el-select
        v-model="selectedStudentId"
        class="student-select"
        :loading="loading"
        placeholder="选择学生"
        aria-label="选择学生"
      >
        <el-option
          v-for="student in students"
          :key="student.id"
          :label="`${student.fullName}（${student.className}）`"
          :value="student.id"
        />
      </el-select>
      <div class="leave-pulse" aria-label="请假状态摘要">
        <span>
          <strong>{{ sessions.length }}</strong>
          可请假课次
        </span>
        <span>
          <strong>{{ pendingCount }}</strong>
          待审核
        </span>
        <span>
          <strong>{{ approvedCount }}</strong>
          已批准
        </span>
      </div>
    </section>

    <section class="selection-section">
      <div class="section-heading">
        <h2>未来课次</h2>
        <p>按时间排列当前学生尚可办理请假的课次。</p>
      </div>
      <div v-loading="sessionLoading" class="session-ledger">
        <article
          v-for="item in sessions"
          :key="item.id"
          class="session-entry"
          :class="`is-${(item.activeLeaveStatus || 'open').toLowerCase()}`"
        >
          <div class="date-stamp">
            <strong>{{ formatDate(item.sessionDate).slice(5) }}</strong>
            <span>{{ formatTime(item.startTime) }}</span>
          </div>
          <div class="session-copy">
            <div>
              <h3>{{ item.courseName || item.offeringCode }}</h3>
              <el-tag
                v-if="item.activeLeaveStatus"
                size="small"
                effect="plain"
                :type="leaveStatusType(item.activeLeaveStatus)"
              >
                {{ leaveStatusLabel(item.activeLeaveStatus) }}
              </el-tag>
            </div>
            <p>
              {{ formatTime(item.startTime) }}-{{ formatTime(item.endTime) }}
              · {{ item.classroom }} · {{ item.offeringCode }}
            </p>
            <small v-if="item.activeLeaveReason">
              申请原因：{{ item.activeLeaveReason }}
            </small>
          </div>
          <el-button
            v-if="!item.activeLeaveRequestId"
            type="primary"
            @click="openLeaveDialog(item)"
          >
            申请请假
          </el-button>
          <el-button
            v-else-if="item.activeLeaveStatus === 'PENDING'"
            type="danger"
            plain
            :loading="withdrawingId === item.activeLeaveRequestId"
            :disabled="withdrawingId !== null"
            @click="withdrawLeave(item.activeLeaveRequestId)"
          >
            撤回申请
          </el-button>
          <span v-else class="closed-note">已进入考勤预标</span>
        </article>
        <div v-if="!sessionLoading && sessions.length === 0" class="empty-state large">
          <strong>暂无可请假课次</strong>
          <span>当前学生近期没有未开始的有效报名课次。</span>
        </div>
      </div>
    </section>

    <section class="entity-panel">
      <div class="entity-panel-header">
        <div>
          <h2>请假记录</h2>
          <p>查看提交、审核和撤回的完整时间线。</p>
        </div>
        <el-select
          v-model="statusFilter"
          class="status-filter"
          placeholder="全部状态"
          aria-label="按请假状态筛选"
        >
          <el-option label="全部状态" value="" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已撤回" value="WITHDRAWN" />
        </el-select>
      </div>
      <el-table
        v-loading="requestLoading"
        :data="studentRequests"
        row-key="id"
        table-layout="auto"
      >
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column label="课次" min-width="165">
          <template #default="{ row }">
            {{ formatDate(row.sessionDate) }} {{ formatTime(row.startTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="请假原因" min-width="190" show-overflow-tooltip />
        <el-table-column label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag effect="plain" :type="leaveStatusType(row.status)">
              {{ leaveStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理记录" min-width="220">
          <template #default="{ row }">
            <span v-if="row.reviewedAt">
              {{ row.reviewedByName }} · {{ formatDateTime(row.reviewedAt) }}
            </span>
            <span v-else-if="row.withdrawnAt">
              已于 {{ formatDateTime(row.withdrawnAt) }} 撤回
            </span>
            <span v-else>等待教师或学校处理</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewRemark" label="审核意见" min-width="160">
          <template #default="{ row }">{{ row.reviewRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              text
              type="danger"
              :loading="withdrawingId === row.id"
              :disabled="withdrawingId !== null"
              @click="withdrawLeave(row.id)"
            >
              撤回
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>暂无请假记录</strong>
            <span>可从上方未来课次中发起申请，或调整状态筛选。</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-dialog
      v-model="leaveDialogVisible"
      title="申请课次请假"
      width="min(520px, 92vw)"
      destroy-on-close
      :before-close="beforeLeaveDialogClose"
    >
      <div v-if="selectedSession" class="dialog-session-summary">
        <strong>{{ selectedSession.courseName }}</strong>
        <span>
          {{ formatDate(selectedSession.sessionDate) }}
          {{ formatTime(selectedSession.startTime) }}-{{ formatTime(selectedSession.endTime) }}
          · {{ selectedSession.classroom }}
        </span>
      </div>
      <el-form label-position="top">
        <el-form-item label="请假原因" required>
          <el-input
            v-model="leaveReason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="请说明请假原因，便于教师审核"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="requestLeaveDialogClose">
          取消
        </el-button>
        <el-button type="primary" :loading="submitting" @click="submitLeave">
          提交请假申请
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.leave-context-panel {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(220px, 360px) auto;
  align-items: center;
  gap: 24px;
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.context-label {
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.leave-context-panel h2 {
  margin: 5px 0 0;
  font-size: 20px;
}

.leave-context-panel p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.student-select {
  width: 100%;
}

.leave-pulse {
  display: flex;
  align-self: stretch;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--paper);
}

.leave-pulse span {
  display: flex;
  min-width: 74px;
  flex-direction: column;
  justify-content: center;
  padding: 10px 14px;
  color: var(--muted);
  font-size: 10px;
}

.leave-pulse span + span {
  border-left: 1px solid var(--line);
}

.leave-pulse strong {
  margin-bottom: 3px;
  color: var(--ink);
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}

.session-ledger {
  display: flex;
  min-height: 180px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.session-entry {
  position: relative;
  display: grid;
  grid-template-columns: 94px minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
  min-height: 108px;
  padding: 18px 22px 18px 27px;
  border-left: 4px solid #b8c8c1;
}

.session-entry + .session-entry {
  border-top: 1px solid var(--line);
}

.session-entry.is-pending {
  border-left-color: #d59a43;
}

.session-entry.is-approved {
  border-left-color: var(--accent);
}

.date-stamp {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-variant-numeric: tabular-nums;
}

.date-stamp strong {
  font-size: 18px;
}

.date-stamp span,
.session-copy small,
.closed-note {
  color: var(--muted);
  font-size: 11px;
}

.session-copy > div {
  display: flex;
  align-items: center;
  gap: 10px;
}

.session-copy h3 {
  margin: 0;
  font-size: 15px;
}

.session-copy p {
  margin: 7px 0 4px;
  color: var(--muted);
  font-size: 12px;
}

.status-filter {
  width: 150px;
}

.dialog-session-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 20px;
  padding: 15px 17px;
  border-left: 3px solid var(--accent);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  background: var(--surface-soft);
}

.dialog-session-summary span {
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 980px) {
  .leave-context-panel {
    grid-template-columns: 1fr 1fr;
  }

  .leave-pulse {
    grid-column: 1 / -1;
  }
}

@media (max-width: 700px) {
  .leave-context-panel,
  .session-entry {
    grid-template-columns: 1fr;
  }

  .leave-pulse {
    grid-column: auto;
    overflow-x: auto;
  }

  .session-entry {
    gap: 12px;
  }

  .session-entry .el-button {
    width: 100%;
  }

  .entity-panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .status-filter {
    width: 100%;
  }
}
</style>
