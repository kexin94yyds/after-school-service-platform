<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { courseApi } from '@/api/courses'
import { gradeApi, type GradeRosterItem, type GradeSummary } from '@/api/grades'
import { getErrorMessage } from '@/api/http'
import type { CourseOffering } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'

const offerings = ref<CourseOffering[]>([])
const selectedOfferingId = ref<number | null>(null)
const roster = ref<GradeRosterItem[]>([])
const summary = ref<GradeSummary[]>([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const saving = ref(false)
const selectedStudent = ref<GradeRosterItem | null>(null)
const form = reactive({ score: 0, learningEvaluation: '' })
const selectedOffering = computed(() => offerings.value.find((item) => item.id === selectedOfferingId.value))

async function load(): Promise<void> {
  loading.value = true; error.value = ''
  try {
    const [offeringRows, summaryRows] = await Promise.all([courseApi.getOfferings(), gradeApi.getSummary()])
    offerings.value = offeringRows; summary.value = summaryRows
    if (!offeringRows.some((item) => item.id === selectedOfferingId.value)) selectedOfferingId.value = offeringRows[0]?.id ?? null
    await loadRoster()
  } catch (loadError) { error.value = getErrorMessage(loadError, '成绩名单加载失败。') }
  finally { loading.value = false }
}
async function loadRoster(): Promise<void> { roster.value = selectedOfferingId.value ? await gradeApi.getRoster(selectedOfferingId.value) : [] }
function edit(row: GradeRosterItem): void { selectedStudent.value = row; form.score = row.score ?? 0; form.learningEvaluation = row.learningEvaluation ?? ''; dialog.value = true }
async function save(): Promise<void> {
  if (!selectedStudent.value || !selectedOfferingId.value) return
  saving.value = true
  try {
    await gradeApi.save({ offeringId: selectedOfferingId.value, studentId: selectedStudent.value.studentId, score: form.score, learningEvaluation: form.learningEvaluation.trim() || null })
    ElMessage.success('成绩与学习评价已保存'); dialog.value = false; await load()
  } catch (actionError) { ElMessage.error(getErrorMessage(actionError, '成绩保存失败。')) }
  finally { saving.value = false }
}
watch(selectedOfferingId, () => { void loadRoster() })
onMounted(load)
</script>
<template><section class="page-stack"><PageHeader kicker="成绩与评价" title="学生成绩录入" description="教师只能维护本人课程，成绩采用 0–100 分，任何修改都会追加不可变历史。"><template #actions><el-button :loading="loading" @click="load">刷新</el-button></template></PageHeader><el-alert v-if="error" :title="error" type="error" show-icon :closable="false"/><section class="entity-panel"><div class="list-toolbar"><el-select v-model="selectedOfferingId" filterable placeholder="选择授课课程"><el-option v-for="item in offerings" :key="item.id" :label="`${item.courseName} · ${item.offeringCode}`" :value="item.id"/></el-select><span v-if="selectedOffering">{{ selectedOffering.teacherName }} · {{ selectedOffering.classroom }}</span></div><el-table :data="roster" table-layout="auto"><el-table-column prop="studentNo" label="学号"/><el-table-column prop="studentName" label="学生"/><el-table-column prop="className" label="班级"/><el-table-column prop="score" label="成绩"><template #default="{ row }">{{ row.score ?? '未录入' }}</template></el-table-column><el-table-column prop="learningEvaluation" label="学习评价" min-width="260"/><el-table-column label="操作" align="right"><template #default="{ row }"><el-button text type="primary" @click="edit(row)">{{ row.gradeId ? '修改' : '录入' }}</el-button></template></el-table-column></el-table></section><section class="entity-panel"><div class="section-heading"><div><span class="section-kicker">课程汇总</span><h2>成绩分布</h2></div></div><el-table :data="summary" table-layout="auto"><el-table-column prop="courseName" label="课程"/><el-table-column prop="gradedCount" label="已评分"/><el-table-column prop="averageScore" label="平均分"/><el-table-column prop="excellentCount" label="优秀"/><el-table-column prop="goodCount" label="良好"/><el-table-column prop="passCount" label="及格"/><el-table-column prop="needsSupportCount" label="待提升"/></el-table></section><el-dialog v-model="dialog" :title="`成绩录入 · ${selectedStudent?.studentName ?? ''}`" width="min(560px, calc(100vw - 32px))"><el-form label-position="top"><el-form-item label="成绩（0–100）"><el-input-number v-model="form.score" :min="0" :max="100" :precision="2" style="width:100%"/></el-form-item><el-form-item label="学习评价"><el-input v-model="form.learningEvaluation" type="textarea" :rows="5" maxlength="1000" show-word-limit/></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog></section></template>
