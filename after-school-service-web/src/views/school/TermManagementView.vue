<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { academicApi, type AcademicTerm, type TermInput, type TermStatus } from '@/api/academic'
import { getErrorMessage } from '@/api/http'
import PageHeader from '@/components/PageHeader.vue'
import { formatDate, statusLabel } from '@/utils/format'

const rows = ref<AcademicTerm[]>([])
const loading = ref(false)
const error = ref('')
const dialog = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<TermInput>({ termCode: '', termName: '', startDate: '', endDate: '', status: 'DRAFT' })
const statuses: TermStatus[] = ['DRAFT', 'ACTIVE', 'CLOSED', 'ARCHIVED']

async function load(): Promise<void> { loading.value = true; error.value=''; try { rows.value = await academicApi.getTerms() } catch (e) { error.value = getErrorMessage(e, '学期加载失败。') } finally { loading.value=false } }
function openCreate(): void { editingId.value=null; Object.assign(form, { termCode:'', termName:'', startDate:'', endDate:'', status:'DRAFT' }); dialog.value=true }
function openEdit(row: AcademicTerm): void { editingId.value=row.id; Object.assign(form, { termCode:row.termCode, termName:row.termName, startDate:row.startDate, endDate:row.endDate, status:row.status }); dialog.value=true }
async function save(): Promise<void> {
  if (!form.termCode.trim() || !form.termName.trim() || !form.startDate || !form.endDate) return
  saving.value=true
  try {
    const payload = { ...form, termCode: form.termCode.trim(), termName: form.termName.trim() }
    if (editingId.value === null) await academicApi.createTerm(payload)
    else await academicApi.updateTerm(editingId.value, payload)
    ElMessage.success(editingId.value === null ? '学期已创建' : '学期已更新'); dialog.value=false; await load()
  } catch (e) { ElMessage.error(getErrorMessage(e, '学期保存失败。')) } finally { saving.value=false }
}
onMounted(load)
</script>
<template><section class="page-stack"><PageHeader kicker="教务基础" title="学期管理" description="教务管理员维护学期起止日期和状态，历史学期关闭后可归档留存。"><template #actions><el-button :loading="loading" @click="load">刷新</el-button><el-button type="primary" @click="openCreate">新建学期</el-button></template></PageHeader><el-alert v-if="error" :title="error" type="error" show-icon :closable="false"/><section class="entity-panel"><el-table :data="rows" table-layout="auto"><el-table-column prop="termCode" label="学期编码"/><el-table-column prop="termName" label="学期名称" min-width="180"/><el-table-column label="起止日期" min-width="220"><template #default="{ row }">{{ formatDate(row.startDate) }}—{{ formatDate(row.endDate) }}</template></el-table-column><el-table-column label="状态"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column><el-table-column label="操作" align="right"><template #default="{ row }"><el-button text type="primary" @click="openEdit(row)">编辑/推进状态</el-button></template></el-table-column></el-table></section><el-dialog v-model="dialog" :title="editingId === null ? '新建学期' : '编辑学期'" width="min(600px, calc(100vw - 32px))"><el-form label-position="top"><el-form-item label="学期编码" required><el-input v-model="form.termCode"/></el-form-item><el-form-item label="学期名称" required><el-input v-model="form.termName"/></el-form-item><div class="form-grid"><el-form-item label="开始日期" required><el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="结束日期" required><el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD"/></el-form-item></div><el-form-item label="状态" required><el-select v-model="form.status" :disabled="editingId === null"><el-option v-for="item in statuses" :key="item" :label="statusLabel(item)" :value="item"/></el-select></el-form-item></el-form><template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template></el-dialog></section></template>
