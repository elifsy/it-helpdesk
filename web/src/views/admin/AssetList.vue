<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetApi, assetCategoryApi, type AssetCategory } from '../../api'
import { ASSET_STATUS_CLASS } from '../../utils/dict'
import { toast } from '../../utils/toast'
import { useAuthStore } from '../../stores/auth'
import AssetFormFields from '../../components/AssetFormFields.vue'
import { emptyAssetForm } from '../../utils/asset-form'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const rows = ref<any[]>([])
const categories = ref<AssetCategory[]>([])
const category = computed(() => typeof route.query.category === 'string' ? route.query.category : '')
const keyword = ref('')
const status = computed(() => typeof route.query.status === 'string' ? route.query.status : '')
const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isSafeInteger(value) && value > 0 && value <= 2147483647 ? value : 1
})
const size = 15
const total = ref(0)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size)))
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const showAdd = ref(false)
const form = ref(emptyAssetForm())
const addError = ref('')
const showCategories = ref(false)
const categoryForm = reactive({ id: null as number | null, name: '', sort: 0 })
const categoryError = ref('')
const statuses = ['', '在用', '闲置', '借出中', '维修中', '待入库', '待报废', '已报废']
let loadVersion = 0

async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  rows.value = []
  total.value = 0
  try {
    const [assets, list] = await Promise.all([
      assetApi.page({ keyword: typeof route.query.keyword === 'string' ? route.query.keyword || undefined : undefined,
        status: status.value || undefined, category: category.value || undefined, page: page.value, size }),
      assetCategoryApi.list()
    ])
    if (version !== loadVersion) return
    if (!Array.isArray(assets.list)) throw new Error('资产列表数据加载失败，请刷新页面后重试')
    const lastPage = Math.max(1, Math.ceil(assets.total / size))
    if (page.value > lastPage) {
      await router.replace({ path: route.path, query: { ...route.query, page: lastPage > 1 ? String(lastPage) : undefined } })
      return
    }
    rows.value = assets.list
    total.value = assets.total || 0
    categories.value = list
    if (!form.value.category) form.value.category = category.value || list[0]?.name || ''
  } catch (e: any) { if (version === loadVersion) error.value = e.message }
  finally { if (version === loadVersion) loading.value = false }
}
watch([category, status, page, () => route.query.keyword], () => {
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  void load()
}, { immediate: true })
onUnmounted(() => { loadVersion++ })

async function writeQuery(changes: Record<string, string | undefined>, replace = false) {
  const target = { path: route.path, query: { ...route.query, ...changes } }
  if (router.resolve(target).fullPath === route.fullPath) await load()
  else if (replace) await router.replace(target)
  else await router.push(target)
}
function search() { void writeQuery({ keyword: keyword.value.trim() || undefined, page: undefined }) }
function selectFilter(key: 'category' | 'status', event: Event) {
  void writeQuery({ [key]: (event.target as HTMLSelectElement).value || undefined, keyword: keyword.value.trim() || undefined, page: undefined })
}
function setPage(value: number) { void writeQuery({ page: value > 1 ? String(value) : undefined }) }

function assetRoute(asset: any) {
  return { path: '/admin/assets/' + encodeURIComponent(asset.no), query: {
    ...(category.value ? { backCategory: category.value } : {}),
    ...(status.value ? { backStatus: status.value } : {}),
    ...(typeof route.query.keyword === 'string' && route.query.keyword ? { backKeyword: route.query.keyword } : {}),
    ...(page.value > 1 ? { backPage: String(page.value) } : {})
  } }
}
function openDetail(asset: any) { void router.push(assetRoute(asset)) }
function openAdd() {
  form.value = emptyAssetForm(category.value || categories.value[0]?.name || '')
  addError.value = ''
  showAdd.value = true
}
function closeAdd() { if (!saving.value) showAdd.value = false }
async function addAsset() {
  if (saving.value || !auth.canCreateAsset) return
  if (!form.value.no.trim() || !form.value.name.trim() || !form.value.category) { toast('请填写资产编号、名称并选择分类'); return }
  if (form.value.buyDate && form.value.warrantyEnd && form.value.warrantyEnd < form.value.buyDate) { addError.value = '维保到期日期不能早于购置日期'; return }
  addError.value = ''
  saving.value = true
  try {
    await assetApi.create({ ...form.value, buyDate: form.value.buyDate || null, warrantyEnd: form.value.warrantyEnd || null })
    showAdd.value = false
    toast('已入库')
    await load()
  } catch (e: any) { addError.value = e.message }
  finally { saving.value = false }
}
function editCategory(row?: AssetCategory) {
  categoryForm.id = row?.id || null; categoryForm.name = row?.name || ''; categoryForm.sort = row?.sort || 0
  categoryError.value = ''
}
async function saveCategory() {
  if (!categoryForm.name.trim()) { categoryError.value = '请填写分类名称'; return }
  saving.value = true
  categoryError.value = ''
  const previous = categories.value.find(item => item.id === categoryForm.id)?.name
  try {
    const payload = { name: categoryForm.name.trim(), sort: Number(categoryForm.sort) }
    const saved = categoryForm.id ? await assetCategoryApi.update(categoryForm.id, payload) : await assetCategoryApi.create(payload)
    const renameFilter = previous && category.value === previous
    if (!previous || form.value.category === previous) form.value.category = saved.name
    editCategory()
    if (renameFilter) await writeQuery({ category: saved.name, page: undefined }, true)
    else await load()
    toast('资产分类已保存')
  } catch (e: any) { categoryError.value = e.message }
  finally { saving.value = false }
}
async function deleteCategory(row: AssetCategory) {
  if (!window.confirm('确认删除分类“' + row.name + '”？')) return
  saving.value = true
  categoryError.value = ''
  try {
    await assetCategoryApi.remove(row.id)
    const clearFilter = category.value === row.name
    if (form.value.category === row.name) form.value.category = ''
    if (categoryForm.id === row.id) editCategory()
    if (clearFilter) await writeQuery({ category: undefined, page: undefined }, true)
    else await load()
    toast('分类已删除')
  } catch (e: any) { categoryError.value = e.message }
  finally { saving.value = false }
}
</script>

<template>
  <div>
    <div class="page-h">
      <div class="asset-heading"><h2>资产台账</h2><p class="ledger-count">共 {{ total }} 台 · 每页 {{ size }} 台</p></div>
      <div class="row asset-toolbar" style="gap: 8px">
        <input v-model="keyword" class="inp" style="width: 220px" placeholder="编号 / 名称 / 型号 / SN / 使用人" aria-label="搜索资产台账" @keyup.enter="search" />
        <select :value="category" class="sel category-filter" aria-label="资产分类筛选" @change="selectFilter('category', $event)">
          <option value="">全部分类</option><option v-for="c in categories" :key="c.id" :value="c.name">{{ c.name }}</option>
        </select>
        <select :value="status" class="sel" style="width: 120px" aria-label="资产状态筛选" @change="selectFilter('status', $event)">
          <option v-for="s in statuses" :key="s" :value="s">{{ s || '全部状态' }}</option>
        </select>
        <button class="btn" @click="search">查询</button>
        <button v-if="auth.canCreateAsset" class="btn" @click="editCategory(); showCategories = true">管理分类</button>
        <button v-if="auth.canCreateAsset" class="btn pri" @click="openAdd">＋ 新增资产</button>
      </div>
    </div>

    <div v-if="error" class="category-error" role="alert">{{ error }}</div>
    <div class="panel desktop-records">
      <table class="tb">
        <thead>
          <tr>
            <th>资产编号</th>
            <th>名称 / 型号</th>
            <th>类别</th>
            <th>SN</th>
            <th>使用人</th>
            <th>位置</th>
            <th>维保到期</th>
            <th>状态</th>
            <th class="asset-operation-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="9" class="empty">加载中…</td>
          </tr>
          <tr v-else-if="error"><td colspan="9" class="empty">资产加载失败，请重新查询</td></tr>
          <tr v-else-if="!rows.length">
            <td colspan="9" class="empty">没有资产</td>
          </tr>
          <tr v-for="a in rows" :key="a.no" class="asset-row" @click="openDetail(a)">
            <td class="mono"><router-link :to="assetRoute(a)" @click.stop>{{ a.no }}</router-link></td>
            <td><router-link style="font-weight:600" :to="assetRoute(a)" @click.stop>{{ a.name }}</router-link><div v-if="a.model" class="small muted asset-model">{{ a.model }}</div></td>
            <td>{{ a.category }}</td>
            <td class="mono">{{ a.sn || '—' }}</td>
            <td>{{ a.userName || '—' }}</td>
            <td>{{ a.location }}</td>
            <td>{{ a.warrantyEnd || '—' }}</td>
            <td><span class="pill" :class="ASSET_STATUS_CLASS[a.status] || 'b-gray'">{{ a.status }}</span></td>
            <td class="asset-operation-cell" @click.stop><button class="btn sm asset-detail-btn" @click="openDetail(a)">查看详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="mobile-records">
      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="error" class="empty">资产加载失败，请重新查询</div>
      <div v-else-if="!rows.length" class="empty">没有资产</div>
      <button v-for="asset in rows" :key="asset.no" class="asset-card" @click="openDetail(asset)">
        <div class="card-line"><span class="mono">{{ asset.no }}</span><span class="pill" :class="ASSET_STATUS_CLASS[asset.status] || 'b-gray'">{{ asset.status }}</span></div>
        <strong>{{ asset.name }}</strong>
        <div v-if="asset.model" class="card-meta asset-model">型号：{{ asset.model }}</div>
        <div class="card-line card-meta"><span>{{ asset.category }}</span><span>{{ asset.userName || '未分配使用人' }}</span></div>
        <div class="card-line card-meta"><span>{{ asset.location || '位置未填写' }}</span><span class="detail-link">查看详情 ›</span></div>
      </button>
    </div>

    <nav class="pager" aria-label="资产台账分页">
      <button class="btn sm" :disabled="loading || !!error || page <= 1" @click="setPage(page - 1)">上一页</button>
      <span class="small muted">{{ page }} / {{ pageCount }}</span>
      <button class="btn sm" :disabled="loading || !!error || page >= pageCount" @click="setPage(page + 1)">下一页</button>
    </nav>

    <div class="small muted" style="margin-top: 10px">
      点击设备编号或名称可查看详情、操作履历和二维码。
    </div>

    <div v-if="showAdd" class="mask" @click.self="closeAdd">
      <form class="dlg asset-add-dialog" role="dialog" aria-modal="true" aria-labelledby="asset-add-title" @submit.prevent="addAsset">
        <div class="dlg-h"><span id="asset-add-title">新增资产</span><button type="button" class="x close-add" aria-label="关闭新增资产" :disabled="saving" @click="closeAdd">×</button></div>
        <div class="dlg-b">
          <div v-if="addError" class="category-error" role="alert">{{ addError }}</div>
          <div class="fld"><label class="lb">资产编号<i>*</i><input v-model="form.no" class="inp" required maxlength="32" aria-label="资产编号" placeholder="IT-PC-2026-0001" :disabled="saving" /></label></div>
          <AssetFormFields :form="form" :categories="categories" category-label="新增资产分类" :disabled="saving">
            <template #category-actions><button type="button" class="btn sm" @click="editCategory(); showCategories = true">添加或管理分类</button></template>
          </AssetFormFields>
        </div>
        <div class="dlg-f">
          <button type="button" class="btn" :disabled="saving" @click="closeAdd">取消</button>
          <button class="btn pri" :disabled="saving || !form.category">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </div>

    <div v-if="showCategories" class="mask category-manager" @click.self="!saving && (showCategories = false)">
      <div class="dlg" style="max-width:640px">
        <div class="dlg-h">管理资产分类<span class="x" @click="!saving && (showCategories = false)">×</span></div>
        <div class="dlg-b">
          <p class="small muted">分类重命名会同步更新关联资产。使用中的分类需先调整资产归类，再删除。</p>
          <div v-if="categoryError" class="category-error" role="alert">{{ categoryError }}</div>
          <form class="category-form" @submit.prevent="saveCategory">
            <label>分类名称<input v-model="categoryForm.name" class="inp" maxlength="32" required placeholder="如：显示器、办公笔记本" aria-label="分类名称" /></label>
            <label>排序<input v-model.number="categoryForm.sort" class="inp" type="number" min="0" max="9999" required aria-label="分类排序" /></label>
            <button class="btn pri" :disabled="saving">{{ categoryForm.id ? '保存修改' : '新增分类' }}</button>
            <button v-if="categoryForm.id" class="btn" type="button" :disabled="saving" @click="editCategory()">取消编辑</button>
          </form>
          <table class="tb"><thead><tr><th>分类</th><th>排序</th><th>资产数</th><th>操作</th></tr></thead><tbody>
            <tr v-for="c in categories" :key="c.id"><td>{{ c.name }}</td><td>{{ c.sort }}</td><td>{{ c.assetCount }}</td><td><div class="asset-actions"><button class="btn sm" :disabled="saving" @click="editCategory(c)">编辑</button><button class="btn sm" :disabled="saving || c.assetCount > 0" :title="c.assetCount > 0 ? '请先调整该分类下的资产' : '删除分类'" @click="deleteCategory(c)">删除</button></div></td></tr>
            <tr v-if="!categories.length"><td colspan="4" class="empty">暂无分类，请先新增</td></tr>
          </tbody></table>
        </div><div class="dlg-f"><button class="btn" :disabled="saving" @click="showCategories = false">完成</button></div>
      </div>
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
.asset-toolbar { flex-wrap:wrap; justify-content:flex-end; }
.asset-heading { flex-shrink:0; }.ledger-count { margin:6px 0 0; font-size:11.5px; color:var(--t3); }.pager { display:flex; align-items:center; justify-content:center; gap:12px; margin-top:14px; }.pager .btn { white-space:nowrap; }
.category-filter { width:150px; }
.asset-actions { display:flex; flex-wrap:wrap; gap:6px; }
.tb .asset-operation-cell { width:108px; white-space:nowrap; text-align:center; }
.asset-detail-btn { display:inline-flex; align-items:center; justify-content:center; min-width:84px; white-space:nowrap; line-height:1; }
.asset-row { cursor:pointer; }.asset-row:hover { background:#f5f9ff; }.asset-row a { color:#006dcc; text-decoration:none; }.asset-row a:hover { text-decoration:underline; }
.category-error { background:#fff0ee; color:#b32921; padding:12px; border-radius:10px; margin:10px 0; font-size:12px; }
.category-form { display:flex; align-items:end; flex-wrap:wrap; gap:10px; margin:14px 0; }
.category-form label { display:flex; flex-direction:column; gap:7px; font-size:12px; flex:1; min-width:120px; }
.category-form label:nth-child(2) { flex:0 0 80px; min-width:80px; }
.category-manager .dlg-b { max-height:70vh; overflow:auto; }
.asset-add-dialog { max-width:650px; display:flex; flex-direction:column; overflow:hidden; }
.asset-add-dialog .dlg-b { overflow-y:auto; min-height:0; }.asset-add-dialog .dlg-h,.asset-add-dialog .dlg-f { flex-shrink:0; }
.asset-add-dialog .lb .inp { display:block; margin-top:7px; }.close-add { border:0; background:none; padding:0; }
.asset-model { margin-top:4px; overflow-wrap:anywhere; }
@media(max-width:900px) { .page-h { flex-direction:column; align-items:stretch; gap:12px; }.asset-toolbar { justify-content:flex-start; } }
.page-h h2 {
  font-size: 20px;
  flex-shrink: 0;
  white-space: nowrap;
}
.page-h { gap:16px; align-items:flex-start; }
.asset-toolbar { flex:1; min-width:0; }
.panel {
  background: #fff;
  border-radius: var(--r);
  box-shadow: var(--shadow);
  overflow: auto;
}
.mobile-records { display:none; }
@media(max-width:899px) {
  .desktop-records { display:none; }
  .mobile-records { display:grid; gap:12px; }
  .asset-card { display:grid; gap:11px; width:100%; padding:16px; border:0; border-radius:18px; background:#fff; text-align:left; color:var(--t1); box-shadow:var(--shadow); }
  .asset-card strong { font-size:15px; font-weight:600; overflow-wrap:anywhere; }
  .card-line { display:flex; align-items:center; justify-content:space-between; gap:10px; min-width:0; }
  .card-line .mono { min-width:0; font-size:11px; overflow-wrap:anywhere; }
  .card-meta { color:var(--t2); font-size:12px; }.detail-link { color:var(--brand); white-space:nowrap; }
  .asset-toolbar .inp { flex-basis:100%; }
}
</style>
