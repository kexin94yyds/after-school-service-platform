<script setup lang="ts">
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { computed, reactive, ref, watch } from 'vue'

import { toApiClientError } from '@/api/http'
import type { ApiEntity } from '@/api/types'
import type {
  EntityColumn,
  EntityField,
  FormValue,
  FormValues,
} from '@/components/entity-crud'
import { useLongFormGuard } from '@/composables/useLongFormGuard'
import { asFormValue } from '@/utils/forms'
import {
  nullableText,
  statusLabel,
  statusTagType,
} from '@/utils/format'

const props = withDefaults(
  defineProps<{
    title: string
    description: string
    rows: ApiEntity[]
    columns: EntityColumn[]
    fields: EntityField[]
    loading: boolean
    error: string
    save: (values: FormValues, id: number | null) => Promise<void>
    canCreate?: boolean
    canEdit?: boolean
  }>(),
  {
    canCreate: true,
    canEdit: true,
  },
)

const emit = defineEmits<{
  retry: []
}>()

const query = ref('')
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const dialogError = ref('')
const fieldErrors = ref<Record<string, string>>({})
const form = reactive<FormValues>({})
const currentPage = ref(1)
const pageSize = ref(10)
const {
  beforeClose: beforeDialogClose,
  captureBaseline: captureFormBaseline,
  requestClose: requestDialogClose,
} = useLongFormGuard({
  visible: dialogVisible,
  saving,
  snapshot: () => ({ ...form }),
})

const filteredRows = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  if (!keyword) return props.rows
  return props.rows.filter((row) =>
    props.columns.some((column) =>
      displayCell(row, column).toLocaleLowerCase().includes(keyword),
    ),
  )
})

const paginatedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})

function resetForm(): void {
  for (const key of Object.keys(form)) {
    delete form[key]
  }
  for (const field of props.fields) {
    form[field.key] = field.defaultValue ?? null
  }
  dialogError.value = ''
  fieldErrors.value = {}
}

function openCreate(): void {
  editingId.value = null
  resetForm()
  captureFormBaseline()
  dialogVisible.value = true
}

function openEdit(row: ApiEntity): void {
  editingId.value = row.id
  resetForm()
  for (const field of props.fields) {
    const value = (row as unknown as Record<string, unknown>)[field.key]
    if (value !== undefined) {
      form[field.key] = field.valueFromRow
        ? field.valueFromRow(value, row)
        : asFormValue(value)
    }
  }
  captureFormBaseline()
  dialogVisible.value = true
}

function displayCell(row: ApiEntity, column: EntityColumn): string {
  const value = (row as unknown as Record<string, unknown>)[column.key]
  return column.formatter
    ? column.formatter(value, row)
    : column.key === 'status'
      ? statusLabel(value)
      : nullableText(value)
}

function normalizeFieldErrors(
  errors: Record<string, string | string[]>,
): Record<string, string> {
  return Object.fromEntries(
    Object.entries(errors).map(([key, value]) => [
      key,
      Array.isArray(value) ? value.join('；') : value,
    ]),
  )
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  for (const field of props.fields) {
    const value = form[field.key]
    if (
      (field.required ||
        (editingId.value === null && field.requiredOnCreate)) &&
      (value === null ||
        value === undefined ||
        String(value).trim() === '')
    ) {
      errors[field.key] = `请填写${field.label}`
    }
  }
  fieldErrors.value = errors
  return Object.keys(errors).length === 0
}

async function submit(): Promise<void> {
  if (!validate()) return
  saving.value = true
  dialogError.value = ''
  try {
    await props.save({ ...form }, editingId.value)
    captureFormBaseline()
    dialogVisible.value = false
    ElMessage.success(editingId.value === null ? '新增成功' : '保存成功')
  } catch (error) {
    const apiError = toApiClientError(error, '保存失败，请检查填写内容。')
    dialogError.value = apiError.message
    fieldErrors.value = {
      ...fieldErrors.value,
      ...normalizeFieldErrors(apiError.fieldErrors),
    }
  } finally {
    saving.value = false
  }
}

watch([query, () => props.rows.length], () => {
  currentPage.value = 1
})

watch([pageSize, () => filteredRows.value.length], () => {
  const maxPage = Math.max(
    1,
    Math.ceil(filteredRows.value.length / pageSize.value),
  )
  if (currentPage.value > maxPage) currentPage.value = maxPage
})

function updateField(key: string, value: FormValue): void {
  if (saving.value) return
  form[key] = value
  if (fieldErrors.value[key]) {
    const next = { ...fieldErrors.value }
    delete next[key]
    fieldErrors.value = next
  }
}
</script>

<template>
  <section class="entity-panel">
    <header class="entity-panel-header">
      <div>
        <h2>{{ title }}</h2>
        <p>{{ description }}</p>
      </div>
      <div class="entity-toolbar">
        <el-input
          v-model="query"
          clearable
          class="entity-search"
          placeholder="搜索当前列表"
          aria-label="搜索当前列表"
        />
        <el-button v-if="canCreate" type="primary" @click="openCreate">
          新增
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="error"
      class="page-alert"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button text type="primary" @click="emit('retry')">重新加载</el-button>
      </template>
    </el-alert>

    <el-table
      v-loading="loading"
      :data="paginatedRows"
      row-key="id"
      class="entity-table"
      table-layout="auto"
    >
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :label="column.label"
        :min-width="column.minWidth ?? 120"
      >
        <template #default="{ row }">
          <el-tag
            v-if="column.tag || column.key === 'status'"
            effect="plain"
            size="small"
            :type="
              statusTagType(
                (row as unknown as Record<string, unknown>)[column.key],
              )
            "
          >
            {{ displayCell(row as ApiEntity, column) }}
          </el-tag>
          <span v-else>{{ displayCell(row as ApiEntity, column) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="canEdit" label="操作" width="90" align="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openEdit(row as ApiEntity)">
            编辑
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-state">
          <strong>暂无记录</strong>
          <span>当前服务端授权范围内没有可显示的数据。</span>
        </div>
      </template>
    </el-table>

    <el-pagination
      v-if="filteredRows.length > pageSize"
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="entity-pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50]"
      :total="filteredRows.length"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? `新增${title}` : `编辑${title}`"
      width="min(620px, calc(100vw - 32px))"
      destroy-on-close
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="beforeDialogClose"
    >
      <el-alert
        v-if="dialogError"
        :title="dialogError"
        type="error"
        :closable="false"
        show-icon
        class="dialog-alert"
      />
      <el-form
        label-position="top"
        :disabled="saving"
        @submit.prevent="submit"
      >
        <div class="form-grid">
          <el-form-item
            v-for="field in fields"
            :key="field.key"
            :label="field.label"
            :required="
              field.required ||
              (editingId === null && field.requiredOnCreate)
            "
            :error="fieldErrors[field.key]"
            :class="{ 'form-item-wide': field.kind === 'textarea' }"
          >
            <el-input
              v-if="
                field.kind === 'text' ||
                field.kind === 'password' ||
                field.kind === 'textarea'
              "
              :model-value="form[field.key] as string | null"
              :type="
                field.kind === 'textarea'
                  ? 'textarea'
                  : field.kind === 'password'
                    ? 'password'
                    : 'text'
              "
              :rows="field.kind === 'textarea' ? 4 : undefined"
              :placeholder="field.placeholder"
              :show-password="field.kind === 'password'"
              :disabled="
                saving || (editingId !== null && field.disabledOnEdit)
              "
              :autocomplete="
                field.kind === 'password' ? 'new-password' : undefined
              "
              @update:model-value="updateField(field.key, $event)"
            />
            <el-input-number
              v-else-if="field.kind === 'number'"
              :model-value="form[field.key] as number | null"
              :min="field.min"
              :max="field.max"
              :step="field.step ?? 1"
              controls-position="right"
              @update:model-value="updateField(field.key, $event)"
            />
            <el-select
              v-else-if="field.kind === 'select'"
              :model-value="form[field.key]"
              :placeholder="field.placeholder || `请选择${field.label}`"
              filterable
              :multiple="field.multiple"
              :disabled="
                saving || (editingId !== null && field.disabledOnEdit)
              "
              @update:model-value="
                updateField(field.key, $event as FormValue)
              "
            >
              <el-option
                v-for="option in field.options ?? []"
                :key="String(option.value)"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-switch
              v-else-if="field.kind === 'switch'"
              :model-value="Boolean(form[field.key])"
              @update:model-value="updateField(field.key, $event)"
            />
            <el-date-picker
              v-else-if="field.kind === 'date'"
              :model-value="form[field.key] as string | null"
              type="date"
              value-format="YYYY-MM-DD"
              @update:model-value="updateField(field.key, $event)"
            />
            <el-date-picker
              v-else-if="field.kind === 'datetime'"
              :model-value="form[field.key] as string | null"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              @update:model-value="updateField(field.key, $event)"
            />
            <el-time-picker
              v-else
              :model-value="form[field.key] as string | null"
              value-format="HH:mm:ss"
              format="HH:mm"
              @update:model-value="updateField(field.key, $event)"
            />
            <small v-if="field.help" class="field-help">{{ field.help }}</small>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="requestDialogClose">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
