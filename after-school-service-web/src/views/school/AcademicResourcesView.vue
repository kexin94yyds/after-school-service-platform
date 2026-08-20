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
  type AcademicTerm,
  type CalendarDayType,
  type CalendarEvent,
  type CalendarEventInput,
  type RoomInput,
  type SchoolRoom,
  type ServicePlan,
  type ServicePlanInput,
  type ServicePlanItem,
  type ServicePlanItemInput,
} from '@/api/academic'
import { ApiClientError, getErrorMessage } from '@/api/http'
import EntityCrudPanel from '@/components/EntityCrudPanel.vue'
import PageHeader from '@/components/PageHeader.vue'
import type {
  EntityColumn,
  EntityField,
  FormValues,
} from '@/components/entity-crud'
import { useLongFormGuard } from '@/composables/useLongFormGuard'
import { useSessionStore } from '@/stores/session'
import {
  formNullableString,
  formNumber,
  formString,
} from '@/utils/forms'
import {
  formatDate,
  formatDateTime,
  statusLabel,
  statusTagType,
} from '@/utils/format'

type ResourceTab = 'plans' | 'rooms' | 'calendar'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const activeTab = ref<ResourceTab>(queryTab(route.query.tab))
const selectedTermId = ref<number | null>(queryNumber(route.query.termId))
const terms = ref<AcademicTerm[]>([])
const plans = ref<ServicePlan[]>([])
const rooms = ref<SchoolRoom[]>([])
const calendarEvents = ref<CalendarEvent[]>([])
const termsLoading = ref(false)
const plansLoading = ref(false)
const roomsLoading = ref(false)
const calendarLoading = ref(false)
const termsError = ref('')
const plansError = ref('')
const roomsError = ref('')
const calendarError = ref('')
const mounted = ref(false)
const calendarLoadVersion = ref(0)
const plansLoadVersion = ref(0)
const planKeyword = ref('')
const submittingPlanId = ref<number | null>(null)
const itemPlan = ref<ServicePlan | null>(null)
const planItems = ref<ServicePlanItem[]>([])
const planItemsLoading = ref(false)
const planItemsError = ref('')

const planDialogVisible = ref(false)
const editingPlanId = ref<number | null>(null)
const planSaving = ref(false)
const planDialogError = ref('')
const planForm = reactive<{
  termId: number | null
  planCode: string
  planName: string
  description: string
}>({
  termId: null,
  planCode: '',
  planName: '',
  description: '',
})
const {
  beforeClose: beforePlanDialogClose,
  captureBaseline: capturePlanBaseline,
  requestClose: requestPlanDialogClose,
} = useLongFormGuard({
  visible: planDialogVisible,
  saving: planSaving,
  snapshot: () => ({ ...planForm }),
})

const selectedTerm = computed(() =>
  terms.value.find((term) => term.id === selectedTermId.value),
)

const writablePlanTerms = computed(() =>
  terms.value.filter((term) =>
    ['DRAFT', 'ACTIVE'].includes(term.status),
  ),
)

const writableCalendarTerms = computed(() =>
  terms.value.filter((term) => term.status !== 'ARCHIVED'),
)

const filteredPlans = computed(() => {
  const normalized = planKeyword.value.trim().toLocaleLowerCase()
  if (!normalized) return plans.value
  return plans.value.filter((plan) =>
    [
      plan.planCode,
      plan.planName,
      plan.description,
      plan.returnReason,
    ].some((value) => value?.toLocaleLowerCase().includes(normalized)),
  )
})

const planItemColumns: EntityColumn[] = [
  { key: 'category', label: '课程类型', minWidth: 140 },
  { key: 'plannedCourseCount', label: '计划课程', minWidth: 100 },
  { key: 'plannedClassCount', label: '计划开班', minWidth: 100 },
  { key: 'capacityPerClass', label: '单班规模', minWidth: 100 },
  { key: 'plannedTeacherCount', label: '师资人数', minWidth: 100 },
  { key: 'notes', label: '配置说明', minWidth: 220 },
]

const planItemFields: EntityField[] = [
  { key: 'category', label: '课程类型', kind: 'text', required: true },
  { key: 'plannedCourseCount', label: '计划课程数', kind: 'number', required: true, min: 1 },
  { key: 'plannedClassCount', label: '计划开班数', kind: 'number', required: true, min: 1 },
  { key: 'capacityPerClass', label: '单班规模', kind: 'number', required: true, min: 1 },
  { key: 'plannedTeacherCount', label: '计划师资人数', kind: 'number', required: true, min: 1 },
  { key: 'notes', label: '师资与安全配置说明', kind: 'textarea' },
]

async function openPlanItems(plan: ServicePlan): Promise<void> {
  itemPlan.value = plan
  await loadPlanItems()
}

async function loadPlanItems(): Promise<void> {
  if (!itemPlan.value) return
  planItemsLoading.value = true
  planItemsError.value = ''
  try {
    planItems.value = await academicApi.getServicePlanItems(itemPlan.value.id)
  } catch (loadError) {
    planItemsError.value = getErrorMessage(loadError, '计划明细加载失败。')
  } finally {
    planItemsLoading.value = false
  }
}

async function savePlanItem(values: FormValues, id: number | null): Promise<void> {
  if (!itemPlan.value) throw new ApiClientError('请先选择服务计划。')
  const payload: ServicePlanItemInput = {
    category: formString(values, 'category'),
    plannedCourseCount: formNumber(values, 'plannedCourseCount'),
    plannedClassCount: formNumber(values, 'plannedClassCount'),
    capacityPerClass: formNumber(values, 'capacityPerClass'),
    plannedTeacherCount: formNumber(values, 'plannedTeacherCount'),
    notes: formNullableString(values, 'notes'),
  }
  if (id === null) await academicApi.createServicePlanItem(itemPlan.value.id, payload)
  else await academicApi.updateServicePlanItem(itemPlan.value.id, id, payload)
  await loadPlanItems()
}

const roomColumns: EntityColumn[] = [
  { key: 'roomCode', label: '教室编码', minWidth: 120 },
  { key: 'roomName', label: '教室名称', minWidth: 150 },
  { key: 'location', label: '位置', minWidth: 180 },
  {
    key: 'capacity',
    label: '容量',
    minWidth: 90,
    formatter: (value) => `${String(value ?? 0)} 人`,
  },
  { key: 'status', label: '状态', minWidth: 90 },
]

const roomFields: EntityField[] = [
  {
    key: 'roomCode',
    label: '教室编码',
    kind: 'text',
    required: true,
    placeholder: '例如 ART-201',
  },
  {
    key: 'roomName',
    label: '教室名称',
    kind: 'text',
    required: true,
    placeholder: '例如 美术教室',
  },
  {
    key: 'location',
    label: '所在位置',
    kind: 'text',
    placeholder: '例如 综合楼 2 层东侧',
  },
  {
    key: 'capacity',
    label: '可容纳人数',
    kind: 'number',
    required: true,
    min: 1,
    max: 500,
    defaultValue: 30,
  },
  {
    key: 'status',
    label: '资源状态',
    kind: 'select',
    required: true,
    defaultValue: 'ACTIVE',
    options: [
      { label: '启用', value: 'ACTIVE' },
      { label: '停用', value: 'INACTIVE' },
    ],
    help: '仍有关联未结束开班的教室不能停用。',
  },
]

const calendarColumns: EntityColumn[] = [
  {
    key: 'eventDate',
    label: '日期',
    minWidth: 120,
    formatter: (value) =>
      formatDate(typeof value === 'string' ? value : null),
  },
  { key: 'eventName', label: '事项', minWidth: 180 },
  {
    key: 'dayType',
    label: '日期属性',
    minWidth: 110,
    tag: true,
    formatter: (value) => calendarDayLabel(value as CalendarDayType),
  },
  { key: 'description', label: '说明', minWidth: 240 },
]

const calendarFields = computed<EntityField[]>(() => [
  {
    key: 'termId',
    label: '所属学期',
    kind: 'select',
    required: true,
    defaultValue:
      writableCalendarTerms.value.find(
        (term) => term.id === selectedTermId.value,
      )?.id ??
      writableCalendarTerms.value[0]?.id ??
      null,
    options: writableCalendarTerms.value.map((term) => ({
      label: `${term.termName}（${statusLabel(term.status)}）`,
      value: term.id,
    })),
  },
  {
    key: 'eventDate',
    label: '日期',
    kind: 'date',
    required: true,
  },
  {
    key: 'dayType',
    label: '日期属性',
    kind: 'select',
    required: true,
    defaultValue: 'TEACHING_DAY',
    options: [
      { label: '正常教学日', value: 'TEACHING_DAY' },
      { label: '补课日', value: 'MAKEUP_DAY' },
      { label: '放假日', value: 'HOLIDAY' },
      { label: '停课日', value: 'SUSPENDED' },
    ],
  },
  {
    key: 'eventName',
    label: '事项名称',
    kind: 'text',
    required: true,
    placeholder: '例如 国庆节放假',
  },
  {
    key: 'description',
    label: '详细说明',
    kind: 'textarea',
    placeholder: '填写涉及范围、补课安排或其他注意事项',
  },
])

function queryNumber(value: unknown): number | null {
  const source = Array.isArray(value) ? value[0] : value
  if (typeof source !== 'string' || !source) return null
  const parsed = Number(source)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function queryTab(value: unknown): ResourceTab {
  const source = Array.isArray(value) ? value[0] : value
  return source === 'rooms' || source === 'calendar' ? source : 'plans'
}

function schoolId(): number {
  const id = session.user?.schoolId
  if (!id) {
    throw new ApiClientError('当前学校管理员账号未绑定学校。', {
      code: 'SCHOOL_CONTEXT_REQUIRED',
    })
  }
  return id
}

function calendarDayLabel(dayType: CalendarDayType): string {
  const labels: Record<CalendarDayType, string> = {
    TEACHING_DAY: '正常教学日',
    MAKEUP_DAY: '补课日',
    HOLIDAY: '放假日',
    SUSPENDED: '停课日',
  }
  return labels[dayType] ?? String(dayType)
}

function syncQuery(): void {
  const termId = selectedTermId.value?.toString()
  const currentTab = Array.isArray(route.query.tab)
    ? route.query.tab[0]
    : route.query.tab
  const currentTerm = Array.isArray(route.query.termId)
    ? route.query.termId[0]
    : route.query.termId
  if (
    currentTab === activeTab.value &&
    (currentTerm || undefined) === termId
  ) {
    return
  }
  const query: LocationQueryRaw = {
    ...route.query,
    tab: activeTab.value,
  }
  if (termId) query.termId = termId
  else delete query.termId
  void router.replace({ query })
}

async function loadTerms(): Promise<void> {
  termsLoading.value = true
  termsError.value = ''
  try {
    terms.value = await academicApi.getTerms()
    if (
      selectedTermId.value === null ||
      !terms.value.some((term) => term.id === selectedTermId.value)
    ) {
      selectedTermId.value =
        terms.value.find((term) => term.status === 'ACTIVE')?.id ??
        terms.value.find((term) => term.status === 'DRAFT')?.id ??
        terms.value[0]?.id ??
        null
    }
  } catch (error) {
    termsError.value = getErrorMessage(error, '学期信息加载失败。')
  } finally {
    termsLoading.value = false
  }
}

async function loadPlans(): Promise<void> {
  const version = ++plansLoadVersion.value
  plansLoading.value = true
  plansError.value = ''
  try {
    const rows = await academicApi.getServicePlans({
      termId: selectedTermId.value ?? undefined,
    })
    if (version === plansLoadVersion.value) plans.value = rows
  } catch (error) {
    if (version === plansLoadVersion.value) {
      plansError.value = getErrorMessage(error, '服务计划加载失败。')
      plans.value = []
    }
  } finally {
    if (version === plansLoadVersion.value) plansLoading.value = false
  }
}

async function loadRooms(): Promise<void> {
  roomsLoading.value = true
  roomsError.value = ''
  try {
    rooms.value = await academicApi.getRooms()
  } catch (error) {
    roomsError.value = getErrorMessage(error, '教室资源加载失败。')
  } finally {
    roomsLoading.value = false
  }
}

async function loadCalendar(): Promise<void> {
  const version = ++calendarLoadVersion.value
  calendarLoading.value = true
  calendarError.value = ''
  try {
    const rows = await academicApi.getCalendarEvents({
      termId: selectedTermId.value ?? undefined,
    })
    if (version === calendarLoadVersion.value) calendarEvents.value = rows
  } catch (error) {
    if (version === calendarLoadVersion.value) {
      calendarError.value = getErrorMessage(error, '校历加载失败。')
      calendarEvents.value = []
    }
  } finally {
    if (version === calendarLoadVersion.value) {
      calendarLoading.value = false
    }
  }
}

async function reloadAll(): Promise<void> {
  await loadTerms()
  await Promise.all([loadPlans(), loadRooms(), loadCalendar()])
}

function openCreatePlan(): void {
  const defaultTerm =
    writablePlanTerms.value.find(
      (term) => term.id === selectedTermId.value,
    ) ?? writablePlanTerms.value[0]
  editingPlanId.value = null
  Object.assign(planForm, {
    termId: defaultTerm?.id ?? null,
    planCode: '',
    planName: '',
    description: '',
  })
  planDialogError.value = ''
  capturePlanBaseline()
  planDialogVisible.value = true
}

function openEditPlan(plan: ServicePlan): void {
  if (!['DRAFT', 'RETURNED'].includes(plan.status)) return
  editingPlanId.value = plan.id
  Object.assign(planForm, {
    termId: plan.termId,
    planCode: plan.planCode,
    planName: plan.planName,
    description: plan.description ?? '',
  })
  planDialogError.value = ''
  capturePlanBaseline()
  planDialogVisible.value = true
}

async function savePlan(): Promise<void> {
  if (
    planForm.termId === null ||
    !planForm.planCode.trim() ||
    !planForm.planName.trim()
  ) {
    planDialogError.value = '请选择学期，并填写计划编码和名称。'
    return
  }
  const selected = terms.value.find((term) => term.id === planForm.termId)
  if (!selected || !['DRAFT', 'ACTIVE'].includes(selected.status)) {
    planDialogError.value = '只能在草稿或启用中的学期维护服务计划。'
    return
  }

  planSaving.value = true
  planDialogError.value = ''
  try {
    const payload: ServicePlanInput = {
      schoolId: schoolId(),
      termId: planForm.termId,
      planCode: planForm.planCode.trim(),
      planName: planForm.planName.trim(),
      description: planForm.description.trim() || null,
    }
    if (editingPlanId.value === null) {
      await academicApi.createServicePlan(payload)
    } else {
      await academicApi.updateServicePlan(editingPlanId.value, payload)
    }
    selectedTermId.value = planForm.termId
    capturePlanBaseline()
    planDialogVisible.value = false
    ElMessage.success(editingPlanId.value === null ? '计划已创建' : '计划已保存')
    await loadPlans()
  } catch (error) {
    planDialogError.value = getErrorMessage(error, '服务计划保存失败。')
  } finally {
    planSaving.value = false
  }
}

async function submitPlan(plan: ServicePlan): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `提交“${plan.planName}”后，计划内容将冻结并进入监管备案流程。确认材料已经完整？`,
      '确认提交备案',
      {
        confirmButtonText: '提交备案',
        cancelButtonText: '继续编辑',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  submittingPlanId.value = plan.id
  try {
    await academicApi.transitionServicePlan(plan.id, 'SUBMITTED')
    ElMessage.success('服务计划已提交备案')
    await loadPlans()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '服务计划提交失败。'))
  } finally {
    submittingPlanId.value = null
  }
}

async function confirmMaterialChange(
  title: string,
  message: string,
): Promise<void> {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '确认继续',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    throw new ApiClientError('操作已取消，原有数据未发生变化。', {
      code: 'USER_CANCELED',
    })
  }
}

async function saveRoom(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const nextStatus = formString(values, 'status') as RoomInput['status']
  const current = rooms.value.find((room) => room.id === id)
  if (current?.status === 'ACTIVE' && nextStatus === 'INACTIVE') {
    await confirmMaterialChange(
      '确认停用教室',
      `停用“${current.roomName}”会阻止后续排课；若仍有关联开班，服务端将拒绝操作。`,
    )
  }
  const payload: RoomInput = {
    schoolId: schoolId(),
    roomCode: formString(values, 'roomCode'),
    roomName: formString(values, 'roomName'),
    location: formNullableString(values, 'location'),
    capacity: formNumber(values, 'capacity'),
    status: nextStatus,
  }
  if (id === null) await academicApi.createRoom(payload)
  else await academicApi.updateRoom(id, payload)
  await loadRooms()
}

async function saveCalendarEvent(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const termId = formNumber(values, 'termId')
  const eventDate = formString(values, 'eventDate')
  const dayType = formString(
    values,
    'dayType',
  ) as CalendarEventInput['dayType']
  const term = terms.value.find((item) => item.id === termId)
  if (!term) {
    throw new ApiClientError('请选择有效学期。', {
      fieldErrors: { termId: '请选择有效学期' },
    })
  }
  if (eventDate < term.startDate || eventDate > term.endDate) {
    throw new ApiClientError('校历日期必须位于所属学期范围内。', {
      fieldErrors: {
        eventDate: `${formatDate(term.startDate)} 至 ${formatDate(term.endDate)}`,
      },
    })
  }

  const current = calendarEvents.value.find((event) => event.id === id)
  const isNewClosure =
    ['HOLIDAY', 'SUSPENDED'].includes(dayType) &&
    (!current || current.dayType !== dayType)
  if (isNewClosure) {
    await confirmMaterialChange(
      dayType === 'HOLIDAY' ? '确认设置放假日' : '确认设置停课日',
      `${formatDate(eventDate)} 将标记为“${calendarDayLabel(dayType)}”，该日期不能作为调课目标日期。`,
    )
  }

  const payload: CalendarEventInput = {
    schoolId: schoolId(),
    termId,
    eventDate,
    dayType,
    eventName: formString(values, 'eventName'),
    description: formNullableString(values, 'description'),
  }
  if (id === null) await academicApi.createCalendarEvent(payload)
  else await academicApi.updateCalendarEvent(id, payload)
  selectedTermId.value = termId
  await loadCalendar()
}

watch([activeTab, selectedTermId], () => {
  syncQuery()
})

watch(selectedTermId, () => {
  if (mounted.value) {
    void Promise.all([loadPlans(), loadCalendar()])
  }
})

onMounted(async () => {
  await loadTerms()
  await Promise.all([loadPlans(), loadRooms(), loadCalendar()])
  mounted.value = true
  syncQuery()
})
</script>

<template>
  <section class="page-stack academic-resources">
    <PageHeader
      kicker="学校学期准备"
      title="学期资源与服务计划"
      description="以统一学期为上下文维护备案计划、标准教室和校历。资源约束会贯穿开班与调课，避免同一时段重复占用。"
    >
      <template #actions>
        <el-button
          :loading="
            termsLoading || plansLoading || roomsLoading || calendarLoading
          "
          @click="reloadAll"
        >
          刷新
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="termsError"
      class="page-alert"
      :title="termsError"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button text type="primary" @click="loadTerms">重新加载</el-button>
      </template>
    </el-alert>

    <section class="term-context">
      <div class="term-context-copy">
        <span class="context-label">当前学期上下文</span>
        <strong>{{ selectedTerm?.termName || '尚未选择学期' }}</strong>
        <small v-if="selectedTerm">
          {{ formatDate(selectedTerm.startDate) }} —
          {{ formatDate(selectedTerm.endDate) }}
          · {{ statusLabel(selectedTerm.status) }}
        </small>
        <small v-else>区域监管端建立学期后，学校才能维护计划和校历。</small>
      </div>
      <el-select
        v-model="selectedTermId"
        class="term-selector"
        filterable
        placeholder="请选择学期"
        :loading="termsLoading"
        :disabled="terms.length === 0"
        aria-label="选择学期上下文"
      >
        <el-option
          v-for="term in terms"
          :key="term.id"
          :label="`${term.termName}（${statusLabel(term.status)}）`"
          :value="term.id"
        />
      </el-select>
    </section>

    <section class="resource-workbench">
      <el-tabs v-model="activeTab" class="business-tabs">
        <el-tab-pane label="服务计划" name="plans">
          <section class="entity-panel">
            <header class="entity-panel-header">
              <div>
                <h2>本校服务计划</h2>
                <p>草稿或退回计划可编辑；提交后由监管端依次备案和启动。</p>
              </div>
              <div class="plan-toolbar">
                <el-input
                  v-model="planKeyword"
                  clearable
                  placeholder="搜索计划编码或名称"
                  aria-label="搜索服务计划"
                />
                <el-button
                  type="primary"
                  :disabled="writablePlanTerms.length === 0"
                  @click="openCreatePlan"
                >
                  新增计划
                </el-button>
              </div>
            </header>

            <el-alert
              v-if="plansError"
              class="embedded-alert"
              :title="plansError"
              type="error"
              :closable="false"
              show-icon
            >
              <template #default>
                <el-button text type="primary" @click="loadPlans">
                  重新加载
                </el-button>
              </template>
            </el-alert>

            <el-table
              v-loading="plansLoading"
              :data="filteredPlans"
              row-key="id"
              class="entity-table"
              table-layout="auto"
            >
              <el-table-column label="计划" min-width="220">
                <template #default="{ row }">
                  <div class="primary-cell">
                    <strong>{{ (row as ServicePlan).planName }}</strong>
                    <span>{{ (row as ServicePlan).planCode }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="105">
                <template #default="{ row }">
                  <el-tag
                    effect="plain"
                    size="small"
                    :type="statusTagType((row as ServicePlan).status)"
                  >
                    {{ statusLabel((row as ServicePlan).status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="计划说明" min-width="260">
                <template #default="{ row }">
                  <div class="description-cell">
                    <span>{{ (row as ServicePlan).description || '未填写说明' }}</span>
                    <em v-if="(row as ServicePlan).returnReason">
                      退回原因：{{ (row as ServicePlan).returnReason }}
                    </em>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="流程时间" min-width="180">
                <template #default="{ row }">
                  <div class="time-cell">
                    <span>
                      提交 {{ formatDateTime((row as ServicePlan).submittedAt) }}
                    </span>
                    <span>
                      备案 {{ formatDateTime((row as ServicePlan).filedAt) }}
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="操作" min-width="155" align="right">
                <template #default="{ row }">
                  <div class="row-actions">
                    <el-button text @click="openPlanItems(row as ServicePlan)">
                      计划明细
                    </el-button>
                    <el-button
                      v-if="['DRAFT', 'RETURNED'].includes((row as ServicePlan).status)"
                      text
                      type="primary"
                      @click="openEditPlan(row as ServicePlan)"
                    >
                      编辑
                    </el-button>
                    <el-button
                      v-if="['DRAFT', 'RETURNED'].includes((row as ServicePlan).status)"
                      text
                      type="warning"
                      :loading="submittingPlanId === (row as ServicePlan).id"
                      @click="submitPlan(row as ServicePlan)"
                    >
                      提交备案
                    </el-button>
                  </div>
                </template>
              </el-table-column>
              <template #empty>
                <div class="empty-state">
                  <strong>当前学期暂无服务计划</strong>
                  <span>新建计划并完善内容后，可提交区域监管备案。</span>
                </div>
              </template>
            </el-table>
          </section>
          <EntityCrudPanel
            v-if="itemPlan"
            :title="`${itemPlan.planName} · 备案明细`"
            description="逐项填写课程类型、计划开班规模和师资配置；至少一条完整明细后才能提交备案。"
            :rows="planItems"
            :columns="planItemColumns"
            :fields="planItemFields"
            :loading="planItemsLoading"
            :error="planItemsError"
            :save="savePlanItem"
            :can-create="['DRAFT', 'RETURNED'].includes(itemPlan.status)"
            :can-edit="['DRAFT', 'RETURNED'].includes(itemPlan.status)"
            @retry="loadPlanItems"
          />
        </el-tab-pane>

        <el-tab-pane label="教室资源" name="rooms">
          <EntityCrudPanel
            title="标准教室"
            description="维护可排课空间及容量。停用和缩减容量都要通过开班依赖校验。"
            :rows="rooms"
            :columns="roomColumns"
            :fields="roomFields"
            :loading="roomsLoading"
            :error="roomsError"
            :save="saveRoom"
            @retry="loadRooms"
          />
        </el-tab-pane>

        <el-tab-pane label="校历" name="calendar">
          <EntityCrudPanel
            title="学期校历"
            description="标记正常教学、补课、放假和停课日期；当前列表随上方学期切换。"
            :rows="calendarEvents"
            :columns="calendarColumns"
            :fields="calendarFields"
            :loading="calendarLoading"
            :error="calendarError"
            :save="saveCalendarEvent"
            :can-create="
              Boolean(selectedTerm && selectedTerm.status !== 'ARCHIVED')
            "
            :can-edit="
              Boolean(selectedTerm && selectedTerm.status !== 'ARCHIVED')
            "
            @retry="loadCalendar"
          />
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog
      v-model="planDialogVisible"
      :title="editingPlanId === null ? '新增服务计划' : '编辑服务计划'"
      width="min(660px, calc(100vw - 32px))"
      destroy-on-close
      :close-on-click-modal="false"
      :before-close="beforePlanDialogClose"
    >
      <el-alert
        v-if="planDialogError"
        :title="planDialogError"
        type="error"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-alert
        v-if="editingPlanId !== null"
        title="计划若已关联开班，所属学期和计划编码将由服务端锁定。"
        type="info"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-form label-position="top" @submit.prevent="savePlan">
        <div class="form-grid">
          <el-form-item label="所属学期" required class="form-item-wide">
            <el-select
              v-model="planForm.termId"
              filterable
              placeholder="请选择学期"
            >
              <el-option
                v-for="term in writablePlanTerms"
                :key="term.id"
                :label="`${term.termName}（${statusLabel(term.status)}）`"
                :value="term.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="计划编码" required>
            <el-input
              v-model="planForm.planCode"
              maxlength="32"
              placeholder="例如 PLAN-2026-FALL"
            />
          </el-form-item>
          <el-form-item label="计划名称" required>
            <el-input
              v-model="planForm.planName"
              maxlength="128"
              placeholder="例如 秋季课后服务实施计划"
            />
          </el-form-item>
          <el-form-item label="计划说明" class="form-item-wide">
            <el-input
              v-model="planForm.description"
              type="textarea"
              :rows="5"
              maxlength="2000"
              show-word-limit
              placeholder="说明服务目标、课程组织、师资与安全保障"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="planSaving" @click="requestPlanDialogClose">
          取消
        </el-button>
        <el-button type="primary" :loading="planSaving" @click="savePlan">
          保存草稿
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.term-context {
  position: relative;
  display: flex;
  min-height: 112px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  overflow: hidden;
  padding: 22px 24px 22px 42px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(90deg, var(--surface-soft), var(--surface) 55%);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.term-context::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 17px;
  width: 2px;
  background: var(--accent);
  content: "";
}

.term-context::after {
  position: absolute;
  top: 50%;
  left: 12px;
  width: 12px;
  height: 12px;
  border: 3px solid var(--surface);
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
  content: "";
  transform: translateY(-50%);
}

.term-context-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

.context-label {
  color: var(--accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.term-context-copy strong {
  font-size: 18px;
}

.term-context-copy small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.55;
}

.term-selector {
  width: min(360px, 42vw);
}

.resource-workbench {
  min-width: 0;
}

.resource-workbench :deep(.el-tabs__content) {
  overflow: visible;
}

.plan-toolbar,
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.plan-toolbar .el-input {
  width: min(260px, 35vw);
}

.embedded-alert {
  margin: 16px 20px 0;
}

.primary-cell,
.description-cell,
.time-cell {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.primary-cell strong {
  color: var(--ink);
  font-size: 13px;
}

.primary-cell span,
.description-cell,
.time-cell {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.55;
}

.description-cell em {
  color: var(--danger);
  font-style: normal;
}

.row-actions {
  justify-content: flex-end;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}

.no-action {
  color: var(--subtle);
  font-size: 11px;
}

@media (max-width: 760px) {
  .term-context {
    align-items: stretch;
    flex-direction: column;
  }

  .term-selector {
    width: 100%;
  }

  .entity-panel-header {
    align-items: stretch;
    flex-direction: column;
  }

  .plan-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .plan-toolbar .el-input,
  .plan-toolbar .el-button {
    width: 100%;
  }
}
</style>
