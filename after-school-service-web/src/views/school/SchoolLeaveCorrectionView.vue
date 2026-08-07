<script setup lang="ts">
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/http'
import {
  leaveCorrectionApi,
  type AttendanceCorrection,
  type AttendanceRevision,
  type AttendanceStatus,
  type CorrectionStatus,
  type LeaveRequest,
  type LeaveStatus,
  type OfferingSummary,
  type ReviewDecision,
} from '@/api/leaveCorrections'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate, formatDateTime, formatTime } from '@/utils/format'

type WorkflowTab = 'leave' | 'correction'
type WorkflowTagType = 'success' | 'warning' | 'danger' | 'info'

const route = useRoute()
const router = useRouter()
const activeTab = ref<WorkflowTab>('leave')
const offerings = ref<OfferingSummary[]>([])
const selectedOfferingId = ref(0)
const leaveStatusFilter = ref<LeaveStatus | ''>('PENDING')
const correctionStatusFilter = ref<CorrectionStatus | ''>('PENDING')
const keyword = ref('')
const leaveRequests = ref<LeaveRequest[]>([])
const corrections = ref<AttendanceCorrection[]>([])
const loading = ref(false)
const leaveLoading = ref(false)
const correctionLoading = ref(false)
const error = ref('')
const leaveReviewVisible = ref(false)
const correctionReviewVisible = ref(false)
const reviewSaving = ref(false)
const selectedLeave = ref<LeaveRequest | null>(null)
const selectedCorrection = ref<AttendanceCorrection | null>(null)
const reviewDecision = ref<ReviewDecision>('APPROVED')
const reviewRemark = ref('')
const revisionDrawerVisible = ref(false)
const revisionLoading = ref(false)
const revisionError = ref('')
const revisions = ref<AttendanceRevision[]>([])
const revisionSubject = ref('')
let initialized = false

const filteredLeaves = computed(() => {
  const search = keyword.value.trim().toLocaleLowerCase()
  if (!search) return leaveRequests.value
  return leaveRequests.value.filter((item) =>
    [item.studentName, item.studentNo, item.courseName, item.offeringCode, item.guardianName]
      .join(' ')
      .toLocaleLowerCase()
      .includes(search),
  )
})

const filteredCorrections = computed(() => {
  const search = keyword.value.trim().toLocaleLowerCase()
  if (!search) return corrections.value
  return corrections.value.filter((item) =>
    [item.studentName, item.studentNo, item.courseName, item.offeringCode, item.requestedByName]
      .join(' ')
      .toLocaleLowerCase()
      .includes(search),
  )
})


function firstQueryValue(value: unknown): string | null {
  if (typeof value === 'string') return value
  return Array.isArray(value) && typeof value[0] === 'string' ? value[0] : null
}

function queryNumber(value: unknown): number {
  const parsed = Number(firstQueryValue(value))
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0
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
      offering: selectedOfferingId.value > 0 ? selectedOfferingId.value.toString() : undefined,
      leaveStatus: leaveStatusFilter.value || undefined,
      correctionStatus: correctionStatusFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
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

function attendanceStatusLabel(status: AttendanceStatus): string {
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
    selectedOfferingId.value = offerings.value.some(
      (item) => item.id === requestedOfferingId,
    )
      ? requestedOfferingId
      : 0
    return true
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '本校开班加载失败。')
    return false
  } finally {
    loading.value = false
  }
}

async function loadLeaves(): Promise<void> {
  leaveLoading.value = true
  error.value = ''
  try {
    leaveRequests.value = await leaveCorrectionApi.getLeaveRequests({
      offeringId: selectedOfferingId.value || undefined,
      status: leaveStatusFilter.value || undefined,
    })
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '本校请假申请加载失败。')
  } finally {
    leaveLoading.value = false
  }
}

async function loadCorrections(): Promise<void> {
  correctionLoading.value = true
  error.value = ''
  try {
    corrections.value = await leaveCorrectionApi.getCorrections({
      offeringId: selectedOfferingId.value || undefined,
      status: correctionStatusFilter.value || undefined,
    })
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '本校纠错申请加载失败。')
  } finally {
    correctionLoading.value = false
  }
}

async function refresh(): Promise<void> {
  await Promise.all([loadLeaves(), loadCorrections()])
}

async function reloadAll(): Promise<void> {
  if (await loadOfferings()) {
    await refresh()
  }
}

function openLeaveReview(item: LeaveRequest, decision: ReviewDecision): void {
  selectedLeave.value = item
  reviewDecision.value = decision
  reviewRemark.value = ''
  leaveReviewVisible.value = true
}

function openCorrectionReview(
  item: AttendanceCorrection,
  decision: ReviewDecision,
): void {
  selectedCorrection.value = item
  reviewDecision.value = decision
  reviewRemark.value = ''
  correctionReviewVisible.value = true
}

async function submitLeaveReview(): Promise<void> {
  if (!selectedLeave.value) return
  reviewSaving.value = true
  try {
    await leaveCorrectionApi.reviewLeave(
      selectedLeave.value.id,
      reviewDecision.value,
      reviewRemark.value.trim() || null,
    )
    leaveReviewVisible.value = false
    ElMessage.success(reviewDecision.value === 'APPROVED' ? '请假已批准' : '请假已驳回')
    await loadLeaves()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '请假审核失败。'))
  } finally {
    reviewSaving.value = false
  }
}

async function submitCorrectionReview(): Promise<void> {
  if (!selectedCorrection.value) return
  reviewSaving.value = true
  try {
    await leaveCorrectionApi.reviewCorrection(
      selectedCorrection.value.id,
      reviewDecision.value,
      reviewRemark.value.trim() || null,
    )
    correctionReviewVisible.value = false
    ElMessage.success(
      reviewDecision.value === 'APPROVED'
        ? '纠错已批准并写入修订历史'
        : '纠错申请已驳回',
    )
    await loadCorrections()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '纠错审批失败。'))
  } finally {
    reviewSaving.value = false
  }
}

async function openRevisions(item: AttendanceCorrection): Promise<void> {
  revisionSubject.value = `${item.studentName} · ${item.courseName}`
  revisions.value = []
  revisionError.value = ''
  revisionDrawerVisible.value = true
  revisionLoading.value = true
  try {
    revisions.value = await leaveCorrectionApi.getRevisions(item.attendanceId)
  } catch (loadError) {
    revisionError.value = getErrorMessage(loadError, '考勤修订历史加载失败。')
  } finally {
    revisionLoading.value = false
  }
}

watch(activeTab, () => {
  if (initialized) syncQuery()
})

watch(selectedOfferingId, () => {
  if (!initialized) return
  syncQuery()
  void refresh()
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

watch(keyword, () => {
  if (initialized) syncQuery()
})

onMounted(async () => {
  activeTab.value = firstQueryValue(route.query.workflow) === 'correction' ? 'correction' : 'leave'
  leaveStatusFilter.value = parseStatus(
    route.query.leaveStatus,
    ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as const,
    'PENDING',
  )
  correctionStatusFilter.value = parseStatus(
    route.query.correctionStatus,
    ['PENDING', 'REJECTED', 'CANCELED', 'APPLIED'] as const,
    'PENDING',
  )
  keyword.value = firstQueryValue(route.query.keyword) ?? ''
  const offeringsLoaded = await loadOfferings()
  initialized = true
  syncQuery()
  if (offeringsLoaded) await refresh()
})
</script>

<template>
  <section class="page-stack approval-page">
    <PageHeader
      kicker="学校复核"
      title="请假与考勤纠错审批"
      description="处理本校请假申请，审批教师提交的考勤纠错，并查看不可覆盖的修订历史。"
    >
      <template #actions>
        <el-button
          :loading="loading || leaveLoading || correctionLoading"
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

    <section class="approval-docket">
      <div>
        <span>待办案卷</span>
        <h2>每一次决定都留下经办人与时间</h2>
        <p>开班和状态筛选会保存到当前链接。</p>
      </div>
      <div class="docket-counts">
        <span><strong>{{ leaveRequests.length }}</strong>当前请假</span>
        <span><strong>{{ corrections.length }}</strong>当前纠错</span>
      </div>
    </section>

    <section class="approval-filters">
      <el-select
        v-model="selectedOfferingId"
        class="offering-filter"
        filterable
        :loading="loading"
      >
        <el-option label="全部本校开班" :value="0" />
        <el-option
          v-for="offering in offerings"
          :key="offering.id"
          :label="`${offering.courseName || offering.offeringCode}（${offering.offeringCode}）`"
          :value="offering.id"
        />
      </el-select>
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索学生、课程、家长或申请人"
      />
    </section>

    <el-tabs v-model="activeTab" class="approval-tabs">
      <el-tab-pane label="请假审核" name="leave">
        <section class="entity-panel">
          <div class="entity-panel-header">
            <div>
              <h2>本校请假申请</h2>
              <p>可复核本校任意开班的待审申请。</p>
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
            :data="filteredLeaves"
            row-key="id"
            table-layout="auto"
          >
            <el-table-column prop="studentName" label="学生" min-width="120">
              <template #default="{ row }">
                <strong>{{ row.studentName }}</strong>
                <small class="cell-note">{{ row.studentNo }} · {{ row.guardianName }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="courseName" label="课程" min-width="140">
              <template #default="{ row }">
                {{ row.courseName }}
                <small class="cell-note">{{ row.offeringCode }}</small>
              </template>
            </el-table-column>
            <el-table-column label="课次" min-width="165">
              <template #default="{ row }">
                {{ formatDate(row.sessionDate) }} {{ formatTime(row.startTime) }}
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="请假原因" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag effect="plain" :type="workflowStatusType(row.status)">
                  {{ leaveStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="审核记录" min-width="190">
              <template #default="{ row }">
                <span v-if="row.reviewedAt">
                  {{ row.reviewedByName }}
                  <small class="cell-note">{{ formatDateTime(row.reviewedAt) }}</small>
                </span>
                <span v-else class="cell-note">尚未审核</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="154" align="right">
              <template #default="{ row }">
                <div v-if="row.status === 'PENDING'" class="row-actions">
                  <el-button text type="danger" @click="openLeaveReview(row, 'REJECTED')">驳回</el-button>
                  <el-button text type="primary" @click="openLeaveReview(row, 'APPROVED')">批准</el-button>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <strong>暂无符合条件的请假</strong>
                <span>调整开班、状态或关键词筛选。</span>
              </div>
            </template>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="纠错审批" name="correction">
        <section class="entity-panel">
          <div class="entity-panel-header">
            <div>
              <h2>考勤纠错申请</h2>
              <p>批准时会原子更新考勤，同时写入只追加的修订历史。</p>
            </div>
            <el-select v-model="correctionStatusFilter" class="status-filter">
              <el-option label="全部状态" value="" />
              <el-option label="待审批" value="PENDING" />
              <el-option label="已生效" value="APPLIED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已取消" value="CANCELED" />
            </el-select>
          </div>
          <el-table
            v-loading="correctionLoading"
            :data="filteredCorrections"
            row-key="id"
            table-layout="auto"
          >
            <el-table-column prop="studentName" label="学生" min-width="120">
              <template #default="{ row }">
                <strong>{{ row.studentName }}</strong>
                <small class="cell-note">{{ row.studentNo }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="courseName" label="课程" min-width="140">
              <template #default="{ row }">
                {{ row.courseName }}
                <small class="cell-note">{{ formatDate(row.sessionDate) }}</small>
              </template>
            </el-table-column>
            <el-table-column label="修订内容" min-width="180">
              <template #default="{ row }">
                {{ attendanceStatusLabel(row.currentAttendanceStatus) }}
                <span class="revision-arrow">→</span>
                <strong>{{ attendanceStatusLabel(row.requestedStatus) }}</strong>
                <small class="cell-note">{{ row.requestedRemark || '无新备注' }}</small>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="纠错原因" min-width="190" show-overflow-tooltip />
            <el-table-column label="申请人" min-width="145">
              <template #default="{ row }">
                {{ row.requestedByName }}
                <small class="cell-note">{{ formatDateTime(row.requestedAt) }}</small>
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag effect="plain" :type="workflowStatusType(row.status)">
                  {{ correctionStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210" align="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button text @click="openRevisions(row)">修订历史</el-button>
                  <template v-if="row.status === 'PENDING'">
                    <el-button text type="danger" @click="openCorrectionReview(row, 'REJECTED')">驳回</el-button>
                    <el-button text type="primary" @click="openCorrectionReview(row, 'APPROVED')">批准</el-button>
                  </template>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-state">
                <strong>暂无符合条件的纠错</strong>
                <span>调整开班、状态或关键词筛选。</span>
              </div>
            </template>
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="leaveReviewVisible"
      :title="reviewDecision === 'APPROVED' ? '批准请假' : '驳回请假'"
      width="min(520px, 92vw)"
      destroy-on-close
    >
      <div v-if="selectedLeave" class="review-summary">
        <strong>{{ selectedLeave.studentName }} · {{ selectedLeave.courseName }}</strong>
        <span>{{ formatDate(selectedLeave.sessionDate) }} {{ formatTime(selectedLeave.startTime) }}</span>
        <p>{{ selectedLeave.reason }}</p>
      </div>
      <el-form label-position="top">
        <el-form-item label="审核意见">
          <el-input
            v-model="reviewRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="reviewDecision === 'APPROVED' ? '可填写复核说明' : '请说明驳回原因'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="reviewSaving" @click="leaveReviewVisible = false">取消</el-button>
        <el-button
          :type="reviewDecision === 'APPROVED' ? 'primary' : 'danger'"
          :loading="reviewSaving"
          @click="submitLeaveReview"
        >
          {{ reviewDecision === 'APPROVED' ? '确认批准' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="correctionReviewVisible"
      :title="reviewDecision === 'APPROVED' ? '批准考勤纠错' : '驳回考勤纠错'"
      width="min(560px, 92vw)"
      destroy-on-close
    >
      <div v-if="selectedCorrection" class="review-summary">
        <strong>{{ selectedCorrection.studentName }} · {{ selectedCorrection.courseName }}</strong>
        <span>
          {{ attendanceStatusLabel(selectedCorrection.currentAttendanceStatus) }}
          → {{ attendanceStatusLabel(selectedCorrection.requestedStatus) }}
        </span>
        <p>{{ selectedCorrection.reason }}</p>
      </div>
      <el-alert
        v-if="reviewDecision === 'APPROVED'"
        class="dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="批准后会立即更新考勤并写入一条不可覆盖的修订记录。"
      />
      <el-form label-position="top">
        <el-form-item label="审批意见">
          <el-input
            v-model="reviewRemark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="reviewDecision === 'APPROVED' ? '可填写核验依据' : '请说明驳回原因'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="reviewSaving" @click="correctionReviewVisible = false">取消</el-button>
        <el-button
          :type="reviewDecision === 'APPROVED' ? 'primary' : 'danger'"
          :loading="reviewSaving"
          @click="submitCorrectionReview"
        >
          {{ reviewDecision === 'APPROVED' ? '批准并应用纠错' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="revisionDrawerVisible"
      :title="`修订历史 · ${revisionSubject}`"
      size="min(560px, 94vw)"
      destroy-on-close
    >
      <el-alert
        v-if="revisionError"
        class="dialog-alert"
        :title="revisionError"
        type="error"
        show-icon
        :closable="false"
      />
      <div v-loading="revisionLoading" class="revision-timeline">
        <article v-for="revision in revisions" :key="revision.id">
          <div class="revision-dot" aria-hidden="true" />
          <div>
            <header>
              <strong>
                {{ attendanceStatusLabel(revision.oldStatus) }}
                <span>→</span>
                {{ attendanceStatusLabel(revision.newStatus) }}
              </strong>
              <time>{{ formatDateTime(revision.changedAt) }}</time>
            </header>
            <p>
              {{ revision.oldRemark || '无备注' }}
              <span>→</span>
              {{ revision.newRemark || '无备注' }}
            </p>
            <small>审批人：{{ revision.changedByName }}</small>
          </div>
        </article>
        <div v-if="!revisionLoading && revisions.length === 0" class="empty-state large">
          <strong>暂无修订历史</strong>
          <span>该考勤尚未应用过已批准的纠错。</span>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.approval-docket {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 22px 24px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.approval-docket > div:first-child > span {
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.approval-docket h2 {
  margin: 6px 0 0;
  font-size: 19px;
}

.approval-docket p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.docket-counts {
  display: flex;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--paper);
}

.docket-counts span {
  display: flex;
  min-width: 94px;
  flex-direction: column;
  padding: 11px 15px;
  color: var(--muted);
  font-size: 10px;
}

.docket-counts span + span {
  border-left: 1px solid var(--line);
}

.docket-counts strong {
  margin-bottom: 3px;
  color: var(--ink);
  font-size: 19px;
}

.approval-filters {
  display: grid;
  grid-template-columns: minmax(250px, 0.8fr) minmax(280px, 1.2fr);
  gap: 12px;
}

.offering-filter {
  width: 100%;
}

.approval-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
}

.approval-tabs :deep(.el-tabs__item) {
  font-weight: 700;
}

.status-filter {
  width: 150px;
}

.cell-note {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 10px;
  font-weight: 400;
}

.revision-arrow {
  margin: 0 7px;
  color: var(--accent);
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}

.review-summary {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 20px;
  padding: 15px 17px;
  border-left: 3px solid var(--accent);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  background: var(--surface-soft);
}

.review-summary span,
.review-summary p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.revision-timeline {
  min-height: 220px;
}

.revision-timeline article {
  position: relative;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 12px;
  padding-bottom: 24px;
}

.revision-timeline article::before {
  position: absolute;
  top: 14px;
  bottom: 0;
  left: 5px;
  width: 1px;
  content: '';
  background: var(--line-strong);
}

.revision-timeline article:last-child::before {
  display: none;
}

.revision-dot {
  z-index: 1;
  width: 11px;
  height: 11px;
  margin-top: 4px;
  border: 3px solid #c9e1d7;
  border-radius: 50%;
  background: var(--accent);
}

.revision-timeline header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.revision-timeline header strong span,
.revision-timeline p span {
  margin: 0 6px;
  color: var(--accent);
}

.revision-timeline time,
.revision-timeline small {
  color: var(--muted);
  font-size: 10px;
}

.revision-timeline p {
  margin: 8px 0 5px;
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 700px) {
  .approval-docket,
  .entity-panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .approval-filters {
    grid-template-columns: 1fr;
  }

  .docket-counts {
    overflow-x: auto;
  }

  .status-filter {
    width: 100%;
  }

  .revision-timeline header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
