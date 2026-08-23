<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import { ApiClientError, getErrorMessage } from '@/api/http'
import { organizationApi } from '@/api/organization'
import type {
  Guardian,
  GuardianInput,
  SchoolClass,
  SchoolClassInput,
  Student,
  StudentInput,
  Teacher,
  TeacherInput,
} from '@/api/types'
import EntityCrudPanel from '@/components/EntityCrudPanel.vue'
import type {
  EntityColumn,
  EntityField,
  FormValues,
} from '@/components/entity-crud'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'
import {
  formNullableString,
  formNumber,
  formNumberArray,
  formString,
} from '@/utils/forms'
import { formatDate, statusLabel } from '@/utils/format'

type ResourceKey = 'classes' | 'teachers' | 'students' | 'guardians'

const session = useSessionStore()
const activeTab = ref<ResourceKey>('classes')
const classes = ref<SchoolClass[]>([])
const teachers = ref<Teacher[]>([])
const students = ref<Student[]>([])
const guardians = ref<Guardian[]>([])
const loading = reactive<Record<ResourceKey, boolean>>({
  classes: false,
  teachers: false,
  students: false,
  guardians: false,
})
const errors = reactive<Record<ResourceKey, string>>({
  classes: '',
  teachers: '',
  students: '',
  guardians: '',
})

const statusOptions = [
  { label: '启用', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
]

const classColumns: EntityColumn[] = [
  { key: 'className', label: '班级名称', minWidth: 150 },
  { key: 'grade', label: '年级', minWidth: 80 },
  { key: 'schoolYear', label: '学年', minWidth: 120 },
  { key: 'status', label: '状态', formatter: statusLabel, minWidth: 90 },
]
const classFields: EntityField[] = [
  { key: 'className', label: '班级名称', kind: 'text', required: true },
  {
    key: 'grade',
    label: '年级',
    kind: 'number',
    required: true,
    min: 1,
    max: 12,
  },
  {
    key: 'schoolYear',
    label: '学年',
    kind: 'text',
    required: true,
    placeholder: '例如 2026-2027',
  },
  {
    key: 'status',
    label: '状态',
    kind: 'select',
    required: true,
    defaultValue: 'ACTIVE',
    options: statusOptions,
  },
]

const teacherColumns: EntityColumn[] = [
  { key: 'teacherNo', label: '教师工号', minWidth: 120 },
  { key: 'fullName', label: '姓名', minWidth: 110 },
  { key: 'username', label: '登录账号', minWidth: 130 },
  { key: 'title', label: '职称', minWidth: 120 },
  { key: 'phone', label: '联系电话', minWidth: 140 },
  { key: 'status', label: '状态', formatter: statusLabel, minWidth: 90 },
]
const teacherFields: EntityField[] = [
  {
    key: 'username',
    label: '登录账号',
    kind: 'text',
    required: true,
    disabledOnEdit: true,
    help: '新增时创建教师登录账号；编辑时账号名称保持不变。',
  },
  {
    key: 'password',
    label: '登录密码',
    kind: 'password',
    requiredOnCreate: true,
    help: '新增时至少 12 个字符；编辑时留空表示不修改密码。',
  },
  { key: 'teacherNo', label: '教师工号', kind: 'text', required: true },
  { key: 'fullName', label: '姓名', kind: 'text', required: true },
  { key: 'phone', label: '联系电话', kind: 'text' },
  { key: 'title', label: '职称', kind: 'text' },
  {
    key: 'status',
    label: '状态',
    kind: 'select',
    required: true,
    defaultValue: 'ACTIVE',
    options: statusOptions,
  },
]

const studentColumns: EntityColumn[] = [
  { key: 'studentNo', label: '学号', minWidth: 120 },
  { key: 'username', label: '登录账号', minWidth: 140 },
  { key: 'fullName', label: '姓名', minWidth: 110 },
  {
    key: 'classId',
    label: '班级',
    minWidth: 150,
    formatter: (value) =>
      classes.value.find((item) => item.id === Number(value))?.className ??
      String(value ?? '-'),
  },
  { key: 'gender', label: '性别', formatter: statusLabel, minWidth: 80 },
  {
    key: 'dateOfBirth',
    label: '出生日期',
    formatter: (value) => formatDate(typeof value === 'string' ? value : null),
    minWidth: 120,
  },
  { key: 'status', label: '状态', formatter: statusLabel, minWidth: 90 },
]
const studentFields = computed<EntityField[]>(() => [
  {
    key: 'classId',
    label: '所属班级',
    kind: 'select',
    required: true,
    options: classes.value.map((item) => ({
      label: `${item.className}（${item.schoolYear}）`,
      value: item.id,
    })),
  },
  { key: 'studentNo', label: '学号', kind: 'text', required: true },
  { key: 'fullName', label: '姓名', kind: 'text', required: true },
  { key: 'username', label: '登录账号', kind: 'text', required: true },
  {
    key: 'password',
    label: '登录密码',
    kind: 'password',
    requiredOnCreate: true,
    minLength: 12,
    placeholder: '新增必填；编辑留空则不修改',
  },
  {
    key: 'gender',
    label: '性别',
    kind: 'select',
    options: [
      { label: '男', value: 'MALE' },
      { label: '女', value: 'FEMALE' },
      { label: '其他', value: 'OTHER' },
    ],
  },
  { key: 'dateOfBirth', label: '出生日期', kind: 'date' },
  {
    key: 'status',
    label: '状态',
    kind: 'select',
    required: true,
    defaultValue: 'ACTIVE',
    options: statusOptions,
  },
])

const guardianColumns: EntityColumn[] = [
  { key: 'fullName', label: '家长姓名', minWidth: 120 },
  { key: 'mobile', label: '手机号', minWidth: 150 },
  { key: 'username', label: '登录账号', minWidth: 130 },
  { key: 'childNames', label: '绑定学生', minWidth: 170 },
  { key: 'status', label: '状态', formatter: statusLabel, minWidth: 90 },
]
const guardianFields = computed<EntityField[]>(() => [
  {
    key: 'username',
    label: '登录账号',
    kind: 'text',
    required: true,
    disabledOnEdit: true,
    help: '新增时创建家长登录账号；编辑时账号名称保持不变。',
  },
  {
    key: 'password',
    label: '登录密码',
    kind: 'password',
    requiredOnCreate: true,
    help: '新增时至少 12 个字符；编辑时留空表示不修改密码。',
  },
  { key: 'fullName', label: '家长姓名', kind: 'text', required: true },
  { key: 'mobile', label: '手机号', kind: 'text', required: true },
  {
    key: 'studentIds',
    label: '绑定学生',
    kind: 'select',
    required: true,
    multiple: true,
    options: students.value.map((item) => ({
      label: `${item.fullName}（${item.studentNo}）`,
      value: item.id,
    })),
    valueFromRow: (value) => {
      if (
        Array.isArray(value) &&
        value.every((item) => typeof item === 'number')
      ) {
        return value
      }
      if (typeof value !== 'string' || !value.trim()) return []
      return value
        .split(',')
        .map((item) => Number(item))
        .filter(Number.isFinite)
    },
  },
  {
    key: 'relationship',
    label: '与学生关系',
    kind: 'text',
    required: true,
    placeholder: '例如父亲、母亲或其他监护人',
    help: '同一次保存中的绑定学生使用相同关系。',
  },
  {
    key: 'primary',
    label: '设为主要监护人',
    kind: 'switch',
    defaultValue: true,
    help: '开启后，所选列表中的第一位学生会标记该家长为主要监护人。',
  },
  {
    key: 'status',
    label: '状态',
    kind: 'select',
    required: true,
    defaultValue: 'ACTIVE',
    options: statusOptions,
  },
])

function schoolId(): number {
  const id = session.user?.schoolId
  if (!id) {
    throw new ApiClientError('当前教务管理员账号未绑定学校。', {
      code: 'SCHOOL_CONTEXT_REQUIRED',
    })
  }
  return id
}

async function loadResource(key: ResourceKey): Promise<void> {
  loading[key] = true
  errors[key] = ''
  try {
    if (key === 'classes') classes.value = await organizationApi.getClasses()
    if (key === 'teachers') teachers.value = await organizationApi.getTeachers()
    if (key === 'students') students.value = await organizationApi.getStudents()
    if (key === 'guardians') guardians.value = await organizationApi.getGuardians()
  } catch (error) {
    errors[key] = getErrorMessage(error, '数据加载失败。')
  } finally {
    loading[key] = false
  }
}

async function saveClass(values: FormValues, id: number | null): Promise<void> {
  const payload: SchoolClassInput = {
    schoolId: schoolId(),
    className: formString(values, 'className'),
    grade: formNumber(values, 'grade'),
    schoolYear: formString(values, 'schoolYear'),
    status: formString(values, 'status') as SchoolClassInput['status'],
  }
  if (id === null) await organizationApi.createClass(payload)
  else await organizationApi.updateClass({ id, ...payload })
  await loadResource('classes')
}

async function saveTeacher(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const password = formNullableString(values, 'password')
  if ((id === null && !password) || (password && password.length < 12)) {
    throw new ApiClientError('登录密码至少需要 12 个字符。', {
      fieldErrors: { password: '请输入至少 12 个字符' },
    })
  }
  const payload: TeacherInput = {
    schoolId: schoolId(),
    username: formString(values, 'username'),
    password,
    teacherNo: formString(values, 'teacherNo'),
    fullName: formString(values, 'fullName'),
    phone: formNullableString(values, 'phone'),
    title: formNullableString(values, 'title'),
    status: formString(values, 'status') as TeacherInput['status'],
  }
  if (id === null) await organizationApi.createTeacher(payload)
  else await organizationApi.updateTeacher({ id, ...payload })
  await loadResource('teachers')
}

async function saveStudent(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const gender = formString(values, 'gender')
  const password = formNullableString(values, 'password')
  if ((id === null && !password) || (password && password.length < 12)) {
    throw new ApiClientError('登录密码至少需要 12 个字符。', {
      fieldErrors: { password: '请输入至少 12 个字符' },
    })
  }
  const payload: StudentInput = {
    schoolId: schoolId(),
    classId: formNumber(values, 'classId'),
    studentNo: formString(values, 'studentNo'),
    fullName: formString(values, 'fullName'),
    username: formString(values, 'username'),
    password,
    gender: gender ? (gender as StudentInput['gender']) : null,
    dateOfBirth: formNullableString(values, 'dateOfBirth'),
    status: formString(values, 'status') as StudentInput['status'],
  }
  if (id === null) await organizationApi.createStudent(payload)
  else await organizationApi.updateStudent({ id, ...payload })
  await loadResource('students')
}

async function saveGuardian(
  values: FormValues,
  id: number | null,
): Promise<void> {
  const password = formNullableString(values, 'password')
  if ((id === null && !password) || (password && password.length < 12)) {
    throw new ApiClientError('登录密码至少需要 12 个字符。', {
      fieldErrors: { password: '请输入至少 12 个字符' },
    })
  }
  const payload: GuardianInput = {
    schoolId: schoolId(),
    username: formString(values, 'username'),
    password,
    fullName: formString(values, 'fullName'),
    mobile: formString(values, 'mobile'),
    studentIds: formNumberArray(values, 'studentIds'),
    relationship: formString(values, 'relationship'),
    primary: values.primary === true,
    status: formString(values, 'status') as GuardianInput['status'],
  }
  if (id === null) await organizationApi.createGuardian(payload)
  else await organizationApi.updateGuardian({ id, ...payload })
  await loadResource('guardians')
}

onMounted(() => {
  void Promise.all([
    loadResource('classes'),
    loadResource('teachers'),
    loadResource('students'),
    loadResource('guardians'),
  ])
})
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="本校基础数据"
      title="组织与人员"
      description="集中维护班级、教师、学生和家长档案。所有查询与写入都由服务端限定在当前学校。"
    />

    <el-tabs v-model="activeTab" class="business-tabs">
      <el-tab-pane label="班级" name="classes">
        <EntityCrudPanel
          title="班级"
          description="维护学年、年级和行政班名称。"
          :rows="classes"
          :columns="classColumns"
          :fields="classFields"
          :loading="loading.classes"
          :error="errors.classes"
          :save="saveClass"
          @retry="loadResource('classes')"
        />
      </el-tab-pane>
      <el-tab-pane label="教师" name="teachers">
        <EntityCrudPanel
          title="教师"
          description="新增教师时同步创建登录账号，编辑时可选择重置密码。"
          :rows="teachers"
          :columns="teacherColumns"
          :fields="teacherFields"
          :loading="loading.teachers"
          :error="errors.teachers"
          :save="saveTeacher"
          @retry="loadResource('teachers')"
        />
      </el-tab-pane>
      <el-tab-pane label="学生" name="students">
        <EntityCrudPanel
          title="学生"
          description="维护学生所属班级和基础身份信息。"
          :rows="students"
          :columns="studentColumns"
          :fields="studentFields"
          :loading="loading.students"
          :error="errors.students"
          :save="saveStudent"
          @retry="loadResource('students')"
        />
      </el-tab-pane>
      <el-tab-pane label="家长" name="guardians">
        <EntityCrudPanel
          title="家长"
          description="维护家长登录账号，并建立当前学校内的学生监护关系。"
          :rows="guardians"
          :columns="guardianColumns"
          :fields="guardianFields"
          :loading="loading.guardians"
          :error="errors.guardians"
          :save="saveGuardian"
          @retry="loadResource('guardians')"
        />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
