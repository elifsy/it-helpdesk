<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import { assetApi, assetCategoryApi, type AssetCategory } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { ASSET_STATUS_CLASS } from '../../utils/dict'
import { toast } from '../../utils/toast'
import AssetFormFields from '../../components/AssetFormFields.vue'
import PersonPicker from '../../components/PersonPicker.vue'
import type { PersonOption } from '../../utils/people-search'
import DeviceIcon from '../../components/DeviceIcon.vue'
import { autoIconKey, iconClass, iconLabel } from '../../utils/deviceIcons'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const detail = ref<any>(null)
const asset = computed(() => detail.value?.asset)
const categories = ref<AssetCategory[]>([])
const loading = ref(false)
const error = ref('')
const saving = ref(false)
const showEdit = ref(false)
const editError = ref('')
const showCategory = ref(false)
const showHolders = ref(false)
const holderError = ref('')
const selectedUser = ref<PersonOption>()
const selectedBorrower = ref<PersonOption>()
const categoryError = ref('')
const targetCategory = ref('')
const showQr = ref(false)
const qrLoading = ref(false)
const qrError = ref('')
const qrContent = ref('')
const canvasRef = ref<HTMLCanvasElement>()
const form = reactive({
  erpNo: '', name: '', model: '', category: '', sn: '', location: '', buyDate: '', warranty: '',
  warrantyEnd: '', status: '', remark: '', icon: '', editToken: ''
})
let loadVersion = 0
const canEdit = computed(() => auth.can('asset.create'))
const unresolvedUser = computed(() => !!asset.value?.userName?.trim() && asset.value.userId == null)
const unresolvedBorrower = computed(() => !!asset.value?.borrower?.trim() && asset.value.borrowerId == null)
const backQuery = computed(() => ({
  ...(route.query.backPage ? { page: String(route.query.backPage) } : {}),
  ...(route.query.backCategory ? { category: String(route.query.backCategory) } : {}),
  ...(route.query.backStatus ? { status: String(route.query.backStatus) } : {}),
  ...(route.query.backKeyword ? { keyword: String(route.query.backKeyword) } : {})
}))
function back() { void router.push({ path: '/admin/assets', query: backQuery.value }) }
function detailIcon() { return asset.value?.icon || autoIconKey(asset.value?.category, asset.value?.name) }
function clearEditQuery() {
  if (route.query.edit) {
    const query = { ...route.query }; delete query.edit
    void router.replace({ path: route.path, query })
  }
}
async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const [result, list] = await Promise.all([assetApi.detail(String(route.params.no)), assetCategoryApi.list()])
    if (version !== loadVersion) return
    detail.value = result; categories.value = list
    if (route.query.edit === '1' && canEdit.value) openEdit()
    else if (route.query.edit) clearEditQuery()
  } catch (e: any) { if (version === loadVersion) error.value = e.message }
  finally { if (version === loadVersion) loading.value = false }
}
watch(() => route.params.no, () => {
  detail.value = null; showEdit.value = false; showCategory.value = false; showQr.value = false
  showHolders.value = false
  void load()
}, { immediate: true })
watch(() => route.query.edit, edit => { if (edit === '1' && asset.value && canEdit.value) openEdit() })
watch(canEdit, allowed => { if (!allowed) { showEdit.value = false; showCategory.value = false; showHolders.value = false; clearEditQuery() } })
onUnmounted(() => { loadVersion++ })
function openEdit() {
  if (!asset.value || !canEdit.value || saving.value) return
  const current = asset.value
  Object.assign(form, {
    erpNo: current.erpNo || '',
    name: current.name, model: current.model || '', category: current.category, sn: current.sn || '', location: current.location || '',
    buyDate: current.buyDate || '', warranty: current.warranty || '', warrantyEnd: current.warrantyEnd || '',
    status: current.status, remark: current.remark || '', icon: current.icon || '', editToken: detail.value.editToken
  })
  editError.value = ''
  showEdit.value = true
}
function closeEdit() { if (!saving.value) { showEdit.value = false; clearEditQuery() } }
function openHolders() {
  if (!canEdit.value || saving.value) return
  holderError.value = ''
  selectedUser.value = undefined
  selectedBorrower.value = undefined
  showHolders.value = true
}
async function saveHolders() {
  if (!canEdit.value || saving.value || (!selectedUser.value && !selectedBorrower.value)) return
  const no = asset.value.no
  saving.value = true
  holderError.value = ''
  try {
    const updated = await assetApi.bindHolders(no, {
      editToken: detail.value.editToken,
      userId: selectedUser.value?.id,
      borrowerId: selectedBorrower.value?.id
    })
    if (String(route.params.no) !== no) return
    detail.value = updated
    showHolders.value = false
    toast('资产归属已确认')
  } catch (e: any) { holderError.value = e.message }
  finally { saving.value = false }
}
async function reloadEditor() {
  if (!window.confirm('重新加载会放弃当前未保存的修改，确认继续？')) return
  showEdit.value = false
  await load()
  if (asset.value && !error.value) openEdit()
}
async function saveEdit() {
  if (saving.value || !canEdit.value) return
  saving.value = true; editError.value = ''
  const no = asset.value.no
  try {
    const updated = await assetApi.update(no, {
      ...form, buyDate: form.buyDate || null, warrantyEnd: form.warrantyEnd || null
    })
    if (String(route.params.no) !== no) return
    detail.value = updated
    showEdit.value = false; clearEditQuery()
    toast('设备信息已保存')
  } catch (e: any) { editError.value = e.message }
  finally { saving.value = false }
}
function openCategory() {
  if (!canEdit.value || !asset.value || saving.value) return
  targetCategory.value = asset.value.category; categoryError.value = ''; showCategory.value = true
}
async function saveCategory() {
  if (saving.value || !canEdit.value) return
  saving.value = true; categoryError.value = ''
  const no = asset.value.no
  try {
    await assetApi.setCategory(no, targetCategory.value)
    if (String(route.params.no) !== no) return
    showCategory.value = false
    await load()
    toast('资产分类已调整')
  } catch (e: any) { categoryError.value = e.message }
  finally { saving.value = false }
}
async function openQr() {
  if (!asset.value) return
  const no = asset.value.no
  showQr.value = true; qrLoading.value = true; qrError.value = ''; qrContent.value = ''
  try {
    const result = await assetApi.qrcode(no)
    if (!showQr.value || asset.value?.no !== no) return
    qrContent.value = result.content
    await nextTick()
    if (canvasRef.value) await QRCode.toCanvas(canvasRef.value, result.content, { width: 248, margin: 1 })
  } catch (e: any) { qrError.value = e.message }
  finally { qrLoading.value = false }
}
async function copyQr() {
  try {
    if (!navigator.clipboard) throw new Error('请选中下方地址复制')
    await navigator.clipboard.writeText(qrContent.value); toast('二维码地址已复制')
  } catch (e: any) { toast(e.message) }
}
function downloadQr() {
  if (!canvasRef.value || !asset.value) return
  const anchor = document.createElement('a')
  anchor.href = canvasRef.value.toDataURL('image/png')
  anchor.download = asset.value.no + '-二维码.png'
  anchor.click()
}
</script>

<template>
  <div class="asset-detail">
    <div class="detail-heading">
      <div><button class="back-link" @click="back">‹ 返回资产台账</button>
        <div class="detail-title">
          <span v-if="asset" class="detail-icon" :class="iconClass(detailIcon())"><DeviceIcon :name="detailIcon()" /></span>
          <div><h2>{{ asset?.name || '设备详情' }}</h2><p class="mono muted">{{ asset?.no || route.params.no }}<template v-if="asset"> · 图标：{{ asset.icon ? iconLabel(asset.icon) : '自动' }}</template></p></div>
        </div>
      </div>
      <div v-if="asset && !loading && !error" class="detail-actions">
        <button v-if="canEdit" class="btn pri" data-action="edit-asset" :disabled="saving" @click="openEdit">编辑设备</button>
        <button class="btn" data-action="asset-qrcode" @click="openQr">查看二维码</button>
        <button v-if="canEdit" class="btn" data-action="asset-category" :disabled="saving" @click="openCategory">调整分类</button>
      </div>
    </div>
    <div v-if="loading" class="empty" role="status">正在加载设备详情…</div>
    <div v-if="error" class="error-box" role="alert">{{ error }}<button class="btn sm" style="margin-left:12px" @click="load()">重新加载</button></div>
    <template v-if="asset && !loading && !error">
      <div class="detail-grid">
        <section class="panel">
          <div class="section-heading"><h3>设备资料</h3><span v-if="!canEdit" class="pill b-gray">只读</span></div>
          <dl class="info-grid">
            <div><dt>资产编号</dt><dd class="mono">{{ asset.no }}</dd></div>
            <div><dt>ERP资产编号</dt><dd class="mono">{{ asset.erpNo || '—' }}</dd></div>
            <div><dt>设备名称</dt><dd>{{ asset.name }}</dd></div>
            <div><dt>型号</dt><dd>{{ asset.model || '—' }}</dd></div>
            <div><dt>资产分类</dt><dd>{{ asset.category }}</dd></div>
            <div><dt>SN 序列号</dt><dd class="mono">{{ asset.sn || '—' }}</dd></div>
            <div><dt>存放位置</dt><dd>{{ asset.location || '—' }}</dd></div>
            <div><dt>购置日期</dt><dd>{{ asset.buyDate || '—' }}</dd></div>
            <div><dt>维保方式</dt><dd>{{ asset.warranty || '—' }}</dd></div>
            <div><dt>维保到期</dt><dd>{{ asset.warrantyEnd || '—' }}</dd></div>
            <div class="wide"><dt>备注</dt><dd class="remark">{{ asset.remark || '暂无备注' }}</dd></div>
          </dl>
        </section>
        <section class="panel">
          <div class="section-heading"><h3>使用与借用</h3><span class="pill" :class="ASSET_STATUS_CLASS[asset.status] || 'b-gray'">{{ asset.status }}</span></div>
          <dl class="usage">
            <div><dt>使用人</dt><dd>{{ asset.userName || '公用 / 未分配' }} <span v-if="unresolvedUser" class="small muted">待确认</span></dd></div>
            <div><dt>借用人</dt><dd>{{ asset.borrower || '未借出' }} <span v-if="unresolvedBorrower" class="small muted">待确认</span></dd></div>
            <div><dt>预计归还</dt><dd>{{ asset.borrowDue || '—' }}</dd></div>
            <div><dt>建档时间</dt><dd>{{ asset.createdAt || '—' }}</dd></div>
            <div><dt>最近更新</dt><dd>{{ asset.updatedAt || '—' }}</dd></div>
          </dl>
          <button v-if="canEdit && (unresolvedUser || unresolvedBorrower)" type="button" class="btn sm" @click="openHolders">确认资产归属</button>
        </section>
      </div>
      <section class="panel history-panel">
        <div class="section-heading"><h3>资产履历</h3><span class="muted small">最近 20 条</span></div>
        <div v-if="!detail.history?.length" class="empty">暂无资产履历</div>
        <ol v-else class="history">
          <li v-for="item in detail.history" :key="item.id"><div class="history-title"><b>{{ item.action }}</b><time>{{ item.createdAt }}</time></div><p>{{ item.detail }}</p><span class="muted small">操作人：{{ item.operator || '系统' }}</span></li>
        </ol>
      </section>
    </template>

    <div v-if="showHolders && asset" class="mask" @click.self="!saving && (showHolders = false)">
      <form class="dlg" @submit.prevent="saveHolders">
        <div class="dlg-h">确认资产归属<button class="close-btn" type="button" aria-label="关闭归属确认" :disabled="saving" @click="showHolders = false">×</button></div>
        <div class="dlg-b">
          <p class="hint">请按部门或工号核对旧台账姓名对应的人员账号。</p>
          <p v-if="holderError" class="error-box" role="alert">{{ holderError }}</p>
          <PersonPicker v-if="unresolvedUser" label="使用人" :manual-name="asset.userName" :search="assetApi.people"
            :selected="selectedUser" :disabled="saving" @select="selectedUser = $event" @clear="selectedUser = undefined" />
          <PersonPicker v-if="unresolvedBorrower" label="借用人" :manual-name="asset.borrower" :search="assetApi.people"
            :selected="selectedBorrower" :disabled="saving" @select="selectedBorrower = $event" @clear="selectedBorrower = undefined" />
        </div>
        <div class="dlg-f">
          <button class="btn" type="button" :disabled="saving" @click="showHolders = false">取消</button>
          <button class="btn pri" :disabled="saving || (!selectedUser && !selectedBorrower)">保存归属</button>
        </div>
      </form>
    </div>

    <div v-if="showEdit && asset" class="mask" @click.self="closeEdit">
      <form class="dlg edit-dialog" @submit.prevent="saveEdit">
        <div class="dlg-h">编辑设备<button type="button" class="close-btn" :disabled="saving" aria-label="关闭编辑" @click="closeEdit">×</button></div>
        <div class="dlg-b">
          <p class="hint">资产编号：<b class="mono">{{ asset.no }}</b></p>
          <div v-if="editError" class="error-box" role="alert">{{ editError }}<button type="button" class="btn sm" style="margin-top:8px" :disabled="saving" @click="reloadEditor">重新加载设备信息</button></div>
          <AssetFormFields :form="form" :categories="categories" :statuses="detail.editableStatuses" :disabled="saving" />
        </div>
        <div class="dlg-f"><button type="button" class="btn" :disabled="saving" @click="closeEdit">取消</button><button class="btn pri" :disabled="saving || !canEdit">{{ saving ? '保存中…' : '保存设备' }}</button></div>
      </form>
    </div>

    <div v-if="showCategory && asset" class="mask" @click.self="!saving && (showCategory = false)">
      <form class="dlg" @submit.prevent="saveCategory">
        <div class="dlg-h">调整资产分类<button class="close-btn" type="button" :disabled="saving" aria-label="关闭分类调整" @click="showCategory = false">×</button></div>
        <div class="dlg-b"><p><b>{{ asset.name }}</b></p><p class="hint mono">{{ asset.no }}</p><div v-if="categoryError" class="error-box" role="alert">{{ categoryError }}</div><label class="field">资产分类<select v-model="targetCategory" class="sel" required aria-label="详情调整分类"><option v-for="c in categories" :key="c.id" :value="c.name">{{ c.name }}</option></select></label></div>
        <div class="dlg-f"><button class="btn" type="button" :disabled="saving" @click="showCategory = false">取消</button><button class="btn pri" :disabled="saving || !canEdit">保存分类</button></div>
      </form>
    </div>

    <div v-if="showQr && asset" class="mask" @click.self="showQr = false">
      <div class="dlg qr-dialog">
        <div class="dlg-h">设备二维码<button class="close-btn" aria-label="关闭二维码" @click="showQr = false">×</button></div>
        <div class="dlg-b"><b>{{ asset.name }}</b><p class="hint mono">{{ asset.no }}</p><div v-if="qrLoading" class="empty">正在生成二维码…</div><div v-if="qrError" class="error-box" role="alert">{{ qrError }}</div><canvas v-show="!qrLoading && !qrError" ref="canvasRef" /><input v-if="qrContent" class="inp qr-url" readonly :value="qrContent" aria-label="二维码地址" @focus="($event.target as HTMLInputElement).select()" /></div>
        <div class="dlg-f"><button class="btn" :disabled="qrLoading || !!qrError || !qrContent" @click="copyQr">复制地址</button><button class="btn pri" :disabled="qrLoading || !!qrError || !qrContent" @click="downloadQr">下载二维码</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-title { display:flex; align-items:center; gap:12px; }
.detail-icon { flex:0 0 46px; width:46px; height:46px; border-radius:12px; padding:8px; }
.detail-heading,.detail-actions,.section-heading,.history-title { display:flex; align-items:center; gap:10px; }
.detail-heading { justify-content:space-between; align-items:flex-start; margin:0 0 20px; }.detail-actions { flex-wrap:wrap; justify-content:flex-end; padding-top:24px; }
h2 { font-size:25px; margin:10px 0 6px; overflow-wrap:anywhere; }h3 { font-size:15px; margin:0; }.back-link { border:0; background:none; color:#0071e3; cursor:pointer; padding:0; font:inherit; font-size:12px; }
.detail-grid { display:grid; grid-template-columns:minmax(0,1.6fr) minmax(250px,1fr); gap:16px; }
.panel { background:#fff; border-radius:16px; padding:20px; box-shadow:var(--shadow); }.section-heading { justify-content:space-between; margin-bottom:18px; }
.info-grid,.edit-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:18px; }.info-grid,.usage { margin:0; }.wide { grid-column:1/-1; }
dt { color:var(--t2); font-size:12px; margin-bottom:7px; }dd { font-size:13px; margin:0; overflow-wrap:anywhere; }.remark { white-space:pre-wrap; line-height:1.8; }
.usage>div { margin:0 0 16px; }.usage>div:last-child { margin-bottom:0; }.hint { color:var(--t2); font-size:12px; line-height:1.8; margin:10px 0; }
.history-panel { margin-top:16px; }.history { list-style:none; padding:0; margin:0; }.history li { padding:15px 0 15px 18px; border-left:2px solid #dbe9fa; border-bottom:1px solid var(--line2); }.history li:last-child { border-bottom:0; }
.history-title { justify-content:space-between; font-size:13px; flex-wrap:wrap; }.history time { font-size:11px; color:var(--t2); }.history p { font-size:12px; line-height:1.8; margin:8px 0; overflow-wrap:anywhere; }
.error-box { background:#fff0ee; color:#b32921; padding:12px; border-radius:10px; font-size:12px; margin-bottom:14px; }
.field { display:flex; flex-direction:column; gap:7px; font-size:12px; }.edit-dialog { max-width:650px; }.edit-dialog .dlg-b { max-height:68vh; overflow:auto; }
.close-btn { border:0; background:none; color:var(--t2); font-size:24px; line-height:1; margin-left:auto; cursor:pointer; }.dlg-h { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.qr-dialog { max-width:380px; }.qr-dialog .dlg-b { text-align:center; }.qr-dialog canvas { display:block; margin:0 auto 16px; max-width:100%; }.qr-url { font-size:11px; }
@media(max-width:1000px) { .detail-grid { grid-template-columns:1fr; }.detail-heading { flex-direction:column; }.detail-actions { padding-top:0; justify-content:flex-start; } }
@media(max-width:520px) { .info-grid,.edit-grid { grid-template-columns:1fr; }.panel { padding:16px; }.wide { grid-column:auto; }h2 { font-size:22px; } }
</style>
