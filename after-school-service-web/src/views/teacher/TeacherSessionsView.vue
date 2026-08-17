<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'

import { courseApi } from '@/api/courses'
import { getErrorMessage } from '@/api/http'
import { teachingApi } from '@/api/teaching'
import { useSessionStore } from '@/stores/session'
import type {
  AttendanceRecord,
  AttendanceStatus,
  AttendanceUpdateRecord,
  CourseOffering,
  LessonSession,
  SessionStatus,
} from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import {
  formatDate,
  formatTime,
  statusLabel,
  statusTagType,
  weekdayLabel,
} from '@/utils/format'

import {
  attendanceDraftSignature,
  guardUnsavedAttendanceChange,
  shouldConfirmUnsavedAttendanceChange,
  UNSAVED_ATTENDANCE_MESSAGE,
} from './attendanceDraft'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const offerings = ref<CourseOffering[]>([])
const selectedOfferingId = ref<number | null>(null)
const sessions = ref<LessonSession[]>([])
const selectedSessionId = ref<number | null>(null)
const attendance = ref<AttendanceRecord[]>([])
const attendanceSessionId = ref<number | null>(null)
const attendanceBaselineSignature = ref<string | null>(null)
const loading = ref(false)
const sessionLoading = ref(false)
const attendanceLoading = ref(false)
const generating = ref(false)
const saving = ref(false)
const sessionDialogVisible = ref(false)
const sessionSaving = ref(false)
const sessionConfirming = ref(false)
const sessionInitialStatus = ref<SessionStatus>('SCHEDULED')
const error = ref('')
let sessionsRequestVersion = 0
let attendanceRequestVersion = 0
const sessionForm = reactive<{
  status: SessionStatus
  notes: string
}>({
  status: 'SCHEDULED',
  notes: '',
})

const selectedOffering = computed(() =>
  offerings.value.find((item) => item.id === selectedOfferingId.value),
)
const selectedSession = computed(() =>
  sessions.value.find((item) => item.id === selectedSessionId.value),
)
const isSchoolAdmin = computed(() => session.role === 'SCHOOL_ADMIN')
const attendanceReadOnly = computed(
  () =>
    selectedSession.value?.status === 'COMPLETED' ||
    selectedSession.value?.status === 'CANCELED',
)
const attendanceProgress = computed(() => {
  const total = attendance.value.length
  const completed = attendance.value.filter((item) => item.status !== null).length
  return total === 0 ? '0 / 0' : `${completed} / ${total}`
})
const attendanceCompletedCount = computed(
  () => attendance.value.filter((item) => item.status !== null).length,
)
const attendanceProgressPercent = computed(() => {
  const total = attendance.value.length
  return total === 0
    ? 0
    : Math.round((attendanceCompletedCount.value / total) * 100)
})
const approvedLeaveCount = computed(
  () => attendance.value.filter((item) => item.leaveRequestId).length,
)
const attendanceDirty = computed(
  () =>
    attendanceBaselineSignature.value !== null &&
    attendanceSessionId.value === selectedSessionId.value &&
    attendanceDraftSignature(attendance.value) !==
      attendanceBaselineSignature.value,
)
const sessionBusy = computed(
  () => sessionSaving.value || sessionConfirming.value,
)

function clearAttendanceDraft(): void {
  attendanceBaselineSignature.value = null
}

function confirmDiscardAttendance(): Promise<unknown> {
  return ElMessageBox.confirm(
    UNSAVED_ATTENDANCE_MESSAGE,
    '放弃考勤修改？',
    {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
    },
  )
}

function guardAttendanceChange(
  continueAction: () => void | Promise<void>,
): Promise<boolean> {
  return guardUnsavedAttendanceChange(
    shouldConfirmUnsavedAttendanceChange(
      attendanceDirty.value,
      session.state === 'guest',
    ),
    confirmDiscardAttendance,
    continueAction,
  )
}

async function requestOfferingSelection(
  offeringId: number | null,
): Promise<void> {
  if (offeringId === selectedOfferingId.value) return

  await guardAttendanceChange(() => {
    clearAttendanceDraft()
    selectedOfferingId.value = offeringId
  })
}

async function requestSessionSelection(sessionId: number | null): Promise<void> {
  if (sessionId === selectedSessionId.value) return

  await guardAttendanceChange(() => {
    clearAttendanceDraft()
    selectedSessionId.value = sessionId
  })
}

function positiveQuery(name: 'offering' | 'session'): number | null {
  const raw = route.query[name]
  const value = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isInteger(value) && value > 0 ? value : null
}

function syncSelectionQuery(
  offeringId: number | null,
  sessionId: number | null,
): void {
  const query = { ...route.query }
  if (offeringId === null) delete query.offering
  else query.offering = String(offeringId)
  if (sessionId === null) delete query.session
  else query.session = String(sessionId)
  void router.replace({ query })
}

const attendanceStatusOptions: Array<{
  label: string
  value: AttendanceStatus
}> = [
  { label: '出勤', value: 'PRESENT' },
  { label: '迟到', value: 'LATE' },
  { label: '请假', value: 'LEAVE' },
  { label: '缺勤', value: 'ABSENT' },
]
const sessionStatusOptions = computed<Array<{
  label: string
  value: SessionStatus
}>>(() => {
  if (selectedSession.value?.status === 'COMPLETED') {
    return [{ label: '已完成', value: 'COMPLETED' }]
  }
  if (selectedSession.value?.status === 'CANCELED') {
    return [{ label: '已取消', value: 'CANCELED' }]
  }
  return [
    { label: '待上课', value: 'SCHEDULED' },
    { label: '已取消', value: 'CANCELED' },
  ]
})

async function loadOfferings(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const items = await courseApi.getOfferings()
    const requestedOfferingId = positiveQuery('offering')
    const currentOfferingId = selectedOfferingId.value
    const nextOfferingId =
      currentOfferingId !== null &&
      items.some((item) => item.id === currentOfferingId)
        ? currentOfferingId
        : items.find((item) => item.id === requestedOfferingId)?.id ??
          items[0]?.id ??
          null

    if (nextOfferingId === currentOfferingId) {
      offerings.value = items
    } else {
      await guardAttendanceChange(() => {
        clearAttendanceDraft()
        offerings.value = items
        selectedOfferingId.value = nextOfferingId
      })
    }
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '授课开班加载失败。')
  } finally {
    loading.value = false
  }
}

async function loadSessions(
  offeringId: number | null,
  requestedSessionId = positiveQuery('session'),
): Promise<void> {
  const requestVersion = ++sessionsRequestVersion
  ++attendanceRequestVersion
  clearAttendanceDraft()
  sessions.value = []
  selectedSessionId.value = null
  attendance.value = []
  attendanceSessionId.value = null
  if (offeringId === null) {
    sessionLoading.value = false
    attendanceLoading.value = false
    return
  }
  sessionLoading.value = true
  error.value = ''
  try {
    const items = await teachingApi.getSessions(offeringId)
    if (
      requestVersion !== sessionsRequestVersion ||
      selectedOfferingId.value !== offeringId
    ) {
      return
    }
    sessions.value = items
    selectedSessionId.value =
      items.find((item) => item.id === requestedSessionId)?.id ??
      items[0]?.id ??
      null
  } catch (loadError) {
    if (requestVersion === sessionsRequestVersion) {
      error.value = getErrorMessage(loadError, '课次加载失败。')
    }
  } finally {
    if (requestVersion === sessionsRequestVersion) {
      sessionLoading.value = false
    }
  }
}

async function loadAttendance(sessionId: number | null): Promise<void> {
  const requestVersion = ++attendanceRequestVersion
  clearAttendanceDraft()
  attendance.value = []
  attendanceSessionId.value = null
  if (sessionId === null) {
    attendanceLoading.value = false
    return
  }
  attendanceLoading.value = true
  error.value = ''
  try {
    const items = (await teachingApi.getAttendance(sessionId)).map(
      (item) => ({ ...item }),
    )
    if (
      requestVersion !== attendanceRequestVersion ||
      selectedSessionId.value !== sessionId
    ) {
      return
    }
    attendance.value = items
    attendanceSessionId.value = sessionId
    attendanceBaselineSignature.value = attendanceDraftSignature(items)
  } catch (loadError) {
    if (requestVersion === attendanceRequestVersion) {
      error.value = getErrorMessage(loadError, '考勤名单加载失败。')
    }
  } finally {
    if (requestVersion === attendanceRequestVersion) {
      attendanceLoading.value = false
    }
  }
}

async function generateSessions(): Promise<void> {
  if (!selectedOfferingId.value) return
  const offeringId = selectedOfferingId.value
  try {
    await ElMessageBox.confirm(
      '系统将依据开班周期和每周上课日生成课次。已有课次由服务端负责去重。',
      '生成课次',
      {
        confirmButtonText: '确认生成',
        cancelButtonText: '取消',
        type: 'info',
      },
    )
  } catch {
    return
  }

  generating.value = true
  try {
    const items = await teachingApi.generateSessions(offeringId)
    if (selectedOfferingId.value === offeringId) {
      const nextSessionId = items.some(
        (item) => item.id === selectedSessionId.value,
      )
        ? selectedSessionId.value
        : items[0]?.id ?? null
      if (nextSessionId === selectedSessionId.value) {
        sessions.value = items
      } else {
        await guardAttendanceChange(() => {
          clearAttendanceDraft()
          sessions.value = items
          selectedSessionId.value = nextSessionId
        })
      }
    }
    ElMessage.success('课次已生成')
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '课次生成失败。'))
  } finally {
    generating.value = false
  }
}

function openSessionEditor(): void {
  if (!selectedSession.value) return
  sessionForm.status = selectedSession.value.status
  sessionInitialStatus.value = selectedSession.value.status
  sessionForm.notes = selectedSession.value.notes ?? ''
  sessionDialogVisible.value = true
}

function closeSessionEditor(): void {
  if (!sessionBusy.value) sessionDialogVisible.value = false
}

function beforeSessionEditorClose(done: () => void): void {
  if (!sessionBusy.value) done()
}

async function saveSession(): Promise<void> {
  if (!selectedSessionId.value || sessionBusy.value) return
  if (
    sessionForm.status === 'CANCELED' &&
    sessionInitialStatus.value !== 'CANCELED'
  ) {
    sessionConfirming.value = true
    try {
      await ElMessageBox.confirm(
        '取消课次后，该课次将不能再登记考勤。请确认已完成必要的通知与安排。',
        '确认取消课次',
        {
          confirmButtonText: '确认取消',
          cancelButtonText: '继续编辑',
          type: 'warning',
        },
      )
    } catch {
      return
    } finally {
      sessionConfirming.value = false
    }
  }
  sessionSaving.value = true
  try {
    const updated = await teachingApi.updateSession(
      selectedSessionId.value,
      sessionForm.status,
      sessionForm.notes.trim() || null,
    )
    const index = sessions.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) sessions.value[index] = updated
    sessionDialogVisible.value = false
    ElMessage.success('课次状态已更新')
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '课次更新失败。'))
  } finally {
    sessionSaving.value = false
  }
}

async function saveAttendance(): Promise<void> {
  const sessionId = selectedSessionId.value
  if (!sessionId) return
  if (
    attendanceLoading.value ||
    attendanceSessionId.value !== sessionId
  ) {
    ElMessage.warning('当前课次名单尚未完成加载，请稍后再保存。')
    return
  }
  if (attendanceReadOnly.value) {
    ElMessage.warning('已完成或已取消课次的考勤为只读记录。')
    return
  }
  const incomplete = attendance.value.some((item) => item.status === null)
  if (incomplete) {
    ElMessage.warning('请为名单中的每位学生选择考勤状态。')
    return
  }
  const records: AttendanceUpdateRecord[] = attendance.value.map((item) => ({
    studentId: item.studentId,
    status: item.status as AttendanceStatus,
    remark: item.remark?.trim() || null,
  }))
  saving.value = true
  try {
    const saved = await teachingApi.updateAttendance(
      sessionId,
      records,
    )
    if (
      selectedSessionId.value === sessionId &&
      attendanceSessionId.value === sessionId
    ) {
      attendance.value = saved
      attendanceBaselineSignature.value = attendanceDraftSignature(saved)
      const sessionIndex = sessions.value.findIndex(
        (item) => item.id === sessionId,
      )
      const currentSession = sessions.value[sessionIndex]
      if (sessionIndex >= 0 && currentSession) {
        sessions.value[sessionIndex] = {
          ...currentSession,
          status: 'COMPLETED',
        }
      }
    }
    ElMessage.success('考勤已保存')
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '考勤保存失败。'))
  } finally {
    saving.value = false
  }
}

function markAllPresent(): void {
  if (attendanceReadOnly.value) return
  for (const record of attendance.value) {
    if (record.status !== 'LEAVE') record.status = 'PRESENT'
  }
  ElMessage.success('已批量标记为出勤，请核对迟到、请假和缺勤学生。')
}

function setAttendanceStatus(
  record: AttendanceRecord,
  status: AttendanceStatus,
): void {
  if (
    attendanceReadOnly.value ||
    attendanceLoading.value ||
    attendanceSessionId.value !== selectedSessionId.value ||
    saving.value
  ) {
    return
  }
  record.status = status
}

watch(selectedOfferingId, (offeringId, previousOfferingId) => {
  const requestedSessionId =
    previousOfferingId === null ? positiveQuery('session') : null
  syncSelectionQuery(offeringId, requestedSessionId)
  void loadSessions(offeringId, requestedSessionId)
})
watch(selectedSessionId, (sessionId) => {
  syncSelectionQuery(selectedOfferingId.value, sessionId)
  void loadAttendance(sessionId)
})

function handleBeforeUnload(event: BeforeUnloadEvent): void {
  if (
    !shouldConfirmUnsavedAttendanceChange(
      attendanceDirty.value,
      session.state === 'guest',
    )
  ) {
    return
  }
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => guardAttendanceChange(() => undefined))

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  void loadOfferings()
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="授课执行"
      :title="isSchoolAdmin ? '本校课次与考勤' : '课次与学生考勤'"
      :description="
        isSchoolAdmin
          ? '管理本校全部开班课次，核对学生出勤并保留教学记录。'
          : '查看本人承担的开班，按课次完成学生出勤登记。'
      "
    >
      <template #actions>
        <el-button :loading="loading" @click="loadOfferings">刷新</el-button>
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

    <section class="teaching-context teaching-course-bar">
      <div class="teaching-course-identity">
        <span class="teaching-course-mark" aria-hidden="true">课</span>
        <div>
          <span class="teaching-course-label">当前授课开班</span>
          <h2>
            {{ selectedOffering?.courseName || selectedOffering?.offeringCode || '选择授课开班' }}
          </h2>
          <p v-if="selectedOffering">
            {{ weekdayLabel(selectedOffering.weekDay) }}
            {{ formatTime(selectedOffering.startTime) }}–{{ formatTime(selectedOffering.endTime) }}
            · {{ selectedOffering.classroom }}
          </p>
          <p v-else>
            {{ isSchoolAdmin ? '可选择本校任一开班。' : '这里只显示由你负责授课的开班。' }}
          </p>
        </div>
      </div>
      <div class="teaching-course-picker">
        <span>{{ isSchoolAdmin ? '本校开班' : '我的开班' }} · {{ offerings.length }}</span>
        <el-select
          :model-value="selectedOfferingId"
          :loading="loading"
          :disabled="saving"
          class="offering-selector"
          placeholder="请选择开班"
          filterable
          aria-label="切换授课开班"
          @update:model-value="requestOfferingSelection"
        >
          <el-option
            v-for="offering in offerings"
            :key="offering.id"
            :label="`${offering.courseName || offering.offeringCode}（${weekdayLabel(offering.weekDay)} ${formatTime(offering.startTime)}）`"
            :value="offering.id"
          />
        </el-select>
      </div>
    </section>

    <div v-if="selectedOffering" class="teaching-grid">
      <section class="session-panel">
        <header>
          <div>
            <h2>课程课次</h2>
            <p>
              {{ selectedOffering.courseName || selectedOffering.offeringCode }}
              · {{ selectedOffering.classroom }}
            </p>
          </div>
          <el-button
            type="primary"
            :loading="generating"
            @click="generateSessions"
          >
            生成课次
          </el-button>
        </header>

        <div v-loading="sessionLoading" class="session-list">
          <button
            v-for="(sessionItem, index) in sessions"
            :key="sessionItem.id"
            type="button"
            :disabled="saving"
            :class="{ active: selectedSessionId === sessionItem.id }"
            @click="requestSessionSelection(sessionItem.id)"
          >
            <span class="session-sequence" aria-hidden="true">
              {{ String(index + 1).padStart(2, '0') }}
            </span>
            <span class="session-copy">
              <strong class="session-date">{{ formatDate(sessionItem.sessionDate) }}</strong>
              <small>
                {{ formatTime(sessionItem.startTime) }}–{{ formatTime(sessionItem.endTime) }}
              </small>
            </span>
            <el-tag
              effect="plain"
              size="small"
              :type="statusTagType(sessionItem.status)"
            >
              {{ statusLabel(sessionItem.status) }}
            </el-tag>
          </button>
          <div v-if="!sessionLoading && sessions.length === 0" class="empty-state">
            <strong>暂无课次</strong>
            <span>可根据当前开班计划生成课次。</span>
          </div>
        </div>
      </section>

      <section class="attendance-panel">
        <header>
          <div class="attendance-heading-copy">
            <h2>学生考勤</h2>
            <p v-if="selectedSession">
              {{ formatDate(selectedSession.sessionDate) }}
              {{ formatTime(selectedSession.startTime) }}–{{ formatTime(selectedSession.endTime) }}
            </p>
            <p v-else>请先选择左侧课次。</p>
            <div v-if="selectedSession" class="attendance-progress-bar">
              <span aria-hidden="true">
                <i :style="{ width: `${attendanceProgressPercent}%` }"></i>
              </span>
              <small>
                已填写 <strong>{{ attendanceProgress }}</strong>
                <template v-if="approvedLeaveCount > 0">
                  · {{ approvedLeaveCount }} 条已批准请假
                </template>
              </small>
            </div>
          </div>
          <div class="header-actions">
            <el-button
              :disabled="
                !selectedSessionId ||
                selectedSession?.status === 'COMPLETED'
              "
              @click="openSessionEditor"
            >
              编辑课次
            </el-button>
            <el-button
              :disabled="
                !selectedSessionId ||
                attendance.length === 0 ||
                attendanceLoading ||
                attendanceSessionId !== selectedSessionId ||
                saving ||
                attendanceReadOnly
              "
              @click="markAllPresent"
            >
              全员出勤
            </el-button>
            <el-button
              type="primary"
              :disabled="
                !selectedSessionId ||
                attendance.length === 0 ||
                attendanceLoading ||
                attendanceSessionId !== selectedSessionId ||
                attendanceReadOnly
              "
              :loading="saving"
              @click="saveAttendance"
            >
              保存考勤
            </el-button>
          </div>
        </header>

        <div v-loading="attendanceLoading" class="attendance-roster">
          <article
            v-for="record in attendance"
            :key="record.studentId"
            class="attendance-student-card"
            :class="{ 'has-approved-leave': record.leaveRequestId }"
          >
            <div class="attendance-student-identity">
              <span aria-hidden="true">
                {{ (record.studentName || record.studentNo || '生').slice(0, 1) }}
              </span>
              <div>
                <strong>{{ record.studentName || record.studentNo || record.studentId }}</strong>
                <small>{{ record.studentNo || '在班学生' }}</small>
              </div>
            </div>

            <div class="attendance-status-field">
              <span>考勤状态</span>
              <div class="attendance-status-options" role="group" :aria-label="`${record.studentName || record.studentNo}考勤状态`">
                <button
                  v-for="option in attendanceStatusOptions"
                  :key="option.value"
                  type="button"
                  :class="[
                    `is-${option.value.toLowerCase()}`,
                    { active: record.status === option.value },
                  ]"
                  :aria-pressed="record.status === option.value"
                  :disabled="
                    attendanceReadOnly ||
                    attendanceLoading ||
                    attendanceSessionId !== selectedSessionId ||
                    saving
                  "
                  @click="setAttendanceStatus(record, option.value)"
                >
                  {{ option.label }}
                </button>
              </div>
            </div>

            <div v-if="record.leaveRequestId" class="attendance-leave-note">
              <el-tag type="warning" effect="plain" size="small">
                已批准请假
              </el-tag>
              <span>{{ record.leaveReason || '家长已完成请假审批' }}</span>
            </div>

            <label class="attendance-remark-field">
              <span>课堂备注</span>
              <el-input
                v-model="record.remark"
                maxlength="255"
                placeholder="可选备注"
                :disabled="
                  attendanceReadOnly ||
                  attendanceLoading ||
                  attendanceSessionId !== selectedSessionId ||
                  saving
                "
              />
            </label>
          </article>

          <div v-if="!attendanceLoading && attendance.length === 0" class="empty-state">
            <strong>{{ selectedSessionId ? '暂无考勤名单' : '尚未选择课次' }}</strong>
            <span>
              {{
                selectedSessionId
                  ? '该课次暂无服务端学生名单。'
                  : '选择课次后加载已报名学生。'
              }}
            </span>
          </div>
        </div>
        <p v-if="approvedLeaveCount > 0" class="attendance-prefill-note">
          当前名单含 {{ approvedLeaveCount }}
          条已批准请假，系统已预填“请假”；保存前仍可按学生实际到课情况复核。
        </p>
      </section>
    </div>

    <div v-if="!loading && offerings.length === 0" class="empty-state page-empty">
      <strong>暂无授课开班</strong>
      <span>当前账号没有服务端授权范围内的开班。</span>
    </div>

    <el-dialog
      v-model="sessionDialogVisible"
      title="编辑课次"
      width="min(520px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :close-on-press-escape="!sessionBusy"
      :show-close="!sessionBusy"
      :before-close="beforeSessionEditorClose"
    >
      <el-form
        label-position="top"
        :disabled="sessionBusy"
        @submit.prevent="saveSession"
      >
        <el-form-item label="课次状态" required>
          <el-select v-model="sessionForm.status">
            <el-option
              v-for="option in sessionStatusOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <div class="form-help">
            课次在完整保存学生考勤后由系统自动标记为已完成。
          </div>
        </el-form-item>
        <el-form-item label="课次备注">
          <el-input
            v-model="sessionForm.notes"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="记录调课、取消或课堂情况"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="sessionBusy" @click="closeSessionEditor">
          取消
        </el-button>
        <el-button type="primary" :loading="sessionBusy" @click="saveSession">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.teaching-course-bar {
  border: 1px solid #cbdced;
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(120deg, #e9f3ff 0%, rgb(255 255 255 / 96%) 54%, #e9f7f0 100%);
  box-shadow: 0 9px 24px rgb(39 77 117 / 6%);
}

.teaching-course-identity,
.teaching-course-picker,
.attendance-student-identity,
.capacity-block,
.attendance-progress-bar small {
  display: flex;
  align-items: center;
}

.teaching-course-identity {
  min-width: 0;
  gap: 14px;
}

.teaching-course-mark {
  display: grid;
  width: 50px;
  height: 50px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 15px;
  color: #ffffff;
  font-family: "STKaiti", "KaiTi", serif;
  font-size: 21px;
  font-weight: 800;
  background: linear-gradient(145deg, #1f5faf, #367dc7);
  box-shadow: 0 8px 18px rgb(31 95 175 / 20%);
}

.teaching-course-label {
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.teaching-course-identity h2 {
  margin-top: 5px;
  font-size: 20px;
}

.teaching-course-picker {
  flex: 0 0 auto;
  gap: 14px;
}

.teaching-course-picker > span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.teaching-grid {
  grid-template-columns: minmax(270px, 0.7fr) minmax(0, 1.75fr);
}

.session-panel > header {
  background: linear-gradient(180deg, #ffffff, #f8fbfe);
}

.session-list {
  position: relative;
  padding: 8px 0;
}

.session-list::before {
  position: absolute;
  top: 20px;
  bottom: 20px;
  left: 39px;
  width: 1px;
  background: #cbdced;
  content: "";
}

.session-list > button {
  position: relative;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 11px;
  padding: 12px 16px;
  border-bottom: 0;
}

.session-list > button + button {
  margin-top: 2px;
}

.session-list > button.active {
  background: linear-gradient(90deg, #e3efff 0%, rgb(240 247 255 / 42%) 100%);
}

.session-list > button.active::after {
  position: absolute;
  inset: 7px auto 7px 0;
  width: 4px;
  border-radius: 0 6px 6px 0;
  background: var(--accent);
  content: "";
}

.session-sequence {
  display: grid;
  width: 31px;
  height: 31px;
  z-index: 1;
  place-items: center;
  border: 4px solid #ffffff;
  border-radius: 50%;
  color: var(--muted);
  font-size: 9px;
  font-weight: 800;
  background: #dce8f5;
  box-shadow: 0 0 0 1px #c5d8ea;
}

.session-list > button.active .session-sequence {
  color: #ffffff;
  background: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.session-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.session-copy small {
  color: var(--muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.attendance-panel > header {
  align-items: flex-start;
  background: linear-gradient(180deg, #ffffff, #f8fbfe);
}

.attendance-heading-copy {
  min-width: min(100%, 260px);
}

.attendance-progress-bar {
  margin-top: 12px;
}

.attendance-progress-bar > span {
  display: block;
  width: min(260px, 100%);
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #e3ebf4;
}

.attendance-progress-bar > span i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--accent), #43a779);
}

.attendance-progress-bar small {
  gap: 4px;
  margin-top: 6px;
  color: var(--muted);
  font-size: 10px;
}

.attendance-progress-bar strong {
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}

.attendance-roster {
  min-height: 180px;
  padding: 14px;
  background: #f3f7fb;
}

.attendance-student-card {
  display: grid;
  grid-template-columns: minmax(130px, 0.7fr) minmax(290px, 1.4fr) minmax(180px, 0.8fr);
  align-items: center;
  gap: 16px;
  padding: 16px;
  border: 1px solid #d4e1ed;
  border-radius: 13px;
  background: #ffffff;
  box-shadow: 0 6px 16px rgb(39 77 117 / 4%);
}

.attendance-student-card + .attendance-student-card {
  margin-top: 10px;
}

.attendance-student-card.has-approved-leave {
  border-color: #ecd4a9;
  background: linear-gradient(105deg, #fffaf0 0%, #ffffff 42%);
}

.attendance-student-identity {
  min-width: 0;
  gap: 10px;
}

.attendance-student-identity > span {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 11px;
  color: #ffffff;
  font-size: 13px;
  font-weight: 800;
  background: var(--accent);
}

.attendance-student-identity div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.attendance-student-identity strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attendance-student-identity small {
  color: var(--subtle);
  font-size: 10px;
}

.attendance-status-field > span,
.attendance-remark-field > span {
  display: block;
  margin-bottom: 7px;
  color: var(--subtle);
  font-size: 10px;
  font-weight: 700;
}

.attendance-status-options {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
}

.attendance-status-options button {
  min-height: 34px;
  padding: 7px 6px;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  background: #ffffff;
  cursor: pointer;
}

.attendance-status-options button:not(:disabled):hover {
  border-color: #8eb2d9;
  color: var(--accent);
  background: var(--accent-soft);
}

.attendance-status-options button.active.is-present {
  border-color: #71bd99;
  color: #237453;
  background: #e3f4eb;
}

.attendance-status-options button.active.is-late {
  border-color: #e1ad64;
  color: #94601d;
  background: #fff1d9;
}

.attendance-status-options button.active.is-leave {
  border-color: #7ca9d9;
  color: #174f94;
  background: #e3efff;
}

.attendance-status-options button.active.is-absent {
  border-color: #df8f8f;
  color: #a33d3d;
  background: #f9e7e7;
}

.attendance-status-options button:disabled {
  cursor: not-allowed;
  opacity: 0.68;
}

.attendance-student-card .attendance-leave-note {
  grid-column: 1 / 3;
  grid-row: 2;
  padding: 9px 11px;
  border-radius: 8px;
  background: #fff6e7;
}

.attendance-remark-field {
  min-width: 0;
}

.attendance-prefill-note {
  border-top-color: #edd8b2;
}

@media (max-width: 1180px) {
  .attendance-student-card {
    grid-template-columns: minmax(120px, 0.7fr) minmax(260px, 1.3fr);
  }

  .attendance-remark-field {
    grid-column: 1 / -1;
  }

  .attendance-student-card .attendance-leave-note {
    grid-column: 1 / -1;
    grid-row: auto;
  }
}

@media (max-width: 900px) {
  .teaching-course-bar,
  .teaching-course-picker {
    align-items: stretch;
    flex-direction: column;
  }

  .teaching-course-picker {
    width: 100%;
    gap: 7px;
  }

  .teaching-course-picker :deep(.offering-selector) {
    width: 100%;
  }

  .teaching-grid {
    grid-template-columns: 1fr;
  }

  .session-panel {
    max-height: none;
  }
}

@media (max-width: 700px) {
  .teaching-course-identity {
    align-items: flex-start;
  }

  .session-list > button {
    grid-template-columns: 42px minmax(0, 1fr) auto;
  }

  .attendance-panel > header {
    align-items: stretch;
  }

  .attendance-roster {
    padding: 10px;
  }

  .attendance-student-card {
    grid-template-columns: 1fr;
    gap: 14px;
    padding: 15px;
  }

  .attendance-status-options {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .attendance-student-card .attendance-leave-note,
  .attendance-remark-field {
    grid-column: auto;
  }
}
</style>
