<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { kbApi } from '../../api'
import { toast } from '../../utils/toast'
import { useAuthStore } from '../../stores/auth'
import KnowledgeSearch from '../../components/KnowledgeSearch.vue'

const auth = useAuthStore(), router = useRouter()
const canManage = computed(() => auth.can('kb.manage'))
const rows = ref<any[]>([]), keyword = ref(''), status = ref('DRAFT')
const loading = ref(false), loadError = ref(''), page = ref(1), total = ref(0)
const size = 20, pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const sourceNo = ref(''), captureBusy = ref(false), captureError = ref('')
const current = ref<any>(null), busy = ref(false), editError = ref('')
const form = reactive({ title: '', category: '', summary: '', content: '' })
const dirty = computed(() => current.value && Object.keys(form).some(key => form[key as keyof typeof form] !== current.value[key]))
const stateName: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }
let loadVersion = 0, active = true

async function load(target = 1) {
  const version = ++loadVersion
  loading.value = true; loadError.value = ''
  try {
    const params = { keyword: keyword.value.trim() || undefined, status: status.value || undefined, page: target, size }
    const result = canManage.value ? await kbApi.managedList(params) : await kbApi.list({ keyword: params.keyword })
    if (version !== loadVersion || !active) return
    if (canManage.value && target > Math.max(1, Math.ceil(result.total / size))) {
      await load(Math.max(1, Math.ceil(result.total / size))); return
    }
    rows.value = canManage.value ? result.list : result
    total.value = canManage.value ? result.total : result.length
    page.value = target
  } catch (e: any) { if (version === loadVersion && active) loadError.value = e.message || '加载失败，请重试' }
  finally { if (version === loadVersion && active) loading.value = false }
}
function setCurrent(article: any) {
  current.value = article
  Object.assign(form, { title: article.title, category: article.category, summary: article.summary || '', content: article.content || '' })
  editError.value = ''
}
async function open(id: number) {
  try { const article = await kbApi.managedDetail(id); if (active) setCurrent(article) }
  catch (e: any) { toast(e.message) }
}
async function capture() {
  if (!sourceNo.value.trim() || captureBusy.value) return
  captureBusy.value = true; captureError.value = ''
  try {
    const article = await kbApi.capture(sourceNo.value.trim())
    if (!active) return
    status.value = article.status; sourceNo.value = ''; setCurrent(article); await load()
    toast('已打开该工单的知识条目')
  } catch (e: any) { captureError.value = e.message || '沉淀失败，请重试' }
  finally { captureBusy.value = false }
}
async function save() {
  if (!current.value || busy.value) return
  if (Object.values(form).some(value => !value.trim())) { editError.value = '请填写标题、分类、摘要和处理步骤'; return }
  busy.value = true; editError.value = ''
  try {
    const article = await kbApi.save(current.value.id, { ...form, revision: current.value.revision })
    if (!active) return
    setCurrent(article); await load(page.value); toast('已保存为草稿，发布后用户可见')
  } catch (e: any) { editError.value = e.message || '保存失败，请重试' }
  finally { busy.value = false }
}
async function changeStatus(action: 'publish' | 'withdraw' | 'archive') {
  if (!current.value || busy.value) return
  if (dirty.value) { editError.value = '内容有修改，请先保存草稿后再操作'; return }
  busy.value = true; editError.value = ''
  try {
    const article = await kbApi.changeStatus(current.value.id, action, current.value.revision)
    if (!active) return
    setCurrent(article); await load(page.value); toast(action === 'publish' ? '已发布，用户可搜索并获得推荐' : action === 'withdraw' ? '已撤回为草稿' : '已归档')
  } catch (e: any) { editError.value = e.message || '操作失败，请重试' }
  finally { busy.value = false }
}
function close() { if (!busy.value) current.value = null }
onMounted(() => load())
onUnmounted(() => { active = false; loadVersion++ })
</script>

<template>
  <div>
    <div class="page-h">
      <h2>{{ canManage ? '知识草稿与发布' : '知识库' }}</h2>
      <KnowledgeSearch v-model="keyword" class="header-search" :managed="canManage" :status="canManage ? status : ''"
        :placeholder="canManage ? '搜索标题 / 摘要 / 来源工单' : '搜索标题 / 摘要'" @search="load()" />
    </div>
    <div class="tip">
      工单确认关闭后自动生成知识草稿。请整理为通用的排查步骤，去除人员、账号等内部信息；发布后才进入用户知识库和报修推荐。
    </div>
    <template v-if="canManage">
      <form v-if="auth.can('ticket.view.all')" class="capture row" @submit.prevent="capture">
        <label for="knowledge-ticket">历史工单沉淀</label>
        <input id="knowledge-ticket" v-model="sourceNo" class="inp" required maxlength="32" placeholder="输入已关闭工单号" :disabled="captureBusy" />
        <button class="btn" :disabled="captureBusy">{{ captureBusy ? '处理中…' : '生成 / 打开草稿' }}</button>
        <span class="small muted">同一工单只生成一次</span>
      </form>
      <p v-if="captureError" class="error" role="alert">{{ captureError }}</p>
      <div class="filters" aria-label="知识状态">
        <button v-for="(label, value) in { DRAFT: '待整理草稿', PUBLISHED: '已发布', ARCHIVED: '已归档', '': '全部' }" :key="value"
          class="btn sm" :class="{ pri: status === value }" :disabled="loading" @click="status = value; load()">{{ label }}</button>
      </div>
    </template>
    <div v-if="loadError" class="error" role="alert">{{ loadError }} <button class="btn sm" @click="load(page)">重试</button></div>
    <div class="panel">
      <table class="tb">
        <thead><tr><th>标题 / 摘要</th><th>分类</th><th v-if="canManage">状态</th><th v-if="canManage">来源工单</th><th>浏览 / 有帮助 / 没帮助</th><th v-if="canManage">操作</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td :colspan="canManage ? 6 : 3" class="empty">加载中…</td></tr>
          <tr v-else-if="!rows.length"><td :colspan="canManage ? 6 : 3" class="empty">{{ status === 'DRAFT' && canManage ? '暂无待整理草稿，工单确认关闭后会自动生成' : '没有匹配的知识条目' }}</td></tr>
          <tr v-for="k in loading ? [] : rows" :key="k.id" :data-knowledge-row="k.id">
            <td class="article-cell"><b>{{ k.title }}</b><div class="small muted summary">{{ k.summary }}</div></td>
            <td>{{ k.category }}</td>
            <td v-if="canManage"><span class="badge" :class="k.status">{{ stateName[k.status] }}</span></td>
            <td v-if="canManage"><button v-if="k.sourceTicketNo && auth.can('ticket.view.all')" class="source-link" @click="router.push('/admin/ticket/' + encodeURIComponent(k.sourceTicketNo))">{{ k.sourceTicketNo }}</button><span v-else>{{ k.sourceTicketNo || '已有知识' }}</span></td>
            <td>{{ k.views }} / {{ k.helpful }} / {{ k.notHelpful }}</td>
            <td v-if="canManage"><button class="btn sm" @click="open(k.id)">整理 / 查看</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="canManage" class="pagination row">
      <span class="small muted">共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span><span class="sp" />
      <button class="btn sm" :disabled="page <= 1 || loading" @click="load(page - 1)">上一页</button>
      <button class="btn sm" :disabled="page >= pages || loading" @click="load(page + 1)">下一页</button>
    </div>
    <div v-if="current" class="mask" @click.self="close">
      <div class="dlg knowledge-editor" role="dialog" aria-modal="true" aria-labelledby="knowledge-editor-title" :aria-busy="busy">
        <div class="dlg-h"><span id="knowledge-editor-title">整理知识 · {{ stateName[current.status] }}</span><button class="x" type="button" aria-label="关闭知识编辑" :disabled="busy" @click="close">×</button></div>
        <div class="dlg-b">
          <p class="small muted">{{ current.sourceTicketNo ? '来源工单：' + current.sourceTicketNo : '已有知识条目' }} · 保存修改后转为草稿，需重新发布。</p>
          <div class="fld"><label for="knowledge-title" class="lb">知识标题<i>*</i></label><input id="knowledge-title" v-model="form.title" class="inp" maxlength="200" :disabled="busy" /></div>
          <div class="fld"><label for="knowledge-category" class="lb">分类<i>*</i></label><input id="knowledge-category" v-model="form.category" class="inp" maxlength="32" :disabled="busy" /></div>
          <div class="fld"><label for="knowledge-summary" class="lb">摘要<i>*</i></label><textarea id="knowledge-summary" v-model="form.summary" class="ta summary-input" maxlength="500" :disabled="busy" /></div>
          <div class="fld"><label for="knowledge-content" class="lb">问题现象与处理步骤<i>*</i></label><textarea id="knowledge-content" v-model="form.content" class="ta content-input" maxlength="20000" :disabled="busy" /></div>
          <p v-if="dirty" class="small muted">有未保存修改，请先保存草稿。</p>
          <p v-if="!auth.can('kb.publish')" class="small muted">整理保存后，请有知识发布权限的人员审核发布。</p>
          <div v-if="editError" class="error" role="alert">{{ editError }} <button class="btn sm" :disabled="busy" @click="open(current.id)">重新加载</button></div>
        </div>
        <div class="dlg-f">
          <button v-if="current.status !== 'ARCHIVED' && (current.status !== 'PUBLISHED' || auth.can('kb.publish'))" class="btn" :disabled="busy || dirty" @click="changeStatus('archive')">归档</button>
          <button v-if="current.status === 'PUBLISHED' && auth.can('kb.publish')" class="btn" :disabled="busy || dirty" @click="changeStatus('withdraw')">撤回发布</button>
          <button class="btn" :disabled="busy" @click="save">{{ busy ? '处理中…' : '保存草稿' }}</button>
          <button v-if="current.status === 'DRAFT' && auth.can('kb.publish')" class="btn pri" :disabled="busy || dirty" @click="changeStatus('publish')">发布知识</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-h { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:14px; flex-wrap:wrap; }
.page-h h2 { font-size:20px; margin:0; }
.header-search { width:380px; max-width:100%; }
.tip { background:var(--brand-l); color:#1d4f91; border-radius:9px; padding:10px 13px; font-size:12.5px; margin-bottom:12px; }
.capture { flex-wrap:wrap; margin:12px 0; }
.capture label { font-size:13px; font-weight:600; }
.capture input { max-width:280px; }
.filters { display:flex; flex-wrap:wrap; gap:8px; margin:14px 0; }
.panel { background:#fff; border-radius:var(--r); box-shadow:var(--shadow); overflow:auto; }
.article-cell { min-width:200px; max-width:450px; overflow-wrap:anywhere; }
.summary { margin-top:4px; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.badge { display:inline-block; padding:4px 8px; border-radius:6px; background:#f3f4f6; color:var(--t2); white-space:nowrap; }
.badge.DRAFT { background:#fff4df; color:#946400; }.badge.PUBLISHED { background:#e7f7ed; color:#237943; }
.source-link { background:none; border:0; color:var(--brand); padding:0; cursor:pointer; white-space:nowrap; }
.pagination { margin-top:14px; flex-wrap:wrap; }
.error { color:#b83227; font-size:13px; margin:10px 0; }
.knowledge-editor { width:760px; max-width:calc(100vw - 24px); max-height:90vh; display:flex; flex-direction:column; }
.knowledge-editor .dlg-b { overflow-y:auto; min-height:0; }
.knowledge-editor .dlg-f { flex-wrap:wrap; flex-shrink:0; }
.knowledge-editor .x { background:none; border:0; }
.knowledge-editor .summary-input { min-height:75px; }
.knowledge-editor .content-input { min-height:220px; }
@media(max-width:899px) { .header-search { width:100%; }.capture input { max-width:100%; flex:1; min-width:180px; }.knowledge-editor { max-height:92dvh; }.knowledge-editor .dlg-f { gap:8px; } }
</style>
