<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { assetRequestApi, serviceContactApi, statsApi, ticketApi, type ServiceContactRoster } from '../../api'
import ContactLeaveStatus from '../../components/ContactLeaveStatus.vue'
import { useAuthStore } from '../../stores/auth'
import { REQ_STATUS_CLASS, REQ_STATUS_NAME, REQ_TYPE, remainText, statusName } from '../../utils/dict'
import { toast } from '../../utils/toast'

const router = useRouter()
const auth = useAuthStore()
const kpi = ref({ pending: 0, processing: 0, overdue: 0, rating: 0, rated: 0 })
const newTickets = ref<any[]>([])
const approvals = ref<any[]>([])
const loading = ref(true)
const duty = ref<ServiceContactRoster>()
let contactTimer: ReturnType<typeof setInterval> | undefined
const cards = computed(() => [
  { key: 'pending', label: '待受理', value: kpi.value.pending, color: 'var(--red)', to: '/admin/tickets?status=NEW', allowed: auth.can('ticket.view.all') },
  { key: 'processing', label: '处理中', value: kpi.value.processing, color: 'var(--brand)', to: '/admin/tickets?status=PROCESSING', allowed: auth.can('ticket.view.all') },
  { key: 'overdue', label: '超时预警', value: kpi.value.overdue, color: 'var(--orange)', to: '/admin/tickets?status=OVERDUE', allowed: auth.can('ticket.view.all') },
  { key: 'rating', label: `满意度（${kpi.value.rated} 条评价）`, value: kpi.value.rating || '—', color: 'var(--green)', to: '/admin/stats', allowed: auth.can('stats.view') }
])

async function loadContacts() {
  try { duty.value = await serviceContactApi.roster() } catch { /* 工作台其他功能仍可用 */ }
}

onMounted(async () => {
  void loadContacts()
  contactTimer = setInterval(() => { if (!document.hidden) void loadContacts() }, 300000)
  try {
    const [pend, appr, overview] = await Promise.all([
      auth.can('ticket.view.all') ? ticketApi.list({ status: 'NEW', page: 1, size: 10 }) : Promise.resolve({ list: [], total: 0 }),
      auth.can('request.view.all') ? assetRequestApi.list({ status: 'PENDING' }) : Promise.resolve([]),
      statsApi.overview()
    ])
    newTickets.value = (pend.list || []).slice(0, 8)
    approvals.value = (appr || []).slice(0, 5)
    kpi.value = { pending: overview.pendingAccept || 0, processing: overview.processing || 0, overdue: overview.overdueCount || 0, rating: overview.avgRating || 0, rated: overview.ratedCount || 0 }
  } catch (e: any) {
    toast(e.message)
  } finally {
    loading.value = false
  }
})
onUnmounted(() => clearInterval(contactTimer))
</script>

<template>
  <div>
    <div class="page-h">
      <h2>今日工作台</h2>
      <span class="small muted">{{ new Date().toLocaleDateString('zh-CN') }}</span>
    </div>

    <div class="kpis">
      <component v-for="card in cards" :key="card.key" :is="card.allowed ? RouterLink : 'div'"
        class="kpi" :class="{ 'kpi-link': card.allowed }" :data-kpi="card.key"
        :to="card.allowed ? card.to : undefined" :aria-label="card.allowed ? '查看' + card.label : undefined">
        <div class="n" :style="{ color: card.color }">{{ card.value }}</div>
        <div class="l">{{ card.label }}</div>
      </component>
    </div>

    <div class="grid2">
      <div class="panel">
        <div class="ph">
          <b>待受理工单</b>
          <a @click="router.push('/admin/tickets?status=NEW')">全部 ›</a>
        </div>
        <div v-if="loading" class="empty">加载中…</div>
        <div v-else-if="!newTickets.length" class="empty">没有待受理工单</div>
        <div
          v-for="t in newTickets"
          :key="t.no"
          class="li"
          :class="{ danger: t.overdue }"
          @click="router.push(`/admin/ticket/${t.no}`)"
        >
          <div class="row">
            <span class="pill" :class="'p' + t.priority[1].toLowerCase()">{{ t.priority }}</span>
            <span class="tt">{{ t.title }}</span>
            <span class="sp" />
            <span class="small muted">{{ remainText(t.remainMinutes) || statusName(t.status) }}</span>
          </div>
          <div class="mm">{{ t.no }} · {{ t.creatorName }} · {{ t.cat1 }}</div>
          <span v-if="t.pendingReportCount" class="pill b-orange">{{ t.pendingReportCount }} 条待补报</span>
        </div>
      </div>

      <div class="panel">
        <div class="ph">
          <b>待审批资产申请</b>
          <a @click="router.push('/admin/approvals')">全部 ›</a>
        </div>
        <div v-if="!approvals.length" class="empty">没有待审批申请</div>
        <div v-for="r in approvals" :key="r.id" class="li" @click="router.push('/admin/approvals')">
          <div class="row">
            <span class="tt">{{ REQ_TYPE[r.type]?.name }}</span>
            <span class="sp" />
            <span class="pill" :class="REQ_STATUS_CLASS[r.status]">{{ REQ_STATUS_NAME[r.status] }}</span>
          </div>
          <div class="mm">{{ r.no }} · {{ r.applicant }} · {{ r.assetName || r.assetNo }}</div>
          <span v-if="r.pendingReportCount" class="pill b-orange">{{ r.pendingReportCount }} 条待补报</span>
        </div>

        <div v-if="duty || auth.can('settings.manage')" class="duty">
          <div class="row">
            <span class="small muted">IT 服务联系人</span>
            <span class="sp" />
            <RouterLink v-if="auth.can('settings.manage')" to="/admin/config?tab=duty" class="small">编辑联系人</RouterLink>
          </div>
          <div v-if="duty?.contacts.length" class="duty-members">
            <div v-for="(contact, index) in duty.contacts" :key="index" class="contact-line">
              <span>{{ contact.member.position }} {{ contact.member.name }}</span>
              <ContactLeaveStatus :contact="contact" />
            </div>
          </div>
          <div v-if="duty?.hotline" class="small muted duty-hotline">
            热线 {{ duty.hotline }}{{ duty.hotlineNote ? '（' + duty.hotlineNote + '）' : '' }}
          </div>
          <div v-if="duty && !duty.contacts.length && !duty.hotline" class="small muted duty-members">暂无联系人</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kpi-link { display:block; color:inherit; text-decoration:none; cursor:pointer; transition:box-shadow .15s, transform .15s; }
.kpi-link:hover { transform:translateY(-2px); box-shadow:0 5px 18px rgba(0,113,227,.12); }
.kpi-link:focus-visible { outline:2px solid var(--brand); outline-offset:3px; }
@media (prefers-reduced-motion:reduce) { .kpi-link { transition:none; }.kpi-link:hover { transform:none; } }
.page-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}
.page-h h2 {
  font-size: 20px;
}
.grid2 {
  display: grid;
  grid-template-columns: 1.35fr 1fr;
  gap: 12px;
  margin-top: 12px;
}
.panel {
  background: #fff;
  border-radius: var(--r);
  padding: 14px 16px;
  box-shadow: var(--shadow);
}
.ph {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.ph a {
  font-size: 12.5px;
  cursor: pointer;
}
.li {
  padding: 10px 0;
  border-bottom: 1px solid var(--line2);
  cursor: pointer;
}
.li:last-child {
  border-bottom: none;
}
.li.danger {
  background: #fff8f7;
  margin: 0 -8px;
  padding: 10px 8px;
  border-radius: 6px;
}
.li .tt {
  font-size: 13px;
  font-weight: 600;
}
.li .mm {
  font-size: 11.5px;
  color: var(--t3);
  margin-top: 3px;
}
.duty {
  margin-top: 14px;
  background: #fafbfc;
  border-radius: 8px;
  padding: 11px 12px;
  font-size: 12.5px;
  overflow-wrap: anywhere;
}
.duty-members { margin-top:6px; }
.contact-line { display:flex; flex-wrap:wrap; align-items:center; gap:6px; padding:3px 0; }
.duty-hotline { margin-top:4px; }
@media (max-width: 1000px) {
  .grid2 {
    grid-template-columns: 1fr;
  }
}
</style>
