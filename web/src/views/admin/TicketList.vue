<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ticketApi } from '../../api'
import { remainText, statusName } from '../../utils/dict'
import { toast } from '../../utils/toast'

const route = useRoute()
const router = useRouter()
const status = computed(() => typeof route.query.status === 'string' ? route.query.status : '')
const keyword = ref('')
const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isSafeInteger(value) && value > 0 ? value : 1
})
const size = 15
const total = ref(0)
const rows = ref<any[]>([])
const loading = ref(false)
let loadVersion = 0

const tabs = [
  { v: '', l: '全部' },
  { v: 'NEW', l: '待受理' },
  { v: 'PROCESSING', l: '处理中' },
  { v: 'OVERDUE', l: '超时预警' },
  { v: 'PENDING_CONFIRM', l: '待确认' },
  { v: 'CLOSED', l: '已关闭' }
]

async function load() {
  const version = ++loadVersion
  loading.value = true
  rows.value = []
  total.value = 0
  try {
    const res = await ticketApi.list({
      status: status.value || undefined,
      keyword: typeof route.query.keyword === 'string' ? route.query.keyword || undefined : undefined,
      page: page.value,
      size
    })
    if (version !== loadVersion) return
    rows.value = res.list || []
    total.value = res.total || 0
  } catch (e: any) {
    if (version === loadVersion) toast(e.message)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

watch([status, page, () => route.query.keyword], () => {
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  void load()
}, { immediate: true })
onUnmounted(() => { loadVersion++ })

function selectStatus(value: string) {
  void router.push({ path: route.path, query: { ...route.query, status: value || undefined, page: undefined } })
}
function setPage(value: number) {
  void router.push({ path: route.path, query: { ...route.query, page: value > 1 ? String(value) : undefined } })
}

function search() {
  const value = keyword.value.trim()
  if (value === (route.query.keyword || '') && page.value === 1) void load()
  else void router.push({ path: route.path, query: { ...route.query, keyword: value || undefined, page: undefined } })
}
</script>

<template>
  <div>
    <div class="page-h">
      <h2>工单中心</h2>
      <div class="row" style="gap: 8px">
        <input v-model="keyword" class="inp" style="width: 220px" placeholder="搜索编号 / 标题 / 描述" @keyup.enter="search" />
        <button class="btn" @click="search">搜索</button>
      </div>
    </div>

    <div class="filters">
      <button type="button" class="filter-tab" v-for="t in tabs" :key="t.v" :class="{ on: status === t.v }" :aria-pressed="status === t.v" @click="selectStatus(t.v)">
        {{ t.l }}
      </button>
      <span class="sp" />
      <span class="small muted">共 {{ total }} 张</span>
    </div>

    <div class="panel">
      <table class="tb desktop-records">
        <thead>
          <tr>
            <th>工单号</th>
            <th>标题</th>
            <th>分类</th>
            <th>优先级</th>
            <th>状态</th>
            <th>工程师</th>
            <th>时限</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="8" class="empty">加载中…</td>
          </tr>
          <tr v-else-if="!rows.length">
            <td colspan="8" class="empty">没有符合条件的工单</td>
          </tr>
          <tr
            v-for="t in rows"
            :key="t.no"
            :class="{ over: t.overdue }"
            style="cursor: pointer"
            data-tutorial-control
            :aria-label="'查看工单 ' + t.no"
            @click="router.push(`/admin/ticket/${t.no}`)"
          >
            <td class="mono">{{ t.no }}</td>
            <td>
              <div style="font-weight: 600">{{ t.title }}</div>
              <div class="small muted">{{ t.creatorName }} · {{ t.deptName }}</div>
              <span v-if="t.pendingReportCount" class="pill b-orange">{{ t.pendingReportCount }} 条待补报</span>
            </td>
            <td>{{ t.cat1 }}</td>
            <td><span class="pill" :class="'p' + t.priority[1].toLowerCase()">{{ t.priority }}</span></td>
            <td>{{ statusName(t.status) }}</td>
            <td>{{ t.assigneeName || '—' }}</td>
            <td :style="{ color: t.overdue ? 'var(--red)' : 'var(--t2)', fontSize: '12px' }">
              {{ remainText(t.remainMinutes, t.workTime, t.slaPausedAt) || '—' }}
            </td>
            <td><button class="btn sm">查看</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="mobile-records">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="!rows.length" class="empty">没有符合条件的工单</div>
      <button v-for="ticket in rows" :key="ticket.no" class="ticket-card" :class="{ overdue: ticket.overdue }" @click="router.push(`/admin/ticket/${ticket.no}`)">
        <div class="card-line"><span class="mono">{{ ticket.no }}</span><span class="pill" :class="'p' + String(ticket.priority || 'P4').slice(-1)">{{ ticket.priority }}</span></div>
        <strong>{{ ticket.title }}</strong>
        <span v-if="ticket.pendingReportCount" class="pill b-orange">{{ ticket.pendingReportCount }} 条待补报</span>
        <div class="card-line card-meta"><span>{{ ticket.creatorName }} · {{ ticket.cat1 }}</span><span>{{ statusName(ticket.status) }}</span></div>
        <div class="card-line card-meta"><span>工程师：{{ ticket.assigneeName || '待接单' }}</span><span :class="{ 'overdue-text': ticket.overdue }">{{ remainText(ticket.remainMinutes, ticket.workTime, ticket.slaPausedAt) || '查看详情 ›' }}</span></div>
      </button>
    </div>

    <div class="pager">
      <button class="btn sm" :disabled="loading || page <= 1" @click="setPage(page - 1)">上一页</button>
      <span class="small muted">{{ page }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
      <button class="btn sm" :disabled="loading || page >= Math.ceil(total / size)" @click="setPage(page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.page-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.page-h h2 {
  font-size: 20px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
}
.filter-tab {
  border:0;
  white-space:nowrap;
  padding: 6px 13px;
  border-radius: 7px;
  background: #fff;
  font-size: 12.5px;
  color: var(--t2);
  cursor: pointer;
  box-shadow: var(--shadow);
}
.filter-tab.on {
  background: var(--brand);
  color: #fff;
  font-weight: 600;
}
.filter-tab:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }
.panel {
  background: #fff;
  border-radius: var(--r);
  box-shadow: var(--shadow);
  overflow: auto;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 14px;
}
.mobile-records { display:none; }
@media(max-width:899px) {
  .panel { display:none; }
  .mobile-records { display:grid; gap:12px; }
  .ticket-card { display:grid; gap:11px; width:100%; text-align:left; padding:16px; border:1px solid transparent; border-radius:18px; background:#fff; color:var(--t1); box-shadow:var(--shadow); }
  .ticket-card strong { font-size:15px; font-weight:600; overflow-wrap:anywhere; }
  .ticket-card.overdue { border-color:#ffded9; }
  .card-line { display:flex; justify-content:space-between; align-items:center; gap:10px; min-width:0; }
  .card-line .mono { min-width:0; overflow-wrap:anywhere; font-size:11px; }
  .card-meta { font-size:12px; color:var(--t2); }
  .overdue-text { color:var(--red); }
  .filters { flex-wrap:nowrap; overflow-x:auto; padding:2px 0 10px; }
  .filter-tab { flex-shrink:0; min-height:38px; }
  .filters .sp { display:none; }.filters .muted { white-space:nowrap; }
}
</style>
