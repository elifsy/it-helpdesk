<script setup lang="ts">
import PendingReportBadges from '../../components/PendingReportBadges.vue'
/**
 * 采购管理（IT/管理员）：
 * 申请单审批 → 逐条明细处理（库存配发 / 转采购 → 到货配发 → 逐台交付）。
 * 一条明细 = 一台设备，互不影响：库存够的直接配发进「待领取」，不够的填 ERP 走采购。
 * 另有「采购目录」维护员工可申请购买的型号。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetApi, dingApprovalApi, purchaseApi } from '../../api'
import { PR_ITEM_STATUS_CLASS, PR_ITEM_STATUS_NAME, PR_STATUS_CLASS, PR_STATUS_NAME } from '../../utils/dict'
import { toast } from '../../utils/toast'
import { hasAssetHolder } from '../../utils/assets'
import { notificationReference } from '../../utils/notification-links'
import { useAuthStore } from '../../stores/auth'
import AssetPicker from '../../components/AssetPicker.vue'
import DeviceIcon from '../../components/DeviceIcon.vue'
import { autoIconKey, iconClass, iconLabel } from '../../utils/deviceIcons'
import DeviceIconPicker from '../../components/DeviceIconPicker.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const requestNo = computed(() => notificationReference(route.query.request))
const tab = ref('PENDING')
const rows = ref<any[]>([])
const loading = ref(false)
let loadVersion = 0
const acting = ref('')

const showOrder = ref(false)
const orderTarget = ref<{ request: any; item: any } | null>(null)
const orderErpNo = ref('')

const showAssign = ref(false)
const assignTarget = ref<{ request: any; item: any } | null>(null)
const assignAssetNo = ref('')
const assignCategory = ref('')
const spareAssets = ref<any[]>([])

const catalog = ref<any[]>([])
/** 钉钉审批开关状态：用于显示「钉钉审批中」与联调按钮 */
const ding = ref<any>({ enabled: false, mock: false })
const showCatalog = ref(false)
const catalogForm = ref<any>({ name: '', category: '', spec: '', remark: '', icon: '', enabled: 1, sort: 0 })
/** 目录列表里每项实际使用的图标 */
function rowIcon(c: any) {
  return c.icon || autoIconKey(c.category, c.name)
}
const editingCatalog = ref(false)

const TABS = [
  { v: 'PENDING', s: '待审批' },
  { v: 'APPROVED', s: '待处理' },
  { v: '', s: '全部' },
  { v: 'CATALOG', s: '采购目录' }
]

const filtered = computed(() => rows.value)

async function load() {
  const version = ++loadVersion
  if (tab.value === 'CATALOG') { await loadCatalog(); return }
  loading.value = true
  try {
    const list = await purchaseApi.list({ status: tab.value || undefined, no: requestNo.value })
    if (version === loadVersion) rows.value = list
  } catch (e: any) {
    if (version === loadVersion) toast(e.message)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function loadCatalog() {
  const version = ++loadVersion
  loading.value = true
  try {
    const list = await purchaseApi.catalog(true)
    if (version === loadVersion) catalog.value = list
  } catch (e: any) {
    if (version === loadVersion) toast(e.message)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function switchTab(v: string) {
  tab.value = v
  if (requestNo.value) void router.replace({ path: route.path })
  else void load()
}

/** 本地联调：模拟钉钉审批结果，再触发一次同步 */
async function mockDing(row: any, status: string) {
  try {
    await dingApprovalApi.mock(row.no, status)
    const r = await dingApprovalApi.sync()
    await load()
    toast(r.handled ? `已同步，处理 ${r.handled} 笔` : '已模拟结果，下次同步生效')
    if (status === 'COMPLETED') tab.value = 'APPROVED'
  } catch (e: any) {
    toast(e.message)
  }
}

async function approve(row: any) {
  if (acting.value) return
  acting.value = row.id
  try {
    await purchaseApi.approve(row.id, '')
    toast('已批准，可以逐项处理设备了')
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally {
    acting.value = ''
  }
}

async function reject(row: any) {
  const reason = window.prompt('驳回原因（可留空）', '')
  if (reason === null) return
  acting.value = row.id
  try {
    await purchaseApi.reject(row.id, reason)
    toast('已驳回')
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally {
    acting.value = ''
  }
}

/* --------------------------- 明细操作 --------------------------- */

function openOrder(request: any, item: any) {
  orderTarget.value = { request, item }
  orderErpNo.value = item.erpNo || ''
  showOrder.value = true
}

async function saveOrder() {
  if (!orderTarget.value) return
  if (!orderErpNo.value.trim()) { toast('请填写 ERP 采购单号'); return }
  const { request, item } = orderTarget.value
  acting.value = 'item' + item.id
  try {
    await purchaseApi.orderItem(request.id, item.id, orderErpNo.value.trim())
    showOrder.value = false
    toast('已转采购，到货后在这一项上配发设备')
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally {
    acting.value = ''
  }
}

async function openAssign(request: any, item: any) {
  assignTarget.value = { request, item }
  assignAssetNo.value = ''
  assignCategory.value = ''
  showAssign.value = true
  try {
    const list = await assetApi.list({})
    spareAssets.value = (list || []).filter((a: any) =>
      ['闲置', '待入库'].includes(a.status) && !hasAssetHolder(a))
  } catch (e: any) {
    toast(e.message)
  }
}

async function confirmAssign() {
  if (!assignTarget.value) return
  if (!assignAssetNo.value) { toast('请选择一台设备'); return }
  const { request, item } = assignTarget.value
  acting.value = 'item' + item.id
  try {
    await purchaseApi.assignItem(request.id, item.id, assignAssetNo.value)
    showAssign.value = false
    toast('已配好设备，这一项进入待领取')
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally {
    acting.value = ''
  }
}

async function deliver(request: any, item: any) {
  if (!window.confirm(`确认把 ${item.assetNo} 交付给 ${request.applicant}？设备会直接绑定到他名下`)) return
  acting.value = 'item' + item.id
  try {
    await purchaseApi.deliverItem(request.id, item.id)
    toast('已交付')
    await load()
  } catch (e: any) {
    toast(e.message)
  } finally {
    acting.value = ''
  }
}

/* ------------------------------ 采购目录 ------------------------------ */

function newCatalog() {
  editingCatalog.value = false
  catalogForm.value = { name: '', category: '', spec: '', remark: '', icon: '', enabled: 1, sort: 0 }
  showCatalog.value = true
}

function editCatalog(item: any) {
  editingCatalog.value = true
  catalogForm.value = { ...item }
  showCatalog.value = true
}

async function saveCatalog() {
  const f = catalogForm.value
  if (!f.name?.trim()) { toast('请填写设备名称'); return }
  if (!f.category?.trim()) { toast('请填写资产分类'); return }
  try {
    await purchaseApi.saveCatalog({
      id: f.id, name: f.name.trim(), category: f.category.trim(),
      spec: f.spec || null, remark: f.remark || null, icon: f.icon || null,
      enabled: f.enabled ? 1 : 0, sort: Number(f.sort) || 0
    })
    showCatalog.value = false
    toast('已保存')
    await loadCatalog()
  } catch (e: any) {
    toast(e.message)
  }
}

async function removeCatalog(item: any) {
  if (!window.confirm(`删除「${item.name}」？已提交的申请单不受影响`)) return
  try {
    await purchaseApi.deleteCatalog(item.id)
    toast('已删除')
    await loadCatalog()
  } catch (e: any) {
    toast(e.message)
  }
}

watch(requestNo, no => {
  if (no) tab.value = ''
  rows.value = []
  void load()
}, { immediate: true })
onMounted(() => {
  dingApprovalApi.status().then((st: any) => { ding.value = st }).catch(() => {})
})
</script>

<template>
  <div class="purchase-page">
    <div class="page-h">
      <h2>采购管理</h2>
      <span class="small muted">员工提交购入申请后，这里审批并逐台安排：库存配发 / 转采购 / 到货交付</span>
    </div>

    <div class="filters">
      <span v-for="t in TABS" :key="t.v" :class="{ on: tab === t.v }" @click="switchTab(t.v)">{{ t.s }}</span>
    </div>

    <div v-if="requestNo" class="row small">
      <span>当前购入申请 {{ requestNo }}</span>
      <button class="btn sm" type="button" @click="switchTab('')">查看全部申请</button>
    </div>
    <div v-if="loading" class="empty">加载中…</div>

    <!-- 采购目录 -->
    <template v-else-if="tab === 'CATALOG'">
      <div class="row" style="margin-bottom:10px">
        <span class="small muted">共 {{ catalog.length }} 项，员工只能申请「启用」的设备</span>
        <span class="sp" />
        <button v-if="auth.can('asset.create')" class="btn pri sm" @click="newCatalog">新增目录项</button>
      </div>
      <div v-if="!catalog.length" class="empty">还没有可申请购买的设备，先新增一项</div>
      <div v-for="c in catalog" :key="c.id" class="card" style="margin-bottom:8px">
        <div class="row">
          <span class="cat-icon" :class="iconClass(rowIcon(c))"><DeviceIcon :name="rowIcon(c)" /></span>
          <b style="font-size:13.5px">{{ c.name }}</b>
          <span class="pill" :class="c.enabled ? 'b-green' : 'b-gray'">{{ c.enabled ? '启用' : '停用' }}</span>
          <span class="sp" />
          <span class="small muted">{{ c.category }} · 图标：{{ c.icon ? iconLabel(c.icon) : '自动' }}</span>
        </div>
        <div class="small muted" style="margin-top:6px">
          <template v-if="c.spec">规格：{{ c.spec }}　</template>
          <template v-if="c.remark">说明：{{ c.remark }}</template>
        </div>
        <div v-if="auth.can('asset.create')" class="row" style="margin-top:8px">
          <span class="sp" />
          <button class="btn sm" @click="editCatalog(c)">编辑</button>
          <button class="btn sm danger" @click="removeCatalog(c)">删除</button>
        </div>
      </div>
    </template>

    <!-- 申请单 -->
    <template v-else>
      <div v-if="!filtered.length" class="empty">{{ requestNo ? '找不到这张购入申请或无权查看' : '该状态下暂无申请' }}</div>
      <div v-for="r in filtered" :key="r.id" class="card pr-card">
        <div class="row">
          <span v-if="r.status === 'PENDING' && r.dingInstanceId" class="pill b-blue">钉钉审批中</span>
          <span v-else class="pill" :class="PR_STATUS_CLASS[r.status]">{{ PR_STATUS_NAME[r.status] }}</span>
          <span v-if="r.status === 'APPROVED'" class="pill b-gray">已交付 {{ r.doneCount }}/{{ r.itemCount }}</span>
          <span class="sp" />
          <span class="mono">{{ r.no }}</span>
        </div>

        <div class="small muted pr-meta">
          {{ r.applicant }}（{{ r.dept }}）· {{ r.createdAt }}<br />
          <PendingReportBadges :counts="r.pendingByType" :repair-count="r.pendingReportCount" />
          事由：{{ r.reason }}
          <template v-if="r.dingInstanceId"><br />钉钉审批：<span class="mono">{{ r.dingInstanceId }}</span><template v-if="r.dingMessage"> · {{ r.dingMessage }}</template></template>
          <template v-if="r.approver"><br />审批人：{{ r.approver }} · {{ r.handledAt }}</template>
          <template v-if="r.remark"><br />备注：{{ r.remark }}</template>
        </div>

        <!-- 明细：一行一台设备 -->
        <div class="items">
          <div v-for="(it, idx) in r.items" :key="it.id" class="item" :class="{ done: it.status === 'COMPLETED' }">
            <div class="item-info">
              <div class="it-name">
                <span class="it-idx">{{ idx + 1 }}</span>
                <span>{{ it.itemName }}</span>
              </div>
              <div class="it-sub">
                {{ it.category }}<template v-if="it.spec"> · {{ it.spec }}</template>
              </div>
            </div>

            <div class="it-state">
              <span class="pill" :class="PR_ITEM_STATUS_CLASS[it.status]">{{ PR_ITEM_STATUS_NAME[it.status] }}</span>
              <span v-if="it.erpNo" class="small muted">ERP <span class="mono">{{ it.erpNo }}</span></span>
              <span v-if="it.assetNo" class="small muted"><span class="mono">{{ it.assetNo }}</span></span>
              <span v-if="it.status === 'COMPLETED'" class="small muted">{{ it.deliveredBy }} · {{ it.deliveredAt }}</span>
            </div>

            <div v-if="r.status === 'APPROVED'" class="it-actions">
              <template v-if="it.status === 'PENDING'">
                <button class="btn sm" :disabled="acting === 'item' + it.id" @click="openAssign(r, it)">库存配发</button>
                <button class="btn sm" :disabled="acting === 'item' + it.id" @click="openOrder(r, it)">转采购</button>
              </template>
              <button v-else-if="it.status === 'ORDERED'" class="btn sm" :disabled="acting === 'item' + it.id"
                @click="openAssign(r, it)">到货配发</button>
              <button v-else-if="it.status === 'PENDING_PICKUP'" class="btn sm pri" :disabled="acting === 'item' + it.id"
                @click="deliver(r, it)">{{ acting === 'item' + it.id ? '处理中…' : '确认交付' }}</button>
            </div>
          </div>
        </div>

        <div v-if="r.status === 'PENDING'" class="row" style="margin-top:10px;gap:8px">
          <span class="sp" />
          <template v-if="r.dingInstanceId">
            <template v-if="ding.mock">
              <button class="btn sm" @click="mockDing(r, 'TERMINATED')">联调：模拟驳回</button>
              <button class="btn sm pri" @click="mockDing(r, 'COMPLETED')">联调：模拟通过</button>
            </template>
          </template>
          <template v-else>
            <button class="btn sm danger" :disabled="acting === r.id" @click="reject(r)">驳回</button>
            <button class="btn sm pri" :disabled="acting === r.id" data-tutorial-effect="write" @click="approve(r)">批准</button>
          </template>
        </div>
      </div>
    </template>

    <!-- 转采购：填 ERP -->
    <div v-if="showOrder" class="mask" @click.self="showOrder = false">
      <div class="dlg" role="dialog" aria-modal="true">
        <div class="dlg-h">转为采购<span class="x" @click="showOrder = false">×</span></div>
        <div class="dlg-b">
          <div class="small muted" style="margin-bottom:10px">
            {{ orderTarget?.request?.no }} · {{ orderTarget?.item?.itemName }}
          </div>
          <label class="fld"><span class="lb">ERP 采购单号<i>*</i></span>
            <input v-model="orderErpNo" class="inp" maxlength="64" placeholder="例：PO-2026-00123" />
          </label>
          <p class="small muted" style="margin:6px 0 0">
            只有这一台设备转为采购，同单其它设备不受影响。到货入库后在它上面点「到货配发」指定具体资产。
          </p>
        </div>
        <div class="dlg-f">
          <button class="btn" @click="showOrder = false">取消</button>
          <button class="btn pri" :disabled="!!acting" @click="saveOrder">确认转采购</button>
        </div>
      </div>
    </div>

    <!-- 配发设备 -->
    <div v-if="showAssign" class="mask" @click.self="showAssign = false">
      <div class="dlg assign-dialog" role="dialog" aria-modal="true">
        <div class="dlg-h">
          <div>
            <h3 style="margin:0;font-size:16px">为这一台设备选择资产</h3>
            <p class="small muted" style="margin:6px 0 0">
              {{ assignTarget?.request?.no }} · {{ assignTarget?.item?.itemName }}
              → 交付给 {{ assignTarget?.request?.applicant }}
            </p>
          </div>
          <span class="x" @click="showAssign = false">×</span>
        </div>
        <div class="assign-body">
          <AssetPicker v-model="assignAssetNo" v-model:category="assignCategory" :assets="spareAssets"
            :categories="[]" category-label="设备分类"
            empty-text="没有闲置/待入库且无人使用的设备，请先把到货设备录入资产台账。" />
        </div>
        <div class="dlg-f">
          <span class="small muted">{{ assignAssetNo || '请选择一台设备' }}</span>
          <span class="sp" />
          <button class="btn" @click="showAssign = false">取消</button>
          <button class="btn pri" :disabled="!assignAssetNo || !!acting" @click="confirmAssign">确认配发</button>
        </div>
      </div>
    </div>

    <!-- 目录编辑 -->
    <div v-if="showCatalog" class="mask" @click.self="showCatalog = false">
      <div class="dlg" role="dialog" aria-modal="true">
        <div class="dlg-h">{{ editingCatalog ? '编辑目录项' : '新增目录项' }}<span class="x" @click="showCatalog = false">×</span></div>
        <div class="dlg-b">
          <label class="fld"><span class="lb">设备名称 / 型号<i>*</i></span>
            <input v-model="catalogForm.name" class="inp" maxlength="120" placeholder="例：联想 ThinkPad T14s（32G/1T）" /></label>
          <label class="fld"><span class="lb">资产分类<i>*</i></span>
            <input v-model="catalogForm.category" class="inp" maxlength="64" placeholder="例：终端设备" /></label>
          <div class="fld">
            <span class="lb">卡片图标 <small class="muted">（不选则按分类和名称自动匹配）</small></span>
            <DeviceIconPicker v-model="catalogForm.icon" :category="catalogForm.category" :name="catalogForm.name"
              label="选择采购目录卡片图标" />
          </div>
          <label class="fld"><span class="lb">规格说明</span>
            <input v-model="catalogForm.spec" class="inp" maxlength="255" placeholder="CPU / 内存 / 硬盘等" /></label>
          <label class="fld"><span class="lb">备注</span>
            <input v-model="catalogForm.remark" class="inp" maxlength="255" /></label>
          <label class="fld" style="display:flex;align-items:center;gap:8px">
            <input v-model="catalogForm.enabled" type="checkbox" /> 启用（员工可申请）
          </label>
          <label class="fld"><span class="lb">排序</span>
            <input v-model.number="catalogForm.sort" class="inp" type="number" style="max-width:120px" /></label>
        </div>
        <div class="dlg-f">
          <button class="btn" @click="showCatalog = false">取消</button>
          <button class="btn pri" @click="saveCatalog">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.purchase-page { display:flex; flex-direction:column; }
.filters { display:flex; gap:6px; flex-wrap:wrap; margin:12px 0; }
.filters span { padding:6px 13px; border-radius:999px; background:#f1f3f6; color:var(--t2); font-size:12.5px; cursor:pointer; }
.filters span.on { background:var(--brand); color:#fff; font-weight:600; }

.pr-card { margin-bottom:10px; }
.pr-meta { margin-top:6px; line-height:1.8; }

.items { margin-top:10px; border:1px solid var(--line2); border-radius:12px; overflow:hidden; }
.item { display:flex; align-items:center; gap:12px; padding:10px 12px; border-bottom:1px solid var(--line2); background:#fff; }
.item:last-child { border-bottom:0; }
.item.done { background:#fbfdfb; }
.item-info { flex:1; min-width:0; }
.it-name { display:flex; align-items:center; gap:7px; font-size:12.5px; font-weight:600; }
.it-idx { flex:0 0 18px; height:18px; border-radius:5px; background:#eef2f7; color:var(--t3); font-size:10.5px; display:grid; place-items:center; font-weight:400; }
.it-sub { margin:3px 0 0 25px; font-size:11px; color:var(--t3); }
.it-state { display:flex; align-items:center; gap:8px; flex-wrap:wrap; flex-shrink:0; }
.it-actions { display:flex; gap:6px; flex-shrink:0; }
.it-actions .btn { white-space:nowrap; }

.cat-icon { flex:0 0 34px; width:34px; height:34px; border-radius:9px; padding:5px; }
.assign-dialog { max-width:960px; max-height:92vh; display:flex; flex-direction:column; overflow:hidden; }
.assign-dialog .dlg-h { flex-shrink:0; display:flex; justify-content:space-between; align-items:flex-start; gap:12px; }
.assign-body { min-height:0; overflow:auto; }

@media(max-width:720px) {
  .item { flex-wrap:wrap; gap:8px; }
  .item-info { flex:1 1 100%; }
  .it-sub { margin-left:25px; }
  .it-actions { margin-left:auto; }
}
</style>
