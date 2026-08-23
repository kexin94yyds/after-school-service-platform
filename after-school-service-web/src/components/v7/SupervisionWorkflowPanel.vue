<script setup lang="ts">
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/http'
import { referenceDataApi } from '@/api/reference-data'
import type { AcademicTermOption, SchoolOption } from '@/api/reference-data'
import { supervisionApi } from '@/api/supervision'
import type {
  AlertStatus,
  AlertType,
  ScanResult,
  RegulatorNotification,
  RectificationMaterial,
  RectificationNotice,
  SupervisionScanRun,
  SupervisionAction,
  SupervisionAlert,
} from '@/api/supervision'
import PageHeader from '@/components/PageHeader.vue'
import { formatDateTime, statusLabel, statusTagType } from '@/utils/format'

interface WorkflowAction {
  targetStatus: AlertStatus
  label: string
  confirmLabel: string
  tone: 'primary' | 'success' | 'warning' | 'danger'
  prompt: string
}

const props = defineProps<{
  mode: 'regulator' | 'school'
}>()

const route = useRoute()
const router = useRouter()
const alerts = ref<SupervisionAlert[]>([])
const schools = ref<SchoolOption[]>([])
const terms = ref<AcademicTermOption[]>([])
const loading = ref(false)
const referenceLoading = ref(false)
const error = ref('')
let alertLoadVersion = 0

const filters = reactive<{
  schoolId: number | null
  type: AlertType | ''
  status: AlertStatus | ''
  dateRange: [string, string] | null
}>({
  schoolId: null,
  type: '',
  status: '',
  dateRange: null,
})

const scanVisible = ref(false)
const scanLoading = ref(false)
const scanResult = ref<ScanResult | null>(null)
const scanForm = reactive({
  schoolId: null as number | null,
  termId: null as number | null,
  lowAttendanceThreshold: 0.8,
  deadlineDays: 7,
})
const scanRunsVisible = ref(false)
const scanRunsLoading = ref(false)
const scanRunsError = ref('')
const scanRuns = ref<SupervisionScanRun[]>([])
let scanRunLoadVersion = 0

const transitionVisible = ref(false)
const transitionLoading = ref(false)
const transitionAlert = ref<SupervisionAlert | null>(null)
const transitionAction = ref<WorkflowAction | null>(null)
const transitionComment = ref('')
const transitionError = ref('')

const historyVisible = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const historyAlert = ref<SupervisionAlert | null>(null)
const history = ref<SupervisionAction[]>([])
let historyLoadVersion = 0

const rectificationVisible = ref(false)
const rectificationAlert = ref<SupervisionAlert | null>(null)
const rectificationNotice = ref<RectificationNotice | null>(null)
const rectificationMaterials = ref<RectificationMaterial[]>([])
const rectificationLoading = ref(false)
const rectificationError = ref('')
const materialInput = ref<HTMLInputElement>()
const uploadingMaterial = ref(false)
const noticeForm = reactive({ title: '', requirements: '', dueAt: '' })

const notificationVisible = ref(false)
const notifications = ref<RegulatorNotification[]>([])
const notificationLoading = ref(false)
const unreadNotificationCount = computed(
  () => notifications.value.filter((item) => !Boolean(item.isRead)).length,
)

const pageCopy = computed(() =>
  props.mode === 'regulator'
    ? {
        kicker: '监管闭环',
        title: '课后服务预警与复核',
        description:
          '自动识别超额开班、师资不足、考勤缺失和未备案开课，向负责监管账号派送并完成整改复核。',
      }
    : {
        kicker: '学校整改',
        title: '预警响应与整改提交',
        description:
          '按规定状态确认预警、记录整改过程，完成后提交监管部门复核。',
      },
)

const summary = computed(() => ({
  open: alerts.value.filter((item) => item.status === 'OPEN').length,
  rectifying: alerts.value.filter((item) =>
    ['ACKNOWLEDGED', 'RECTIFYING', 'RETURNED'].includes(item.status),
  ).length,
  waiting: alerts.value.filter((item) => item.status === 'WAITING_VERIFY')
    .length,
  overdue: alerts.value.filter(
    (item) => item.status !== 'CLOSED' && Boolean(item.overdue),
  ).length,
}))

const alertTypes: Array<{ value: AlertType; label: string }> = [
  { value: 'OVERDUE_ATTENDANCE', label: '考勤逾期' },
  { value: 'OFFERING_NO_SESSIONS', label: '开课未排课' },
  { value: 'LOW_ATTENDANCE', label: '低出勤率' },
  { value: 'OVER_CAPACITY', label: '超额开班' },
  { value: 'STAFF_SHORTAGE', label: '师资不足' },
  { value: 'MISSING_ATTENDANCE', label: '考勤缺失' },
  { value: 'UNFILED_OFFERING', label: '未备案开课' },
]

async function loadNotifications(): Promise<void> {
  if (props.mode !== 'regulator') return
  notificationLoading.value = true
  try {
    notifications.value = await supervisionApi.notifications()
  } catch (loadError) {
    ElMessage.error(getErrorMessage(loadError, '监管通知加载失败。'))
  } finally {
    notificationLoading.value = false
  }
}

async function openNotifications(): Promise<void> {
  notificationVisible.value = true
  await loadNotifications()
}

async function markNotificationRead(item: RegulatorNotification): Promise<void> {
  if (Boolean(item.isRead)) return
  await supervisionApi.markNotificationRead(item.id)
  await loadNotifications()
}

async function openRectification(alert: SupervisionAlert): Promise<void> {
  rectificationAlert.value = alert
  rectificationVisible.value = true
  rectificationLoading.value = true
  rectificationError.value = ''
  const [noticeResult, materialResult] = await Promise.allSettled([
    supervisionApi.notice(alert.id),
    supervisionApi.materials(alert.id),
  ])
  rectificationNotice.value = noticeResult.status === 'fulfilled' ? noticeResult.value : null
  rectificationMaterials.value = materialResult.status === 'fulfilled' ? materialResult.value : []
  if (rectificationNotice.value) {
    noticeForm.title = rectificationNotice.value.title
    noticeForm.requirements = rectificationNotice.value.requirements
    noticeForm.dueAt = rectificationNotice.value.dueAt.slice(0, 16)
  } else {
    noticeForm.title = `${alert.title}整改通知`
    noticeForm.requirements = ''
    const due = new Date(Date.now() + 7 * 86400000)
    noticeForm.dueAt = due.toISOString().slice(0, 16)
  }
  rectificationLoading.value = false
}

async function issueNotice(): Promise<void> {
  const alert = rectificationAlert.value
  if (!alert || !noticeForm.title.trim() || !noticeForm.requirements.trim() || !noticeForm.dueAt) {
    rectificationError.value = '请完整填写通知标题、整改要求和截止时间。'
    return
  }
  rectificationLoading.value = true
  try {
    rectificationNotice.value = await supervisionApi.issueNotice(alert.id, {
      title: noticeForm.title.trim(),
      requirements: noticeForm.requirements.trim(),
      dueAt: `${noticeForm.dueAt}:00`,
    })
    ElMessage.success('整改通知已下发')
    await loadAlerts()
    await loadNotifications()
  } catch (actionError) {
    rectificationError.value = getErrorMessage(actionError, '整改通知下发失败。')
  } finally {
    rectificationLoading.value = false
  }
}

async function uploadMaterial(event: Event): Promise<void> {
  const alert = rectificationAlert.value
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!alert || !file) return
  uploadingMaterial.value = true
  try {
    await supervisionApi.uploadMaterial(alert.id, file)
    rectificationMaterials.value = await supervisionApi.materials(alert.id)
    ElMessage.success('整改材料已上传并留痕')
  } catch (actionError) {
    rectificationError.value = getErrorMessage(actionError, '整改材料上传失败。')
  } finally {
    uploadingMaterial.value = false
  }
}

const alertStatuses: AlertStatus[] = [
  'OPEN',
  'ACKNOWLEDGED',
  'RECTIFYING',
  'WAITING_VERIFY',
  'RETURNED',
  'CLOSED',
]

function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

function positiveNumber(value: unknown): number | null {
  const parsed = Number(firstQueryValue(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function isAlertType(value: string | undefined): value is AlertType {
  return alertTypes.some((item) => item.value === value)
}

function isAlertStatus(value: string | undefined): value is AlertStatus {
  return alertStatuses.includes(value as AlertStatus)
}

function hydrateFilters(): void {
  const type = firstQueryValue(route.query.type)
  const status = firstQueryValue(route.query.status)
  const from = firstQueryValue(route.query.from)
  const to = firstQueryValue(route.query.to)
  filters.schoolId = props.mode === 'regulator' ? positiveNumber(route.query.schoolId) : null
  filters.type = isAlertType(type) ? type : ''
  filters.status = isAlertStatus(status) ? status : ''
  filters.dateRange = from && to ? [from, to] : null
}

async function saveFiltersToUrl(): Promise<void> {
  const query: Record<string, string> = {}
  if (props.mode === 'regulator' && filters.schoolId) {
    query.schoolId = String(filters.schoolId)
  }
  if (filters.type) query.type = filters.type
  if (filters.status) query.status = filters.status
  if (filters.dateRange) {
    query.from = filters.dateRange[0]
    query.to = filters.dateRange[1]
  }
  await router.replace({ query })
}

async function loadReferences(): Promise<void> {
  if (props.mode !== 'regulator') return
  referenceLoading.value = true
  try {
    const [schoolItems, termItems] = await Promise.all([
      referenceDataApi.getSchools(),
      referenceDataApi.getTerms(),
    ])
    schools.value = schoolItems
    terms.value = termItems
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '学校与学期选项加载失败。')
  } finally {
    referenceLoading.value = false
  }
}

async function loadAlerts(options: { saveQuery?: boolean } = {}): Promise<void> {
  const requestVersion = ++alertLoadVersion
  const query = {
    schoolId: props.mode === 'regulator' ? filters.schoolId : undefined,
    type: filters.type,
    status: filters.status,
    detectedFrom: filters.dateRange?.[0]
      ? `${filters.dateRange[0]}T00:00:00`
      : undefined,
    detectedTo: filters.dateRange?.[1]
      ? `${filters.dateRange[1]}T23:59:59`
      : undefined,
  }
  loading.value = true
  error.value = ''
  try {
    if (options.saveQuery) await saveFiltersToUrl()
    const rows = await supervisionApi.list(query)
    if (requestVersion === alertLoadVersion) alerts.value = rows
  } catch (loadError) {
    if (requestVersion === alertLoadVersion) {
      alerts.value = []
      error.value = getErrorMessage(loadError, '监管预警加载失败。')
    }
  } finally {
    if (requestVersion === alertLoadVersion) loading.value = false
  }
}

async function resetFilters(): Promise<void> {
  filters.schoolId = null
  filters.type = ''
  filters.status = ''
  filters.dateRange = null
  await loadAlerts({ saveQuery: true })
}

function typeLabel(value: AlertType): string {
  return alertTypes.find((item) => item.value === value)?.label ?? value
}

function workflowActions(alert: SupervisionAlert): WorkflowAction[] {
  if (props.mode === 'regulator' && alert.status === 'WAITING_VERIFY') {
    return [
      {
        targetStatus: 'CLOSED',
        label: '复核通过',
        confirmLabel: '确认关闭',
        tone: 'success',
        prompt: '请记录复核结论。关闭后会保留完整处理历史。',
      },
      {
        targetStatus: 'RETURNED',
        label: '退回整改',
        confirmLabel: '确认退回',
        tone: 'warning',
        prompt: '请说明未通过复核的原因与补充整改要求。',
      },
    ]
  }
  if (props.mode === 'school') {
    const actions: Partial<Record<AlertStatus, WorkflowAction>> = {
      OPEN: {
        targetStatus: 'ACKNOWLEDGED',
        label: '确认接收',
        confirmLabel: '确认接收',
        tone: 'primary',
        prompt: '请说明负责人或初步处理安排。',
      },
      ACKNOWLEDGED: {
        targetStatus: 'RECTIFYING',
        label: '开始整改',
        confirmLabel: '确认开始',
        tone: 'primary',
        prompt: '请记录整改措施和计划。',
      },
      RETURNED: {
        targetStatus: 'RECTIFYING',
        label: '继续整改',
        confirmLabel: '确认继续',
        tone: 'primary',
        prompt: '请对照监管退回意见说明补充措施。',
      },
      RECTIFYING: {
        targetStatus: 'WAITING_VERIFY',
        label: '提交复核',
        confirmLabel: '确认提交',
        tone: 'success',
        prompt: '请概括已完成的整改内容，提交后等待监管复核。',
      },
    }
    const action = actions[alert.status]
    return action ? [action] : []
  }
  return []
}

function openTransition(alert: SupervisionAlert, action: WorkflowAction): void {
  transitionAlert.value = alert
  transitionAction.value = action
  transitionComment.value = ''
  transitionError.value = ''
  transitionVisible.value = true
}

async function submitTransition(): Promise<void> {
  const alert = transitionAlert.value
  const action = transitionAction.value
  const comment = transitionComment.value.trim()
  if (!alert || !action) return
  if (!comment) {
    transitionError.value = '请填写处理说明。'
    return
  }
  if (comment.length > 1000) {
    transitionError.value = '处理说明不能超过 1000 个字。'
    return
  }
  transitionLoading.value = true
  transitionError.value = ''
  try {
    await supervisionApi.transition(alert.id, {
      targetStatus: action.targetStatus,
      comment,
    })
    transitionVisible.value = false
    ElMessage.success(`预警已更新为“${statusLabel(action.targetStatus)}”`)
    await loadAlerts()
    if (historyVisible.value && historyAlert.value?.id === alert.id) {
      await openHistory(alert)
    }
  } catch (actionError) {
    transitionError.value = getErrorMessage(actionError, '预警状态更新失败。')
  } finally {
    transitionLoading.value = false
  }
}

async function openHistory(alert: SupervisionAlert): Promise<void> {
  const requestVersion = ++historyLoadVersion
  historyAlert.value = alert
  historyVisible.value = true
  historyLoading.value = true
  historyError.value = ''
  history.value = []
  try {
    const rows = await supervisionApi.history(alert.id)
    if (
      requestVersion === historyLoadVersion &&
      historyVisible.value &&
      historyAlert.value?.id === alert.id
    ) {
      history.value = rows
    }
  } catch (loadError) {
    if (requestVersion === historyLoadVersion) {
      historyError.value = getErrorMessage(loadError, '预警处理历史加载失败。')
    }
  } finally {
    if (requestVersion === historyLoadVersion) historyLoading.value = false
  }
}

function invalidateHistoryLoad(): void {
  ++historyLoadVersion
  historyLoading.value = false
}

function openScan(): void {
  scanResult.value = null
  scanVisible.value = true
}

async function runScan(): Promise<void> {
  if (
    scanForm.lowAttendanceThreshold < 0.01 ||
    scanForm.lowAttendanceThreshold > 1
  ) {
    ElMessage.warning('低出勤率阈值必须在 0.01 到 1 之间。')
    return
  }
  if (scanForm.deadlineDays < 1 || scanForm.deadlineDays > 30) {
    ElMessage.warning('整改期限必须在 1 到 30 天之间。')
    return
  }
  scanLoading.value = true
  try {
    scanResult.value = await supervisionApi.scan({ ...scanForm })
    ElMessage.success(
      scanResult.value.createdCount > 0
        ? `已新增 ${scanResult.value.createdCount} 条预警`
        : '扫描完成，未发现新预警',
    )
    await loadAlerts()
    await loadNotifications()
    if (scanRunsVisible.value) await loadScanRuns()
  } catch (scanError) {
    ElMessage.error(getErrorMessage(scanError, '监管扫描执行失败。'))
  } finally {
    scanLoading.value = false
  }
}

async function loadScanRuns(): Promise<void> {
  const requestVersion = ++scanRunLoadVersion
  scanRunsLoading.value = true
  scanRunsError.value = ''
  try {
    const rows = await supervisionApi.scanRuns(100)
    if (requestVersion === scanRunLoadVersion) scanRuns.value = rows
  } catch (loadError) {
    if (requestVersion === scanRunLoadVersion) {
      scanRuns.value = []
      scanRunsError.value = getErrorMessage(
        loadError,
        '扫描运行记录加载失败。',
      )
    }
  } finally {
    if (requestVersion === scanRunLoadVersion) scanRunsLoading.value = false
  }
}

async function openScanRuns(): Promise<void> {
  scanRunsVisible.value = true
  await loadScanRuns()
}

function scanSourceLabel(source: SupervisionScanRun['triggerSource']): string {
  return source === 'SCHEDULED' ? '定时扫描' : '手工扫描'
}

function scanOperatorLabel(run: SupervisionScanRun): string {
  if (run.triggerSource === 'SCHEDULED') return '系统任务'
  return run.operatorName || '监管账号'
}

function actorRoleLabel(role: SupervisionAction['actorRole']): string {
  const labels: Record<SupervisionAction['actorRole'], string> = {
    REGULATOR: '监管人员',
    SCHOOL_ADMIN: '教务管理员',
    SYSTEM: '系统任务',
  }
  return labels[role]
}

function actionLabel(value: string): string {
  const labels: Record<string, string> = {
    CREATE: '生成预警',
    ACKNOWLEDGE: '学校确认',
    START_RECTIFICATION: '开始整改',
    RESUME_RECTIFICATION: '继续整改',
    SUBMIT_VERIFICATION: '提交复核',
    ISSUE_NOTICE: '下发整改通知',
    SUBMIT_MATERIAL: '提交整改材料',
    VERIFY_CLOSE: '复核关闭',
    RETURN_FOR_RECTIFICATION: '退回整改',
  }
  return labels[value] ?? value
}

onMounted(async () => {
  hydrateFilters()
  await Promise.all([loadReferences(), loadAlerts(), loadNotifications()])
})
</script>

<template>
  <section class="page-stack supervision-page">
    <PageHeader
      :kicker="pageCopy.kicker"
      :title="pageCopy.title"
      :description="pageCopy.description"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadAlerts()">刷新</el-button>
        <el-button v-if="mode === 'regulator'" @click="openScanRuns">
          扫描记录
        </el-button>
        <el-button v-if="mode === 'regulator'" @click="openNotifications">
          监管通知（{{ unreadNotificationCount }}）
        </el-button>
        <el-button v-if="mode === 'regulator'" type="primary" @click="openScan">
          执行扫描
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

    <section class="workflow-metrics" aria-label="预警处理概况">
      <article>
        <span>待响应</span>
        <strong>{{ summary.open }}</strong>
      </article>
      <article>
        <span>处理中</span>
        <strong>{{ summary.rectifying }}</strong>
      </article>
      <article>
        <span>待复核</span>
        <strong>{{ summary.waiting }}</strong>
      </article>
      <article :class="{ 'metric-risk': summary.overdue > 0 }">
        <span>已逾期</span>
        <strong>{{ summary.overdue }}</strong>
      </article>
    </section>

    <section class="workflow-panel">
      <el-form class="workflow-filters" label-position="top" @submit.prevent>
        <el-form-item v-if="mode === 'regulator'" label="学校范围">
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
        <el-form-item label="预警类型">
          <el-select v-model="filters.type" clearable placeholder="全部类型">
            <el-option
              v-for="item in alertTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="处理状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态">
            <el-option
              v-for="item in alertStatuses"
              :key="item"
              :label="statusLabel(item)"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发现日期">
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
          <el-button type="primary" :loading="loading" @click="loadAlerts({ saveQuery: true })">
            查询
          </el-button>
        </div>
      </el-form>

      <el-skeleton v-if="loading && alerts.length === 0" :rows="6" animated />
      <el-table
        v-else
        v-loading="loading"
        :data="alerts"
        row-key="id"
        table-layout="auto"
      >
        <el-table-column v-if="mode === 'regulator'" prop="schoolName" label="学校" min-width="170" />
        <el-table-column label="预警与课程" min-width="260">
          <template #default="{ row }">
            <div class="alert-title-cell">
              <strong>{{ row.title }}</strong>
              <span>{{ row.courseName }} / {{ row.offeringCode }}</span>
              <small>{{ row.description }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="130">
          <template #default="{ row }">{{ typeLabel(row.alertType) }}</template>
        </el-table-column>
        <el-table-column label="等级" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.severity)" effect="plain">
              {{ statusLabel(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="整改时限" min-width="165">
          <template #default="{ row }">
            <div :class="['deadline-cell', { overdue: row.status !== 'CLOSED' && Boolean(row.overdue) }]">
              {{ formatDateTime(row.rectificationDeadline) }}
              <small v-if="row.status !== 'CLOSED' && Boolean(row.overdue)">已逾期</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="190" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button text @click="openHistory(row)">详情</el-button>
              <el-button text type="primary" @click="openRectification(row)">
                {{ mode === 'regulator' ? '整改通知' : '整改材料' }}
              </el-button>
              <el-button
                v-for="action in workflowActions(row)"
                :key="action.targetStatus"
                text
                :type="action.tone"
                @click="openTransition(row, action)"
              >
                {{ action.label }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>暂无匹配预警</strong>
            <span v-if="mode === 'regulator'">可调整筛选条件，或执行一次新的监管扫描。</span>
            <span v-else>本校当前没有需要处理的监管预警。</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-drawer v-model="notificationVisible" title="监管通知" size="min(680px, 96vw)">
      <div class="scan-run-toolbar">
        <p>只显示当前监管账号负责学校产生的预警和复核待办。</p>
        <el-button :loading="notificationLoading" @click="loadNotifications">刷新</el-button>
      </div>
      <el-table v-loading="notificationLoading" :data="notifications" table-layout="auto">
        <el-table-column prop="schoolName" label="学校" min-width="150" />
        <el-table-column label="通知" min-width="240">
          <template #default="{ row }">
            <strong>{{ row.title }}</strong><br />
            <small>{{ row.content }}</small>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="!Boolean(row.isRead)" text @click="markNotificationRead(row)">已读</el-button>
            <span v-else>已读</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog
      v-model="rectificationVisible"
      :title="mode === 'regulator' ? '整改通知与材料' : '整改材料提交'"
      width="min(760px, 94vw)"
    >
      <el-alert
        v-if="rectificationError"
        :title="rectificationError"
        type="error"
        :closable="false"
        show-icon
      />
      <el-form v-if="mode === 'regulator'" label-position="top">
        <el-form-item label="通知标题" required>
          <el-input v-model="noticeForm.title" maxlength="128" />
        </el-form-item>
        <el-form-item label="整改要求" required>
          <el-input v-model="noticeForm.requirements" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="整改截止时间" required>
          <el-date-picker v-model="noticeForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" />
        </el-form-item>
        <el-button type="primary" :loading="rectificationLoading" @click="issueNotice">
          下发/更新整改通知
        </el-button>
      </el-form>
      <el-alert
        v-else-if="rectificationNotice"
        :title="rectificationNotice.title"
        :description="`${rectificationNotice.requirements}；截止 ${formatDateTime(rectificationNotice.dueAt)}`"
        type="info"
        show-icon
        :closable="false"
      />
      <div class="scan-run-toolbar" style="margin-top: 20px">
        <p>PDF/JPG/PNG，单文件不超过 10 MB；每次上传均保留版本、哈希、上传人和时间。</p>
        <el-button
          v-if="mode === 'school'"
          type="primary"
          :loading="uploadingMaterial"
          :disabled="!rectificationNotice"
          @click="materialInput?.click()"
        >
          上传整改材料
        </el-button>
        <input
          ref="materialInput"
          hidden
          type="file"
          accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
          @change="uploadMaterial"
        />
      </div>
      <el-table :data="rectificationMaterials" table-layout="auto">
        <el-table-column prop="originalName" label="文件" min-width="200" />
        <el-table-column prop="uploadedByName" label="上传人" min-width="110" />
        <el-table-column label="上传时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button text @click="supervisionApi.downloadMaterial(row)">下载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="scanVisible" title="执行监管扫描" width="min(620px, 92vw)">
      <el-alert
        title="扫描只会新增当前没有活跃记录的预警，重复问题不会重复派单。"
        type="info"
        show-icon
        :closable="false"
      />
      <el-form class="dialog-form" label-position="top" @submit.prevent>
        <div class="dialog-form-grid">
          <el-form-item label="学校范围">
            <el-select v-model="scanForm.schoolId" clearable placeholder="全部学校">
              <el-option
                v-for="school in schools"
                :key="school.id"
                :label="school.schoolName"
                :value="school.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="学期范围">
            <el-select v-model="scanForm.termId" clearable placeholder="全部学期">
              <el-option
                v-for="term in terms"
                :key="term.id"
                :label="term.termName"
                :value="term.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="低出勤率阈值">
            <el-input-number
              v-model="scanForm.lowAttendanceThreshold"
              :min="0.01"
              :max="1"
              :step="0.05"
              :precision="2"
            />
            <span class="field-help">0.80 表示出勤率低于 80% 时生成预警。</span>
          </el-form-item>
          <el-form-item label="整改期限">
            <el-input-number v-model="scanForm.deadlineDays" :min="1" :max="30" />
            <span class="field-help">单位为天，将从扫描时间开始计算。</span>
          </el-form-item>
        </div>
      </el-form>
      <el-alert
        v-if="scanResult"
        class="scan-result"
        :title="`扫描 ${scanResult.candidateCount} 个候选问题，新增 ${scanResult.createdCount} 条，去重 ${scanResult.deduplicatedCount} 条。`"
        :type="scanResult.createdCount > 0 ? 'success' : 'info'"
        show-icon
        :closable="false"
      />
      <template #footer>
        <el-button @click="scanVisible = false">关闭</el-button>
        <el-button type="primary" :loading="scanLoading" @click="runScan">
          确认扫描
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="scanRunsVisible"
      title="监管扫描运行记录"
      size="min(760px, 96vw)"
    >
      <div class="scan-run-toolbar">
        <p>保留手工与定时扫描的成功、失败及重入拒绝结果。</p>
        <el-button :loading="scanRunsLoading" @click="loadScanRuns">
          刷新记录
        </el-button>
      </div>
      <el-alert
        v-if="scanRunsError"
        :title="scanRunsError"
        type="error"
        show-icon
        :closable="false"
      />
      <el-table
        v-loading="scanRunsLoading"
        :data="scanRuns"
        row-key="id"
        table-layout="auto"
      >
        <el-table-column label="启动时间" min-width="165">
          <template #default="{ row }">
            {{ formatDateTime(row.startedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="来源 / 操作人" min-width="150">
          <template #default="{ row }">
            <div class="scan-run-source">
              <strong>{{ scanSourceLabel(row.triggerSource) }}</strong>
              <span>{{ scanOperatorLabel(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="候选 / 新增" min-width="105">
          <template #default="{ row }">
            {{ row.candidateCount ?? '-' }} / {{ row.createdCount ?? '-' }}
          </template>
        </el-table-column>
        <el-table-column label="结束或失败信息" min-width="210">
          <template #default="{ row }">
            <div class="scan-run-finish">
              <span>{{ formatDateTime(row.finishedAt) }}</span>
              <small v-if="row.failureSummary">{{ row.failureSummary }}</small>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state large">
            <strong>暂无扫描运行记录</strong>
            <span>执行手工扫描或等待定时任务后，运行结果会出现在这里。</span>
          </div>
        </template>
      </el-table>
    </el-drawer>

    <el-dialog
      v-model="transitionVisible"
      :title="transitionAction?.label || '更新预警'"
      width="min(560px, 92vw)"
    >
      <div v-if="transitionAlert && transitionAction" class="transition-context">
        <strong>{{ transitionAlert.title }}</strong>
        <span>
          {{ statusLabel(transitionAlert.status) }}
          <b>→</b>
          {{ statusLabel(transitionAction.targetStatus) }}
        </span>
        <p>{{ transitionAction.prompt }}</p>
      </div>
      <el-alert
        v-if="transitionError"
        class="dialog-alert"
        :title="transitionError"
        type="error"
        show-icon
        :closable="false"
      />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="处理说明" required>
          <el-input
            v-model="transitionComment"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="记录处理依据、整改措施或复核结论"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="transitionLoading" @click="transitionVisible = false">取消</el-button>
        <el-button
          :type="transitionAction?.tone || 'primary'"
          :loading="transitionLoading"
          @click="submitTransition"
        >
          {{ transitionAction?.confirmLabel || '确认提交' }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="historyVisible"
      :title="historyAlert ? `预警 #${historyAlert.id} 处理记录` : '处理记录'"
      size="min(560px, 94vw)"
      @close="invalidateHistoryLoad"
    >
      <section v-if="historyAlert" class="history-summary">
        <span>{{ historyAlert.schoolName }}</span>
        <strong>{{ historyAlert.title }}</strong>
        <p>{{ historyAlert.courseName }} / {{ typeLabel(historyAlert.alertType) }}</p>
        <el-tag :type="statusTagType(historyAlert.status)" effect="plain">
          {{ statusLabel(historyAlert.status) }}
        </el-tag>
      </section>
      <el-alert
        v-if="historyError"
        :title="historyError"
        type="error"
        show-icon
        :closable="false"
      />
      <el-skeleton v-if="historyLoading" :rows="5" animated />
      <el-timeline v-else-if="history.length" class="history-timeline">
        <el-timeline-item
          v-for="item in history"
          :key="item.id"
          :timestamp="formatDateTime(item.actedAt)"
          placement="top"
        >
          <div class="history-item">
            <strong>{{ actionLabel(item.actionType) }}</strong>
            <span>{{ item.actorName }} / {{ actorRoleLabel(item.actorRole) }}</span>
            <p>{{ item.comment }}</p>
            <small>
              {{ item.fromStatus ? statusLabel(item.fromStatus) : '新建' }}
              → {{ statusLabel(item.toStatus) }}
            </small>
          </div>
        </el-timeline-item>
      </el-timeline>
      <div v-else-if="!historyError" class="empty-state large">
        <strong>暂无处理记录</strong>
        <span>预警流转后，每一次处理都会记录在这里。</span>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.supervision-page {
  --workflow-risk: #a13d37;
}

.workflow-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.workflow-metrics article {
  display: flex;
  min-height: 112px;
  flex-direction: column;
  justify-content: space-between;
  padding: 20px;
}

.workflow-metrics article + article {
  border-left: 1px solid var(--line);
}

.workflow-metrics span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 650;
}

.workflow-metrics strong {
  font-size: 30px;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.04em;
}

.workflow-metrics .metric-risk strong {
  color: var(--workflow-risk);
}

.workflow-panel {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.workflow-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr)) auto;
  align-items: end;
  gap: 12px;
  padding: 18px 20px 2px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-soft);
}

.workflow-filters :deep(.el-select),
.workflow-filters :deep(.el-date-editor),
.dialog-form :deep(.el-select),
.dialog-form :deep(.el-input-number) {
  width: 100%;
}

.filter-actions,
.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.filter-actions {
  padding-bottom: 18px;
}

.filter-actions :deep(.el-button + .el-button),
.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.workflow-panel > :deep(.el-skeleton) {
  padding: 24px;
}

.alert-title-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  line-height: 1.5;
}

.alert-title-cell strong {
  color: var(--ink);
  font-size: 13px;
}

.alert-title-cell span {
  color: var(--accent);
  font-size: 11px;
  font-weight: 650;
}

.alert-title-cell small {
  display: -webkit-box;
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.deadline-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-variant-numeric: tabular-nums;
}

.deadline-cell small {
  color: var(--workflow-risk);
  font-weight: 700;
}

.deadline-cell.overdue {
  color: var(--workflow-risk);
}

.dialog-form {
  margin-top: 20px;
}

.dialog-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.scan-result {
  margin-top: 4px;
}

.scan-run-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.scan-run-toolbar p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.scan-run-source,
.scan-run-finish {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.scan-run-source span,
.scan-run-finish span,
.scan-run-finish small {
  color: var(--muted);
  font-size: 11px;
}

.scan-run-finish small {
  color: var(--workflow-risk);
  white-space: normal;
}

.transition-context {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.transition-context > span {
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
}

.transition-context b {
  padding: 0 7px;
}

.transition-context p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.65;
}

.history-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 7px 14px;
  margin-bottom: 24px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.history-summary span,
.history-summary p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--muted);
  font-size: 11px;
}

.history-timeline {
  padding: 8px 8px 0 4px;
}

.history-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 4px 0 10px;
}

.history-item span,
.history-item small {
  color: var(--muted);
  font-size: 11px;
}

.history-item p {
  margin: 2px 0;
  color: var(--ink);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
}

@media (max-width: 1180px) {
  .workflow-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-actions {
    grid-column: 1 / -1;
  }
}

@media (max-width: 700px) {
  .workflow-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workflow-metrics article:nth-child(3) {
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .workflow-metrics article:nth-child(4) {
    border-top: 1px solid var(--line);
  }

  .workflow-filters,
  .dialog-form-grid {
    grid-template-columns: 1fr;
  }

  .filter-actions {
    grid-column: auto;
  }

  .filter-actions :deep(.el-button) {
    flex: 1;
  }
}
</style>
