<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ApiClientError, getErrorMessage } from '@/api/http'
import { organizationApi } from '@/api/organization'
import type {
  School,
  RegulatorAccount,
  RegulatorInput,
  SchoolAdminAccount,
  SchoolAdminInput,
  SchoolInput,
} from '@/api/types'
import EntityCrudPanel from '@/components/EntityCrudPanel.vue'
import type {
  EntityColumn,
  EntityField,
  FormValues,
} from '@/components/entity-crud'
import PageHeader from '@/components/PageHeader.vue'
import { formNullableString, formString } from '@/utils/forms'
import { formatDateTime, statusLabel } from '@/utils/format'

const schools = ref<School[]>([])
const regulators = ref<RegulatorAccount[]>([])
const regulatorLoading = ref(false)
const regulatorError = ref('')
const loading = ref(false)
const error = ref('')
const selectedSchoolId = ref<number | null>(null)
const schoolAdmins = ref<SchoolAdminAccount[]>([])
const adminLoading = ref(false)
const adminError = ref('')
let adminLoadVersion = 0

const selectedSchool = computed(() =>
  schools.value.find((school) => school.id === selectedSchoolId.value),
)

const regulatorColumns: EntityColumn[] = [
  { key: 'username', label: '监管账号', minWidth: 140 },
  { key: 'displayName', label: '姓名', minWidth: 120 },
  { key: 'mobile', label: '手机号', minWidth: 140 },
  { key: 'schoolNames', label: '负责学校', minWidth: 240 },
  {
    key: 'enabled',
    label: '状态',
    minWidth: 90,
    tag: true,
    formatter: (value) => (value === true ? '启用' : '停用'),
  },
]

const regulatorFields = computed<EntityField[]>(() => [
  { key: 'username', label: '登录账号', kind: 'text', required: true },
  { key: 'displayName', label: '姓名', kind: 'text', required: true },
  { key: 'mobile', label: '手机号', kind: 'text' },
  {
    key: 'schoolIds',
    label: '负责学校',
    kind: 'select',
    required: true,
    multiple: true,
    options: schools.value.map((school) => ({
      label: school.schoolName,
      value: school.id,
    })),
    valueFromRow: (value) => Array.isArray(value) ? value as number[] : [],
  },
  {
    key: 'password',
    label: '登录密码',
    kind: 'password',
    requiredOnCreate: true,
    help: '新增时设置 12 至 72 个字符；编辑时留空表示不重置密码。',
  },
  {
    key: 'enabled',
    label: '允许登录',
    kind: 'switch',
    defaultValue: true,
  },
])

const columns: EntityColumn[] = [
  { key: 'schoolCode', label: '学校编码', minWidth: 130 },
  { key: 'schoolName', label: '学校名称', minWidth: 180 },
  { key: 'districtCode', label: '区域编码', minWidth: 130 },
  { key: 'address', label: '地址', minWidth: 220 },
  { key: 'contactPhone', label: '联系电话', minWidth: 140 },
  {
    key: 'status',
    label: '状态',
    minWidth: 90,
    formatter: statusLabel,
  },
]

const fields: EntityField[] = [
  {
    key: 'schoolCode',
    label: '学校编码',
    kind: 'text',
    required: true,
    disabledOnEdit: true,
    help: '学校编码创建后保持不变。',
  },
  { key: 'schoolName', label: '学校名称', kind: 'text', required: true },
  { key: 'districtCode', label: '区域编码', kind: 'text', required: true },
  { key: 'address', label: '学校地址', kind: 'text' },
  { key: 'contactPhone', label: '联系电话', kind: 'text' },
  {
    key: 'status',
    label: '状态',
    kind: 'select',
    required: true,
    defaultValue: 'ACTIVE',
    options: [
      { label: '启用', value: 'ACTIVE' },
      { label: '停用', value: 'INACTIVE' },
    ],
  },
]

const adminColumns: EntityColumn[] = [
  { key: 'username', label: '登录账号', minWidth: 140 },
  { key: 'displayName', label: '姓名', minWidth: 120 },
  { key: 'mobile', label: '手机号', minWidth: 140 },
  {
    key: 'enabled',
    label: '状态',
    minWidth: 90,
    tag: true,
    formatter: (value) => (value === true ? '启用' : '停用'),
  },
  {
    key: 'lastLoginAt',
    label: '最近登录',
    minWidth: 170,
    formatter: (value) =>
      formatDateTime(typeof value === 'string' ? value : null),
  },
]

const adminFields: EntityField[] = [
  { key: 'username', label: '登录账号', kind: 'text', required: true },
  { key: 'displayName', label: '姓名', kind: 'text', required: true },
  { key: 'mobile', label: '手机号', kind: 'text' },
  {
    key: 'password',
    label: '登录密码',
    kind: 'password',
    requiredOnCreate: true,
    help: '新增时设置 12 至 72 个字符；编辑时留空表示不修改密码。',
  },
  {
    key: 'enabled',
    label: '允许登录',
    kind: 'switch',
    defaultValue: true,
  },
]

async function loadSchools(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    schools.value = await organizationApi.getSchools()
    if (
      selectedSchoolId.value === null ||
      !schools.value.some((school) => school.id === selectedSchoolId.value)
    ) {
      selectedSchoolId.value = schools.value[0]?.id ?? null
    }
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '学校列表加载失败。')
  } finally {
    loading.value = false
  }
}

async function loadRegulators(): Promise<void> {
  regulatorLoading.value = true
  regulatorError.value = ''
  try {
    regulators.value = await organizationApi.getRegulators()
  } catch (loadError) {
    regulatorError.value = getErrorMessage(loadError, '监管账号加载失败。')
  } finally {
    regulatorLoading.value = false
  }
}

async function saveRegulator(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const password = formNullableString(values, 'password')
  const schoolIds = Array.isArray(values.schoolIds)
    ? values.schoolIds.map(Number)
    : []
  if (schoolIds.length === 0) {
    throw new ApiClientError('请至少选择一所负责学校。', {
      code: 'SCHOOL_SCOPE_REQUIRED',
      fieldErrors: { schoolIds: '至少选择一所学校' },
    })
  }
  if ((id === null && !password) || (password && password.length < 12)) {
    throw new ApiClientError('登录密码至少需要 12 个字符。', {
      code: 'INVALID_PASSWORD',
      fieldErrors: { password: '请输入至少 12 个字符' },
    })
  }
  const payload: RegulatorInput = {
    username: formString(values, 'username'),
    displayName: formString(values, 'displayName'),
    mobile: formNullableString(values, 'mobile'),
    password,
    enabled: values.enabled === true,
    schoolIds,
  }
  if (id === null) await organizationApi.createRegulator(payload)
  else await organizationApi.updateRegulator({ id, ...payload })
  await loadRegulators()
}

async function saveSchool(values: FormValues, id: number | null): Promise<void> {
  const payload: SchoolInput = {
    schoolCode: formString(values, 'schoolCode'),
    schoolName: formString(values, 'schoolName'),
    districtCode: formString(values, 'districtCode'),
    address: formNullableString(values, 'address'),
    contactPhone: formNullableString(values, 'contactPhone'),
    status: formString(values, 'status') as SchoolInput['status'],
  }
  if (id === null) {
    await organizationApi.createSchool(payload)
  } else {
    await organizationApi.updateSchool({ id, ...payload })
  }
  await loadSchools()
}

async function loadSchoolAdmins(schoolId: number | null): Promise<void> {
  const loadVersion = ++adminLoadVersion
  schoolAdmins.value = []
  adminError.value = ''
  if (schoolId === null) {
    adminLoading.value = false
    return
  }

  adminLoading.value = true
  try {
    const rows = await organizationApi.getSchoolAdmins(schoolId)
    if (loadVersion === adminLoadVersion) {
      schoolAdmins.value = rows
    }
  } catch (loadError) {
    if (loadVersion === adminLoadVersion) {
      adminError.value = getErrorMessage(
        loadError,
        '学校管理员账号加载失败。',
      )
    }
  } finally {
    if (loadVersion === adminLoadVersion) {
      adminLoading.value = false
    }
  }
}

async function saveSchoolAdmin(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const schoolId = selectedSchoolId.value
  if (schoolId === null) {
    throw new ApiClientError('请先选择学校。', {
      code: 'SCHOOL_REQUIRED',
    })
  }

  const password = formNullableString(values, 'password')
  if (
    (id === null && password === null) ||
    (password !== null && (password.length < 12 || password.length > 72))
  ) {
    throw new ApiClientError('登录密码需为 12 至 72 个字符。', {
      code: 'INVALID_PASSWORD',
      fieldErrors: {
        password: '请输入 12 至 72 个字符',
      },
    })
  }

  const payload: SchoolAdminInput = {
    username: formString(values, 'username'),
    displayName: formString(values, 'displayName'),
    mobile: formNullableString(values, 'mobile'),
    password,
    enabled: values.enabled === true,
  }

  if (id === null) {
    await organizationApi.createSchoolAdmin(schoolId, payload)
  } else {
    await organizationApi.updateSchoolAdmin(schoolId, { id, ...payload })
  }
  await loadSchoolAdmins(schoolId)
}

watch(selectedSchoolId, (schoolId) => {
  void loadSchoolAdmins(schoolId)
})

onMounted(async () => {
  await loadSchools()
  await loadRegulators()
})
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="区域基础数据"
      title="学校与管理员"
      description="维护接入学校及其管理账号。学校状态、账号权限和数据范围继续由服务端约束。"
    />
    <EntityCrudPanel
      title="教育监管账号"
      description="由监管人员开户注册、重置密码并分配负责学校；系统不开放公众自助注册。"
      :rows="regulators"
      :columns="regulatorColumns"
      :fields="regulatorFields"
      :loading="regulatorLoading"
      :error="regulatorError"
      :save="saveRegulator"
      @retry="loadRegulators"
    />
    <EntityCrudPanel
      title="学校"
      description="监管账号可维护区域内学校，其他角色无此入口。"
      :rows="schools"
      :columns="columns"
      :fields="fields"
      :loading="loading"
      :error="error"
      :save="saveSchool"
      @retry="loadSchools"
    />
    <section class="student-selector-panel">
      <div>
        <h2>管理员账号所属学校</h2>
        <p>选择一所学校，查看和维护该校的学校管理员账号。</p>
      </div>
      <el-select
        v-model="selectedSchoolId"
        class="student-selector"
        placeholder="请选择学校"
        filterable
        :disabled="schools.length === 0"
        aria-label="管理员账号所属学校"
      >
        <el-option
          v-for="school in schools"
          :key="school.id"
          :label="`${school.schoolName}（${school.schoolCode}）`"
          :value="school.id"
        />
      </el-select>
    </section>
    <EntityCrudPanel
      v-if="selectedSchoolId !== null"
      title="学校管理员账号"
      :description="`维护${selectedSchool?.schoolName ?? '所选学校'}的登录账号、联系信息和启用状态。`"
      :rows="schoolAdmins"
      :columns="adminColumns"
      :fields="adminFields"
      :loading="adminLoading"
      :error="adminError"
      :save="saveSchoolAdmin"
      @retry="loadSchoolAdmins(selectedSchoolId)"
    />
    <div v-else class="empty-state page-empty">
      <strong>暂无可管理学校</strong>
      <span>请先新增一所学校，再为其配置学校管理员账号。</span>
    </div>
  </section>
</template>
