<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { toApiClientError } from '@/api/http'
import type { RoleCode } from '@/api/types'
import { roleHomePaths } from '@/router'
import { useSessionStore } from '@/stores/session'

interface LoginForm {
  username: string
  password: string
}

const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const loginError = ref('')
const fieldErrors = ref<Record<string, string>>({})
const form = reactive<LoginForm>({ username: '', password: '' })

const rules: FormRules<LoginForm> = {
  username: [
    { required: true, message: '请输入账号', trigger: ['blur', 'change'] },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: ['blur', 'change'] },
  ],
}

function safeRedirect(role: RoleCode): string {
  const candidate =
    typeof route.query.redirect === 'string' ? route.query.redirect : ''
  if (!candidate.startsWith('/') || candidate.startsWith('//')) {
    return roleHomePaths[role]
  }
  const resolved = router.resolve(candidate)
  const allowedRoles = resolved.meta.roles
  return !allowedRoles || allowedRoles.includes(role)
    ? resolved.fullPath
    : roleHomePaths[role]
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
  if (submitting.value) return
  submitting.value = true
  loginError.value = ''
  fieldErrors.value = {}
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return
    const user = await session.signIn(form.username.trim(), form.password)
    await router.replace(safeRedirect(user.role))
  } catch (error) {
    const apiError = toApiClientError(error, '登录失败，请核对账号和密码。')
    loginError.value = apiError.message
    fieldErrors.value = normalizeFieldErrors(apiError.fieldErrors)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-intro" aria-labelledby="platform-title">
      <div class="intro-brand">
        <span class="brand-mark">课</span>
        <span>中小学课后服务</span>
      </div>
      <div>
        <p class="intro-label">计划、选课、教学与监管统一协作</p>
        <h1 id="platform-title">让每项课后服务，都可执行、可追溯</h1>
        <p class="intro-copy">
          从计划备案、课程排班到请假考勤、整改评价，四类角色在同一业务链上协同完成。
        </p>
        <figure class="classroom-visual">
          <img
            src="/images/after-school-classroom.jpg"
            alt="老师带领小学生开展显微镜科学实践课"
          />
          <figcaption>
            <span>今日课后服务 · 15:30—17:30</span>
            <strong>兴趣成长，也有序可管</strong>
            <div class="classroom-tags" aria-label="课后服务课程示例">
              <span>艺术实践</span>
              <span>科学探索</span>
              <span>活力运动</span>
            </div>
          </figcaption>
        </figure>
      </div>
      <div class="intro-capabilities" aria-label="平台能力">
        <span>计划备案</span>
        <span>教务执行</span>
        <span>家校服务</span>
        <span>监管闭环</span>
      </div>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <header>
          <span class="page-kicker">安全会话登录</span>
          <h2>登录工作台</h2>
          <p>系统将根据账号的服务端角色进入对应工作台。</p>
        </header>

        <el-alert
          v-if="loginError"
          class="login-alert"
          :title="loginError"
          type="error"
          show-icon
          :closable="false"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item
            label="账号"
            prop="username"
            :error="fieldErrors.username"
          >
            <el-input
              v-model="form.username"
              :disabled="submitting"
              autocomplete="username"
              placeholder="请输入登录账号"
              autofocus
            />
          </el-form-item>
          <el-form-item
            label="密码"
            prop="password"
            :error="fieldErrors.password"
          >
            <el-input
              v-model="form.password"
              :disabled="submitting"
              type="password"
              autocomplete="current-password"
              placeholder="请输入登录密码"
              show-password
            />
          </el-form-item>
          <el-button
            class="login-submit"
            type="primary"
            native-type="submit"
            :loading="submitting"
          >
            登录
          </el-button>
        </el-form>

        <p class="security-note">
          请使用学校或监管部门分配的账号登录，离开公共设备前请退出系统。
        </p>
      </div>
    </section>
  </main>
</template>
