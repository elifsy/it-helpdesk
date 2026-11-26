<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetApi, assetCategoryApi, assetRequestApi, purchaseApi, type AssetCategory, type BorrowDueList } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useMissingReportStore } from '../../stores/missingReports'
import { assetActions, eligibleAssets } from '../../utils/assets'
import { ASSET_STATUS_CLASS, HANDOVER_HINT, HANDOVER_NAME, PR_ITEM_STATUS_CLASS, PR_ITEM_STATUS_NAME, PR_STATUS_CLASS, PR_STATUS_NAME, REQ_STATUS_CLASS, REQ_STATUS_NAME, REQ_TYPE } from '../../utils/dict'
import { autoIconKey } from '../../utils/deviceIcons'
import { toast } from '../../utils/toast'
import { notificationReference } from '../../utils/notification-links'
import { tutorialWorkflow } from '../../utils/tutorialPractice'
import PurchaseFlow from '../../components/PurchaseFlow.vue'
import AssetRequestFlow from '../../components/AssetRequestFlow.vue'
import DeviceIcon from '../../components/DeviceIcon.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const practiceType = tutorialWorkflow.value
const reportState = useMissingReportStore()
const restricted = computed(() => !practiceType && !!reportState.summary?.blocked)
const returnAsset = computed(() => notificationReference(route.query.returnAsset))
const dueAssets = ref<BorrowDueList>()
const dueError = ref('')
const dueSection = ref<HTMLElement>()
const returnSupplements = computed(() => dueAssets.value?.returnSupplementAssets || [])
watch([dueSection, () => route.query.tab], ([section, tab], _previous, onCleanup) => {
  if (!section || tab !== 'overdue') return
  const frame = requestAnimationFrame(() => section.scrollIntoView({ block: 'start' }))
  onCleanup(() => cancelAnimationFrame(frame))
}, { flush: 'post' })
const focusedApplication = computed(() => {
  const request = notificationReference(route.query.request)
  if (request) return { kind: 'asset', no: request }
  const purchase = notificationReference(route.query.purchase)
  return purchase ? { kind: 'purchase', no: purchase } : null
})
const assetFocused = computed(() => focusedApplication.value?.kind === 'asset')
const purchaseFocused = computed(() => focusedApplication.value?.kind === 'purchase')
const focusedRows = ref<any[]>([])
const focusLoading = ref(false)
const focusError = ref('')
let focusVersion = 0
const assets = ref<any[]>([])
const requestAssets = computed(() => restricted.value ? dueAssets.value?.overdue || [] : assets.value)
const categories = ref<AssetCategory[]>([])
const category = ref('')
const requests = ref<any[]>([])
const requestRows = computed(() => assetFocused.value ? focusedRows.value : requests.value)
const requestPageSize = 10
const requestPage = ref(1)
const requestPageCount = computed(() => Math.max(1, Math.ceil(requestRows.value.length / requestPageSize)))
const pageRequests = computed(() =>
  requestRows.value.slice((requestPage.value - 1) * requestPageSize, requestPage.value * requestPageSize))
const requestSection = ref<HTMLDetailsElement>()
watch([requestSection, () => route.query.tab], ([section, tab], _previous, onCleanup) => {
  if (!section || tab !== 'requests') return
  section.open = true
  const frame = requestAnimationFrame(() => section.scrollIntoView({ block: 'start' }))
  onCleanup(() => cancelAnimationFrame(frame))
}, { flush: 'post' })
const loading = ref(true)
const detail = ref<any>(null)
const deviceError = ref('')
const flow = ref<InstanceType<typeof AssetRequestFlow>>()
let deviceVersion = 0
let loadVersion = 0
const myId = computed(() => auth.user?.id)
const filteredAssets = computed(() => assets.value.filter(a => !category.value || a.category === category.value))
/** 本人待审批或待交接的申请涉及的设备，选择设备时排除，避免重复提交 */
const pendingRequests = computed(() => requests.value.filter(r => r.status === 'PENDING' || r.status === 'APPROVED'))
const pendingNos = computed(() => pendingRequests.value.map(r => r.assetNo))
const pendingReturns = computed(() => new Set(pendingRequests.value.filter(r => r.type === 'RETURN').map(r => r.assetNo)))
const dueLists = computed(() => {
  const overdue = dueAssets.value?.overdue || []
  return [
    { key: 'overdue', name: '逾期未申请归还', tone: 'b-red', rows: overdue.filter(device =>
      !pendingReturns.value.has(device.no) && !returnSupplements.value.includes(device.no)) },
    { key: 'supplement', name: '待补交归还', tone: 'b-orange', rows: overdue.filter(device => returnSupplements.value.includes(device.no)) },
    { key: 'pending', name: '归还处理中', tone: 'b-blue', rows: overdue.filter(device => pendingReturns.value.has(device.no)) },
    { key: 'upcoming', name: '即将到期', tone: 'b-orange', rows: restricted.value ? [] : dueAssets.value?.upcoming || [] }
  ].filter(group => group.rows.length)
})
const unavailableNos = computed(() => [...new Set([...pendingNos.value, ...returnSupplements.value])])
const quick = computed(() => (['RECEIVE', 'BORROW', 'REPLACE', 'RETURN'] as const).map(type => ({
  type, label: REQ_TYPE[type].name.replace('资产', ''), desc: REQ_TYPE[type].desc,
  count: practiceType === type ? 1 : eligibleAssets(assets.value, type, myId.value, category.value, unavailableNos.value).length
})))

/* ------------------------------ 购入申请 ------------------------------ */
/** 与「领用」同一套选择弹窗：选型号 → 填事由 → 提交 */
const purchaseFlow = ref<InstanceType<typeof PurchaseFlow>>()
const purchaseList = ref<any[]>([])
const purchaseRows = computed(() => purchaseFocused.value ? focusedRows.value : purchaseList.value)
const purchaseSection = ref<HTMLDetailsElement>()
const purchaseLoading = ref(false)
const purchaseError = ref('')
let purchaseLoadVersion = 0
const canReadRequests = computed(() => auth.can('request.view.own') || auth.can('request.view.all'))
type Cancellation = { kind: 'asset' | 'purchase'; id: number; no: string }
const cancelDialog = ref<HTMLDialogElement>()
const cancelTarget = ref<Cancellation | null>(null)
const cancelling = ref(false)
const cancelError = ref('')

async function loadFocus() {
  const version = ++focusVersion
  const target = focusedApplication.value
  focusedRows.value = []
  focusError.value = ''
  focusLoading.value = !!target
  if (!target || restricted.value) {
    focusLoading.value = false
    return
  }
  requestPage.value = 1
  try {
    const api = target.kind === 'asset' ? assetRequestApi : purchaseApi
    const rows = await api.list({ mine: true, no: target.no })
    if (version === focusVersion) focusedRows.value = rows || []
  } catch (e: any) {
    if (version === focusVersion) focusError.value = e.message || '申请读取失败，请重试'
  } finally {
    if (version === focusVersion) {
      focusLoading.value = false
      await nextTick()
      const section = target.kind === 'asset' ? requestSection.value : purchaseSection.value
      if (version === focusVersion) section?.scrollIntoView({ block: 'start' })
    }
  }
}
watch([focusedApplication, restricted], loadFocus, { immediate: true })

function openPurchase() {
  if (!restricted.value) purchaseFlow.value?.open()
}

async function askCancellation(kind: Cancellation['kind'], id: number, no: string) {
  if (cancelTarget.value || cancelling.value) return
  cancelTarget.value = { kind, id, no }
  cancelError.value = ''
  await nextTick()
  cancelDialog.value?.showModal()
}

function closeCancellation() {
  if (!cancelling.value) cancelDialog.value?.close()
}

function escapeCancellation(event: Event) {
  if (cancelling.value) event.preventDefault()
}

async function confirmCancellation() {
  const target = cancelTarget.value
  if (!target || cancelling.value) return
  cancelling.value = true
  cancelError.value = ''
  try {
    const api = target.kind === 'purchase' ? purchaseApi : assetRequestApi
    await api.cancel(target.id)
    cancelDialog.value?.close()
    toast('已撤销')
    await Promise.all([load(), loadFocus(), reportState.refresh(auth.user?.id)])
  } catch (cause) {
    cancelError.value = cause instanceof Error ? cause.message : '撤销失败，请重试'
  } finally {
    cancelling.value = false
  }
}

function onRequestSubmitted() {
  requestPage.value = 1
  if (requestSection.value) requestSection.value.open = true
  void reportState.refresh(auth.user?.id)
}

function onPurchaseSubmitted() {
  if (purchaseSection.value) purchaseSection.value.open = true
  void load()
}

async function loadPurchases() {
  const version = ++purchaseLoadVersion
  purchaseLoading.value = true
  purchaseError.value = ''
  try {
    const list = await purchaseApi.list({ mine: true })
    if (version === purchaseLoadVersion) purchaseList.value = list || []
  } catch (e: any) {
    if (version === purchaseLoadVersion) purchaseError.value = e.message || '购入申请加载失败'
  } finally {
    if (version === purchaseLoadVersion) purchaseLoading.value = false
  }
}

onMounted(async () => {
  await load()
  if (returnAsset.value) openReturnLink(returnAsset.value)
  else if (route.query.type) openPick(String(route.query.type))
  else if (route.query.asset) await openDevice(String(route.query.asset))
})
watch(restricted, () => { void load() })
watch(returnAsset, async no => {
  if (!no) return
  await load()
  if (returnAsset.value === no) openReturnLink(no)
})
watch(() => route.query.type, type => { if (type) openPick(String(type)) })
watch(() => route.query.asset, no => {
  if (no) void openDevice(String(no))
  else { deviceVersion++; detail.value = null; deviceError.value = '' }
})

function actionsOf(asset: any) {
  const pending = unavailableNos.value.includes(asset.no)
  return assetActions(asset, myId.value).filter(action => {
    // 已有待审批申请的设备，不再提供重复申请入口（报修不受影响）
    if (pending && action !== 'REPAIR') return false
    return action === 'REPAIR' ? auth.can('ticket.create') : auth.can('request.create')
  })
}
/** 设备详情弹窗里的操作，同样屏蔽待审批设备的重复申请 */
function detailActions() {
  const no = detail.value?.asset?.no
  const pending = no ? unavailableNos.value.includes(no) : false
  return (detail.value?.actions || []).filter((t: string) => !(pending && t !== 'REPAIR'))
}
async function load() {
  const version = ++loadVersion
  loading.value = true
  dueError.value = ''
  if (!restricted.value && canReadRequests.value) void loadPurchases()
  const due = practiceType ? Promise.resolve(undefined) : assetApi.borrowDue().catch((error: Error) => {
    if (version === loadVersion) dueError.value = error.message || '逾期设备加载失败'
    return undefined
  })
  try {
    const [a, r, c, dueList] = await Promise.all([
      restricted.value ? Promise.resolve([]) : assetApi.list({}),
      canReadRequests.value ? assetRequestApi.list({ mine: true }) : Promise.resolve([]),
      restricted.value ? Promise.resolve([]) : assetCategoryApi.list(),
      due
    ])
    if (version !== loadVersion) return
    dueAssets.value = dueList
    assets.value = a || []; requests.value = r || []; categories.value = c
    requestPage.value = Math.min(requestPage.value, requestPageCount.value)
    if (category.value && !c.some(item => item.name === category.value)) category.value = ''
  } catch (e: any) { if (version === loadVersion) toast(e.message) }
  finally { if (version === loadVersion) loading.value = false }
}
function openPick(type: string, no?: string) {
  if (!auth.can('request.create') || !['RECEIVE', 'BORROW', 'REPLACE', 'RETURN'].includes(type)) return
  if (restricted.value && type !== 'RETURN') return
  detail.value = null
  flow.value?.open(type, no)
}
function openReturnLink(no: string) {
  if (returnSupplements.value.includes(no)) {
    void router.replace('/missing-reports')
    return
  }
  const asset = requestAssets.value.find(item => item.no === no)
  if (asset && asset.status === '借出中' && asset.borrowerId === myId.value && auth.can('request.create')) {
    if (!pendingNos.value.includes(no)) {
      openPick('RETURN', no)
      return
    }
    if (requestSection.value) requestSection.value.open = true
    toast('该设备已有申请正在处理，可在「我的申请」查看')
  } else {
    toast('该设备当前无需归还，或账号没有申请权限')
  }
  void router.replace({ path: '/assets' })
}
function applyFor(type: string, no: string) {
  if (type === 'REPAIR') { router.push({ path: '/new', query: { asset: no } }); return }
  flow.value?.open(type, no)
}
function onFlowClosed() {
  if (route.query.type || route.query.asset || route.query.returnAsset) void router.replace({ path: '/assets' })
}
async function openDevice(no: string) {
  if (restricted.value) return
  const version = ++deviceVersion
  detail.value = null; deviceError.value = ''
  try { const result = await assetApi.detail(no); if (version === deviceVersion) detail.value = result }
  catch (e: any) { if (version === deviceVersion) deviceError.value = e.message || '设备读取失败，请重新尝试' }
}
function closeDetail() {
  deviceVersion++
  detail.value = null
  deviceError.value = ''
  if (route.query.asset) router.replace({ path: '/assets' })
}
</script>

<template>
  <div>
    <template v-if="!restricted">
    <div v-if="deviceError" class="device-error" role="alert">
      <span>{{ deviceError }}</span>
      <button v-if="route.query.asset" class="btn sm" @click="openDevice(String(route.query.asset))">重新读取</button>
      <button class="btn sm" @click="closeDetail">关闭</button>
    </div>
    <div class="tip">选业务 → 按分类挑设备 → 填写事由提交。更换选择自己名下的在用设备，归还选择自己借用中的设备。</div>
    <div class="category-toolbar">
      <label for="service-category">资产分类</label>
      <select id="service-category" v-model="category" class="sel" aria-label="资产服务分类">
        <option value="">全部分类</option>
        <option v-for="c in categories" :key="c.id" :value="c.name">{{ c.name }}</option>
      </select>
    </div>

    <div v-if="auth.can('request.create')" class="sec-t" style="margin-top: 8px"><h3>发起资产申请</h3></div>
    <div v-if="auth.can('request.create')" class="grid">
      <div v-for="q in quick" :key="q.type" class="qcard" :class="{ dim: !q.count }" :data-workflow-asset="q.type"
        role="button" tabindex="0" @click="openPick(q.type)" @keydown.enter.prevent="($event.currentTarget as HTMLElement).click()"
        @keydown.space.prevent="($event.currentTarget as HTMLElement).click()">
        <div class="qi">{{ REQ_TYPE[q.type].icon }}</div>
        <div class="sp">
          <div class="qt">{{ q.label }}</div>
          <div class="qm" :title="q.count ? `可选 ${q.count} 台设备` : '暂无可选设备'">{{ q.count ? `可选 ${q.count} 台` : '暂无可选' }}</div>
        </div>
        <span style="color: var(--t4)">›</span>
      </div>
      <div class="qcard purchase-card" aria-label="购入申请" @click="openPurchase">
        <div class="qi">⊕</div>
        <div class="sp">
          <div class="qt">购入申请</div>
          <div class="qm">没有合适的设备可领？申请采购一台新的</div>
        </div>
        <span style="color: var(--t4)">›</span>
      </div>
    </div>

    </template>

    <section v-if="restricted || dueLists.length || dueError" ref="dueSection" class="service-section borrow-due" :aria-label="restricted ? '逾期未归还' : '借用到期'">
      <h3 class="due-heading">{{ restricted ? '逾期未归还' : '借用到期' }}</h3>
      <div class="section-content">
        <div v-if="dueError" class="empty" role="alert">{{ dueError }} <button type="button" class="btn sm" @click="load">重试</button></div>
        <div v-else-if="restricted && !dueLists.length" class="empty">{{ loading ? '正在读取逾期设备…' : '暂无逾期设备' }}</div>
        <div v-for="group in dueLists" :key="group.key" class="due-group">
          <h4 class="due-group-heading">{{ group.name }} <span class="pill" :class="group.tone">{{ group.rows.length }} 台</span></h4>
          <ul class="due-cards" role="list">
            <li v-for="device in group.rows" :key="device.no" class="card due-card">
              <div class="due-card-device">
                <span class="due-device-icon"><DeviceIcon :name="device.icon && device.icon !== 'auto' ? device.icon : autoIconKey(device.category, device.name)" /></span>
                <div class="due-device-identity">
                  <h5>{{ device.name }}</h5>
                  <span class="mono muted">{{ device.no }}</span>
                </div>
              </div>
              <div class="due-card-footer">
                <div class="due-date"><span class="muted">约定归还</span><time :datetime="device.borrowDue">{{ device.borrowDue }}</time></div>
                <router-link v-if="returnSupplements.includes(device.no)" class="btn sm" to="/missing-reports">补交归还申请</router-link>
                <span v-else-if="pendingNos.includes(device.no)" class="pill b-blue">归还申请处理中</span>
                <button v-else-if="auth.can('request.create')" type="button" class="btn sm" :class="{ pri: group.key === 'overdue' }"
                  @click="openPick('RETURN', device.no)">申请归还</button>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <template v-if="!restricted">
    <details ref="requestSection" class="service-section" :open="assetFocused || undefined">
      <summary class="service-summary">
        <span>我的申请</span>
        <span class="section-count">{{ requestRows.length }} 条</span>
        <span class="section-chevron" aria-hidden="true"></span>
      </summary>
      <div class="section-content request-list">
        <div v-if="assetFocused" class="row small">
          <span>当前申请 {{ focusedApplication?.no }}</span>
          <router-link to="/assets" class="btn sm">查看全部申请</router-link>
        </div>
        <div v-if="assetFocused ? focusLoading : loading && !requests.length" class="empty">加载中…</div>
        <div v-else-if="assetFocused && focusError" class="empty" role="alert">
          {{ focusError }} <button type="button" class="btn sm" @click="loadFocus">重新加载</button>
        </div>
        <div v-else-if="!requestRows.length" class="empty">{{ assetFocused ? '找不到这张申请或无权查看' : '还没有申请记录' }}</div>
        <div v-for="r in pageRequests" :key="r.id" class="card">
          <div class="row">
            <b style="font-size: 13px">{{ REQ_TYPE[r.type]?.name }}</b>
            <span class="pill" :class="REQ_STATUS_CLASS[r.status]">{{ REQ_STATUS_NAME[r.status] }}</span>
            <span v-if="r.status === 'PENDING' && r.dingInstanceId" class="pill b-blue">钉钉审批中</span>
            <span v-if="r.status === 'APPROVED' && r.handoverStatus" class="pill"
              :class="r.handoverStatus === 'PICKUP' ? 'b-green' : 'b-orange'">{{ HANDOVER_NAME[r.handoverStatus] }}</span>
            <span class="sp" />
            <span class="mono">{{ r.no }}</span>
          </div>
          <div class="small muted" style="margin-top: 6px; line-height: 1.7">
            设备：{{ r.assetName || r.assetNo }}<span v-if="r.assetCategory"> · {{ r.assetCategory }}</span><br />
            事由：{{ r.reason }}
            <template v-if="r.expectReturn"><br />约定归还：{{ r.expectReturn }}</template>
            <template v-if="r.newAssetNo"><br />替换新机：{{ r.newAssetNo }}</template>
            <template v-if="r.status === 'APPROVED' && r.handoverStatus"><br />
              <span style="color:var(--brand)">{{ HANDOVER_HINT[r.handoverStatus] }}</span>
            </template>
            <template v-if="r.dingInstanceId"><br />钉钉审批单：<span class="mono">{{ r.dingInstanceId }}</span><template v-if="r.dingMessage"> · {{ r.dingMessage }}</template></template>
            <template v-if="r.remark"><br />{{ r.remark }}</template>
          </div>
          <div class="row" style="margin-top: 8px">
            <span class="small muted">{{ r.createdAt }}</span>
            <span class="sp" />
            <button class="btn sm" @click="openDevice(r.assetNo)">查看设备</button>
            <button
              v-if="r.status === 'PENDING' && auth.can('request.create')"
              class="btn sm danger" type="button" :disabled="cancelling"
              @click="askCancellation('asset', r.id, r.no)"
            >
              撤销
            </button>
          </div>
        </div>
        <nav v-if="requestPageCount > 1" class="request-pager" aria-label="我的申请分页">
          <button type="button" class="btn sm" :disabled="loading || requestPage <= 1" @click="requestPage--">上一页</button>
          <span class="small muted" aria-live="polite">{{ requestPage }} / {{ requestPageCount }}</span>
          <button type="button" class="btn sm" :disabled="loading || requestPage >= requestPageCount" @click="requestPage++">下一页</button>
        </nav>
      </div>
    </details>

    <details ref="purchaseSection" class="service-section" :open="purchaseFocused || undefined">
      <summary class="service-summary">
        <span>购入申请</span>
        <span class="section-count">{{ purchaseRows.length }} 条</span>
        <span class="section-chevron" aria-hidden="true"></span>
      </summary>
      <div class="section-content purchase-list">
        <div v-if="purchaseFocused" class="row small">
          <span>当前购入申请 {{ focusedApplication?.no }}</span>
          <router-link to="/assets" class="btn sm">查看全部申请</router-link>
        </div>
        <div v-if="purchaseFocused ? focusLoading : purchaseLoading && !purchaseList.length" class="empty">加载中…</div>
        <div v-else-if="purchaseFocused ? focusError : purchaseError" class="empty" role="alert">
          {{ purchaseFocused ? focusError : purchaseError }}
          <button type="button" class="btn sm" @click="purchaseFocused ? loadFocus() : loadPurchases()">重新加载</button>
        </div>
        <div v-else-if="!purchaseRows.length" class="empty">{{ purchaseFocused ? '找不到这张购入申请或无权查看' : '还没有购入申请' }}</div>
        <div v-for="p in purchaseRows" :key="p.id" class="card">
          <div class="row">
            <b style="font-size: 13px">{{ p.itemSummary || p.itemName }}</b>
            <span class="pill" :class="PR_STATUS_CLASS[p.status]">{{ PR_STATUS_NAME[p.status] }}</span>
            <span v-if="p.status === 'APPROVED'" class="pill b-gray">已交付 {{ p.doneCount }}/{{ p.itemCount }}</span>
            <span class="sp" />
            <span class="mono">{{ p.no }}</span>
          </div>
          <div class="small muted" style="margin-top: 6px; line-height: 1.7">
            事由：{{ p.reason }}
            <template v-if="p.remark"><br />{{ p.remark }}</template>
          </div>
          <div class="pr-items">
            <div v-for="(it, idx) in p.items" :key="it.id" class="pr-item">
              <span class="pri-idx">{{ idx + 1 }}</span>
              <span class="pri-name">{{ it.itemName }}</span>
              <span class="pill" :class="PR_ITEM_STATUS_CLASS[it.status]">{{ PR_ITEM_STATUS_NAME[it.status] }}</span>
              <span v-if="it.status === 'ORDERED' && it.erpNo" class="small muted">ERP <span class="mono">{{ it.erpNo }}</span></span>
              <span v-if="it.status === 'PENDING_PICKUP'" class="small muted">待领取 <span class="mono">{{ it.assetNo }}</span></span>
              <span v-if="it.status === 'COMPLETED'" class="small muted"><span class="mono">{{ it.assetNo }}</span> · {{ it.deliveredAt }}</span>
            </div>
          </div>
          <div v-if="p.items?.some((i: any) => i.status === 'PENDING_PICKUP')" class="small" style="margin-top: 6px; color: var(--brand)">
            有设备已备好，请到 IT 服务台领取；每台可单独领取。
          </div>
          <div class="row" style="margin-top: 8px">
            <span class="small muted">{{ p.createdAt }}</span>
            <span class="sp" />
            <button v-if="p.status === 'PENDING'" class="btn sm danger" type="button" :disabled="cancelling"
              @click="askCancellation('purchase', p.id, p.no)">撤销</button>
          </div>
        </div>
      </div>
    </details>

    <details class="service-section">
      <summary class="service-summary">
        <span>设备清单</span>
        <span class="section-count">{{ filteredAssets.length }} 台</span>
        <span class="section-chevron" aria-hidden="true"></span>
      </summary>
      <div class="section-content">
        <div v-if="loading && !filteredAssets.length" class="empty">加载中…</div>
        <div v-else-if="!filteredAssets.length" class="empty">该分类下暂无资产</div>
        <div v-else class="asset-list">
          <div v-for="a in filteredAssets" :key="a.no" class="card">
            <div class="card-head">
              <div class="ch-title">{{ a.name }}</div>
              <div class="ch-meta">
                <span class="mono">{{ a.no }}</span>
                <span v-if="pendingNos.includes(a.no)" class="pill b-orange">申请待审批</span>
                <span v-else-if="a.reservedBy" class="pill b-orange">待领取 · {{ a.reservedBy }}</span>
                <span class="pill" :class="ASSET_STATUS_CLASS[a.status] || 'b-gray'">{{ a.status }}</span>
              </div>
            </div>
            <div class="small muted" style="margin-top: 6px">
              分类 {{ a.category }} · {{ a.userName ? `使用人 ${a.userName}` : '公用 / 未分配' }}
              <template v-if="a.borrower"> · 借用人 {{ a.borrower }}</template>
              · 位置 {{ a.location }}
            </div>
            <div class="row" style="flex-wrap: wrap; margin-top: 8px; gap: 6px">
              <button
                v-for="t in actionsOf(a).filter((x) => x !== 'REPAIR')"
                :key="t"
                class="btn sm"
                @click="applyFor(t, a.no)"
              >
                {{ REQ_TYPE[t].name }}
              </button>
              <button class="btn sm" @click="openDevice(a.no)">详情</button>
            </div>
          </div>
        </div>
      </div>
    </details>

    </template>
    <!-- 选设备 / 申请表单：与员工首页共用同一套流程 -->
    <AssetRequestFlow
      ref="flow"
      :assets="requestAssets"
      :categories="categories"
      :loading="loading"
      :reload="load"
      :pending-nos="unavailableNos"
      @submitted="onRequestSubmitted"
      @closed="onFlowClosed"
    />

    <!-- 设备详情 -->
    <div v-if="!restricted && detail" class="mask" @click.self="closeDetail">
      <div class="dlg device-dialog" role="dialog" aria-modal="true" aria-label="设备操作" :data-asset-no="detail.asset.no">
        <div class="dlg-h">设备操作<span class="x" @click="closeDetail">×</span></div>
        <div class="dlg-b">
          <div class="row">
            <b>{{ detail.asset.name }}</b>
            <span class="sp" />
            <span class="pill" :class="ASSET_STATUS_CLASS[detail.asset.status] || 'b-gray'">{{ detail.asset.status }}</span>
          </div>
          <div class="mono" style="margin-top: 4px">{{ detail.asset.no }} · SN {{ detail.asset.sn || '—' }}</div>
          <div class="small muted" style="margin-top:6px">型号：{{ detail.asset.model || '—' }}</div>
          <div class="small muted" style="margin-top: 8px; line-height: 1.8">
            分类：{{ detail.asset.category }}<br />
            使用人：{{ detail.asset.userName || '公用 / 未分配' }}<br />
            位置：{{ detail.asset.location }}<br />
            维保：{{ detail.asset.warranty }}，至 {{ detail.asset.warrantyEnd || '—' }}
          </div>
          <div class="row device-actions" style="flex-wrap: wrap; gap: 6px; margin-top: 12px">
            <button v-for="t in detailActions()" :key="t" class="btn sm" @click="applyFor(t, detail.asset.no)">
              {{ REQ_TYPE[t]?.name || t }}
            </button>
            <button v-if="auth.can('asset.create')" class="btn sm" @click="router.push('/admin/assets/' + encodeURIComponent(detail.asset.no))">编辑与管理</button>
          </div>
          <div class="sec-t"><h3>资产履历</h3></div>
          <div v-if="!detail.history?.length" class="empty">暂无履历</div>
          <div class="tl">
            <div v-for="h in detail.history" :key="h.id" class="tli">
              <span class="dot" />
              <div class="tt">{{ h.detail }}</div>
              <div class="tm">{{ h.createdAt }} · {{ h.operator }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
  <PurchaseFlow v-if="!restricted" ref="purchaseFlow" @submitted="onPurchaseSubmitted" />
  <dialog ref="cancelDialog" class="dlg cancel-dialog" role="alertdialog"
    aria-labelledby="cancel-application-title" aria-describedby="cancel-application-description"
    @cancel="escapeCancellation" @close="cancelTarget = null">
    <template v-if="cancelTarget">
      <div id="cancel-application-title" class="dlg-h">确认撤销{{ cancelTarget.kind === 'purchase' ? '购入申请' : '资产申请' }}</div>
      <div class="dlg-b">
        <p id="cancel-application-description">是否撤销申请 <strong>{{ cancelTarget.no }}</strong>？</p>
        <p v-if="cancelError" class="cancel-error" role="alert">{{ cancelError }}</p>
      </div>
      <div class="dlg-f">
        <button class="btn" type="button" :disabled="cancelling" autofocus @click="closeCancellation">返回</button>
        <button class="btn cancel-confirm" type="button" :disabled="cancelling" @click="confirmCancellation">
          {{ cancelling ? '正在撤销…' : '确认撤销' }}
        </button>
      </div>
    </template>
  </dialog>
</template>

<style scoped>
.borrow-due { padding-block:5px; }
.due-heading { font-size:16px; }
.due-group + .due-group { margin-top:16px; }
.due-group-heading { display:flex; align-items:center; flex-wrap:wrap; gap:7px; margin-bottom:7px; font-size:13px; }
.due-cards {
  display:grid;
  grid-template-columns:repeat(auto-fit, minmax(min(100%, 290px), 1fr));
  gap:8px;
  padding:0;
  margin:0;
  list-style:none;
}
.due-card { display:grid; grid-template-rows:1fr auto; gap:10px; min-width:0; padding:13px 14px; border:1px solid var(--line2); }
.due-card-device { display:flex; align-items:center; gap:10px; min-width:0; }
.due-device-icon {
  display:grid;
  place-items:center;
  flex:0 0 40px;
  height:40px;
  border-radius:11px;
  background:var(--brand-l);
  color:var(--brand);
}
.due-device-icon svg { width:28px; height:28px; }
.due-device-identity { min-width:0; overflow-wrap:anywhere; }
.due-device-identity h5 { margin:0; font-size:14px; line-height:1.45; }
.due-device-identity .mono { display:block; margin-top:3px; font-size:11px; }
.due-card-footer {
  display:flex;
  align-items:center;
  justify-content:space-between;
  flex-wrap:wrap;
  gap:8px;
  padding-top:9px;
  border-top:1px solid var(--line2);
}
.due-date { display:flex; flex-wrap:wrap; gap:3px 6px; min-width:0; font-size:12px; }
.due-date time { color:var(--t2); font-variant-numeric:tabular-nums; }

.cancel-dialog { max-width:min(420px, calc(100% - 32px)); margin:auto; border:0; padding:0; color:var(--t1); }
.cancel-dialog::backdrop { background:rgba(0, 0, 0, .42); }
.cancel-dialog p { margin:0; line-height:1.6; overflow-wrap:anywhere; }
.cancel-dialog .cancel-error { margin-top:10px; color:var(--red); }
.cancel-confirm { background:#b42318; border-color:#b42318; color:#fff; }
.cancel-confirm:not(:disabled):hover { background:#942017; border-color:#942017; }
.service-section { margin-top: 12px; min-width: 0; }
.service-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 10px 14px;
  border: 1px solid var(--line2);
  border-radius: 12px;
  background: #fff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  list-style: none;
}
.service-summary::-webkit-details-marker { display: none; }
.service-section { scroll-margin-top: calc(96px + env(safe-area-inset-top, 0px)); }
.service-summary:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.section-count { color: var(--t3); font-size: 12px; font-weight: 400; }
.section-chevron {
  width: 8px;
  height: 8px;
  margin-left: auto;
  margin-right: 3px;
  border-right: 2px solid var(--t3);
  border-bottom: 2px solid var(--t3);
  transform: translateY(-2px) rotate(45deg);
}
.service-section[open] > .service-summary .section-chevron { transform: translateY(2px) rotate(225deg); }
.section-content { margin-top: 8px; }
.request-list, .purchase-list, .asset-list { display: grid; gap: 8px; }
.request-list .card, .purchase-list .card, .asset-list .card { margin: 0; min-width: 0; }
.request-list .card .row, .purchase-list .card .row { flex-wrap: wrap; }
.request-pager { display: flex; justify-content: center; align-items: center; gap: 12px; padding: 4px 0; }

.pr-items { margin-top:8px; border:1px solid var(--line2); border-radius:10px; overflow:hidden; }
.pr-item { display:flex; align-items:center; gap:7px; padding:8px 10px; border-bottom:1px solid var(--line2); font-size:11.5px; }
.pr-item:last-child { border-bottom:0; }
.pri-idx { flex:0 0 17px; height:17px; border-radius:4px; background:#eef2f7; color:var(--t3); font-size:10px; display:grid; place-items:center; }
.pri-name { flex:1; min-width:0; overflow-wrap:anywhere; color:var(--t2); }

/* 购入申请：与首页一致，整行横跨的补充入口 */
.purchase-card { grid-column: 1 / -1; border: 1px dashed #cfd9e6; box-shadow: none; background: #fcfdff; }
.purchase-card:hover { border-color: #94bff1; box-shadow: 0 8px 22px rgba(0, 0, 0, 0.06); }
.purchase-card .qi { background: #eef2f7; color: var(--t2); }

.card-head { display:flex; flex-direction:column; gap:5px; min-width:0; }
.ch-title { font-size:13.5px; font-weight:600; line-height:1.5; overflow-wrap:anywhere; }
.ch-meta { display:flex; align-items:center; gap:6px; flex-wrap:wrap; min-width:0; }
.ch-meta .mono { flex:1 1 100%; }
.ch-meta .mono { font-size:11px; color:var(--t3); }

.device-error { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-bottom:14px; padding:12px; border-radius:12px; background:#fff0ee; color:#b32921; font-size:13px; }
.device-error span { flex:1 1 100%; }
.category-toolbar { display:flex; align-items:center; gap:12px; margin:14px 0; font-size:13px; }
.category-toolbar .sel { max-width:280px; }
.tip {
  background: var(--brand-l);
  border-radius: 9px;
  padding: 10px 13px;
  font-size: 12px;
  color: #1d4f91;
  line-height: 1.65;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
}
/* .qcard 系列样式在全局 main.css，与员工首页共用 */
@media (min-width: 900px) {
  .tip {
    max-width: 720px;
  }
  .grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .asset-list {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
}
</style>
