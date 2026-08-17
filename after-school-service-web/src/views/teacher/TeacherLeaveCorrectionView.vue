<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/http'
import {
  leaveCorrectionApi,
  type AttendanceCorrection,
  type AttendanceStatus,
  type AttendanceTarget,
  type CorrectionStatus,
  type LeaveRequest,
  type LeaveStatus,
  type OfferingSummary,
  type ReviewDecision,
  type SessionSummary,
} from '@/api/leaveCorrections'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate, formatDateTime, formatTime, weekdayLabel } from '@/utils/format'

type WorkflowTab = 'leave' | 'correction'
type WorkflowTagType = 'success' | 'warning' | 'danger' | 'info'

const route = useRoute()
const router = useRouter()
const activeTab = ref<WorkflowTab>('leave')
const offerings = ref<OfferingSummary[]>([])
const selectedOfferingId = ref(0)
const sessions = ref<SessionSummary[]>([])
const selectedSessionId = ref<number | null>(null)
const attendance = ref<AttendanceTarget[]>([])
const loadedAttendanceSessionId = ref<number | null>(null)
const leaveRequests = ref<LeaveRequest[]>([])
const corrections = ref<AttendanceCorrection[]>([])
const leaveStatusFilter = ref<LeaveStatus | ''>('PENDING')
const correctionStatusFilter = ref<CorrectionStatus | ''>('')
const loading = ref(false)
const leaveLoading = ref(false)
const correctionLoading = ref(false)
const sessionLoading = ref(false)
const attendanceLoading = ref(false)
const error = ref('')
const reviewDialogVisible = ref(false)
const reviewSaving = ref(false)
const selectedLeave = ref<LeaveRequest | null>(null)
const leaveDecision = ref<ReviewDecision>('APPROVED')
const leaveReviewRemark = ref('')
const correctionDialogVisible = ref(false)
const correctionSaving = ref(false)
const selectedAttendance = ref<AttendanceTarget | null>(null)
const correctionTargetSessionId = ref<number | null>(null)
const cancelingCorrectionId = ref<number | null>(null)
const correctionForm = reactive<{
  requestedStatus: AttendanceStatus
  requestedRemark: string
  reason: string
}>({
  requestedStatus: 'PRESENT',
  requestedRemark: '',
  reason: '',
})
let initialized = false
let leaveLoadVersion = 0
let correctionLoadVersion = 0
let sessionLoadVersion = 0
let attendanceLoadVersion = 0

const selectedOffering = computed(() =>
  offerings.value.find((item) => item.id === selectedOfferingId.value),
)
const completedSessions = computed(() =>
  sessions.value.filter((item) => item.status === 'COMPLETED'),
)
const selectedSession = computed(() =>
  completedSessions.value.find((item) => item.id === selectedSessionId.value),
)

const attendanceStatusOptions: Array<{
  label: string
  value: AttendanceStatus
}> = [
  { label: '出勤', value: 'PRESENT' },
  { label: '迟到', value: 'LATE' },
  { label: '请假', value: 'LEAVE' },
  { label: '缺勤', value: 'ABSENT' },
]

function firstQueryValue(value: unknown): string | null {
  if (typeof value === 'string') return value
  return Array.isArray(value) && typeof value[0] === 'string' ? value[0] : null
}

function queryNumber(value: unknown): number | null {
  const parsed = Number(firstQueryValue(value))
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function parseStatus<T extends string>(
  value: unknown,
  allowed: readonly T[],
  fallback: T | '',
): T | '' {
  const parsed = firstQueryValue(value)
  return parsed && allowed.includes(parsed as T) ? (parsed as T) : fallback
}

function syncQuery(): void {
  void router.replace({
    query: {
      ...route.query,
      workflow: activeTab.value,
      offering:
        selectedOfferingId.value > 0
          ? selectedOfferingId.value.toString()
          : undefined,
      session: selectedSessionId.value?.toString(),
      leaveStatus: leaveStatusFilter.value || undefined,
      correctionStatus: correctionStatusFilter.value || undefined,
    },
  })
}

function leaveStatusLabel(status: LeaveStatus): string {
  const labels: Record<LeaveStatus, string> = {
    PENDING: '待审核',
    APPROVED: '已批准',
    REJECTED: '已驳回',
    WITHDRAWN: '已撤回',
  }
  return labels[status]
}

function correctionStatusLabel(status: CorrectionStatus): string {
  const labels: Record<CorrectionStatus, string> = {
    PENDING: '待审批',
    REJECTED: '已驳回',
    CANCELED: '已取消',
    APPLIED: '已生效',
  }
  return labels[status]
}

function attendanceStatusLabel(status: AttendanceStatus | null): string {
  if (!status) return '未记录'
  const labels: Record<AttendanceStatus, string> = {
    PRESENT: '出勤',
    LATE: '迟到',
    LEAVE: '请假',
    ABSENT: '缺勤',
  }
  return labels[status]
}

function workflowStatusType(status: string): WorkflowTagType {
  if (['APPROVED', 'APPLIED', 'PRESENT'].includes(status)) return 'success'
  if (['PENDING', 'LEAVE', 'LATE'].includes(status)) return 'warning'
  if (['REJECTED', 'CANCELED', 'ABSENT'].includes(status)) return 'danger'
  return 'info'
}

async function loadOfferings(): Promise<boolean> {
  loading.value = true
  error.value = ''
  try {
    offerings.value = await leaveCorrectionApi.getOfferings()
    const requestedOfferingId = queryNumber(route.query.offering)
    selectedOfferingId.value =
      offerings.value.find((item) => item.id === requestedOfferingId)?.id ?? 0
    return true
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '本人开班加载失败。')
    return false
  } finally {
    loading.value = false
  }
}

async function loadLeaves(): Promise<void> {
  const requestVersion = ++leaveLoadVersion
  const offeringId = selectedOfferingId.value || undefined
  const status = leaveStatusFilter.value || undefined
  leaveLoading.value = true
  error.value = ''
  try {
    const rows = await leaveCorrectionApi.getLeaveRequests({
      offeringId,
      status,
    })
    if (requestVersion === leaveLoadVersion) leaveRequests.value = rows
  } catch (loadError) {
    if (requestVersion === leaveLoadVersion) {
      error.value = getErrorMessage(loadError, '请假申请加载失败。')
    }
  } finally {
    if (requestVersion === leaveLoadVersion) leaveLoading.value = false
  }
}

async function loadCorrections(): Promise<void> {
  const requestVersion = ++correctionLoadVersion
  const offeringId = selectedOfferingId.value || undefined
  const status = correctionStatusFilter.value || undefined
  correctionLoading.value = true
  error.value = ''
  try {
    const rows = await leaveCorrectionApi.getCorrections({
      offeringId,
      status,
    })
    if (requestVersion === correctionLoadVersion) corrections.value = rows
  } catch (loadError) {
    if (requestVersion === correctionLoadVersion) {
      error.value = getErrorMessage(loadError, '纠错申请加载失败。')
    }
  } finally {
    if (requestVersion === correctionLoadVersion) {
      correctionLoading.value = false
    }
  }
}

async function loadSessions(
  offeringId: number,
  preferredSessionId: number | null,
): Promise<void> {
  const requestVersion = ++sessionLoadVersion
  ++attendanceLoadVersion
  sessions.value = []
  selectedSessionId.value = null
  attendance.value = []
  loadedAttendanceSessionId.value = null
  selectedAttendance.value = null
  correctionTargetSessionId.value = null
  correctionDialogVisible.value = false
  attendanceLoading.value = false
  if (offeringId === 0) {
    sessionLoading.value = false
    return
  }
  sessionLoading.value = true
  error.value = ''
  try {
    const rows = await leaveCorrectionApi.getSessions(offeringId)
    if (
      requestVersion !== sessionLoadVersion ||
      selectedOfferingId.value !== offeringId
    ) {
      return
    }
    sessions.value = rows
    selectedSessionId.value =
      completedSessions.value.find((item) => item.id === preferredSessionId)
        ?.id ??
      completedSessions.value[0]?.id ??
      null
  } catch (loadError) {
    if (requestVersion === sessionLoadVersion) {
      error.value = getErrorMessage(loadError, '已完成课次加载失败。')
    }
  } finally {
    if (requestVersion === sessionLoadVersion) {
      sessionLoading.value = false
    }
  }
}

async function loadAttendance(sessionId: number | null): Promise<void> {
  const requestVersion = ++attendanceLoadVersion
  attendance.value = []
  loadedAttendanceSessionId.value = null
  selectedAttendance.value = null
  correctionTargetSessionId.value = null
  correctionDialogVisible.value = false
  if (sessionId === null) {
    attendanceLoading.value = false
    return
  }
  attendanceLoading.value = true
  error.value = ''
  try {
    const rows = await leaveCorrectionApi.getAttendance(sessionId)
    if (
      requestVersion !== attendanceLoadVersion ||
      selectedSessionId.value !== sessionId
    ) {
      return
    }
    attendance.value = rows
    loadedAttendanceSessionId.value = sessionId
  } catch (loadError) {
    if (requestVersion === attendanceLoadVersion) {
      error.value = getErrorMessage(loadError, '考勤记录加载失败。')
    }
  } finally {
    if (requestVersion === attendanceLoadVersion) {
      attendanceLoading.value = false
    }
  }
}

async function refresh(
  preferredSessionId: number | null = selectedSessionId.value,
): Promise<void> {
  await Promise.all([
    loadLeaves(),
    loadCorrections(),
    loadSessions(selectedOfferingId.value, preferredSessionId),
  ])
}

async function reloadAll(): Promise<void> {
  const preferredSessionId = selectedSessionId.value
  if (await loadOfferings()) {
    await refresh(preferredSessionId)
  }
}

function openLeaveReview(item: LeaveRequest, decision: ReviewDecision): void {
  selectedLeave.value = item
  leaveDecision.value = decision
  leaveReviewRemark.value = ''
  reviewDialogVisible.value = true
}

async function submitLeaveReview(): Promise<void> {
  if (!selectedLeave.value || reviewSaving.value) return
  const remark = leaveReviewRemark.value.trim()
  if (leaveDecision.value === 'REJECTED' && !remark) {
    ElMessage.warning('请填写驳回原因。')
    return
  }
  reviewSaving.value = true
  try {
    await leaveCorrectionApi.reviewLeave(
      selectedLeave.value.id,
      leaveDecision.value,
      remark || null,
    )
    reviewDialogVisible.value = false
    ElMessage.success(leaveDecision.value === 'APPROVED' ? '请假已批准' : '请假已驳回')
    await loadLeaves()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '请假审核失败。'))
  } finally {
    reviewSaving.value = false
  }
}

function openCorrection(item: AttendanceTarget): void {
  if (
    !item.status ||
    !selectedSession.value ||
    loadedAttendanceSessionId.value !== selectedSessionId.value
  ) {
    ElMessage.warning('考勤记录已变化，请重新选择课次。')
    return
  }
  selectedAttendance.value = item
  correctionTargetSessionId.value = selectedSessionId.value
  correctionForm.requestedStatus = item.status
  correctionForm.requestedRemark = item.remark ?? ''
  correctionForm.reason = ''
  correctionDialogVisible.value = true
}

async function submitCorrection(): Promise<void> {
  const targetSessionId = correctionTargetSessionId.value
  if (
    !selectedAttendance.value ||
    !targetSessionId ||
    selectedSessionId.value !== targetSessionId ||
    loadedAttendanceSessionId.value !== targetSessionId
  ) {
    correctionDialogVisible.value = false
    selectedAttendance.value = null
    correctionTargetSessionId.value = null
    ElMessage.warning('当前课次已变化，请重新选择学生发起纠错。')
    return
  }
  const reason = correctionForm.reason.trim()
  if (!reason) {
    ElMessage.warning('请说明纠错原因。')
    return
  }
  const requestedRemark = correctionForm.requestedRemark.trim() || null
  if (
    correctionForm.requestedStatus === selectedAttendance.value.status &&
    requestedRemark === selectedAttendance.value.remark
  ) {
    ElMessage.warning('请先修改考勤状态或备注。')
    return
  }
  correctionSaving.value = true
  try {
    await leaveCorrectionApi.requestCorrection({
      sessionId: targetSessionId,
      studentId: selectedAttendance.value.studentId,
      requestedStatus: correctionForm.requestedStatus,
      requestedRemark,
      reason,
    })
    correctionDialogVisible.value = false
    correctionTargetSessionId.value = null
    ElMessage.success('考勤纠错已提交学校审批')
    await loadCorrections()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '考勤纠错申请失败。'))
  } finally {
    correctionSaving.value = false
  }
}

async function cancelCorrection(item: AttendanceCorrection): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认取消${item.studentName}的考勤纠错申请吗？`,
      '取消纠错申请',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '保留申请',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  cancelingCorrectionId.value = item.id
  try {
    await leaveCorrectionApi.cancelCorrection(item.id)
    ElMessage.success('纠错申请已取消')
    await loadCorrections()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '取消纠错申请失败。'))
  } finally {
    cancelingCorrectionId.value = null
  }
}

watch(activeTab, () => {
  if (initialized) syncQuery()
})

watch(selectedOfferingId, (offeringId) => {
  if (!initialized) return
  const sessionPromise = loadSessions(offeringId, null)
  syncQuery()
  void Promise.all([loadLeaves(), loadCorrections(), sessionPromise])
})

watch(selectedSessionId, (sessionId) => {
  if (!initialized) return
  syncQuery()
  void loadAttendance(sessionId)
})

watch(leaveStatusFilter, () => {
  if (!initialized) return
  syncQuery()
  void loadLeaves()
})

watch(correctionStatusFilter, () => {
  if (!initialized) return
  syncQuery()
  void loadCorrections()
})

onMounted(async () => {
  const requestedSessionId = queryNumber(route.query.session)
  activeTab.value = firstQueryValue(route.query.workflow) === 'correction' ? 'correction' : 'leave'
  leaveStatusFilter.value = parseStatus(
    route.query.leaveStatus,
    ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as const,
    'PENDING',
  )
  correctionStatusFilter.value = parseStatus(
    route.query.correctionStatus,
    ['PENDING', 'REJECTED', 'CANCELED', 'APPLIED'] as const,
    '',
  )
  const offeringsLoaded = await loadOfferings()
  initialized = true
  if (offeringsLoaded) await refresh(requestedSessionId)
  syncQuery()
})
</script>

<template>
  <section class="page-stack workflow-page">
    <PageHeader
      kicker="教师工作流"
      title="请假审核与考勤纠错"
      description="处理本人开班的请假申请；已完成课次的考勤不能直接改写，需逐名发起纠错并由学校审批。"
    >
      <template #actions>
        <el-button
          :loading="
            loading ||
            leaveLoading ||
            correctionLoading ||
            sessionLoading ||
            attendanceLoading
          "
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

    <section class="workflow-context-panel">
      <div>
        <span>查看范围</span>
        <h2>
          {{
            selectedOffering?.courseName ||
            (offerings.length ? '全部本人开班' : '暂无授课开班')
          }}
        </h2>
        <p v-if="selectedOffering">
          {{ selectedOffering.offeringCode }} ·
          {{ weekdayLabel(selectedOffering.weekDay) }}
          {{ formatTime(selectedOffering.startTime) }} ·
          {{ selectedOffering.classroom }}
        </p>
        <p v-else-if="offerings.length">
          请假与纠错记录按本人全部开班汇总。
        </p>
      </div>
      <el-select
        v-model="selectedOfferingId"
        class="offering-select"
        filterable
        :loading="loading"
        placeholder="选择本人开班"
      >
        <el-option label="全部本人开班" :value="0" />
        <el-option
          v-for="offering in offerings"
          :key="offering.id"
          :label="`${offering.courseName || offering.offeringCode}（${offering.offeringCode}）`"
          :value="offering.id"
        />
      </el-select>
      <div class="pending-signals">
        <span><strong>{{ leaveRequests.length }}</strong>当前请假</span>
        <span><strong>{{ corrections.length }}</strong>当前纠错</span>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="workflow-tabs">
      <el-tab-pane label="请假审核" name="leave">
        <section class="entity-panel">
          <div class="entity-panel-header">
            <div>
              <h2>请假申请</h2>
              <p>审批后将在该课次考勤名单中预标“请假”。</p>
            </div>
            <el-select v-model="leaveStatusFilter" class="status-filter">
              <el-option label="全部状态" value="" />
              <el-option label="待审核" value="PENDING" />
              <el-option label="已批准" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已撤回" value="WITHDRAWN" />
            </el-select>
          </div>
          <el-table
            v-loading="leaveLoading"
            :data="leaveRequests"
            row-key="id"
            table-layout="auto"
          >
            <el-table-column prop="studentName" label="学生" min-width="120">
              <template #default="{ row }">
                <strong>{{ row.studentName }}</strong>
                <small class="cell-note">{{ row.studentNo }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="courseName" label="课程" min-width="140" />
            <el-table-column label="课次" min-width="170">
              <template #default="{ row }">
                {{ formatDate(row.sessionDate) }} {{ formatTime(row.startTime) }}
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="请假原因" min-width="210" show-overflow-tooltip />
            <el-table-column label="提交时间" min-width="165">
              <template #default="{ row }">{{ formatDateTime(row.submittedAt) }}</template>
            </el-table-column>
            <el-table-column label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag effect="plain" :type="workflowStatusType(row.status)">
                  {{ leaveStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" align="right">
              <template #default="{ row }">
                <div v-if="row.status === 'PENDING'" class="row-actions">
                  <el-button text type="danger" @click="openLeaveReview(row, 'REJECTED')">
                    驳回
                  </el-button>
                  <el-button text type="primary" @click="openLeaveReview(row, 'APPROVED')">
                    批准
                  </el-button>
                </div>
                <span v-else class="cell-note">{{ row.reviewRemark || '已处理' }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <strong>暂无符合条件的请假</strong>
                <span>更换开班或状态筛选查看其他申请。</span>
              </div>
            </template>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="考勤纠错" name="correction">
        <div class="correction-flow" aria-label="考勤纠错流程">
          <span>已完成考勤</span><i />
          <span>教师逐名申请</span><i />
          <span>学校审批</span><i />
          <span>写入修订历史</span>
        </div>

        <section class="entity-panel target-panel">
          <el-alert
            v-if="selectedOfferingId === 0"
            class="correction-context-alert"
            title="当前为全部开班汇总；请先从上方选择一个具体开班，再选择已完成课次发起纠错。"
            type="info"
            show-icon
            :closable="false"
          />
          <div class="entity-panel-header">
            <div>
              <h2>选择已完成课次</h2>
              <p>原考勤保持只读，从学生行发起单条纠错。</p>
            </div>
            <el-select
              v-model="selectedSessionId"
              class="session-select"
              :loading="sessionLoading"
              :disabled="selectedOfferingId === 0"
              placeholder="选择已完成课次"
            >
              <el-option
                v-for="item in completedSessions"
                :key="item.id"
                :label="`${formatDate(item.sessionDate)} ${formatTime(item.startTime)}`"
                :value="item.id"
              />
            </el-select>
          </div>
          <el-table
            v-loading="attendanceLoading || sessionLoading"
            :data="attendance"
            row-key="studentId"
            table-layout="auto"
          >
            <el-table-column prop="studentName" label="学生" min-width="130">
              <template #default="{ row }">
                <strong>{{ row.studentName }}</strong>
                <small class="cell-note">{{ row.className }} · {{ row.studentNo }}</small>
              </template>
            </el-table-column>
            <el-table-column label="当前考勤" min-width="110">
              <template #default="{ row }">
                <el-tag effect="plain" :type="workflowStatusType(row.status || '')">
                  {{ attendanceStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="当前备注" min-width="180">
              <template #default="{ row }">{{ row.remark || '-' }}</template>
            </el-table-column>
            <el-table-column label="记录信息" min-width="200">
              <template #default="{ row }">
                {{ row.recordedByName || '-' }}
                <span class="cell-note">{{ formatDateTime(row.recordedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="right">
              <template #default="{ row }">
                <el-button
                  text
                  type="primary"
                  :disabled="
                    !row.status ||
                    attendanceLoading ||
                    loadedAttendanceSessionId !== selectedSessionId
                  "
                  @click="openCorrection(row)"
                >
                  申请纠错
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <strong>
                  {{
                    selectedOfferingId === 0
                      ? '请先选择具体开班'
                      : selectedSession
                        ? '本课次暂无考勤'
                        : '请先选择已完成课次'
                  }}
                </strong>
                <span>
                  {{
                    selectedOfferingId === 0
                      ? '汇总模式仅查看记录，选定开班后才能发起纠错。'
                      : '只有已完成且已生成考勤的学生可发起纠错。'
                  }}
                </span>
              </div>
            </template>
          </el-table>
        </section>

        <section class="entity-panel correction-list-panel">
          <div class="entity-panel-header">
            <div>
              <h2>纠错申请记录</h2>
              <p>跟踪待审批、已驳回、已取消和已生效记录。</p>
            </div>
            <el-select
              v-model="correctionStatusFilter"
              class="status-filter"
              placeholder="全部状态"
            >
              <el-option label="全部状态" value="" />
              <el-option label="待审批" value="PENDING" />
              <el-option label="已生效" value="APPLIED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已取消" value="CANCELED" />
            </el-select>
          </div>
          <el-table
            v-loading="correctionLoading"
            :data="corrections"
            row-key="id"
            table-layout="auto"
          >
            <el-table-column prop="studentName" label="学生" min-width="120" />
            <el-table-column label="课次" min-width="150">
              <template #default="{ row }">
                {{ formatDate(row.sessionDate) }} {{ formatTime(row.startTime) }}
              </template>
            </el-table-column>
            <el-table-column label="修订目标" min-width="175">
              <template #default="{ row }">
                {{ attendanceStatusLabel(row.currentAttendanceStatus) }}
                →
                <strong>{{ attendanceStatusLabel(row.requestedStatus) }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="纠错原因" min-width="190" show-overflow-tooltip />
            <el-table-column label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag effect="plain" :type="workflowStatusType(row.status)">
                  {{ correctionStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'PENDING'"
                  text
                  type="danger"
                  :loading="cancelingCorrectionId === row.id"
                  :disabled="cancelingCorrectionId !== null"
                  @click="cancelCorrection(row)"
                >
                  取消
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <strong>暂无纠错申请</strong>
                <span>可从上方已完成课次的考勤记录发起。</span>
              </div>
            </template>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="reviewDialogVisible"
      :title="leaveDecision === 'APPROVED' ? '批准请假' : '驳回请假'"
      width="min(520px, 92vw)"
      destroy-on-close
    >
      <div v-if="selectedLeave" class="dialog-case-summary">
        <strong>{{ selectedLeave.studentName }} · {{ selectedLeave.courseName }}</strong>
        <span>
          {{ formatDate(selectedLeave.sessionDate) }} {{ formatTime(selectedLeave.startTime) }}
        </span>
        <p>{{ selectedLeave.reason }}</p>
      </div>
      <el-form label-position="top">
        <el-form-item
          label="审核意见"
          :required="leaveDecision === 'REJECTED'"
        >
          <el-input
            v-model="leaveReviewRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="leaveDecision === 'APPROVED' ? '可填写核验说明' : '请说明驳回原因'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="reviewSaving" @click="reviewDialogVisible = false">取消</el-button>
        <el-button
          :type="leaveDecision === 'APPROVED' ? 'primary' : 'danger'"
          :loading="reviewSaving"
          @click="submitLeaveReview"
        >
          {{ leaveDecision === 'APPROVED' ? '确认批准' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="correctionDialogVisible"
      title="申请考勤纠错"
      width="min(560px, 92vw)"
      destroy-on-close
    >
      <div v-if="selectedAttendance" class="dialog-case-summary">
        <strong>{{ selectedAttendance.studentName }}</strong>
        <span>
          原考勤：{{ attendanceStatusLabel(selectedAttendance.status) }}
          <template v-if="selectedAttendance.remark">· {{ selectedAttendance.remark }}</template>
        </span>
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="修订为" required>
            <el-select v-model="correctionForm.requestedStatus">
              <el-option
                v-for="option in attendanceStatusOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="修订后备注">
            <el-input
              v-model="correctionForm.requestedRemark"
              maxlength="255"
              placeholder="可留空"
            />
          </el-form-item>
          <el-form-item class="form-item-wide" label="纠错原因" required>
            <el-input
              v-model="correctionForm.reason"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="说明为什么需要修订，便于学校审批"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="correctionSaving" @click="correctionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="correctionSaving" @click="submitCorrection">
          提交纠错申请
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.workflow-context-panel {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(260px, 420px) auto;
  align-items: center;
  gap: 24px;
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.workflow-context-panel > div:first-child > span {
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.workflow-context-panel h2 {
  margin: 5px 0 0;
  font-size: 20px;
}

.workflow-context-panel p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.offering-select {
  width: 100%;
}

.pending-signals {
  display: flex;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--paper);
}

.pending-signals span {
  display: flex;
  min-width: 84px;
  flex-direction: column;
  padding: 10px 14px;
  color: var(--muted);
  font-size: 10px;
}

.pending-signals span + span {
  border-left: 1px solid var(--line);
}

.pending-signals strong {
  margin-bottom: 3px;
  color: var(--ink);
  font-size: 18px;
}

.workflow-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.workflow-tabs :deep(.el-tabs__item) {
  font-weight: 700;
}

.status-filter,
.session-select {
  width: min(260px, 40vw);
}

.cell-note {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 10px;
  font-weight: 400;
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}

.correction-flow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding: 13px 16px;
  border: 1px solid #cfe0d8;
  border-radius: var(--radius-md);
  color: #315d4e;
  font-size: 11px;
  font-weight: 650;
  background: #edf5f1;
}

.correction-flow i {
  width: 28px;
  height: 1px;
  background: #9fbbaf;
}

.correction-list-panel {
  margin-top: 16px;
}

.correction-context-alert {
  margin-bottom: 16px;
}

.dialog-case-summary {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 20px;
  padding: 15px 17px;
  border-left: 3px solid var(--accent);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  background: var(--surface-soft);
}

.dialog-case-summary span,
.dialog-case-summary p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 980px) {
  .workflow-context-panel {
    grid-template-columns: 1fr 1fr;
  }

  .pending-signals {
    grid-column: 1 / -1;
  }
}

@media (max-width: 700px) {
  .workflow-context-panel {
    grid-template-columns: 1fr;
  }

  .pending-signals {
    grid-column: auto;
  }

  .entity-panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .status-filter,
  .session-select {
    width: 100%;
  }

  .correction-flow {
    align-items: flex-start;
    flex-direction: column;
  }

  .correction-flow i {
    width: 1px;
    height: 12px;
    margin-left: 4px;
  }
}
</style>
