<script setup lang="ts">
import { computed } from 'vue'

import PageHeader from '@/components/PageHeader.vue'

export interface DashboardMetric {
  label: string
  value: string | number | null
  hint?: string
  featured?: boolean
}

export interface DashboardShortcut {
  title: string
  description: string
  to: string
  action: string
}

export interface DashboardTask {
  title: string
  description: string
  value: string | number | null
  to: string
  tone?: 'default' | 'warning' | 'danger'
}

const props = defineProps<{
  kicker: string
  title: string
  description: string
  metrics: DashboardMetric[]
  tasks?: DashboardTask[]
  shortcuts: DashboardShortcut[]
  loading: boolean
  error: string
}>()

const orderedMetrics = computed(() => [
  ...props.metrics.filter((metric) => metric.featured),
  ...props.metrics.filter((metric) => !metric.featured),
])

function taskCount(task: DashboardTask): number {
  const value = Number(task.value)
  return Number.isFinite(value) ? Math.max(0, value) : 0
}

const orderedTasks = computed(() =>
  (props.tasks ?? [])
    .map((task, index) => ({ task, index }))
    .sort((a, b) => {
      const countDifference = Number(taskCount(b.task) > 0) - Number(taskCount(a.task) > 0)
      if (countDifference !== 0) return countDifference

      if (taskCount(a.task) > 0 && taskCount(b.task) > 0) {
        const priority = { danger: 2, warning: 1, default: 0 }
        const aPriority = priority[a.task.tone ?? 'default']
        const bPriority = priority[b.task.tone ?? 'default']
        if (aPriority !== bPriority) return bPriority - aPriority
      }

      return a.index - b.index
    })
    .map(({ task }) => task),
)

const totalTaskCount = computed(() =>
  (props.tasks ?? []).reduce((total, task) => total + taskCount(task), 0),
)

defineEmits<{
  retry: []
}>()
</script>

<template>
  <section class="dashboard-page">
    <PageHeader :kicker="kicker" :title="title" :description="description">
      <template #actions>
        <el-button :loading="loading" @click="$emit('retry')">刷新数据</el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="error"
      class="page-alert"
      :title="error"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default>
        <el-button text type="primary" @click="$emit('retry')">重新加载</el-button>
      </template>
    </el-alert>

    <div class="metric-grid" aria-label="工作台指标">
      <article
        v-for="metric in orderedMetrics"
        :key="metric.label"
        class="metric-cell"
        :class="{ 'is-featured': metric.featured }"
      >
        <span class="metric-label">{{ metric.label }}</span>
        <el-skeleton v-if="loading" :rows="0" animated>
          <template #template>
            <el-skeleton-item variant="h1" class="metric-skeleton" />
          </template>
        </el-skeleton>
        <strong v-else>{{ metric.value ?? '-' }}</strong>
        <small v-if="metric.hint">{{ metric.hint }}</small>
        <span class="metric-signal" aria-hidden="true">
          <i></i><i></i><i></i>
        </span>
      </article>
    </div>

    <section v-if="tasks?.length" class="dashboard-task-section">
      <div class="task-section-heading">
        <div class="section-heading">
          <h2>当前待办</h2>
          <p>优先处理需要审核、补充或闭环的业务事项。</p>
        </div>
        <span
          class="task-summary"
          :class="{ 'is-clear': !loading && totalTaskCount === 0 }"
        >
          <i aria-hidden="true"></i>
          {{ loading ? '正在更新' : totalTaskCount === 0 ? '当前待办已清' : `${totalTaskCount} 项待处理` }}
        </span>
      </div>
      <div class="dashboard-task-grid">
        <RouterLink
          v-for="task in orderedTasks"
          :key="`${task.to}-${task.title}`"
          :to="task.to"
          class="dashboard-task-card"
          :class="[
            `is-${task.tone ?? 'default'}`,
            {
              'has-items': !loading && taskCount(task) > 0,
              'is-clear': !loading && taskCount(task) === 0,
              'is-pending': loading,
            },
          ]"
        >
          <div class="task-card-copy">
            <span class="task-state">
              {{ loading ? '更新中' : taskCount(task) > 0 ? (task.tone === 'danger' ? '优先处置' : '需处理') : '已清' }}
            </span>
            <h3>{{ task.title }}</h3>
            <p>{{ task.description }}</p>
          </div>
          <div class="task-card-result">
            <span><strong>{{ task.value ?? '-' }}</strong> 项</span>
            <small>{{ taskCount(task) > 0 ? '进入处理' : '查看记录' }} →</small>
          </div>
        </RouterLink>
      </div>
    </section>

    <section class="shortcut-section">
      <div class="section-heading">
        <h2>常用业务</h2>
        <p>页面数据始终以当前服务端会话的授权范围为准。</p>
      </div>
      <div class="shortcut-grid">
        <RouterLink
          v-for="shortcut in shortcuts"
          :key="shortcut.to"
          :to="shortcut.to"
          class="shortcut-card"
        >
          <div>
            <h3>{{ shortcut.title }}</h3>
            <p>{{ shortcut.description }}</p>
          </div>
          <span>{{ shortcut.action }}</span>
        </RouterLink>
      </div>
    </section>
  </section>
</template>
