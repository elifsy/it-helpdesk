<script setup lang="ts">
import { computed } from 'vue'
import type { ServiceContact } from '../api'

const props = defineProps<{ contact?: ServiceContact }>()
const labels: Record<ServiceContact['status'], string> = {
  ON_LEAVE: '请假中', TODAY: '今日有请假', NONE: '今日无请假', UNKNOWN: '请假状态暂不可查', UNLINKED: '待关联钉钉'
}
const title = computed(() => {
  if (props.contact?.leaveStart && props.contact.leaveEnd)
    return `请假时间 ${props.contact.leaveStart} 至 ${props.contact.leaveEnd}`
  return undefined
})
</script>

<template>
  <span v-if="contact" class="leave-status" :title="title">
    <span class="pill" :class="contact.status === 'ON_LEAVE' || contact.status === 'TODAY' ? 'b-orange' : 'b-gray'">{{ labels[contact.status] }}</span>
    <small v-if="contact.leaveStart && contact.leaveEnd" class="muted">
      {{ contact.leaveStart.slice(0, 16) }} 至 {{ contact.leaveEnd.slice(0, 16) }}
    </small>
  </span>
</template>

<style scoped>
.leave-status { display:inline-flex; flex-wrap:wrap; align-items:center; gap:5px; }
</style>
