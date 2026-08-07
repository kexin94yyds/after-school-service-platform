<script setup lang="ts">
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { init, use } from 'echarts/core'
import type { EChartsType } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  title: string
  labels: string[]
  values: number[]
  valueLabel: string
}>()

const chartElement = ref<HTMLDivElement>()
let chart: EChartsType | null = null
let resizeObserver: ResizeObserver | null = null

function renderChart(): void {
  if (!chart) return
  chart.setOption({
    animationDuration: 260,
    color: ['#176b52'],
    grid: { top: 34, right: 18, bottom: 48, left: 48, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: (value: unknown) => String(value),
    },
    xAxis: {
      type: 'category',
      data: props.labels,
      axisLabel: {
        color: '#66726c',
        interval: 0,
        overflow: 'truncate',
        width: 100,
      },
      axisLine: { lineStyle: { color: '#d9e0db' } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      name: props.valueLabel,
      minInterval: 1,
      nameTextStyle: { color: '#66726c' },
      axisLabel: { color: '#66726c' },
      splitLine: { lineStyle: { color: '#edf1ee' } },
    },
    series: [
      {
        type: 'bar',
        data: props.values,
        barMaxWidth: 44,
        itemStyle: { borderRadius: [5, 5, 0, 0] },
      },
    ],
  })
}

watch(
  () => [props.labels, props.values, props.valueLabel],
  renderChart,
  { deep: true },
)

onMounted(() => {
  if (!chartElement.value) return
  chart = init(chartElement.value)
  renderChart()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartElement.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="chart-panel">
    <h2>{{ title }}</h2>
    <div ref="chartElement" class="report-chart" role="img" :aria-label="title" />
  </section>
</template>
