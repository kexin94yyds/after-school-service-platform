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
  type PlanStatus,
  type ServicePlan,
  type ServicePlanItem,
  type TermInput,
  type TermStatus,
} from '@/api/academic'
import { getErrorMessage } from '@/api/http'
import { organizationApi } from '@/api/organization'
import type { School } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import {
  formatDate,
  formatDateTime,
  statusLabel,
  statusTagType,
} from '@/utils/format'

interface PlanAction {
  target: Exclude<PlanStatus, 'DRAFT'>
  label: string
  type: 'primary' | 'success' | 'warning' | 'danger'
}

const route = useRoute()
const router = useRouter()

const planStatusOptions: Array<{ label: string; value: PlanStatus }> = [
  { label: '草稿', value: 'DRAFT' },
  { label: '待备案', value: 'SUBMITTED' },
  { label: '已备案', value: 'FILED' },
  { label: '已退回', value: 'RETURNED' },
  { label: '执行中', value: 'ACTIVE' },
  { label: '已关闭', value: 'CLOSED' },
  { label: '已归档', value: 'ARCHIVED' },
]

const schools = ref<School[]>([])
const terms = ref<AcademicTerm[]>([])
const plans = ref<ServicePlan[]>([])
const baseLoading = ref(false)
const planLoading = ref(false)
const baseError = ref('')
const planError = ref('')
const keyword = ref('')
const mounted = ref(false)
const planLoadVersion = ref(0)
const transitioningId = ref<number | null>(null)
const itemDialogVisible = ref(false)
const itemPlan = ref<ServicePlan | null>(null)
const planItems = ref<ServicePlanItem[]>([])
const itemLoading = ref(false)
const itemError = ref('')

const filters = reactive<{
  schoolId: number | null
  termId: number | null
  status: PlanStatus | ''
}>({
  schoolId: queryNumber(route.query.schoolId),
  termId: queryNumber(route.query.termId),
  status: queryPlanStatus(route.query.planStatus),
})

const termDialogVisible = ref(false)
const editingTermId = ref<number | null>(null)
const termSaving = ref(false)
const termDialogError = ref('')
const termForm = reactive<TermInput>({
  termCode: '',
  termName: '',
  startDate: '',
  endDate: '',
  status: 'DRAFT',
})

const sortedTerms = computed(() =>
  [...terms.value].sort((left, right) =>
    right.startDate.localeCompare(left.startDate),
  ),
)

async function showPlanItems(plan: ServicePlan): Promise<void> {
  itemPlan.value = plan
  itemDialogVisible.value = true
  itemLoading.value = true
  itemError.value = ''
  try {
    planItems.value = await academicApi.getServicePlanItems(plan.id)
  } catch (loadError) {
    planItems.value = []
    itemError.value = getErrorMessage(loadError, '备案明细加载失败。')
  } finally {
    itemLoading.value = false
  }
}

const selectedTerm = computed(() =>
  terms.value.find((term) => term.id === filters.termId),
)

const filteredPlans = computed(() => {
  const normalized = keyword.value.trim().toLocaleLowerCase()
  return plans.value.filter((plan) => {
    if (filters.status && plan.status !== filters.status) return false
    if (!normalized) return true
    return [
      plan.planCode,
      plan.planName,
      plan.schoolName,
      plan.termName,
      plan.description,
      plan.returnReason,
    ].some((value) => value?.toLocaleLowerCase().includes(normalized))
  })
})

const planMetrics = computed(() => [
  {
    label: '当前结果',
    value: filteredPlans.value.length,
    note: '符合筛选条件的计划',
  },
  {
    label: '待备案',
    value: plans.value.filter((plan) => plan.status === 'SUBMITTED').length,
    note: '需要监管处理',
  },
  {
    label: '执行中',
    value: plans.value.filter((plan) => plan.status === 'ACTIVE').length,
    note: '已进入实施阶段',
  },
  {
    label: '被退回',
    value: plans.value.filter((plan) => plan.status === 'RETURNED').length,
    note: '等待学校修订',
  },
])

const termStatusOptions = computed<Array<{
  label: string
  value: TermStatus
}>>(() => {
  const current = terms.value.find((term) => term.id === editingTermId.value)
    ?.status
  if (!current) {
    return [{ label: '草稿', value: 'DRAFT' }]
  }
  if (current === 'DRAFT') {
    return [
      { label: '草稿', value: 'DRAFT' },
      { label: '启用', value: 'ACTIVE' },
    ]
  }
  if (current === 'ACTIVE') {
    return [
      { label: '启用', value: 'ACTIVE' },
      { label: '关闭', value: 'CLOSED' },
    ]
  }
  if (current === 'CLOSED') {
    return [
      { label: '关闭', value: 'CLOSED' },
      { label: '归档', value: 'ARCHIVED' },
    ]
  }
  return [{ label: '归档', value: 'ARCHIVED' }]
})

function queryNumber(value: unknown): number | null {
  const source = Array.isArray(value) ? value[0] : value
  if (typeof source !== 'string' || !source) return null
  const parsed = Number(source)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function queryPlanStatus(value: unknown): PlanStatus | '' {
  const source = Array.isArray(value) ? value[0] : value
  return planStatusOptions.some((option) => option.value === source)
    ? (source as PlanStatus)
    : ''
}

function sameQueryValue(
  value: unknown,
  expected: string | undefined,
): boolean {
  const source = Array.isArray(value) ? value[0] : value
  return (source || undefined) === expected
}

function syncQuery(): void {
  const schoolId = filters.schoolId?.toString()
  const termId = filters.termId?.toString()
  const planStatus = filters.status || undefined
  if (
    sameQueryValue(route.query.schoolId, schoolId) &&
    sameQueryValue(route.query.termId, termId) &&
    sameQueryValue(route.query.planStatus, planStatus)
  ) {
    return
  }
  const query: LocationQueryRaw = { ...route.query }
  if (schoolId) query.schoolId = schoolId
  else delete query.schoolId
  if (termId) query.termId = termId
  else delete query.termId
  if (planStatus) query.planStatus = planStatus
  else delete query.planStatus
  void router.replace({ query })
}

async function loadBase(): Promise<void> {
  baseLoading.value = true
  baseError.value = ''
  const [termResult, schoolResult] = await Promise.allSettled([
    academicApi.getTerms(),
    organizationApi.getSchools(),
  ])
  if (termResult.status === 'fulfilled') {
    terms.value = termResult.value
    if (
      filters.termId !== null &&
      !terms.value.some((term) => term.id === filters.termId)
    ) {
      filters.termId = null
    }
  } else {
    baseError.value = getErrorMessage(
      termResult.reason,
      '学期数据加载失败。',
    )
  }
  if (schoolResult.status === 'fulfilled') {
    schools.value = schoolResult.value
    if (
      filters.schoolId !== null &&
      !schools.value.some((school) => school.id === filters.schoolId)
    ) {
      filters.schoolId = null
    }
  } else {
    const message = getErrorMessage(
      schoolResult.reason,
      '学校列表加载失败。',
    )
    baseError.value = baseError.value
      ? `${baseError.value} ${message}`
      : message
  }
  baseLoading.value = false
}

async function loadPlans(): Promise<void> {
  const version = ++planLoadVersion.value
  planLoading.value = true
  planError.value = ''
  try {
    const rows = await academicApi.getServicePlans({
      schoolId: filters.schoolId ?? undefined,
      termId: filters.termId ?? undefined,
    })
    if (version === planLoadVersion.value) plans.value = rows
  } catch (error) {
    if (version === planLoadVersion.value) {
      planError.value = getErrorMessage(error, '服务计划加载失败。')
      plans.value = []
    }
  } finally {
    if (version === planLoadVersion.value) planLoading.value = false
  }
}

async function reloadAll(): Promise<void> {
  await loadBase()
  await loadPlans()
}

function selectTerm(termId: number): void {
  filters.termId = filters.termId === termId ? null : termId
}

function openCreateTerm(): void {
  editingTermId.value = null
  Object.assign(termForm, {
    termCode: '',
    termName: '',
    startDate: '',
    endDate: '',
    status: 'DRAFT',
  } satisfies TermInput)
  termDialogError.value = ''
  termDialogVisible.value = true
}

function openEditTerm(term: AcademicTerm): void {
  editingTermId.value = term.id
  Object.assign(termForm, {
    termCode: term.termCode,
    termName: term.termName,
    startDate: term.startDate,
    endDate: term.endDate,
    status: term.status,
  } satisfies TermInput)
  termDialogError.value = ''
  termDialogVisible.value = true
}

function validateTerm(): string {
  if (
    !termForm.termCode.trim() ||
    !termForm.termName.trim() ||
    !termForm.startDate ||
    !termForm.endDate
  ) {
    return '请完整填写学期编码、名称和日期范围。'
  }
  if (termForm.startDate > termForm.endDate) {
    return '学期结束日期不能早于开始日期。'
  }
  return ''
}

async function saveTerm(): Promise<void> {
  termDialogError.value = validateTerm()
  if (termDialogError.value) return

  const current = terms.value.find((term) => term.id === editingTermId.value)
  if (current && current.status !== termForm.status) {
    try {
      await ElMessageBox.confirm(
        `学期将从“${statusLabel(current.status)}”变为“${statusLabel(termForm.status)}”。状态推进后不可回退，是否继续？`,
        '确认推进学期状态',
        {
          confirmButtonText: '确认推进',
          cancelButtonText: '取消',
          type: termForm.status === 'ACTIVE' ? 'warning' : 'error',
        },
      )
    } catch {
      return
    }
  }

  termSaving.value = true
  try {
    const payload: TermInput = {
      termCode: termForm.termCode.trim(),
      termName: termForm.termName.trim(),
      startDate: termForm.startDate,
      endDate: termForm.endDate,
      status: termForm.status,
    }
    if (editingTermId.value === null) {
      await academicApi.createTerm(payload)
    } else {
      await academicApi.updateTerm(editingTermId.value, payload)
    }
    termDialogVisible.value = false
    ElMessage.success(editingTermId.value === null ? '学期已创建' : '学期已更新')
    await Promise.all([loadBase(), loadPlans()])
  } catch (error) {
    termDialogError.value = getErrorMessage(error, '学期保存失败。')
  } finally {
    termSaving.value = false
  }
}

function planActions(plan: ServicePlan): PlanAction[] {
  switch (plan.status) {
    case 'SUBMITTED':
      return [
        { target: 'FILED', label: '确认备案', type: 'success' },
        { target: 'RETURNED', label: '退回修订', type: 'danger' },
      ]
    case 'FILED':
      return [{ target: 'ACTIVE', label: '启动执行', type: 'primary' }]
    case 'ACTIVE':
      return [{ target: 'CLOSED', label: '关闭计划', type: 'warning' }]
    case 'CLOSED':
      return [{ target: 'ARCHIVED', label: '归档', type: 'warning' }]
    default:
      return []
  }
}

function transitionPrompt(
  plan: ServicePlan,
  action: PlanAction,
): string {
  const subject = `“${plan.schoolName || '该学校'} · ${plan.planName}”`
  switch (action.target) {
    case 'FILED':
      return `确认 ${subject} 的材料完整并予以备案？`
    case 'ACTIVE':
      return `确认启动 ${subject}？所属学期必须已经启用。`
    case 'CLOSED':
      return `关闭后学校不能再把该计划用于新的排课，确认继续？`
    case 'ARCHIVED':
      return `归档后该计划仅用于历史查询，确认继续？`
    default:
      return `确认将 ${subject} 推进至“${statusLabel(action.target)}”？`
  }
}

async function transitionPlan(
  plan: ServicePlan,
  action: PlanAction,
): Promise<void> {
  let reason: string | null = null
  try {
    if (action.target === 'RETURNED') {
      const result = await ElMessageBox.prompt(
        `请说明“${plan.planName}”需要修订的内容，学校将在计划详情中看到该原因。`,
        '退回服务计划',
        {
          confirmButtonText: '确认退回',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputPlaceholder: '例如：请补充课程容量测算与安全预案',
          inputValidator: (value) =>
            value.trim().length > 0 || '退回原因不能为空',
          type: 'warning',
        },
      )
      reason = result.value.trim()
    } else {
      await ElMessageBox.confirm(
        transitionPrompt(plan, action),
        `确认${action.label}`,
        {
          confirmButtonText: action.label,
          cancelButtonText: '取消',
          type:
            action.target === 'CLOSED' || action.target === 'ARCHIVED'
              ? 'error'
              : 'warning',
        },
      )
    }
  } catch {
    return
  }

  transitioningId.value = plan.id
  try {
    await academicApi.transitionServicePlan(
      plan.id,
      action.target,
      reason,
    )
    ElMessage.success(`计划已更新为“${statusLabel(action.target)}”`)
    await loadPlans()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '计划状态更新失败。'))
  } finally {
    transitioningId.value = null
  }
}

watch(
  () => [filters.schoolId, filters.termId],
  () => {
    syncQuery()
    if (mounted.value) void loadPlans()
  },
)

watch(
  () => filters.status,
  syncQuery,
)

onMounted(async () => {
  await loadBase()
  await loadPlans()
  mounted.value = true
  syncQuery()
})
</script>

<template>
  <section class="page-stack academic-governance">
    <PageHeader
      kicker="区域学年治理"
      title="学期与服务计划备案"
      description="先维护区域统一学期，再沿备案、执行、关闭和归档轨道审查各校课后服务计划。每次状态推进均保留服务端审计记录。"
    >
      <template #actions>
        <el-button :loading="baseLoading || planLoading" @click="reloadAll">
          刷新
        </el-button>
        <el-button type="primary" @click="openCreateTerm">新增学期</el-button>
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

    <section class="term-rail" aria-labelledby="term-rail-title">
      <header class="section-heading">
        <div>
          <h2 id="term-rail-title">学期轨道</h2>
          <p>点击学期可限定下方计划，再次点击取消限定。</p>
        </div>
        <span v-if="selectedTerm" class="rail-context">
          正在查看：{{ selectedTerm.termName }}
        </span>
      </header>
      <div v-loading="baseLoading" class="term-track">
        <article
          v-for="term in sortedTerms"
          :key="term.id"
          class="term-stop"
          :class="{ active: filters.termId === term.id }"
        >
          <button
            type="button"
            class="term-select"
            :aria-pressed="filters.termId === term.id"
            @click="selectTerm(term.id)"
          >
            <span class="term-marker" aria-hidden="true" />
            <span class="term-copy">
              <strong>{{ term.termName }}</strong>
              <small>
                {{ formatDate(term.startDate) }} — {{ formatDate(term.endDate) }}
              </small>
            </span>
            <el-tag
              size="small"
              effect="plain"
              :type="statusTagType(term.status)"
            >
              {{ statusLabel(term.status) }}
            </el-tag>
          </button>
          <button
            type="button"
            class="term-edit"
            :aria-label="`编辑学期：${term.termName}`"
            @click="openEditTerm(term)"
          >
            编辑
          </button>
        </article>
        <div v-if="!baseLoading && terms.length === 0" class="empty-state">
          <strong>尚未建立学期</strong>
          <span>创建第一个区域统一学期后，各校才能编制服务计划。</span>
        </div>
      </div>
    </section>

    <section class="metric-grid plan-metrics" aria-label="备案计划概况">
      <article
        v-for="metric in planMetrics"
        :key="metric.label"
        class="metric-cell"
      >
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
        <small>{{ metric.note }}</small>
      </article>
    </section>

    <section class="entity-panel plan-panel">
      <header class="entity-panel-header">
        <div>
          <h2>学校服务计划</h2>
          <p>监管端只能推进备案生命周期；草稿内容由学校维护。</p>
        </div>
        <div class="plan-toolbar">
          <el-select
            v-model="filters.schoolId"
            clearable
            filterable
            placeholder="全部学校"
            aria-label="按学校筛选"
          >
            <el-option
              v-for="school in schools"
              :key="school.id"
              :label="school.schoolName"
              :value="school.id"
            />
          </el-select>
          <el-select
            v-model="filters.status"
            clearable
            placeholder="全部状态"
            aria-label="按计划状态筛选"
          >
            <el-option
              v-for="option in planStatusOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-input
            v-model="keyword"
            clearable
            placeholder="搜索学校、编码或名称"
            aria-label="搜索服务计划"
          />
        </div>
      </header>

      <el-alert
        v-if="planError"
        class="embedded-alert"
        :title="planError"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default>
          <el-button text type="primary" @click="loadPlans">重新加载</el-button>
        </template>
      </el-alert>

      <el-table
        v-loading="planLoading"
        :data="filteredPlans"
        row-key="id"
        class="entity-table"
        table-layout="auto"
      >
        <el-table-column label="学校 / 计划" min-width="220">
          <template #default="{ row }">
            <div class="primary-cell">
              <strong>{{ (row as ServicePlan).planName }}</strong>
              <span>
                {{ (row as ServicePlan).schoolName || '未知学校' }} ·
                {{ (row as ServicePlan).planCode }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属学期" min-width="150">
          <template #default="{ row }">
            {{ (row as ServicePlan).termName || (row as ServicePlan).termCode || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="statusTagType((row as ServicePlan).status)"
            >
              {{ statusLabel((row as ServicePlan).status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交 / 备案" min-width="180">
          <template #default="{ row }">
            <div class="time-cell">
              <span>提交 {{ formatDateTime((row as ServicePlan).submittedAt) }}</span>
              <span>备案 {{ formatDateTime((row as ServicePlan).filedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
          <template #default="{ row }">
            <div class="description-cell">
              <span>{{ (row as ServicePlan).description || '未填写计划说明' }}</span>
              <em v-if="(row as ServicePlan).returnReason">
                退回原因：{{ (row as ServicePlan).returnReason }}
              </em>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="185" align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button text @click="showPlanItems(row as ServicePlan)">
                查看明细
              </el-button>
              <el-button
                v-for="action in planActions(row as ServicePlan)"
                :key="action.target"
                text
                :type="action.type"
                :loading="transitioningId === (row as ServicePlan).id"
                :disabled="
                  transitioningId !== null &&
                  transitioningId !== (row as ServicePlan).id
                "
                @click="transitionPlan(row as ServicePlan, action)"
              >
                {{ action.label }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>没有符合条件的服务计划</strong>
            <span>可调整学校、学期、状态或关键词筛选。</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-dialog
      v-model="itemDialogVisible"
      :title="`${itemPlan?.schoolName || ''} · ${itemPlan?.planName || '备案明细'}`"
      width="min(860px, calc(100vw - 32px))"
    >
      <el-alert
        v-if="itemError"
        :title="itemError"
        type="error"
        :closable="false"
        show-icon
      />
      <el-table v-loading="itemLoading" :data="planItems" table-layout="auto">
        <el-table-column prop="category" label="课程类型" min-width="140" />
        <el-table-column prop="plannedCourseCount" label="计划课程" min-width="90" />
        <el-table-column prop="plannedClassCount" label="计划开班" min-width="90" />
        <el-table-column prop="capacityPerClass" label="单班规模" min-width="90" />
        <el-table-column prop="plannedTeacherCount" label="师资人数" min-width="90" />
        <el-table-column prop="notes" label="配置说明" min-width="220" />
      </el-table>
    </el-dialog>

    <el-dialog
      v-model="termDialogVisible"
      :title="editingTermId === null ? '新增学期' : '编辑学期'"
      width="min(620px, calc(100vw - 32px))"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="termDialogError"
        :title="termDialogError"
        type="error"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-alert
        v-if="editingTermId !== null"
        title="学期启用或已有学校计划后，编码及日期范围会被服务端锁定。"
        type="info"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-form label-position="top" @submit.prevent="saveTerm">
        <div class="form-grid">
          <el-form-item label="学期编码" required>
            <el-input
              v-model="termForm.termCode"
              maxlength="32"
              placeholder="例如 2026-FALL"
            />
          </el-form-item>
          <el-form-item label="学期名称" required>
            <el-input
              v-model="termForm.termName"
              maxlength="128"
              placeholder="例如 2026 年秋季学期"
            />
          </el-form-item>
          <el-form-item label="开始日期" required>
            <el-date-picker
              v-model="termForm.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择开始日期"
            />
          </el-form-item>
          <el-form-item label="结束日期" required>
            <el-date-picker
              v-model="termForm.endDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择结束日期"
            />
          </el-form-item>
          <el-form-item label="学期状态" required class="form-item-wide">
            <el-select v-model="termForm.status">
              <el-option
                v-for="option in termStatusOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="termDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="termSaving" @click="saveTerm">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.academic-governance {
  --rail-line: #bfd2c8;
}

.term-rail {
  overflow: hidden;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: 0 10px 30px rgb(25 66 52 / 5%);
}

.term-rail .section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.term-rail h2 {
  margin: 0;
  font-size: 18px;
}

.term-rail p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.rail-context {
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
}

.term-track {
  position: relative;
  display: grid;
  min-height: 116px;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 12px;
}

.term-track::before {
  position: absolute;
  top: 20px;
  right: 20px;
  left: 20px;
  height: 1px;
  background: var(--rail-line);
  content: "";
}

.term-stop {
  position: relative;
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: stretch;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  color: var(--ink);
  background: var(--paper);
  transition:
    border-color 160ms ease,
    background-color 160ms ease;
}

.term-stop:hover,
.term-stop:focus-within,
.term-stop.active {
  border-color: #8fb6a5;
  background: var(--surface-soft);
}

.term-select {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  padding: 12px;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.term-marker {
  position: relative;
  width: 13px;
  height: 13px;
  margin-top: 2px;
  border: 3px solid var(--surface);
  border-radius: 50%;
  background: #88a99a;
  box-shadow: 0 0 0 1px #88a99a;
}

.term-stop.active .term-marker {
  background: var(--accent);
  box-shadow: 0 0 0 2px var(--accent);
}

.term-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

.term-copy strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.term-copy small {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.5;
}

.term-edit {
  min-width: 56px;
  padding: 0 12px;
  border: 0;
  border-left: 1px solid var(--line);
  color: var(--accent);
  font: inherit;
  font-size: 11px;
  font-weight: 700;
  background: transparent;
  cursor: pointer;
}

.term-edit:hover {
  background: rgb(23 107 82 / 8%);
}

.plan-toolbar {
  display: grid;
  width: min(100%, 680px);
  grid-template-columns: 180px 140px minmax(220px, 1fr);
  gap: 10px;
}

.embedded-alert {
  margin: 16px 20px 0;
}

.primary-cell,
.time-cell,
.description-cell {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.primary-cell strong {
  font-size: 13px;
}

.primary-cell span,
.time-cell,
.description-cell {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.55;
}

.description-cell em {
  color: var(--danger);
  font-style: normal;
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}

.no-action {
  color: var(--subtle);
  font-size: 11px;
}

@media (max-width: 1000px) {
  .plan-toolbar {
    width: 100%;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .plan-toolbar .el-input {
    grid-column: 1 / -1;
  }

  .entity-panel-header {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (max-width: 700px) {
  .term-rail {
    padding: 18px;
  }

  .term-rail .section-heading {
    flex-direction: column;
  }

  .term-track {
    grid-template-columns: 1fr;
  }

  .plan-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .plan-metrics .metric-cell:nth-child(3) {
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .plan-metrics .metric-cell:nth-child(4) {
    border-top: 1px solid var(--line);
  }

  .plan-toolbar {
    grid-template-columns: 1fr;
  }

  .plan-toolbar .el-input {
    grid-column: auto;
  }
}
</style>
