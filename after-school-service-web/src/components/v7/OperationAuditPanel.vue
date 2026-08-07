<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { auditApi } from '@/api/audit'
import type { AuditMethod, OperationAuditLog } from '@/api/audit'
import { getErrorMessage } from '@/api/http'
import { referenceDataApi } from '@/api/reference-data'
import type { SchoolOption } from '@/api/reference-data'
import PageHeader from '@/components/PageHeader.vue'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{
  mode: 'regulator' | 'school'
}>()

const route = useRoute()
const router = useRouter()
const logs = ref<OperationAuditLog[]>([])
const schools = ref<SchoolOption[]>([])
const loading = ref(false)
const schoolLoading = ref(false)
const error = ref('')

const filters = reactive<{
  schoolId: number | null
  actorUserId: string
  method: AuditMethod | ''
  pathPrefix: string
  dateRange: [string, string] | null
}>({
  schoolId: null,
  actorUserId: '',
  method: '',
  pathPrefix: '',
  dateRange: null,
})

const methods: AuditMethod[] = ['POST', 'PUT', 'PATCH', 'DELETE']

const pageCopy = computed(() =>
  props.mode === 'regulator'
    ? {
        kicker: '操作留痕',
        title: '区域操作审计',
        description:
          '查询区域内关键写操作，按学校、操作者和接口范围追溯业务变更。',
      }
    : {
        kicker: '本校审计',
        title: '学校操作审计',
        description:
          '查看当前学校范围内的关键写操作，核对操作者、请求结果与发生时间。',
      },
)

const summary = computed(() => {
  const successful = logs.value.filter(
    (item) => item.responseStatus >= 200 && item.responseStatus < 400,
  ).length
  return {
    total: logs.value.length,
    actors: new Set(logs.value.map((item) => item.actorUserId)).size,
    successful,
    failed: logs.value.length - successful,
  }
})

function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

function positiveNumber(value: unknown): number | null {
  const parsed = Number(firstQueryValue(value))
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function isAuditMethod(value: string | undefined): value is AuditMethod {
  return methods.includes(value as AuditMethod)
}

function hydrateFilters(): void {
  const method = firstQueryValue(route.query.method)
  const from = firstQueryValue(route.query.from)
  const to = firstQueryValue(route.query.to)
  filters.schoolId = props.mode === 'regulator' ? positiveNumber(route.query.schoolId) : null
  filters.actorUserId = firstQueryValue(route.query.actorUserId) ?? ''
  filters.method = isAuditMethod(method) ? method : ''
  filters.pathPrefix = firstQueryValue(route.query.pathPrefix) ?? ''
  filters.dateRange = from && to ? [from, to] : null
}

async function saveFiltersToUrl(): Promise<void> {
  const query: Record<string, string> = {}
  if (props.mode === 'regulator' && filters.schoolId) {
    query.schoolId = String(filters.schoolId)
  }
  if (filters.actorUserId.trim()) query.actorUserId = filters.actorUserId.trim()
  if (filters.method) query.method = filters.method
  if (filters.pathPrefix.trim()) query.pathPrefix = filters.pathPrefix.trim()
  if (filters.dateRange) {
    query.from = filters.dateRange[0]
    query.to = filters.dateRange[1]
  }
  await router.replace({ query })
}

function actorIdFilter(): number | undefined {
  const parsed = Number(filters.actorUserId)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : undefined
}

async function loadSchools(): Promise<void> {
  if (props.mode !== 'regulator') return
  schoolLoading.value = true
  try {
    schools.value = await referenceDataApi.getSchools()
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '学校选项加载失败。')
  } finally {
    schoolLoading.value = false
  }
}

async function loadLogs(options: { saveQuery?: boolean } = {}): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (options.saveQuery) await saveFiltersToUrl()
    logs.value = await auditApi.list({
      schoolId: props.mode === 'regulator' ? filters.schoolId : undefined,
      actorUserId: actorIdFilter(),
      method: filters.method,
      pathPrefix: filters.pathPrefix.trim(),
      occurredFrom: filters.dateRange?.[0]
        ? `${filters.dateRange[0]}T00:00:00`
        : undefined,
      occurredTo: filters.dateRange?.[1]
        ? `${filters.dateRange[1]}T23:59:59`
        : undefined,
    })
  } catch (loadError) {
    logs.value = []
    error.value = getErrorMessage(loadError, '操作审计记录加载失败。')
  } finally {
    loading.value = false
  }
}

async function resetFilters(): Promise<void> {
  filters.schoolId = null
  filters.actorUserId = ''
  filters.method = ''
  filters.pathPrefix = ''
  filters.dateRange = null
  await loadLogs({ saveQuery: true })
}

function roleLabel(role: OperationAuditLog['actorRole']): string {
  const labels: Record<OperationAuditLog['actorRole'], string> = {
    REGULATOR: '监管人员',
    SCHOOL_ADMIN: '学校管理员',
    TEACHER: '教师',
    GUARDIAN: '家长',
  }
  return labels[role]
}

function responseTagType(status: number): 'success' | 'warning' | 'danger' {
  if (status >= 200 && status < 400) return 'success'
  if (status >= 400 && status < 500) return 'warning'
  return 'danger'
}

onMounted(async () => {
  hydrateFilters()
  await Promise.all([loadSchools(), loadLogs()])
})
</script>

<template>
  <section class="page-stack audit-page">
    <PageHeader
      :kicker="pageCopy.kicker"
      :title="pageCopy.title"
      :description="pageCopy.description"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadLogs()">刷新记录</el-button>
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

    <section class="audit-metrics" aria-label="审计记录概况">
      <article>
        <span>当前记录</span>
        <strong>{{ summary.total }}</strong>
      </article>
      <article>
        <span>涉及账号</span>
        <strong>{{ summary.actors }}</strong>
      </article>
      <article>
        <span>成功响应</span>
        <strong>{{ summary.successful }}</strong>
      </article>
      <article :class="{ 'metric-risk': summary.failed > 0 }">
        <span>异常响应</span>
        <strong>{{ summary.failed }}</strong>
      </article>
    </section>

    <section class="audit-panel">
      <el-form class="audit-filters" label-position="top" @submit.prevent>
        <el-form-item v-if="mode === 'regulator'" label="学校范围">
          <el-select
            v-model="filters.schoolId"
            :loading="schoolLoading"
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
        <el-form-item label="操作者账号 ID">
          <el-input
            v-model="filters.actorUserId"
            inputmode="numeric"
            clearable
            placeholder="全部账号"
          />
        </el-form-item>
        <el-form-item label="请求方法">
          <el-select v-model="filters.method" clearable placeholder="全部方法">
            <el-option v-for="method in methods" :key="method" :label="method" :value="method" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口路径前缀">
          <el-input v-model="filters.pathPrefix" clearable placeholder="例如 /api/courses" />
        </el-form-item>
        <el-form-item label="发生日期">
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
          <el-button type="primary" :loading="loading" @click="loadLogs({ saveQuery: true })">
            查询
          </el-button>
        </div>
      </el-form>

      <el-skeleton v-if="loading && logs.length === 0" :rows="6" animated />
      <el-table v-else v-loading="loading" :data="logs" row-key="id" table-layout="auto">
        <el-table-column label="操作者" min-width="185">
          <template #default="{ row }">
            <div class="actor-cell">
              <strong>{{ row.actorName }}</strong>
              <span>{{ row.actorUsername }} · ID {{ row.actorUserId }}</span>
              <small>{{ roleLabel(row.actorRole) }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="mode === 'regulator'" label="业务学校" min-width="170">
          <template #default="{ row }">
            {{ row.targetSchoolName || row.schoolName || row.actorSchoolName || '区域级操作' }}
          </template>
        </el-table-column>
        <el-table-column label="请求" min-width="320">
          <template #default="{ row }">
            <div class="request-cell">
              <el-tag size="small" effect="plain">{{ row.httpMethod }}</el-tag>
              <code>{{ row.requestPath }}</code>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="响应" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="responseTagType(row.responseStatus)" effect="plain">
              {{ row.responseStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源指纹" min-width="160">
          <template #default="{ row }">
            <span class="fingerprint">{{ row.sourceFingerprint }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发生时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.occurredAt) }}</template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>暂无匹配的操作记录</strong>
            <span>可调整操作者、路径或日期筛选后重新查询。</span>
          </div>
        </template>
      </el-table>
    </section>
  </section>
</template>

<style scoped>
.audit-page {
  gap: 18px;
}

.audit-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.audit-metrics article,
.audit-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
}

.audit-metrics article {
  padding: 16px 18px;
}

.audit-metrics span {
  color: var(--muted);
  font-size: 13px;
}

.audit-metrics strong {
  display: block;
  margin-top: 7px;
  color: var(--ink);
  font-size: 26px;
  line-height: 1;
}

.audit-metrics .metric-risk {
  border-color: #e6b8b2;
  background: #fff8f6;
}

.metric-risk strong {
  color: #b13a2d;
}

.audit-panel {
  padding: 18px;
  overflow: hidden;
}

.audit-filters {
  display: grid;
  grid-template-columns: repeat(5, minmax(150px, 1fr)) auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 18px;
}

.audit-filters :deep(.el-form-item) {
  margin-bottom: 0;
}

.audit-filters :deep(.el-select),
.audit-filters :deep(.el-date-editor) {
  width: 100%;
}

.filter-actions {
  display: flex;
  gap: 8px;
  padding-bottom: 1px;
}

.actor-cell,
.request-cell {
  display: flex;
  align-items: flex-start;
  gap: 7px;
}

.actor-cell {
  flex-direction: column;
}

.actor-cell span,
.actor-cell small {
  color: var(--muted);
  line-height: 1.3;
}

.request-cell {
  align-items: center;
}

.request-cell code,
.fingerprint {
  overflow-wrap: anywhere;
  font-size: 12px;
}

.fingerprint {
  color: var(--muted);
}

.empty-state {
  display: flex;
  min-height: 180px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--muted);
}

.empty-state strong {
  color: var(--ink);
  font-size: 16px;
}

@media (max-width: 1180px) {
  .audit-filters {
    grid-template-columns: repeat(3, minmax(160px, 1fr));
  }
}

@media (max-width: 760px) {
  .audit-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .audit-filters {
    grid-template-columns: 1fr;
  }

  .filter-actions {
    justify-content: flex-end;
  }
}
</style>
