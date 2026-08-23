<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { enrollmentApi } from '@/api/enrollments'
import { gradeApi, type StudentGrade } from '@/api/grades'
import { getErrorMessage } from '@/api/http'
import { leaveCorrectionApi, type GuardianLeaveSession, type LeaveRequest } from '@/api/leaveCorrections'
import type { GuardianAttendance, GuardianMonthlyAttendance, GuardianStudent } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate, formatTime, statusLabel } from '@/utils/format'

const students = ref<GuardianStudent[]>([])
const selectedStudentId = ref<number | null>(null)
const sessions = ref<GuardianLeaveSession[]>([])
const leaves = ref<LeaveRequest[]>([])
const attendance = ref<GuardianAttendance[]>([])
const monthly = ref<GuardianMonthlyAttendance | null>(null)
const grades = ref<StudentGrade[]>([])
const month = ref(new Date().toISOString().slice(0, 7))
const loading = ref(false)
const error = ref('')
const selected = computed(() => students.value.find((item) => item.id === selectedStudentId.value))
const selectedGrades = computed(() => grades.value.filter((item) => item.studentId === selectedStudentId.value))

async function loadBase(): Promise<void> {
  loading.value = true; error.value = ''
  try {
    const [studentRows, gradeRows, leaveRows] = await Promise.all([
      enrollmentApi.getGuardianStudents(), gradeApi.getGrades(), leaveCorrectionApi.getLeaveRequests(),
    ])
    students.value = studentRows; grades.value = gradeRows; leaves.value = leaveRows
    if (!studentRows.some((item) => item.id === selectedStudentId.value)) selectedStudentId.value = studentRows[0]?.id ?? null
    await loadChild()
  } catch (loadError) { error.value = getErrorMessage(loadError, '子女教务信息加载失败。') }
  finally { loading.value = false }
}
async function loadChild(): Promise<void> {
  const id = selectedStudentId.value
  if (!id) { sessions.value = []; attendance.value = []; monthly.value = null; return }
  const [sessionRows, attendanceRows, monthlyRow] = await Promise.all([
    leaveCorrectionApi.getGuardianSessions(id), enrollmentApi.getStudentAttendance(id),
    enrollmentApi.getStudentMonthlyAttendance(id, month.value),
  ])
  if (id !== selectedStudentId.value) return
  sessions.value = sessionRows; attendance.value = attendanceRows; monthly.value = monthlyRow
}
watch(selectedStudentId, () => { void loadChild() })
onMounted(loadBase)
</script>
<template>
  <section class="page-stack">
    <PageHeader kicker="家长服务" title="子女课表、考勤与成绩" description="家长只查看已绑定子女的教务信息，不代替学生执行选课或请假。"><template #actions><el-button :loading="loading" @click="loadBase">刷新</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="entity-panel"><div class="list-toolbar"><el-select v-model="selectedStudentId" placeholder="选择子女"><el-option v-for="item in students" :key="item.id" :label="`${item.fullName} · ${item.className}`" :value="item.id"/></el-select><span v-if="selected">{{ selected.studentNo }}</span></div></section>
    <section class="entity-panel"><div class="section-heading"><div><span class="section-kicker">个人课表</span><h2>未来课次</h2></div></div><el-table :data="sessions" table-layout="auto"><el-table-column label="日期"><template #default="{ row }">{{ formatDate(row.sessionDate) }}</template></el-table-column><el-table-column prop="courseName" label="课程"/><el-table-column label="时间"><template #default="{ row }">{{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}</template></el-table-column><el-table-column prop="classroom" label="教室"/><el-table-column label="请假"><template #default="{ row }">{{ row.activeLeaveStatus ? statusLabel(row.activeLeaveStatus) : '-' }}</template></el-table-column></el-table></section>
    <section class="entity-panel"><div class="section-heading"><div><span class="section-kicker">请假进度</span><h2>教师审核结果</h2></div></div><el-table :data="leaves.filter((item) => item.studentId === selectedStudentId)" table-layout="auto"><el-table-column prop="courseName" label="课程"/><el-table-column label="状态"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column><el-table-column prop="reason" label="原因"/><el-table-column prop="reviewRemark" label="教师意见"/></el-table></section>
    <section class="entity-panel"><div class="list-toolbar"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" @change="loadChild"/><span v-if="monthly">月度出勤率 {{ monthly.summary.attendanceRate || 0 }}%</span></div><el-table :data="attendance" table-layout="auto"><el-table-column prop="courseName" label="课程"/><el-table-column label="日期"><template #default="{ row }">{{ formatDate(row.sessionDate) }}</template></el-table-column><el-table-column label="考勤"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column><el-table-column prop="remark" label="备注"/></el-table></section>
    <section class="entity-panel"><div class="section-heading"><div><span class="section-kicker">课程成绩</span><h2>成绩与教师反馈</h2></div></div><el-table :data="selectedGrades" table-layout="auto"><el-table-column prop="courseName" label="课程"/><el-table-column prop="teacherName" label="教师"/><el-table-column prop="score" label="成绩"/><el-table-column prop="learningEvaluation" label="教师反馈" min-width="260"/></el-table></section>
  </section>
</template>
