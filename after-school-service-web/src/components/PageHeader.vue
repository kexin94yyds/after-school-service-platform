<script setup lang="ts">
defineProps<{
  title: string
  description: string
  kicker?: string
}>()
</script>

<template>
  <header class="page-header">
    <div>
      <span v-if="kicker" class="page-kicker">{{ kicker }}</span>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>
