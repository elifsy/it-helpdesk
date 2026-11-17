<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { assetApi, ticketApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { ASSET_STATUS_CLASS, STATUS_CLASS, statusName } from '../utils/dict'

const props = defineProps<{ kind: 'problem' | 'assets'; assetNo?: string; status?: string; label: string }>()
const emit = defineEmits<{ close: []; count: [value: number] }>()
const auth = useAuthStore()
const dialog = ref<HTMLElement>()
const assets = ref<any[]>([])
const asset = ref<any>(null)
const history = ref<any[]>([])
const tickets = ref<any[]>([])
const keyword = ref('')
const assetPage = ref(1)
const ticketPage = ref(1)
const ticketTotal = ref(0)
const pageSize = 10
const assetsLoading = ref(false)
const assetLoading = ref(false)
const ticketsLoading = ref(false)
const assetsError = ref('')
const assetError = ref('')
const ticketsError = ref('')
let loadVersion = 0
let ticketVersion = 0
const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
let previousOverflow = ''

const title = computed(() => props.kind === 'problem' ? '设备报修明细' : props.label + '明细')
const filteredAssets = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return value ? assets.value.filter(row => [row.no, row.name, row.category, row.sn, row.userName, row.borrower, row.location]
    .some(field => String(field || '').toLowerCase().includes(value))) : assets.value
})
const shownAssets = computed(() => filteredAssets.value.slice((assetPage.value - 1) * pageSize, assetPage.value * pageSize))
const assetPages = computed(() => Math.max(1, Math.ceil(filteredAssets.value.length / pageSize)))
const ticketPages = computed(() => Math.max(1, Math.ceil(ticketTotal.value / pageSize)))
watch(keyword, () => { assetPage.value = 1 })

async function loadAssets(version: number) {
  if (!auth.can('asset.view')) return
  assetsLoading.value = true
  try {
    const rows = await assetApi.list({ status: props.status || undefined })
    if (version !== loadVersion) return
    assets.value = rows || []
    emit('count', assets.value.length)
  } catch (error: any) { if (version === loadVersion) assetsError.value = error.message }
  finally { if (version === loadVersion) assetsLoading.value = false }
}
async function loadAsset(version: number) {
  if (!auth.can('asset.view') || !props.assetNo) return
  assetLoading.value = true
  try {
    const result = await assetApi.detail(encodeURIComponent(props.assetNo))
    if (version !== loadVersion) return
    asset.value = result.asset
    history.value = result.history || []
  } catch (error: any) {
    if (version === loadVersion) assetError.value = error.code === 404 ? '资产台账中未找到该设备，仍可查看下方历史报修记录。' : error.message
  } finally { if (version === loadVersion) assetLoading.value = false }
}
async function loadTickets(page: number, version = loadVersion) {
  if (!auth.can('ticket.view.all') || !props.assetNo) return
  const request = ++ticketVersion
  ticketPage.value = page
  ticketsLoading.value = true
  ticketsError.value = ''
  tickets.value = []
  try {
    const result = await ticketApi.list({ assetNo: props.assetNo, page, size: pageSize })
    if (version !== loadVersion || request !== ticketVersion) return
    tickets.value = result.list || []
    ticketTotal.value = result.total || 0
    emit('count', ticketTotal.value)
  } catch (error: any) { if (version === loadVersion && request === ticketVersion) ticketsError.value = error.message }
  finally { if (version === loadVersion && request === ticketVersion) ticketsLoading.value = false }
}
function load() {
  const version = ++loadVersion
  ticketVersion++
  assets.value = []; asset.value = null; history.value = []; tickets.value = []
  assetsError.value = ''; assetError.value = ''; ticketsError.value = ''
  assetsLoading.value = false; assetLoading.value = false; ticketsLoading.value = false
  keyword.value = ''; assetPage.value = 1; ticketPage.value = 1; ticketTotal.value = 0
  if (props.kind === 'assets') void loadAssets(version)
  else { void loadAsset(version); void loadTickets(1, version) }
}
watch([() => props.kind, () => props.assetNo, () => props.status], load, { immediate: true })
watch(() => auth.permissions.join(','), () => emit('close'))

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); emit('close'); return }
  if (event.key !== 'Tab') return
  const elements = Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not([disabled]),a[href],input,summary,[tabindex="0"]') || [])
    .filter(element => element.getClientRects().length > 0)
  const first = elements[0], last = elements[elements.length - 1]
  if (!first) { event.preventDefault(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.value)) { event.preventDefault(); first.focus() }
}
onMounted(async () => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  await nextTick()
  dialog.value?.querySelector<HTMLButtonElement>('.dialog-close')?.focus()
})
onUnmounted(() => {
  loadVersion++; ticketVersion++
  document.body.style.overflow = previousOverflow
  if (trigger && document.contains(trigger)) trigger.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="mask stats-data-mask" @click.self="emit('close')">
      <section ref="dialog" class="dlg stats-data-dialog" role="dialog" aria-modal="true" aria-labelledby="stats-data-title" tabindex="-1" @keydown="onKeydown">
        <header class="dlg-h"><h3 id="stats-data-title">{{ title }}</h3><button type="button" class="dialog-close" aria-label="关闭数据明细" @click="emit('close')">×</button></header>
        <div class="dlg-b">
          <template v-if="kind === 'assets'">
            <div v-if="!auth.can('asset.view')" class="notice">当前账号没有查看资产明细的权限。</div>
            <template v-else>
              <div class="data-toolbar"><input v-model="keyword" class="inp" placeholder="搜索编号、名称、分类、使用人" aria-label="搜索资产明细" /><span class="small muted">共 {{ filteredAssets.length }} 台</span></div>
              <div v-if="assetsError" class="data-error" role="alert">{{ assetsError }}<button class="btn sm" @click="load">重新加载</button></div>
              <div class="data-scroll">
                <table class="tb asset-data-table"><thead><tr><th>资产编号</th><th>设备名称</th><th>分类</th><th>状态</th><th>使用人 / 借用人</th><th>位置</th><th>维保到期</th></tr></thead><tbody>
                  <tr v-if="assetsLoading"><td colspan="7" class="empty" role="status">正在加载资产…</td></tr>
                  <tr v-else-if="!assetsError && !shownAssets.length"><td colspan="7" class="empty">{{ keyword ? '没有符合搜索条件的资产' : '暂无' + (status || '') + '资产' }}</td></tr>
                  <tr v-for="row in shownAssets" :key="row.no" class="asset-data-row"><td class="mono">{{ row.no }}</td><td>{{ row.name }}</td><td>{{ row.category }}</td><td><span class="pill" :class="ASSET_STATUS_CLASS[row.status] || 'b-gray'">{{ row.status }}</span></td><td>{{ row.borrower || row.userName || '—' }}</td><td>{{ row.location || '—' }}</td><td>{{ row.warrantyEnd || '—' }}</td></tr>
                </tbody></table>
              </div>
              <div v-if="!assetsLoading && !assetsError" class="data-pager"><button class="btn sm" :disabled="assetPage <= 1" @click="assetPage--">上一页</button><span>{{ assetPage }} / {{ assetPages }}</span><button class="btn sm" :disabled="assetPage >= assetPages" @click="assetPage++">下一页</button></div>
            </template>
          </template>
          <template v-else>
            <p class="selected-asset mono">{{ assetNo }}</p>
            <div v-if="!auth.can('asset.view')" class="notice">当前账号没有查看设备资料的权限。</div>
            <div v-if="assetLoading" class="empty" role="status">正在加载设备资料…</div>
            <div v-if="assetError" class="notice" role="status">{{ assetError }}<button class="btn sm" @click="load">重新加载</button></div>
            <dl v-if="asset" class="asset-info">
              <div><dt>设备名称</dt><dd>{{ asset.name }}</dd></div><div><dt>当前状态</dt><dd><span class="pill" :class="ASSET_STATUS_CLASS[asset.status] || 'b-gray'">{{ asset.status }}</span></dd></div>
              <div><dt>资产分类</dt><dd>{{ asset.category }}</dd></div><div><dt>SN 序列号</dt><dd class="mono">{{ asset.sn || '—' }}</dd></div>
              <div><dt>使用人 / 借用人</dt><dd>{{ asset.borrower || asset.userName || '—' }}</dd></div><div><dt>存放位置</dt><dd>{{ asset.location || '—' }}</dd></div>
              <div><dt>购置日期</dt><dd>{{ asset.buyDate || '—' }}</dd></div><div><dt>维保到期</dt><dd>{{ asset.warrantyEnd || '—' }}</dd></div>
              <div v-if="asset.remark" class="wide"><dt>备注</dt><dd>{{ asset.remark }}</dd></div>
            </dl>
            <details v-if="history.length" class="asset-history"><summary>资产履历（最近 {{ history.length }} 条）</summary><ul><li v-for="item in history" :key="item.id"><b>{{ item.action }}</b> · {{ item.detail }}<span>{{ item.createdAt }} · {{ item.operator }}</span></li></ul></details>
            <div class="repair-heading"><h4>历史报修工单</h4><span v-if="auth.can('ticket.view.all') && !ticketsLoading && !ticketsError" class="small muted">共 {{ ticketTotal }} 次 · 全部历史</span></div>
            <div v-if="!auth.can('ticket.view.all')" class="notice">当前账号没有查看全部工单明细的权限。</div>
            <template v-else>
              <div v-if="ticketsError" class="data-error" role="alert">{{ ticketsError }}<button class="btn sm" @click="loadTickets(ticketPage)">重新加载工单</button></div>
              <div class="data-scroll">
                <table class="tb repair-data-table"><thead><tr><th>工单号 / 提交时间</th><th>问题与处理结果</th><th>状态</th><th>报修人</th><th>接单 IT</th></tr></thead><tbody>
                  <tr v-if="ticketsLoading"><td colspan="5" class="empty" role="status">正在加载报修记录…</td></tr>
                  <tr v-else-if="!ticketsError && !tickets.length"><td colspan="5" class="empty">该设备暂无报修工单</td></tr>
                  <tr v-for="row in tickets" :key="row.no" class="repair-data-row"><td><div class="mono">{{ row.no }}</div><div class="record-meta">{{ row.createdAt }}</div></td><td><b>{{ row.title }}</b><p class="record-description">{{ row.description }}</p><p v-if="row.solutionNote" class="record-solution">处理结果：{{ row.solutionNote }}</p></td><td><span class="pill" :class="STATUS_CLASS[row.status] || 'b-gray'">{{ statusName(row.status) }}</span></td><td>{{ row.creatorName }}</td><td>{{ row.assigneeName || '待接单' }}</td></tr>
                </tbody></table>
              </div>
              <div v-if="!ticketsLoading && !ticketsError" class="data-pager"><button class="btn sm" :disabled="ticketPage <= 1" @click="loadTickets(ticketPage - 1)">上一页</button><span>{{ ticketPage }} / {{ ticketPages }}</span><button class="btn sm" :disabled="ticketPage >= ticketPages" @click="loadTickets(ticketPage + 1)">下一页</button></div>
            </template>
          </template>
        </div>
        <footer class="dlg-f"><button type="button" class="btn" @click="emit('close')">关闭</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.stats-data-dialog { max-width:1000px; max-height:88vh; display:flex; flex-direction:column; overflow:hidden; }
.dlg-h,.dlg-f { flex-shrink:0; }.dlg-h h3 { font-size:16px; }.dlg-b { overflow-y:auto; min-height:0; }
.dialog-close { border:0; background:transparent; color:var(--t3); font-size:26px; width:32px; height:32px; border-radius:8px; cursor:pointer; }
.dialog-close:hover { background:#f2f5f9; color:var(--t1); }.dialog-close:focus-visible { outline:2px solid var(--brand); }
.data-toolbar { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; margin-bottom:14px; }.data-toolbar .inp { width:min(360px,100%); }
.data-scroll { overflow-x:auto; }.asset-data-table { min-width:790px; }.repair-data-table { min-width:760px; }
.tb th,.tb td { padding:11px 10px; }.tb td { font-size:12px; }.pill { white-space:nowrap; }
.selected-asset { margin:0 0 14px; color:var(--brand); overflow-wrap:anywhere; }
.asset-info { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px 24px; padding:18px; margin:0; border-radius:14px; background:#f7f9fc; }
.asset-info dt { color:var(--t3); font-size:11.5px; margin-bottom:5px; }.asset-info dd { margin:0; color:var(--t1); font-size:13px; font-weight:600; overflow-wrap:anywhere; }.asset-info .wide { grid-column:1 / -1; }
.asset-history { margin-top:16px; font-size:12px; }.asset-history summary { cursor:pointer; color:var(--brand); }.asset-history ul { list-style:none; padding:0; margin:10px 0; }.asset-history li { padding:8px 0; border-bottom:1px solid var(--line2); }.asset-history li span { display:block; color:var(--t3); margin-top:4px; }
.repair-heading { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; margin:24px 0 12px; }.repair-heading h4 { font-size:14px; }
.record-meta { color:var(--t3); font-size:11px; margin-top:5px; white-space:nowrap; }.record-description,.record-solution { max-width:420px; white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.6; margin:5px 0 0; color:var(--t2); }.record-solution { color:var(--brand); }
.data-pager { display:flex; align-items:center; justify-content:center; gap:12px; margin-top:16px; font-size:12px; color:var(--t3); }
.notice,.data-error { padding:12px; border-radius:10px; margin:10px 0; font-size:12px; line-height:1.7; background:#f4f7fb; color:var(--t2); }.data-error { background:#fff0ee; color:var(--red); }.notice button,.data-error button { margin-left:10px; }
@media(max-width:600px) { .stats-data-mask { padding:10px; }.stats-data-dialog { max-height:92dvh; }.dlg-h,.dlg-b,.dlg-f { padding-left:14px; padding-right:14px; }.asset-info { grid-template-columns:1fr; gap:13px; }.data-toolbar .inp { width:100%; } }
</style>
