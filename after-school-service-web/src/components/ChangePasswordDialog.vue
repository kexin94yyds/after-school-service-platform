<script setup lang="ts">
import { reactive, ref, watch } from 'vue'

import { changePassword } from '@/api/auth'
import { toApiClientError } from '@/api/http'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const saving = ref(false)
const dialogError = ref('')
const fieldErrors = ref<Record<string, string>>({})
const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

function reset(): void {
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  dialogError.value = ''
  fieldErrors.value = {}
}

function close(): void {
  if (!saving.value) emit('update:modelValue', false)
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  if (!form.currentPassword) {
    errors.currentPassword = '请输入当前密码'
  }
  if (form.newPassword.length < 12) {
    errors.newPassword = '新密码至少需要 12 个字符'
  }
  if (!form.confirmPassword) {
    errors.confirmPassword = '请再次输入新密码'
  } else if (form.confirmPassword !== form.newPassword) {
    errors.confirmPassword = '两次输入的新密码不一致'
  }
  fieldErrors.value = errors
  return Object.keys(errors).length === 0
}

function normalizeFieldErrors(
  errors: Record<string, string | string[]>,
): Record<string, string> {
  return Object.fromEntries(
    Object.entries(errors).map(([key, value]) => [
      key,
      Array.isArray(value) ? value.join('；') : value,
    ]),
  )
}

async function submit(): Promise<void> {
  dialogError.value = ''
  if (!validate()) return
  saving.value = true
  try {
    await changePassword(form.currentPassword, form.newPassword)
    emit('update:modelValue', false)
    emit('success')
    reset()
  } catch (error) {
    const apiError = toApiClientError(error, '密码修改失败，请重试。')
    dialogError.value = apiError.message
    fieldErrors.value = {
      ...fieldErrors.value,
      ...normalizeFieldErrors(apiError.fieldErrors),
    }
  } finally {
    saving.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) reset()
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="修改密码"
    width="min(500px, calc(100vw - 32px))"
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="dialogError"
      class="dialog-alert"
      :title="dialogError"
      type="error"
      show-icon
      :closable="false"
    />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item
        label="当前密码"
        required
        :error="fieldErrors.currentPassword"
      >
        <el-input
          v-model="form.currentPassword"
          type="password"
          autocomplete="current-password"
          show-password
          placeholder="请输入当前密码"
        />
      </el-form-item>
      <el-form-item
        label="新密码"
        required
        :error="fieldErrors.newPassword"
      >
        <el-input
          v-model="form.newPassword"
          type="password"
          autocomplete="new-password"
          show-password
          placeholder="请输入至少 12 个字符"
        />
        <small class="field-help">
          建议组合使用大小写字母、数字和符号。
        </small>
      </el-form-item>
      <el-form-item
        label="确认新密码"
        required
        :error="fieldErrors.confirmPassword"
      >
        <el-input
          v-model="form.confirmPassword"
          type="password"
          autocomplete="new-password"
          show-password
          placeholder="请再次输入新密码"
          @keyup.enter="submit"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">
        确认修改
      </el-button>
    </template>
  </el-dialog>
</template>
