<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { gradeApi, type StudentGrade } from '@/api/grades'
import { getErrorMessage } from '@/api/http'
import PageHeader from '@/components/PageHeader.vue'
import { formatDateTime } from '@/utils/format'
const rows = ref<StudentGrade[]>([]); const loading = ref(false); const error = ref('')
async function load(): Promise<void> { loading.value = true; error.value = ''; try { rows.value = await gradeApi.getGrades() } catch (e) { error.value = getErrorMessage(e, '成绩加载失败。') } finally { loading.value = false } }
onMounted(load)
</script>
<template><section class="page-stack"><PageHeader kicker="成绩与评价" title="我的课程成绩" description="只显示本人成绩与教师反馈，成绩修改会保留完整历史。"><template #actions><el-button :loading="loading" @click="load">刷新</el-button></template></PageHeader><el-alert v-if="error" :title="error" type="error" show-icon :closable="false"/><section class="entity-panel"><el-table v-loading="loading" :data="rows" table-layout="auto"><el-table-column prop="courseName" label="课程" min-width="160"/><el-table-column prop="teacherName" label="教师"/><el-table-column prop="score" label="成绩"/><el-table-column prop="learningEvaluation" label="教师学习评价" min-width="260"/><el-table-column label="更新时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column></el-table></section></section></template>
