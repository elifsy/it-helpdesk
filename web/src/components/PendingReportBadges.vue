<script setup lang="ts">
import { computed } from 'vue'
import type { PendingReportCounts } from '../api'

const props = defineProps<{ counts?: PendingReportCounts | null; repairCount?: number | null }>()
const badges = computed(() => {
  const counts = props.counts
  return [
    { count: counts?.repair ?? props.repairCount, label: '待补报', tone: 'b-orange' },
    { count: counts?.receive, label: '待补领用', tone: 'b-blue' },
    { count: counts?.borrow, label: '待补借用', tone: 'b-blue' },
    { count: counts?.replace, label: '待补更换', tone: 'b-blue' },
    { count: counts?.returnCount, label: '待补归还', tone: 'b-blue' },
    { count: counts?.overdueReturn, label: '逾期未归还', tone: 'b-red', unit: '台' }
  ].filter(item => item.count)
})
</script>

<template>
  <span v-if="badges.length" class="pending-badges">
    <span v-for="item in badges" :key="item.label" class="pill" :class="item.tone">{{ item.count }} {{ item.unit || '条' }}{{ item.label }}</span>
  </span>
</template>

<style scoped>
.pending-badges { display:flex; flex-wrap:wrap; gap:4px; margin-top:4px; }
.pending-badges .pill { white-space:nowrap; }
</style>
