<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetApi, assetCategoryApi, assetRequestApi, type AssetCategory } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { assetActions, eligibleAssets } from '../../utils/assets'
import { ASSET_STATUS_CLASS, REQ_STATUS_CLASS, REQ_STATUS_NAME, REQ_TYPE } from '../../utils/dict'
import { toast } from '../../utils/toast'
import AssetRequestFlow from '../../components/AssetRequestFlow.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const assets = ref<any[]>([])
const categories = ref<AssetCategory[]>([])
const category = ref('')
const requests = ref<any[]>([])
const loading = ref(true)
const detail = ref<any>(null)
const deviceError = ref('')
const flow = ref<InstanceType<typeof AssetRequestFlow>>()
let deviceVersion = 0
const myName = computed(() => auth.user?.name || '')
const filteredAssets = computed(() => assets.value.filter(a => !category.value || a.category === category.value))
/** 本人待审批申请涉及的设备，选择设备时排除，避免重复提交 */
const pendingNos = computed(() => requests.value.filter(r => r.status === 'PENDING').map(r => r.assetNo))
const quick = computed(() => (['RECEIVE', 'BORROW', 'REPLACE', 'RETURN'] as const).map(type => ({
  type, label: REQ_TYPE[type].name.replace('资产', ''), desc: REQ_TYPE[type].desc,
  count: eligibleAssets(assets.value, type, myName.value, category.value, pendingNos.value).length
})))

onMounted(async () => {
  await load()
  if (route.query.type) openPick(String(route.query.type))
  else if (route.query.asset) await openDevice(String(route.query.asset))
})
watch(() => route.query.type, type => { if (type) openPick(String(type)) })
watch(() => route.query.asset, no => {
  if (no) void openDevice(String(no))
  else { deviceVersion++; detail.value = null; deviceError.value = '' }
})

function actionsOf(asset: any) {
  const pending = pendingNos.value.includes(asset.no)
  return assetActions(asset, myName.value).filter(action => {
    // 已有待审批申请的设备，不再提供重复申请入口（报修不受影响）
    if (pending && action !== 'REPAIR') return false
    return action === 'REPAIR' ? auth.can('ticket.create') : auth.can('request.create')
  })
}
/** 设备详情弹窗里的操作，同样屏蔽待审批设备的重复申请 */
function detailActions() {
  const no = detail.value?.asset?.no
  const pending = no ? pendingNos.value.includes(no) : false
  return (detail.value?.actions || []).filter((t: string) => !(pending && t !== 'REPAIR'))
}
async function load() {
  loading.value = true
  try {
    const [a, r, c] = await Promise.all([
      assetApi.list({}),
      auth.can('request.view.own') || auth.can('request.view.all') ? assetRequestApi.list({ mine: true }) : Promise.resolve([]),
      assetCategoryApi.list()
    ])
    assets.value = a || []; requests.value = r || []; categories.value = c
    if (category.value && !c.some(item => item.name === category.value)) category.value = ''
  } catch (e: any) { toast(e.message) }
  finally { loading.value = false }
}
function openPick(type: string, no?: string) {
  if (!auth.can('request.create') || !['RECEIVE', 'BORROW', 'REPLACE', 'RETURN'].includes(type)) return
  detail.value = null
  flow.value?.open(type, no)
}
function applyFor(type: string, no: string) {
  if (type === 'REPAIR') { router.push({ path: '/new', query: { asset: no } }); return }
  flow.value?.open(type, no)
}
function onFlowClosed() {
  if (route.query.type || route.query.asset) void router.replace({ path: '/assets' })
}
async function openDevice(no: string) {
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
      <div v-for="q in quick" :key="q.type" class="qcard" :class="{ dim: !q.count }" @click="openPick(q.type)">
        <div class="qi">{{ REQ_TYPE[q.type].icon }}</div>
        <div class="sp">
          <div class="qt">{{ q.label }}</div>
          <div class="qm" :title="q.count ? `可选 ${q.count} 台设备` : '暂无可选设备'">{{ q.count ? `可选 ${q.count} 台` : '暂无可选' }}</div>
        </div>
        <span style="color: var(--t4)">›</span>
      </div>
    </div>

    <div class="sec-t"><h3>我的申请</h3></div>
    <div v-if="!requests.length" class="empty">还没有申请记录</div>
    <div v-for="r in requests" :key="r.id" class="card" style="margin-bottom: 8px">
      <div class="row">
        <b style="font-size: 13px">{{ REQ_TYPE[r.type]?.name }}</b>
        <span class="pill" :class="REQ_STATUS_CLASS[r.status]">{{ REQ_STATUS_NAME[r.status] }}</span>
        <span class="sp" />
        <span class="mono">{{ r.no }}</span>
      </div>
      <div class="small muted" style="margin-top: 6px; line-height: 1.7">
        设备：{{ r.assetName || r.assetNo }}<span v-if="r.assetCategory"> · {{ r.assetCategory }}</span><br />
        事由：{{ r.reason }}
        <template v-if="r.expectReturn"><br />约定归还：{{ r.expectReturn }}</template>
        <template v-if="r.newAssetNo"><br />替换新机：{{ r.newAssetNo }}</template>
        <template v-if="r.remark"><br />{{ r.remark }}</template>
      </div>
      <div class="row" style="margin-top: 8px">
        <span class="small muted">{{ r.createdAt }}</span>
        <span class="sp" />
        <button class="btn sm" @click="openDevice(r.assetNo)">查看设备</button>
        <button
          v-if="r.status === 'PENDING' && auth.can('request.create')"
          class="btn sm danger"
          @click="assetRequestApi.cancel(r.id).then(load)"
        >
          撤销
        </button>
      </div>
    </div>

    <div class="sec-t">
      <h3>设备清单 <span class="small muted" style="font-weight: 400">（{{ filteredAssets.length }} 台）</span></h3>
    </div>
    <div v-if="loading" class="empty">加载中…</div>
    <div class="asset-list">
      <div v-if="!loading && !filteredAssets.length" class="empty">该分类下暂无资产</div>
      <div v-for="a in filteredAssets" :key="a.no" class="card" style="margin-bottom: 8px">
        <div class="row">
          <div class="sp">
            <div style="font-size: 13.5px; font-weight: 600">{{ a.name }}</div>
            <div class="mono" style="margin-top: 2px">{{ a.no }}</div>
          </div>
          <span v-if="pendingNos.includes(a.no)" class="pill b-orange">申请待审批</span>
          <span class="pill" :class="ASSET_STATUS_CLASS[a.status] || 'b-gray'">{{ a.status }}</span>
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

    <!-- 选设备 / 申请表单：与员工首页共用同一套流程 -->
    <AssetRequestFlow
      ref="flow"
      :assets="assets"
      :categories="categories"
      :loading="loading"
      :reload="load"
      :pending-nos="pendingNos"
      @submitted="load"
      @closed="onFlowClosed"
    />

    <!-- 设备详情 -->
    <div v-if="detail" class="mask" @click.self="closeDetail">
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
</template>

<style scoped>
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
  .asset-list .card {
    margin-bottom: 0;
  }
}
</style>
