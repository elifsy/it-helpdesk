<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ticketApi } from '../../api'
import { statusName } from '../../utils/dict'
import { ticketQueueText } from '../../utils/ticketQueue'
import { toast } from '../../utils/toast'

const router = useRouter()
const tab = ref('OPEN')
const rows = ref<any[]>([])
const loading = ref(false)

const tabs = [
  { v: 'OPEN', l: '处理中' },
  { v: 'PENDING_CONFIRM', l: '待确认' },
  { v: 'ALL', l: '全部' }
]

const shown = computed(() => {
  if (tab.value === 'ALL') return rows.value
  if (tab.value === 'OPEN') return rows.value.filter((t) => !['CLOSED', 'REJECTED', 'CANCELLED'].includes(t.status))
  return rows.value.filter((t) => t.status === tab.value)
})

async function load() {
  loading.value = true
  try {
    const page = await ticketApi.list({ mine: true, page: 1, size: 50 })
    rows.value = page.list || []
  } catch (e: any) {
    toast(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="tabs2">
      <div v-for="t in tabs" :key="t.v" :class="{ on: tab === t.v }" @click="tab = t.v">{{ t.l }}</div>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!shown.length" class="empty">这里还没有工单</div>

    <div class="list">
      <div v-for="t in shown" :key="t.no" class="wo" @click="router.push(`/ticket/${t.no}`)">
        <div class="row">
          <div class="sp">
            <div class="tt">{{ t.title }}</div>
            <div class="mm">{{ t.no }} · {{ t.cat1 }} · {{ t.createdAt }}</div>
          </div>
          <div style="text-align: right">
            <span class="pill" :class="t.overdue ? 'p1' : 'p' + t.priority[1].toLowerCase()">{{ t.priority }}</span>
            <div class="small muted" style="margin-top: 4px">{{ statusName(t.status) }}</div>
          </div>
        </div>
        <div v-if="ticketQueueText(t)" class="ticket-queue">{{ ticketQueueText(t) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ticket-queue { margin-top:10px; padding-top:9px; border-top:1px solid var(--line2); color:var(--brand); font-size:12px; line-height:1.6; overflow-wrap:anywhere; }
.tabs2 {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}
.tabs2 div {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  background: #fff;
  border-radius: 8px;
  font-size: 13px;
  color: var(--t2);
  cursor: pointer;
  box-shadow: var(--shadow);
}
.tabs2 div.on {
  background: var(--brand);
  color: #fff;
  font-weight: 600;
}
.wo {
  background: #fff;
  border-radius: var(--r);
  padding: 12px 14px;
  margin-bottom: 8px;
  cursor: pointer;
  box-shadow: var(--shadow);
}
.wo .tt {
  font-size: 13.5px;
  font-weight: 600;
}
.wo .mm {
  font-size: 11.5px;
  color: var(--t3);
  margin-top: 3px;
}
@media (min-width: 900px) {
  .tabs2 {
    max-width: 420px;
  }
  .list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .wo {
    margin-bottom: 0;
    padding: 15px 17px;
  }
  .wo .tt {
    font-size: 14px;
  }
}
</style>
