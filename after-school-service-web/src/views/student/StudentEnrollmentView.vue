<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import type { Enrollment, GuardianOffering, StudentProfile } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import { formatDateTime, formatTime, weekdayLabel } from '@/utils/format'

const profile = ref<StudentProfile | null>(null)
const offerings = ref<GuardianOffering[]>([])
const enrollments = ref<Enrollment[]>([])
const keyword = ref('')
const category = ref('')
const loading = ref(false)
const actingId = ref<number | null>(null)
const switchDialog = ref(false)
const switching = ref<Enrollment | null>(null)
const switchTargetId = ref<number | null>(null)
const error = ref('')

const categories = computed(() => [...new Set(offerings.value.map((item) => item.category))])
const activeEnrollments = computed(() => enrollments.value.filter((item) => item.status === 'ENROLLED'))
const filteredOfferings = computed(() => offerings.value.filter((item) => {
  const text = `${item.courseName}${item.teacherName}${item.category}`.toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase()))
    && (!category.value || item.category === category.value)
}))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [student, offeringRows, enrollmentRows] = await Promise.all([
      enrollmentApi.getStudentProfile(),
      enrollmentApi.getOwnOfferings(),
      enrollmentApi.getEnrollments(),
    ])
    profile.value = student
    offerings.value = offeringRows
    enrollments.value = enrollmentRows
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '选课信息加载失败。')
  } finally {
    loading.value = false
  }
}

async function enroll(offering: GuardianOffering): Promise<void> {
  if (!profile.value || !offering.canEnroll) return
  actingId.value = offering.id
  try {
    await enrollmentApi.enroll(profile.value.id, offering.id)
    ElMessage.success('选课成功')
    await load()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '选课失败。'))
  } finally {
    actingId.value = null
  }
}

async function cancel(row: Enrollment): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认退选“${row.courseName}”吗？`, '退选确认', {
      confirmButtonText: '确认退选', cancelButtonText: '保留课程', type: 'warning',
    })
  } catch { return }
  actingId.value = row.id
  try {
    await enrollmentApi.cancel(row.id)
    ElMessage.success('退选成功')
    await load()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '退选失败。'))
  } finally { actingId.value = null }
}

function openSwitch(row: Enrollment): void {
  switching.value = row
  switchTargetId.value = null
  switchDialog.value = true
}

async function confirmSwitch(): Promise<void> {
  if (!switching.value || !switchTargetId.value) return
  actingId.value = switching.value.id
  try {
    await enrollmentApi.switchEnrollment(switching.value.id, switchTargetId.value)
    ElMessage.success('改选成功，原课程与新课程已原子更新')
    switchDialog.value = false
    await load()
  } catch (actionError) {
    ElMessage.error(getErrorMessage(actionError, '改选失败。'))
  } finally { actingId.value = null }
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <PageHeader kicker="学生选课" title="选择我的课后课程" description="系统按年级、报名时间、容量与课表冲突实时校验，满额课程自动停止报名。">
      <template #actions><el-button :loading="loading" @click="load">刷新</el-button></template>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="entity-panel">
      <div class="list-toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索课程、类别或教师" />
        <el-select v-model="category" clearable placeholder="全部课程类别">
          <el-option v-for="item in categories" :key="item" :label="item" :value="item" />
        </el-select>
      </div>
      <el-table v-loading="loading" :data="filteredOfferings" table-layout="auto">
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="category" label="类别" min-width="110" />
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">周{{ weekdayLabel(row.weekDay) }} {{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="teacherName" label="教师" min-width="100" />
        <el-table-column label="名额" min-width="100">
          <template #default="{ row }">{{ row.enrolledCount }}/{{ row.capacity }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="180">
          <template #default="{ row }">{{ row.eligibilityMessage }}</template>
        </el-table-column>
        <el-table-column label="操作" align="right" min-width="110">
          <template #default="{ row }">
            <el-button type="primary" text :disabled="!row.canEnroll" :loading="actingId === row.id" @click="enroll(row)">选课</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <section class="entity-panel">
      <div class="section-heading"><div><span class="section-kicker">我的选课</span><h2>已选课程</h2></div></div>
      <el-table :data="activeEnrollments" table-layout="auto">
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="teacherName" label="教师" min-width="100" />
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">周{{ weekdayLabel(row.weekDay) }} {{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="选课时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.enrolledAt) }}</template></el-table-column>
        <el-table-column label="操作" align="right" min-width="160">
          <template #default="{ row }">
            <el-button text @click="openSwitch(row)">改选</el-button>
            <el-button text type="danger" :loading="actingId === row.id" @click="cancel(row)">退选</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <el-dialog v-model="switchDialog" title="改选课程" width="min(520px, calc(100vw - 32px))">
      <el-select v-model="switchTargetId" filterable placeholder="选择目标课程" style="width: 100%">
        <el-option v-for="item in offerings.filter((row) => row.canEnroll && row.id !== switching?.offeringId)" :key="item.id" :label="`${item.courseName} · ${item.teacherName}`" :value="item.id" />
      </el-select>
      <template #footer><el-button @click="switchDialog = false">取消</el-button><el-button type="primary" :disabled="!switchTargetId" @click="confirmSwitch">确认改选</el-button></template>
    </el-dialog>
  </section>
</template>
