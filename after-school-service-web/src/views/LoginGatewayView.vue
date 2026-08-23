<script setup lang="ts">
const entries = [
  {
    title: '学生',
    mark: '学',
    description: '选课、课表、请假、考勤与成绩',
    to: '/login/student',
  },
  {
    title: '家长',
    mark: '家',
    description: '查看子女课表、考勤、成绩并评价课程',
    to: '/login/parent',
  },
  {
    title: '教师',
    mark: '师',
    description: '授课任务、请假审核、考勤与成绩录入',
    to: '/login/teacher',
  },
  {
    title: '教务管理员',
    mark: '教',
    description: '课程、开课计划、排课与教务统计',
    to: '/login/admin',
  },
]
</script>

<template>
  <main class="login-gateway">
    <section class="login-gateway-card" aria-labelledby="gateway-title">
      <span class="page-kicker">中小学课后服务</span>
      <h1 id="gateway-title">选择登录身份</h1>
      <p>四类角色使用独立登录页面，系统会在服务端核对账号身份。</p>
      <div class="login-role-grid">
        <RouterLink
          v-for="entry in entries"
          :key="entry.to"
          class="login-role-entry"
          :to="entry.to"
        >
          <span class="login-role-mark">{{ entry.mark }}</span>
          <span>
            <strong>{{ entry.title }}</strong>
            <small>{{ entry.description }}</small>
          </span>
        </RouterLink>
      </div>
    </section>
  </main>
</template>
