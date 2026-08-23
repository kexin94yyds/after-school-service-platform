<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import type { StudentScheduleItem } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate, formatTime, statusLabel } from '@/utils/format'

const rows = ref<StudentScheduleItem[]>([])
const loading = ref(false)
const error = ref('')
const upcoming = computed(() => rows.value.filter((item) => item.sessionDate >= new Date().toISOString().slice(0, 10)))
async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try { rows.value = await enrollmentApi.getOwnSchedule() }
  catch (loadError) { error.value = getErrorMessage(loadError, '个人课表加载失败。') }
  finally { loading.value = false }
}
onMounted(load)
</script>
<template>
  <section class="page-stack">
    <PageHeader kicker="个人课表" title="我的实际课次" description="课表来自教务排课与调课后的真实课次，不使用过期的重复周模板。"><template #actions><el-button :loading="loading" @click="load">刷新</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="entity-panel">
      <el-table v-loading="loading" :data="upcoming" table-layout="auto">
        <el-table-column label="日期" min-width="120"><template #default="{ row }">{{ formatDate(row.sessionDate) }}</template></el-table-column>
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column prop="teacherName" label="教师" min-width="100" />
        <el-table-column label="时间" min-width="140"><template #default="{ row }">{{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}</template></el-table-column>
        <el-table-column prop="classroom" label="教室" min-width="120" />
        <el-table-column label="状态" min-width="100"><template #default="{ row }">{{ row.leaveStatus ? statusLabel(row.leaveStatus) : statusLabel(row.status) }}</template></el-table-column>
      </el-table>
    </section>
  </section>
</template>
