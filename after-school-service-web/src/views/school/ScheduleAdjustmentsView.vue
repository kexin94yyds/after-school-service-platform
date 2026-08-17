<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import {
  computed,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue'
import {
  useRoute,
  useRouter,
  type LocationQueryRaw,
} from 'vue-router'

import {
  academicApi,
  type AcademicOffering,
  type AcademicSession,
  type RescheduleInput,
  type ScheduleAdjustment,
  type SchoolRoom,
} from '@/api/academic'
import { ApiClientError, getErrorMessage } from '@/api/http'
import PageHeader from '@/components/PageHeader.vue'
import { useLongFormGuard } from '@/composables/useLongFormGuard'
import { useSessionStore } from '@/stores/session'
import {
  combineDateAndTime,
  formatDate,
  formatDateTime,
  formatTime,
  statusLabel,
  statusTagType,
  weekdayLabel,
} from '@/utils/format'

type SessionFilter = '' | AcademicSession['status']

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()

const offerings = ref<AcademicOffering[]>([])
const rooms = ref<SchoolRoom[]>([])
const sessions = ref<AcademicSession[]>([])
const adjustments = ref<ScheduleAdjustment[]>([])
const selectedOfferingId = ref<number | null>(
  queryNumber(route.query.offeringId),
)
const sessionFilter = ref<SessionFilter>(
  querySessionFilter(route.query.sessionStatus),
)
const pendingSessionId = ref<number | null>(
  queryNumber(route.query.sessionId),
)
const baseLoading = ref(false)
const detailLoading = ref(false)
const baseError = ref('')
const detailError = ref('')
const mounted = ref(false)
const detailLoadVersion = ref(0)

const rescheduleDialogVisible = ref(false)
const editingSession = ref<AcademicSession | null>(null)
const rescheduling = ref(false)
const revertingAdjustmentId = ref<number | null>(null)
const rescheduleError = ref('')
const rescheduleForm = reactive<{
  sessionDate: string
  startTime: string
  endTime: string
  roomId: number | null
  reason: string
}>({
  sessionDate: '',
  startTime: '',
  endTime: '',
  roomId: null,
  reason: '',
})
const {
  beforeClose: beforeRescheduleDialogClose,
  captureBaseline: captureRescheduleBaseline,
  requestClose: requestRescheduleDialogClose,
} = useLongFormGuard({
  visible: rescheduleDialogVisible,
  saving: rescheduling,
  snapshot: () => ({ ...rescheduleForm }),
})

const selectedOffering = computed(() =>
  offerings.value.find(
    (offering) => offering.id === selectedOfferingId.value,
  ),
)

const filteredSessions = computed(() =>
  sessionFilter.value
    ? sessions.value.filter(
        (lesson) => lesson.status === sessionFilter.value,
      )
    : sessions.value,
)

const activeRooms = computed(() =>
  rooms.value.filter((room) => room.status === 'ACTIVE'),
)

const rescheduleMetrics = computed(() => [
  {
    label: '课次总数',
    value: sessions.value.length,
    note: selectedOffering.value?.courseName || '当前开班',
  },
  {
    label: '待上课',
    value: sessions.value.filter((lesson) => lesson.status === 'SCHEDULED')
      .length,
    note: '可在开课前申请调课',
  },
  {
    label: '已调课',
    value: adjustments.value.length,
    note: '当前开班的历史记录',
  },
  {
    label: '可用教室',
    value: activeRooms.value.length,
    note: '仍需满足容量与冲突校验',
  },
])

function queryNumber(value: unknown): number | null {
  const source = Array.isArray(value) ? value[0] : value
  if (typeof source !== 'string' || !source) return null
  const parsed = Number(source)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function querySessionFilter(value: unknown): SessionFilter {
  const source = Array.isArray(value) ? value[0] : value
  return source === 'SCHEDULED' ||
    source === 'COMPLETED' ||
    source === 'CANCELED'
    ? source
    : ''
}

function schoolId(): number {
  const id = sessionStore.user?.schoolId
  if (!id) {
    throw new ApiClientError('当前学校管理员账号未绑定学校。', {
      code: 'SCHOOL_CONTEXT_REQUIRED',
    })
  }
  return id
}

function queryValue(value: unknown): string | undefined {
  const source = Array.isArray(value) ? value[0] : value
  return typeof source === 'string' && source ? source : undefined
}

function syncQuery(): void {
  const offeringId = selectedOfferingId.value?.toString()
  const sessionStatus = sessionFilter.value || undefined
  const sessionId =
    rescheduleDialogVisible.value && editingSession.value
      ? editingSession.value.id.toString()
      : undefined
  if (
    queryValue(route.query.offeringId) === offeringId &&
    queryValue(route.query.sessionStatus) === sessionStatus &&
    queryValue(route.query.sessionId) === sessionId
  ) {
    return
  }
  const query: LocationQueryRaw = { ...route.query }
  if (offeringId) query.offeringId = offeringId
  else delete query.offeringId
  if (sessionStatus) query.sessionStatus = sessionStatus
  else delete query.sessionStatus
  if (sessionId) query.sessionId = sessionId
  else delete query.sessionId
  void router.replace({ query })
}

function offeringLabel(offering: AcademicOffering): string {
  const time = `${weekdayLabel(offering.weekDay)} ${formatTime(offering.startTime)}`
  return `${offering.courseName || offering.offeringCode} · ${time} · ${offering.classroom}`
}

async function loadBase(): Promise<void> {
  baseLoading.value = true
  baseError.value = ''
  const [offeringResult, roomResult] = await Promise.allSettled([
    academicApi.getOfferings(),
    academicApi.getRooms(),
  ])
  if (offeringResult.status === 'fulfilled') {
    offerings.value = offeringResult.value
    if (
      selectedOfferingId.value === null ||
      !offerings.value.some(
        (offering) => offering.id === selectedOfferingId.value,
      )
    ) {
      selectedOfferingId.value =
        offerings.value.find((offering) =>
          ['PUBLISHED', 'CLOSED'].includes(offering.status),
        )?.id ??
        offerings.value[0]?.id ??
        null
    }
  } else {
    baseError.value = getErrorMessage(
      offeringResult.reason,
      '开班列表加载失败。',
    )
  }
  if (roomResult.status === 'fulfilled') {
    rooms.value = roomResult.value
  } else {
    const message = getErrorMessage(roomResult.reason, '教室资源加载失败。')
    baseError.value = baseError.value
      ? `${baseError.value} ${message}`
      : message
  }
  baseLoading.value = false
}

async function loadDetails(): Promise<void> {
  const offeringId = selectedOfferingId.value
  const version = ++detailLoadVersion.value
  sessions.value = []
  adjustments.value = []
  detailError.value = ''
  if (offeringId === null) {
    detailLoading.value = false
    return
  }
  detailLoading.value = true
  let scopedSchoolId: number
  try {
    scopedSchoolId = schoolId()
  } catch (error) {
    detailError.value = getErrorMessage(error, '学校数据范围校验失败。')
    detailLoading.value = false
    return
  }
  const [sessionResult, adjustmentResult] = await Promise.allSettled([
    academicApi.getSessions(offeringId),
    academicApi.getScheduleAdjustments({
      schoolId: scopedSchoolId,
      offeringId,
    }),
  ])
  if (version !== detailLoadVersion.value) return

  if (sessionResult.status === 'fulfilled') {
    sessions.value = sessionResult.value
  } else {
    detailError.value = getErrorMessage(
      sessionResult.reason,
      '课次列表加载失败。',
    )
  }
  if (adjustmentResult.status === 'fulfilled') {
    adjustments.value = adjustmentResult.value
  } else {
    const message = getErrorMessage(
      adjustmentResult.reason,
      '调课记录加载失败。',
    )
    detailError.value = detailError.value
      ? `${detailError.value} ${message}`
      : message
  }
  detailLoading.value = false

  if (pendingSessionId.value !== null) {
    const pending = sessions.value.find(
      (lesson) => lesson.id === pendingSessionId.value,
    )
    pendingSessionId.value = null
    if (pending && !rescheduleReason(pending)) openReschedule(pending)
  }
}

async function reloadAll(): Promise<void> {
  await loadBase()
  await loadDetails()
}

function lessonDateTime(lesson: AcademicSession): Date {
  return combineDateAndTime(lesson.sessionDate, lesson.startTime)
}

function rescheduleReason(lesson: AcademicSession): string {
  const offering = selectedOffering.value
  if (!offering) return '开班信息未加载'
  if (lesson.status !== 'SCHEDULED') return '只有待上课课次可以调课'
  if (!['PUBLISHED', 'CLOSED'].includes(offering.status)) {
    return '仅已发布或已截止报名的开班可以调课'
  }
  if (lessonDateTime(lesson).getTime() <= Date.now()) {
    return '已开始的课次不能调课'
  }
  if ((lesson.recordedCount ?? 0) > 0) return '已有考勤记录，不能调课'
  return ''
}

function defaultRoomId(lesson: AcademicSession): number | null {
  const byName = activeRooms.value.find(
    (room) => room.roomName === lesson.classroom,
  )
  return byName?.id ?? selectedOffering.value?.roomId ?? null
}

function openReschedule(lesson: AcademicSession): void {
  const reason = rescheduleReason(lesson)
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  editingSession.value = lesson
  Object.assign(rescheduleForm, {
    sessionDate: lesson.sessionDate,
    startTime: formatTime(lesson.startTime),
    endTime: formatTime(lesson.endTime),
    roomId: defaultRoomId(lesson),
    reason: '',
  })
  rescheduleError.value = ''
  captureRescheduleBaseline()
  rescheduleDialogVisible.value = true
  syncQuery()
}

function closeReschedule(): void {
  editingSession.value = null
  rescheduleError.value = ''
  syncQuery()
}

function validateReschedule(): string {
  const offering = selectedOffering.value
  if (!editingSession.value || !offering) return '课次信息已变化，请刷新后重试。'
  if (
    !rescheduleForm.sessionDate ||
    !rescheduleForm.startTime ||
    !rescheduleForm.endTime ||
    rescheduleForm.roomId === null ||
    !rescheduleForm.reason.trim()
  ) {
    return '请完整填写目标日期、时间、教室和调课原因。'
  }
  if (rescheduleForm.startTime >= rescheduleForm.endTime) {
    return '调课结束时间必须晚于开始时间。'
  }
  if (
    rescheduleForm.sessionDate < offering.startDate ||
    rescheduleForm.sessionDate > offering.endDate
  ) {
    return `调课日期须位于 ${formatDate(offering.startDate)} 至 ${formatDate(offering.endDate)}。`
  }
  const target = combineDateAndTime(
    rescheduleForm.sessionDate,
    rescheduleForm.startTime,
  )
  if (Number.isNaN(target.getTime()) || target.getTime() <= Date.now()) {
    return '调课后的开始时间必须晚于当前时间。'
  }
  const room = rooms.value.find((item) => item.id === rescheduleForm.roomId)
  if (!room || room.status !== 'ACTIVE') return '请选择仍在启用的教室。'
  if (room.capacity < offering.capacity) {
    return `所选教室容量不足，当前开班需要至少 ${offering.capacity} 人。`
  }
  const unchangedRoom =
    rescheduleForm.roomId === defaultRoomId(editingSession.value)
  if (
    rescheduleForm.sessionDate === editingSession.value.sessionDate &&
    rescheduleForm.startTime === formatTime(editingSession.value.startTime) &&
    rescheduleForm.endTime === formatTime(editingSession.value.endTime) &&
    unchangedRoom
  ) {
    return '调课后的日期、时间和教室与当前安排完全相同。'
  }
  return ''
}

async function submitReschedule(): Promise<void> {
  rescheduleError.value = validateReschedule()
  if (rescheduleError.value || !editingSession.value) return
  const room = rooms.value.find((item) => item.id === rescheduleForm.roomId)
  if (!room) return

  try {
    await ElMessageBox.confirm(
      `课次将调整至 ${formatDate(rescheduleForm.sessionDate)} ${rescheduleForm.startTime}-${rescheduleForm.endTime}，教室为“${room.roomName}”。系统还会校验教师、教室、校历和容量冲突。`,
      '确认调课',
      {
        confirmButtonText: '确认调课',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }

  rescheduling.value = true
  try {
    const payload: RescheduleInput = {
      sessionDate: rescheduleForm.sessionDate,
      startTime: rescheduleForm.startTime,
      endTime: rescheduleForm.endTime,
      roomId: room.id,
      reason: rescheduleForm.reason.trim(),
    }
    await academicApi.reschedule(editingSession.value.id, payload)
    captureRescheduleBaseline()
    rescheduleDialogVisible.value = false
    ElMessage.success('调课已生效，并写入调整记录')
    await loadDetails()
  } catch (error) {
    rescheduleError.value = getErrorMessage(error, '调课失败。')
  } finally {
    rescheduling.value = false
  }
}

function adjustmentRoute(adjustment: ScheduleAdjustment): string {
  return `${formatDate(adjustment.originalSessionDate)} ${formatTime(adjustment.originalStartTime)} → ${formatDate(adjustment.adjustedSessionDate)} ${formatTime(adjustment.adjustedStartTime)}`
}

function latestAppliedAdjustmentId(sessionId: number): number | null {
  return (
    adjustments.value.find(
      (item) => item.sessionId === sessionId && item.status === 'APPLIED',
    )?.id ?? null
  )
}

function revertReason(adjustment: ScheduleAdjustment): string {
  if (adjustment.status !== 'APPLIED') return '该调课记录已撤销'
  if (latestAppliedAdjustmentId(adjustment.sessionId) !== adjustment.id) {
    return '只能撤销同一课次当前最新的生效调课'
  }
  return ''
}

async function revertAdjustment(
  adjustment: ScheduleAdjustment,
): Promise<void> {
  const reason = revertReason(adjustment)
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  try {
    await ElMessageBox.confirm(
      `课次将恢复为 ${formatDate(adjustment.originalSessionDate)} ${formatTime(adjustment.originalStartTime)}-${formatTime(adjustment.originalEndTime)} · ${adjustment.originalClassroom}。系统会重新校验学期、教师、教室、校历和全体在报学生冲突。`,
      '确认撤销调课',
      {
        confirmButtonText: '确认撤销',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }

  revertingAdjustmentId.value = adjustment.id
  try {
    await academicApi.revertScheduleAdjustment(adjustment.id)
    ElMessage.success('调课已撤销，课次已恢复原安排')
    await loadDetails()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '撤销调课失败。'))
  } finally {
    revertingAdjustmentId.value = null
  }
}

watch([selectedOfferingId, sessionFilter], () => {
  syncQuery()
})

watch(selectedOfferingId, () => {
  if (!mounted.value) return
  rescheduleDialogVisible.value = false
  editingSession.value = null
  void loadDetails()
})

watch(rescheduleDialogVisible, (visible) => {
  if (!visible) closeReschedule()
})

onMounted(async () => {
  await loadBase()
  await loadDetails()
  mounted.value = true
  syncQuery()
})
</script>

<template>
  <section class="page-stack schedule-adjustments">
    <PageHeader
      kicker="资源冲突控制"
      title="课次调课与留痕"
      description="围绕一个开班查看课次与历史调整。每次调课会同步更新课次，并校验教师、教室容量、时段占用、校历和考勤状态。"
    >
      <template #actions>
        <el-button
          :loading="baseLoading || detailLoading"
          @click="reloadAll"
        >
          刷新
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="baseError"
      class="page-alert"
      :title="baseError"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button text type="primary" @click="loadBase">重新加载</el-button>
      </template>
    </el-alert>

    <section class="offering-context">
      <div>
        <span class="context-label">调课对象</span>
        <h2>{{ selectedOffering?.courseName || '请选择一个开班' }}</h2>
        <p v-if="selectedOffering">
          {{ selectedOffering.termName || selectedOffering.term }} ·
          {{ weekdayLabel(selectedOffering.weekDay) }}
          {{ formatTime(selectedOffering.startTime) }}-{{
            formatTime(selectedOffering.endTime)
          }}
          · {{ selectedOffering.classroom }}
        </p>
        <p v-else>开班发布并生成课次后，可以在这里发起调课。</p>
      </div>
      <el-select
        v-model="selectedOfferingId"
        class="offering-selector"
        filterable
        placeholder="请选择开班"
        :loading="baseLoading"
        :disabled="offerings.length === 0"
        aria-label="选择调课开班"
      >
        <el-option
          v-for="offering in offerings"
          :key="offering.id"
          :label="offeringLabel(offering)"
          :value="offering.id"
        />
      </el-select>
    </section>

    <section class="metric-grid adjustment-metrics" aria-label="调课概况">
      <article
        v-for="metric in rescheduleMetrics"
        :key="metric.label"
        class="metric-cell"
      >
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
        <small>{{ metric.note }}</small>
      </article>
    </section>

    <el-alert
      v-if="detailError"
      class="page-alert"
      :title="detailError"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button text type="primary" @click="loadDetails">
          重新加载
        </el-button>
      </template>
    </el-alert>

    <section class="adjustment-grid">
      <section class="entity-panel session-list">
        <header class="entity-panel-header">
          <div>
            <h2>课次安排</h2>
            <p>只有尚未开始、无考勤记录的待上课课次可发起调课。</p>
          </div>
          <el-select
            v-model="sessionFilter"
            clearable
            placeholder="全部状态"
            aria-label="筛选课次状态"
          >
            <el-option label="待上课" value="SCHEDULED" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已取消" value="CANCELED" />
          </el-select>
        </header>
        <el-table
          v-loading="detailLoading"
          :data="filteredSessions"
          row-key="id"
          class="entity-table"
          table-layout="auto"
        >
          <el-table-column label="日期 / 时间" min-width="155">
            <template #default="{ row }">
              <div class="primary-cell">
                <strong>{{ formatDate((row as AcademicSession).sessionDate) }}</strong>
                <span>
                  {{ formatTime((row as AcademicSession).startTime) }}-{{
                    formatTime((row as AcademicSession).endTime)
                  }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="classroom" label="教室" min-width="110" />
          <el-table-column label="状态" width="95">
            <template #default="{ row }">
              <el-tag
                effect="plain"
                size="small"
                :type="statusTagType((row as AcademicSession).status)"
              >
                {{ statusLabel((row as AcademicSession).status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="考勤" width="90">
            <template #default="{ row }">
              {{ (row as AcademicSession).recordedCount || 0 }} 条
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="115" align="right">
            <template #default="{ row }">
              <el-tooltip
                :content="rescheduleReason(row as AcademicSession) || '调整本课次'"
                placement="top"
              >
                <span>
                  <el-button
                    text
                    type="primary"
                    :disabled="Boolean(rescheduleReason(row as AcademicSession))"
                    @click="openReschedule(row as AcademicSession)"
                  >
                    调课
                  </el-button>
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state">
              <strong>
                {{
                  selectedOfferingId === null
                    ? '请先选择开班'
                    : '当前筛选下暂无课次'
                }}
              </strong>
              <span>课次需先在教学管理中生成，之后才可发起调课。</span>
            </div>
          </template>
        </el-table>
      </section>

      <section class="entity-panel adjustment-history">
        <header class="entity-panel-header">
          <div>
            <h2>调整记录</h2>
            <p>按生效时间倒序显示，保留原安排、目标安排与操作原因。</p>
          </div>
        </header>
        <div v-loading="detailLoading" class="history-list">
          <article
            v-for="adjustment in adjustments"
            :key="adjustment.id"
            class="history-item"
          >
            <span class="history-marker" aria-hidden="true" />
            <div class="history-main">
              <header>
                <strong>{{ adjustmentRoute(adjustment) }}</strong>
                <div class="history-actions">
                  <el-tag
                    effect="plain"
                    size="small"
                    :type="statusTagType(adjustment.status)"
                  >
                    {{ statusLabel(adjustment.status) }}
                  </el-tag>
                  <el-tooltip
                    :content="revertReason(adjustment) || '恢复这次调课前的课次安排'"
                    placement="top"
                  >
                    <span>
                      <el-button
                        text
                        type="warning"
                        size="small"
                        :loading="revertingAdjustmentId === adjustment.id"
                        :disabled="
                          Boolean(revertReason(adjustment)) ||
                          (revertingAdjustmentId !== null &&
                            revertingAdjustmentId !== adjustment.id)
                        "
                        @click="revertAdjustment(adjustment)"
                      >
                        撤销调课
                      </el-button>
                    </span>
                  </el-tooltip>
                </div>
              </header>
              <p>
                {{ adjustment.originalClassroom }} →
                {{ adjustment.adjustedClassroom }}
              </p>
              <blockquote>{{ adjustment.reason }}</blockquote>
              <small>
                {{ adjustment.requestedByName || '学校管理员' }} ·
                {{ formatDateTime(adjustment.appliedAt) }}
              </small>
            </div>
          </article>
          <div v-if="!detailLoading && adjustments.length === 0" class="empty-state">
            <strong>暂无调课记录</strong>
            <span>成功调课后，原安排与新安排会同时记录在这里。</span>
          </div>
        </div>
      </section>
    </section>

    <el-dialog
      v-model="rescheduleDialogVisible"
      title="调整课次"
      width="min(680px, calc(100vw - 32px))"
      destroy-on-close
      :close-on-click-modal="false"
      :before-close="beforeRescheduleDialogClose"
    >
      <el-alert
        v-if="rescheduleError"
        :title="rescheduleError"
        type="error"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <div v-if="editingSession" class="current-schedule">
        <span>当前安排</span>
        <strong>
          {{ formatDate(editingSession.sessionDate) }}
          {{ formatTime(editingSession.startTime) }}-{{
            formatTime(editingSession.endTime)
          }}
          · {{ editingSession.classroom }}
        </strong>
      </div>
      <el-form label-position="top" @submit.prevent="submitReschedule">
        <div class="form-grid">
          <el-form-item label="目标日期" required>
            <el-date-picker
              v-model="rescheduleForm.sessionDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
            />
          </el-form-item>
          <el-form-item label="目标教室" required>
            <el-select
              v-model="rescheduleForm.roomId"
              filterable
              placeholder="请选择教室"
            >
              <el-option
                v-for="room in activeRooms"
                :key="room.id"
                :label="`${room.roomName}（${room.capacity} 人）`"
                :value="room.id"
                :disabled="
                  Boolean(
                    selectedOffering &&
                    room.capacity < selectedOffering.capacity,
                  )
                "
              />
            </el-select>
          </el-form-item>
          <el-form-item label="开始时间" required>
            <el-time-picker
              v-model="rescheduleForm.startTime"
              value-format="HH:mm"
              format="HH:mm"
              placeholder="选择开始时间"
            />
          </el-form-item>
          <el-form-item label="结束时间" required>
            <el-time-picker
              v-model="rescheduleForm.endTime"
              value-format="HH:mm"
              format="HH:mm"
              placeholder="选择结束时间"
            />
          </el-form-item>
          <el-form-item label="调课原因" required class="form-item-wide">
            <el-input
              v-model="rescheduleForm.reason"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="说明调课原因，供后续审计和追溯"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="rescheduling" @click="requestRescheduleDialogClose">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="rescheduling"
          @click="submitReschedule"
        >
          确认调课
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.offering-context {
  display: flex;
  min-height: 126px;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 23px 25px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.context-label {
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.offering-context h2 {
  margin: 6px 0 0;
  font-size: 20px;
}

.offering-context p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.offering-selector {
  width: min(480px, 46vw);
}

.adjustment-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(340px, 0.85fr);
  align-items: start;
  gap: 18px;
}

.session-list .entity-panel-header .el-select {
  width: 140px;
}

.primary-cell {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.primary-cell strong {
  font-size: 13px;
}

.primary-cell span {
  color: var(--muted);
  font-size: 11px;
}

.history-list {
  position: relative;
  min-height: 260px;
  padding: 22px 24px 24px;
}

.history-list::before {
  position: absolute;
  top: 31px;
  bottom: 31px;
  left: 30px;
  width: 1px;
  background: var(--line-strong);
  content: "";
}

.history-item {
  position: relative;
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  gap: 12px;
}

.history-item + .history-item {
  margin-top: 22px;
}

.history-marker {
  z-index: 1;
  width: 12px;
  height: 12px;
  margin-top: 4px;
  border: 3px solid var(--surface);
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.history-main {
  min-width: 0;
  padding-bottom: 4px;
}

.history-main header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.history-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
}

.history-actions :deep(.el-button) {
  margin-left: 0;
}

.history-main strong {
  font-size: 12px;
  line-height: 1.55;
}

.history-main p,
.history-main small {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 10px;
}

.history-main blockquote {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-left: 2px solid #a9c5b8;
  color: #44534c;
  font-size: 11px;
  line-height: 1.55;
  background: var(--surface-soft);
}

.current-schedule {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 18px;
  padding: 13px 15px;
  border-left: 3px solid var(--accent);
  color: var(--muted);
  font-size: 11px;
  background: var(--surface-soft);
}

.current-schedule strong {
  color: var(--ink);
  font-size: 12px;
  text-align: right;
}

@media (max-width: 1120px) {
  .adjustment-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 700px) {
  .offering-context {
    align-items: stretch;
    flex-direction: column;
  }

  .offering-selector {
    width: 100%;
  }

  .adjustment-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .adjustment-metrics .metric-cell:nth-child(3) {
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .adjustment-metrics .metric-cell:nth-child(4) {
    border-top: 1px solid var(--line);
  }

  .current-schedule {
    align-items: flex-start;
    flex-direction: column;
  }

  .current-schedule strong {
    text-align: left;
  }
}
</style>
