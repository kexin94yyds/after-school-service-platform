<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { enrollmentApi } from '@/api/enrollments'
import { getErrorMessage } from '@/api/http'
import { leaveCorrectionApi, type GuardianLeaveSession, type LeaveRequest } from '@/api/leaveCorrections'
import type { GuardianAttendance, GuardianMonthlyAttendance, StudentProfile } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate, formatTime, statusLabel } from '@/utils/format'

const profile = ref<StudentProfile | null>(null)
const sessions = ref<GuardianLeaveSession[]>([])
const leaves = ref<LeaveRequest[]>([])
const attendance = ref<GuardianAttendance[]>([])
const monthly = ref<GuardianMonthlyAttendance | null>(null)
const month = ref(new Date().toISOString().slice(0, 7))
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ sessionId: 0, courseName: '', reason: '' })
const activeLeaveIds = computed(() => new Set(leaves.value.filter((item) => ['PENDING', 'APPROVED'].includes(item.status)).map((item) => item.sessionId)))

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try {
    const [student, sessionRows, leaveRows, attendanceRows, monthlyRow] = await Promise.all([
      enrollmentApi.getStudentProfile(), leaveCorrectionApi.getStudentSessions(),
      leaveCorrectionApi.getLeaveRequests(), enrollmentApi.getOwnAttendance(),
      enrollmentApi.getOwnMonthlyAttendance(month.value),
    ])
    profile.value = student; sessions.value = sessionRows; leaves.value = leaveRows
    attendance.value = attendanceRows; monthly.value = monthlyRow
  } catch (loadError) { error.value = getErrorMessage(loadError, '请假与考勤加载失败。') }
  finally { loading.value = false }
}
function openLeave(row: GuardianLeaveSession): void {
  form.sessionId = row.id; form.courseName = row.courseName; form.reason = ''; dialog.value = true
}
async function submitLeave(): Promise<void> {
  if (!profile.value || !form.reason.trim()) return
  saving.value = true
  try {
    await leaveCorrectionApi.submitLeave(form.sessionId, profile.value.id, form.reason.trim())
    ElMessage.success('请假申请已提交'); dialog.value = false; await load()
  } catch (actionError) { ElMessage.error(getErrorMessage(actionError, '请假提交失败。')) }
  finally { saving.value = false }
}
async function withdraw(row: LeaveRequest): Promise<void> {
  try { await leaveCorrectionApi.withdrawLeave(row.id); ElMessage.success('请假已撤回'); await load() }
  catch (actionError) { ElMessage.error(getErrorMessage(actionError, '撤回失败。')) }
}
onMounted(load)
</script>
<template>
  <section class="page-stack">
    <PageHeader kicker="请假与考勤" title="我的课堂记录" description="请假只允许未来课次；教师审核后同步进入考勤，月度记录按实际课次汇总。"><template #actions><el-button :loading="loading" @click="load">刷新</el-button></template></PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="entity-panel">
      <div class="section-heading"><div><span class="section-kicker">未来课次</span><h2>可请假课程</h2></div></div>
      <el-table :data="sessions" table-layout="auto">
        <el-table-column label="日期"><template #default="{ row }">{{ formatDate(row.sessionDate) }}</template></el-table-column>
        <el-table-column prop="courseName" label="课程" min-width="150" />
        <el-table-column label="时间"><template #default="{ row }">{{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}</template></el-table-column>
        <el-table-column prop="classroom" label="教室" />
        <el-table-column label="操作" align="right"><template #default="{ row }"><el-button text type="primary" :disabled="activeLeaveIds.has(row.id)" @click="openLeave(row)">{{ activeLeaveIds.has(row.id) ? '已申请' : '申请请假' }}</el-button></template></el-table-column>
      </el-table>
    </section>
    <section class="entity-panel">
      <div class="section-heading"><div><span class="section-kicker">审批进度</span><h2>请假记录</h2></div></div>
      <el-table :data="leaves" table-layout="auto">
        <el-table-column prop="courseName" label="课程" />
        <el-table-column label="日期"><template #default="{ row }">{{ formatDate(row.sessionDate) }}</template></el-table-column>
        <el-table-column label="状态"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="reviewRemark" label="教师意见" />
        <el-table-column label="操作" align="right"><template #default="{ row }"><el-button v-if="row.status === 'PENDING'" text type="danger" @click="withdraw(row)">撤回</el-button></template></el-table-column>
      </el-table>
    </section>
    <section class="entity-panel">
      <div class="list-toolbar"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" @change="load" /></div>
      <div v-if="monthly" class="metric-grid"><article><span>总课次</span><strong>{{ monthly.summary.totalCount || 0 }}</strong></article><article><span>出勤率</span><strong>{{ monthly.summary.attendanceRate || 0 }}%</strong></article><article><span>迟到</span><strong>{{ monthly.summary.lateCount || 0 }}</strong></article><article><span>缺勤</span><strong>{{ monthly.summary.absentCount || 0 }}</strong></article></div>
      <el-table :data="attendance" table-layout="auto"><el-table-column prop="courseName" label="课程" /><el-table-column label="日期"><template #default="{ row }">{{ formatDate(row.sessionDate) }}</template></el-table-column><el-table-column label="考勤"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column><el-table-column prop="remark" label="备注" /></el-table>
    </section>
    <el-dialog v-model="dialog" title="提交请假" width="min(520px, calc(100vw - 32px))"><p>{{ form.courseName }}</p><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="请填写请假原因" /><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.reason.trim()" @click="submitLeave">提交</el-button></template></el-dialog>
  </section>
</template>
