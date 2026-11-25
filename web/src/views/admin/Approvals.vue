<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetApi, assetCategoryApi, assetRequestApi, dingApprovalApi, type AssetCategory } from '../../api'
import { HANDOVER_HINT, HANDOVER_NAME, REQ_STATUS_CLASS, REQ_STATUS_NAME, REQ_TYPE, handoverActionLabel } from '../../utils/dict'
import { toast } from '../../utils/toast'
import { hasAssetHolder } from '../../utils/assets'
import { notificationReference } from '../../utils/notification-links'
import { useAuthStore } from '../../stores/auth'
import AssetPicker from '../../components/AssetPicker.vue'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const requestNo = computed(() => notificationReference(route.query.request))

const tab = ref('PENDING')
const rows = ref<any[]>([])
const loading = ref(false)
let loadVersion = 0

const showApprove = ref(false)
const approveDialog = ref<HTMLElement>()
let approveOverflow: string | null = null
const current = ref<any>(null)
const spareList = ref<any[]>([])
const newAssetNo = ref('')
const categories = ref<AssetCategory[]>([])
const category = ref('')
const newCategory = ref('')
const filteredSpareList = computed(() => spareList.value.filter(a => !newCategory.value || a.category === newCategory.value))
const filteredRows = computed(() => rows.value.filter(r => !category.value || r.assetCategory === category.value))
const selectedReplacement = computed(() => spareList.value.find(asset => asset.no === newAssetNo.value))
watch(filteredSpareList, list => { if (!list.some(a => a.no === newAssetNo.value)) newAssetNo.value = '' })
watch(showApprove, async open => {
  if (open) {
    approveOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'
    await nextTick(); approveDialog.value?.querySelector<HTMLButtonElement>('.approve-close')?.focus()
  } else if (approveOverflow !== null) { document.body.style.overflow = approveOverflow; approveOverflow = null }
})
onUnmounted(() => { if (approveOverflow !== null) document.body.style.overflow = approveOverflow })

async function load() {
  const version = ++loadVersion
  loading.value = true
  try {
    const [requests, list] = await Promise.all([assetRequestApi.list({ status: tab.value === 'ALL' ? undefined : tab.value, no: requestNo.value }), auth.can('asset.view') ? assetCategoryApi.list() : Promise.resolve([])])
    if (version !== loadVersion) return
    rows.value = requests
    categories.value = auth.can('asset.view') ? list : Array.from(new Set<string>(requests.map((r: any) => r.assetCategory).filter(Boolean))).map((name, index) => ({ id: index, name, sort: index, assetCount: 0 }))
  } catch (e: any) {
    if (version === loadVersion) toast(e.message)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function switchTab(value: string) {
  tab.value = value
  if (requestNo.value) void router.replace({ path: route.path })
  else void load()
}

const dingStatus = ref<any>({ enabled: false, mock: false })
const syncing = ref(false)
async function loadDingStatus() {
  try { dingStatus.value = await dingApprovalApi.status() } catch { /* 无权限或未开启时忽略 */ }
}
async function syncDing() {
  if (syncing.value) return
  syncing.value = true
  try {
    const r = await dingApprovalApi.sync()
    toast(r.handled ? `已同步，处理 ${r.handled} 笔` : '已同步，暂无状态变化')
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally { syncing.value = false }
}
/** IT/管理员确认线下交接（待领取 → 交付；待归还 → 收回；更换分两次） */
const handing = ref(0)
async function doHandover(row: any) {
  if (handing.value) return
  const stage = row.handoverStatus
  const tip = stage === 'RETURN'
    ? (row.type === 'REPLACE'
        ? `确认已收回原设备 ${row.assetNo}？确认后转入「待领取新机」`
        : `确认已收回设备 ${row.assetNo}？`)
    : `确认已把设备 ${row.type === 'REPLACE' ? row.newAssetNo : row.assetNo} 交付给 ${row.applicant}？`
  if (!window.confirm(tip)) return
  handing.value = row.id
  try {
    const r = await assetRequestApi.handover(row.id)
    toast(r.status === 'COMPLETED'
      ? '已完成'
      : (r.handoverStatus === 'PICKUP' ? '旧机已收回，已转入待领取' : '已确认'))
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally {
    handing.value = 0
  }
}

/** 本地联调：模拟钉钉审批结果（仅 approvalMock 开启时出现） */
async function mockDing(row: any, status: string) {
  try {
    await dingApprovalApi.mock(row.no, status)
    await syncDing()
  } catch (e: any) {
    toast(e.message)
  }
}
watch(requestNo, no => {
  if (no) { tab.value = 'ALL'; category.value = '' }
  rows.value = []
  void load()
}, { immediate: true })
onMounted(loadDingStatus)

async function openApprove(r: any) {
  current.value = r
  if (r.type === 'REPLACE') {
    const all = await assetApi.list({ status: '闲置' })
    spareList.value = all.filter((a: any) => a.no !== r.assetNo && !hasAssetHolder(a))
    newCategory.value = r.assetCategory || ''
    newAssetNo.value = ''
  }
  showApprove.value = true
}

async function doApprove() {
  try {
    if (current.value.type === 'REPLACE' && !filteredSpareList.value.some(a => a.no === newAssetNo.value)) {
      toast('请选择替换的新设备')
      return
    }
    const replacement = current.value.type === 'REPLACE' ? filteredSpareList.value.find(a => a.no === newAssetNo.value) : null
    await assetRequestApi.approve(current.value.id, { newAssetNo: replacement?.no, newAssetCategory: replacement?.category })
    showApprove.value = false
    toast('已批准')
    await load()
  } catch (e: any) {
    toast(e.message)
  }
}

async function doReject(r: any) {
  const reason = window.prompt('驳回原因（会通知申请人）')
  if (!reason) return
  try {
    await assetRequestApi.reject(r.id, reason)
    toast('已驳回')
    await load()
  } catch (e: any) {
    toast(e.message)
  }
}
</script>

<template>
  <div>
    <div class="page-h"><h2>{{ auth.canApprove ? '申请审批' : '资产申请' }}</h2></div>

    <div class="filters">
      <span :class="{ on: tab === 'PENDING' }" @click="switchTab('PENDING')">待审批</span>
      <span :class="{ on: tab === 'ALL' }" @click="switchTab('ALL')">全部</span>
      <span class="sp" />
      <button v-if="dingStatus.enabled" class="btn sm" :disabled="syncing" @click="syncDing">{{ syncing ? '同步中…' : '立即同步钉钉结果' }}</button>
      <select v-model="category" class="sel" style="width:150px" aria-label="申请分类筛选"><option value="">全部分类</option><option v-for="c in categories" :key="c.id" :value="c.name">{{ c.name }}</option></select>
      <span class="small muted">共 {{ filteredRows.length }} 条</span>
    </div>

    <div v-if="requestNo" class="row small">
      <span>当前申请 {{ requestNo }}</span>
      <button class="btn sm" type="button" @click="switchTab('ALL')">查看全部申请</button>
    </div>
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!filteredRows.length" class="empty">{{ requestNo ? '找不到这张申请或无权查看' : '该分类下没有匹配的申请' }}</div>

    <div v-else class="grid">
      <div v-for="r in filteredRows" :key="r.id" class="card">
        <div class="row">
          <span class="pill b-blue">{{ REQ_TYPE[r.type]?.name }}</span>
          <span v-if="r.status === 'APPROVED' && r.handoverStatus" class="pill" :class="r.handoverStatus === 'PICKUP' ? 'b-green' : 'b-orange'">{{ HANDOVER_NAME[r.handoverStatus] }}</span>
          <span v-else class="pill" :class="REQ_STATUS_CLASS[r.status]">{{ REQ_STATUS_NAME[r.status] }}</span>
          <span class="sp" />
          <span class="mono">{{ r.no }}</span>
        </div>
        <div style="margin-top: 9px; font-size: 13.5px; font-weight: 600">
          {{ r.assetName || r.assetNo }}
          <span class="mono" style="font-weight: 400">（{{ r.assetNo }}）</span>
        </div>
        <div class="small muted" style="margin-top: 5px; line-height: 1.8">
          分类：{{ r.assetCategory || '—' }}<br />
          申请人：{{ r.applicant }}（{{ r.dept }}）<br />
          <template v-if="r.pendingReportCount"><span class="pill b-orange">{{ r.pendingReportCount }} 条待补报</span><br /></template>
          事由：{{ r.reason }}
          <template v-if="r.expectReturn"><br />约定归还：{{ r.expectReturn }}</template>
          <template v-if="r.newAssetNo"><br />替换新机：{{ r.newAssetNo }}</template>
          <template v-if="r.dingInstanceId"><br />钉钉审批：<span class="mono">{{ r.dingInstanceId }}</span> · {{ r.dingStatus || 'RUNNING' }}<template v-if="r.dingMessage"> · {{ r.dingMessage }}</template></template>
          <template v-if="r.approver"><br />处理人：{{ r.approver }} · {{ r.handledAt }}</template>
          <template v-if="r.remark"><br />备注：{{ r.remark }}</template>
          <template v-if="r.status === 'APPROVED' && r.handoverStatus"><br />
            <span style="color:var(--brand)">{{ HANDOVER_HINT[r.handoverStatus] }}</span>
          </template>
        </div>
        <div v-if="r.status === 'APPROVED' && r.handoverStatus && auth.canApprove" class="row" style="margin-top: 10px">
          <span class="sp" />
          <button class="btn sm pri" :disabled="handing === r.id" data-tutorial-effect="write" @click="doHandover(r)">
            {{ handing === r.id ? '处理中…' : handoverActionLabel(r.type, r.handoverStatus) }}
          </button>
        </div>
        <div v-else-if="r.status === 'PENDING' && auth.canApprove && r.dingInstanceId" class="row" style="margin-top: 10px">
          <span class="pill b-blue">钉钉审批中，请在钉钉处理</span>
          <span class="sp" />
          <template v-if="dingStatus.mock">
            <button class="btn sm" @click="mockDing(r, 'TERMINATED')">联调：模拟驳回</button>
            <button class="btn sm pri" @click="mockDing(r, 'COMPLETED')">联调：模拟通过</button>
          </template>
        </div>
        <div v-else-if="r.status === 'PENDING' && auth.canApprove" class="row" style="margin-top: 10px">
          <span class="sp" />
          <button class="btn sm danger" @click="doReject(r)">驳回</button>
          <button class="btn sm pri" @click="openApprove(r)">批准</button>
        </div>
      </div>
    </div>

    <div v-if="showApprove" class="mask approval-mask" @click.self="showApprove = false" @keydown.esc="showApprove = false">
      <div ref="approveDialog" class="dlg" :class="{ 'replacement-dialog': current.type === 'REPLACE' }" role="dialog" aria-modal="true" aria-label="资产申请审批">
        <div class="dlg-h">批准 {{ REQ_TYPE[current.type]?.name }}<button type="button" class="approve-close" aria-label="关闭审批" @click="showApprove = false">×</button></div>
        <div class="dlg-b" :class="{ 'replacement-body': current.type === 'REPLACE' }">
          <div class="small approval-summary" style="line-height: 1.9; color: var(--t2)">
            申请单：<span class="mono">{{ current.no }}</span><br />
            设备：{{ current.assetName }}（{{ current.assetNo }}）<br />
            申请人：{{ current.applicant }}
          </div>

          <div v-if="current.type === 'REPLACE'">
            <AssetPicker v-model="newAssetNo" v-model:category="newCategory" :assets="spareList" :categories="categories" category-label="替换设备分类" empty-text="该分类下暂无闲置替换设备，请选择其他分类。" style="--asset-picker-height:min(45vh,420px)" />
            <div class="small muted replacement-help">
              批准后：旧机回收至备件库、新机绑定申请人、该设备未结工单自动转移。
            </div>
          </div>
        </div>
        <div class="dlg-f" :class="{ 'replacement-footer': current.type === 'REPLACE' }">
          <span v-if="current.type === 'REPLACE'" class="replacement-summary">{{ selectedReplacement ? '已选：' + selectedReplacement.name : '请选择一台替换设备' }}</span>
          <button class="btn" @click="showApprove = false">取消</button>
          <button class="btn pri" :disabled="current.type === 'REPLACE' && !newAssetNo" @click="doApprove">确认批准</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.approve-close { border:0; border-radius:8px; background:transparent; color:var(--t3); font-size:26px; width:32px; height:32px; cursor:pointer; }.approve-close:hover { background:#f2f4f7; color:var(--t1); }
.replacement-dialog { max-width:1000px; max-height:92vh; display:flex; flex-direction:column; overflow:hidden; }.replacement-dialog .dlg-h,.replacement-dialog .dlg-f { flex-shrink:0; }
.replacement-dialog .replacement-body { min-height:0; overflow:auto; padding:0; }.replacement-body .approval-summary { padding:14px 20px; border-bottom:1px solid var(--line2); }
.replacement-help { padding:11px 20px; line-height:1.7; background:#fafbfc; border-top:1px solid var(--line2); }.replacement-footer { align-items:center; gap:10px; border-top:1px solid var(--line2); }
.replacement-summary { min-width:0; flex:1; color:var(--t3); font-size:11.5px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }.replacement-footer .btn { flex-shrink:0; white-space:nowrap; }
@media(max-width:600px) { .approval-mask { padding:10px; }.replacement-dialog { max-height:94dvh; }.replacement-body .approval-summary { padding:12px 14px; font-size:11px; }.replacement-help { padding:10px 14px; font-size:10px; }.replacement-footer { padding-left:14px; padding-right:14px; }.replacement-summary { font-size:10px; } }
.page-h h2 {
  font-size: 20px;
  margin-bottom: 14px;
}
.filters {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 12px;
}
.filters > span:not(.sp):not(.small) {
  padding: 6px 13px;
  border-radius: 7px;
  background: #fff;
  font-size: 12.5px;
  color: var(--t2);
  cursor: pointer;
  box-shadow: var(--shadow);
}
.filters > span.on {
  background: var(--brand);
  color: #fff;
  font-weight: 600;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 12px;
}
</style>
