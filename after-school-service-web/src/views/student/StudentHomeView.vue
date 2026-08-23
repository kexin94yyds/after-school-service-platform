<script setup lang="ts">
import RoleDashboard from '@/components/RoleDashboard.vue'

const shortcuts = [
  { title: '学生选课', description: '查看可选课程并完成退选或改选。', to: '/student/enrollments', action: '进入选课' },
  { title: '个人课表', description: '按日期查看本人实际上课安排。', to: '/student/schedule', action: '查看课表' },
  { title: '请假与考勤', description: '提交未来课次请假并查看月度考勤。', to: '/student/attendance', action: '查看记录' },
  { title: '成绩与评价', description: '查看课程成绩和教师学习评价。', to: '/student/grades', action: '查看成绩' },
]
</script>

<template>
  <RoleDashboard
    kicker="学生服务"
    title="我的课后服务"
    description="围绕选课、课表、上课、请假、考勤和成绩完成个人学习闭环。"
    :metrics="[]"
    :tasks="[]"
    :shortcuts="shortcuts"
    :loading="false"
    error=""
  />
</template>
