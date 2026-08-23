<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'
import { computed, onMounted, ref } from 'vue'

import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import { organizationApi } from '@/api/organization'
import type { Enrollment, EnrollmentAction, EnrollmentStatus, SchoolClass } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import {
  formatDateTime,
  statusLabel,
  statusTagType,
} from '@/utils/format'

const enrollments = ref<Enrollment[]>([])
const loading = ref(false)
const error = ref('')
const statusFilter = ref<EnrollmentStatus | ''>('')
const keyword = ref('')
const cancelingEnrollmentId = ref<number | null>(null)
const rosterOfferingId = ref<number | null>(null)
const classes = ref<SchoolClass[]>([])
const rosterClassId = ref<number | null>(null)
const actions = ref<EnrollmentAction[]>([])
const actionDialogVisible = ref(false)
const actionLoading = ref(false)

const offeringOptions = computed(() =>
  Array.from(
    new Map(
      enrollments.value.map((item) => [
        item.offeringId,
        { id: item.offeringId, label: `${item.courseName}（${item.offeringCode}）` },
      ]),
    ).values(),
  ),
)

const filteredEnrollments = computed(() => {
  const search = keyword.value.trim().toLocaleLowerCase()
  return enrollments.value.filter((item) => {
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value
    const searchText = [
      item.studentName,
      item.studentNo,
      item.courseName,
      item.offeringCode,
      item.guardianName,
      item.canceledByName,
    ]
      .filter(Boolean)
      .join(' ')
      .toLocaleLowerCase()
    return matchesStatus && (!search || searchText.includes(search))
  })
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [enrollmentRows, classRows] = await Promise.all([
      enrollmentApi.getEnrollments(), organizationApi.getClasses(),
    ])
    enrollments.value = enrollmentRows
    classes.value = classRows
  } catch (loadError) {
    error.value = getErrorMessage(loadError, '报名列表加载失败。')
  } finally {
    loading.value = false
  }
}

async function cancelEnrollment(enrollment: Enrollment): Promise<void> {
  if (
    enrollment.status !== 'ENROLLED' ||
    cancelingEnrollmentId.value !== null
  ) {
    return
  }

  cancelingEnrollmentId.value = enrollment.id
  try {
    await ElMessageBox.confirm(
      `确认取消${enrollment.studentName ? `${enrollment.studentName}的` : ''}${enrollment.courseName ? `“${enrollment.courseName}”` : '该课程'}报名吗？`,
      '管理员取消报名',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '保留报名',
        type: 'warning',
      },
    )
  } catch {
    cancelingEnrollmentId.value = null
    return
  }

  try {
    await enrollmentApi.cancel(enrollment.id)
    ElMessage.success('报名已取消')
    await load()
  } catch (actionError) {
    ElMessage.error(
      getErrorMessage(actionError, '取消报名失败，请重试。'),
    )
  } finally {
    cancelingEnrollmentId.value = null
  }
}

async function showActions(enrollment: Enrollment): Promise<void> {
  actionDialogVisible.value = true
  actionLoading.value = true
  try {
    actions.value = await enrollmentApi.getActions(enrollment.id)
  } catch (loadError) {
    actions.value = []
    ElMessage.error(getErrorMessage(loadError, '报名变更历史加载失败。'))
  } finally {
    actionLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page-stack">
    <PageHeader
      kicker="报名结果"
      title="报名管理"
      description="查看本校学生报名状态、课程归属和取消操作记录。"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
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

    <section class="entity-panel">
      <div class="list-toolbar">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索学生、家长、课程或开班编码"
        />
        <el-select v-model="statusFilter" placeholder="全部状态">
          <el-option label="全部状态" value="" />
          <el-option label="已报名" value="ENROLLED" />
          <el-option label="已取消" value="CANCELED" />
        </el-select>
        <el-select
          v-model="rosterOfferingId"
          clearable
          filterable
          placeholder="选择开班导出名单"
        >
          <el-option
            v-for="offering in offeringOptions"
            :key="offering.id"
            :label="offering.label"
            :value="offering.id"
          />
        </el-select>
        <el-button
          :disabled="rosterOfferingId === null"
          @click="rosterOfferingId && enrollmentApi.downloadRoster(rosterOfferingId)"
        >
          导出选课名单 Excel
        </el-button>
        <el-select v-model="rosterClassId" clearable filterable placeholder="选择行政班导出">
          <el-option v-for="item in classes" :key="item.id" :label="item.className" :value="item.id" />
        </el-select>
        <el-button :disabled="rosterClassId === null" @click="rosterClassId && enrollmentApi.downloadClassRoster(rosterClassId)">
          导出班级名单 Excel
        </el-button>
      </div>
      <el-table
        v-loading="loading"
        :data="filteredEnrollments"
        row-key="id"
        table-layout="auto"
      >
        <el-table-column prop="studentName" label="学生" min-width="120">
          <template #default="{ row }">
            {{ row.studentName || row.studentNo || row.studentId }}
          </template>
        </el-table-column>
        <el-table-column prop="courseName" label="课程" min-width="160">
          <template #default="{ row }">
            {{ row.courseName || row.offeringCode || row.offeringId }}
          </template>
        </el-table-column>
        <el-table-column prop="offeringCode" label="开班编码" min-width="140" />
        <el-table-column prop="guardianName" label="家长" min-width="120" />
        <el-table-column prop="status" label="状态" min-width="100">
          <template #default="{ row }">
            <el-tag effect="plain" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enrolledAt" label="报名时间" min-width="170">
          <template #default="{ row }">
            {{ formatDateTime(row.enrolledAt) }}
          </template>
        </el-table-column>
        <el-table-column label="取消记录" min-width="190">
          <template #default="{ row }">
            <span v-if="row.canceledAt">
              {{ formatDateTime(row.canceledAt) }}
              <template v-if="row.canceledByName">
                · {{ row.canceledByName }}
              </template>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="180" align="right">
          <template #default="{ row }">
            <el-button text @click="showActions(row as Enrollment)">变更历史</el-button>
            <el-button
              v-if="row.status === 'ENROLLED'"
              text
              type="danger"
              :loading="cancelingEnrollmentId === row.id"
              :disabled="cancelingEnrollmentId !== null"
              @click="cancelEnrollment(row as Enrollment)"
            >
              取消报名
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <strong>暂无报名记录</strong>
            <span>调整搜索条件，或等待家长完成课程报名。</span>
          </div>
        </template>
      </el-table>
    </section>

    <el-dialog v-model="actionDialogVisible" title="报名变更历史" width="min(760px, calc(100vw - 32px))">
      <el-table v-loading="actionLoading" :data="actions" table-layout="auto">
        <el-table-column prop="actionType" label="动作" min-width="110">
          <template #default="{ row }">{{ statusLabel(row.actionType) }}</template>
        </el-table-column>
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="actorName" label="操作人" min-width="110" />
        <el-table-column prop="actedAt" label="时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.actedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>
