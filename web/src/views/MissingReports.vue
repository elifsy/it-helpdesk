<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, toRaw, watch } from 'vue'
import { useRoute } from 'vue-router'
import { attachmentApi, metaApi, missingReportApi, type MissingReport, type MissingReportRequester } from '../api'
import { TICKET_TYPES, type TicketCategoriesByType } from '../config/ticket-types'
import { useAuthStore } from '../stores/auth'
import { useMissingReportStore } from '../stores/missingReports'
import { readMissingReportDraft, writeMissingReportDraft } from '../utils/missing-report-draft'
import { toast } from '../utils/toast'
import PersonPicker from '../components/PersonPicker.vue'

const route = useRoute()
const auth = useAuthStore()
const state = useMissingReportStore()
const staff = computed(() => route.path === '/admin/missing-reports')
const records = ref<MissingReport[]>([])
const detail = ref<Awaited<ReturnType<typeof missingReportApi.detail>>>()
const detailPanel = ref<HTMLElement>()
const page = ref(1)
const pages = ref(1)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const registrationOpen = ref(false)
const requester = ref<MissingReportRequester>()
const file = ref<File>()
const evidenceInput = ref<HTMLInputElement>()
const registrationForm = ref<HTMLFormElement>()
const emptyRegistration = { title: '', description: '', channel: 'CHAT', completedAt: '' }
const registration = reactive({ ...emptyRegistration })
const validationAttempted = ref(false)
const missing = computed(() => ({
  requester: !requester.value,
  title: !registration.title.trim(),
  channel: !registration.channel,
  completedAt: !registration.completedAt,
  description: !registration.description.trim(),
  evidence: !file.value?.size
}))
const registrationComplete = computed(() => Object.values(missing.value).every(value => !value))
const draftAccount = computed(() => staff.value && auth.user
  ? `${auth.config?.demoEnabled ? 'demo' : 'user'}:${auth.user.id}` : '')
const draftReady = ref(false)
const draftStatus = ref<'idle' | 'saving' | 'saved' | 'failed'>('idle')
const draft = computed(() => requester.value || file.value || registration.title || registration.description
  || registration.completedAt || registration.channel !== emptyRegistration.channel
  ? { registration: { ...registration }, requester: requester.value && toRaw(requester.value), evidence: file.value }
  : undefined)
const cancelReason = ref('')
const cancelling = ref(false)
const categories = ref<TicketCategoriesByType>({})
const form = reactive({ title: '', description: '', location: '', type: 'INCIDENT', cat1: '', cat2: '' })
const primary = computed(() => Object.keys(categories.value[form.type] || {}))
const secondary = computed(() => categories.value[form.type]?.[form.cat1] || [])
type ReportEvidence = Awaited<ReturnType<typeof missingReportApi.detail>>['evidence'][number]
type EvidencePreview = { status: 'loading' | 'failed'; url?: never } | { status: 'ready'; url: string }
const evidencePreviews = ref<Partial<Record<number, EvidencePreview>>>({})
const labels = { PENDING: '待补报', SUBMITTED: '已补报', CANCELLED: '已撤销' }
let listVersion = 0
let detailVersion = 0
let draftVersion = 0

function resetRegistration() {
  requester.value = undefined
  file.value = undefined
  Object.assign(registration, emptyRegistration)
  validationAttempted.value = false
}

function clearEvidence() {
  for (const preview of Object.values(evidencePreviews.value)) {
    if (preview?.url) URL.revokeObjectURL(preview.url)
  }
  evidencePreviews.value = {}
}

function closeDetail() {
  detailVersion++
  clearEvidence()
  detail.value = undefined
  cancelling.value = false
  cancelReason.value = ''
}

async function load() {
  const version = ++listVersion
  loading.value = true
  error.value = ''
  try {
    const result = staff.value ? await missingReportApi.list(page.value) : await missingReportApi.mine()
    if (version !== listVersion) return
    if (Array.isArray(result)) records.value = result
    else {
      records.value = result.list
      pages.value = Math.max(1, Math.ceil(result.total / result.size))
    }
  } catch (e: any) { if (version === listVersion) error.value = e.message || '事项加载失败' }
  finally { if (version === listVersion) loading.value = false }
}

function resetCategory() {
  form.cat1 = ''
  form.cat2 = ''
}

async function openDetail(row: MissingReport) {
  closeDetail()
  const version = detailVersion
  error.value = ''
  try {
    const result = await missingReportApi.detail(row.no)
    if (version !== detailVersion) return
    detail.value = result
    for (const item of result.evidence) {
      if (isImage(item)) void loadEvidence(item)
    }
    Object.assign(form, { title: result.report.title, description: result.report.description, location: '', type: 'INCIDENT' })
    resetCategory()
    await nextTick()
    if (version === detailVersion) detailPanel.value?.scrollIntoView({ block: 'start' })
    if (!staff.value && result.report.status === 'PENDING' && !Object.keys(categories.value).length)
      categories.value = await metaApi.categoriesByType()
  } catch (e: any) { if (version === detailVersion) error.value = e.message || '详情加载失败' }
}

function isImage(item: ReportEvidence) {
  return item.contentType?.startsWith('image/') || /\.(png|jpe?g|gif|webp)$/i.test(item.storedName)
}

async function loadEvidence({ id, storedName }: ReportEvidence) {
  const current = evidencePreviews.value[id]
  if (current && current.status !== 'failed') return
  const version = detailVersion
  evidencePreviews.value[id] = { status: 'loading' }
  try {
    const blob = await attachmentApi.blob(storedName)
    if (version === detailVersion) evidencePreviews.value[id] = { status: 'ready', url: URL.createObjectURL(blob) }
  } catch {
    if (version === detailVersion) evidencePreviews.value[id] = { status: 'failed' }
  }
}

function imageFailed(id: number, event: Event) {
  const url = evidencePreviews.value[id]?.url
  if (!url || (event.currentTarget as HTMLImageElement).src !== url) return
  URL.revokeObjectURL(url)
  evidencePreviews.value[id] = { status: 'failed' }
}

function selectEvidence(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  if (selected) file.value = selected
  input.value = ''
}

async function highlightMissingFields() {
  validationAttempted.value = true
  await nextTick()
  const fields = registrationForm.value?.querySelectorAll<HTMLElement>('[aria-invalid="true"]')
  if (!fields?.length) return
  fields[0].focus({ preventScroll: true })
  fields[0].scrollIntoView({ block: 'nearest' })
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  for (const field of fields) {
    field.animate?.([
      { boxShadow: '0 0 0 1px var(--red)' },
      { boxShadow: '0 0 0 5px var(--red-bg)' },
      { boxShadow: '0 0 0 1px var(--red)' }
    ], { duration: 400, iterations: 2 })
  }
}

async function register() {
  const account = draftAccount.value
  if (busy.value || !draftReady.value || !account) return
  if (!registrationComplete.value || !requester.value || !file.value) {
    await highlightMissingFields()
    return
  }
  busy.value = true
  error.value = ''
  try {
    const completedAt = registration.completedAt.replace('T', ' ')
    await missingReportApi.register({ ...registration, requesterId: requester.value.id,
      completedAt: completedAt.length === 16 ? completedAt + ':00' : completedAt }, file.value)
    const cleared = await writeMissingReportDraft(account).then(() => true, () => false)
    if (account !== draftAccount.value) return
    registrationOpen.value = false
    resetRegistration()
    page.value = 1
    await load()
    toast(cleared ? '已登记待补报事项' : '已登记待补报事项，草稿清理失败')
  } catch (e: any) { error.value = e.message || '登记失败' }
  finally { busy.value = false }
}

async function finish(cancel: boolean) {
  const no = detail.value?.report.no
  if (!no || busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (cancel) await missingReportApi.cancel(no, cancelReason.value.trim())
    else await missingReportApi.submit(no, form)
    closeDetail()
    await Promise.all([load(), state.refresh(auth.user?.id)])
    toast(cancel ? '已撤销登记' : '补报已提交，工单已归档')
  } catch (e: any) { error.value = e.message || '保存失败' }
  finally { busy.value = false }
}

watch(staff, () => {
  page.value = 1
  closeDetail()
  registrationOpen.value = false
  void load()
}, { immediate: true })

watch(draftAccount, async (account, _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  draftVersion++
  draftReady.value = false
  draftStatus.value = 'idle'
  registrationOpen.value = false
  resetRegistration()
  if (!account) return
  try {
    const saved = await readMissingReportDraft(account)
    if (!active) return
    if (saved) {
      Object.assign(registration, saved.registration)
      requester.value = saved.requester
      file.value = saved.evidence
      draftStatus.value = 'saved'
    }
  } catch {
    if (active) draftStatus.value = 'failed'
  } finally {
    await nextTick()
    if (active) draftReady.value = true
  }
}, { immediate: true, flush: 'sync' })

watch(draft, async value => {
  const account = draftAccount.value
  if (!draftReady.value || !account) return
  const version = ++draftVersion
  draftStatus.value = 'saving'
  try {
    await writeMissingReportDraft(account, value)
    if (version === draftVersion) draftStatus.value = value ? 'saved' : 'idle'
  } catch {
    if (version === draftVersion) draftStatus.value = 'failed'
  }
})
onUnmounted(() => { listVersion++; closeDetail() })
</script>

<template>
  <div class="reports">
    <header class="report-heading">
      <h2>{{ staff ? '紧急事项补报' : '我的待补报事项' }}</h2>
      <button v-if="staff" class="btn pri" type="button" :disabled="busy || !draftReady" @click="registrationOpen = !registrationOpen">
        {{ registrationOpen ? '收起登记' : draft ? '继续填写草稿' : '登记已完成事项' }}
      </button>
      <router-link v-else class="btn sm" to="/home">返回首页</router-link>
    </header>
    <p v-if="error" class="report-error" role="alert">{{ error }}</p>

    <section v-if="staff && registrationOpen" class="card">
      <h3>登记已完成事项</h3>
      <form ref="registrationForm" class="report-form registration-form" novalidate @submit.prevent="register">
        <PersonPicker class="wide" label="需求人" required :search="missingReportApi.search" :selected="requester" :disabled="busy"
          :invalid="validationAttempted && missing.requester"
          @select="requester = $event" @clear="requester = undefined" />
        <label class="wide"><span class="lb">事项标题<i aria-hidden="true">*</i></span>
          <input v-model.trim="registration.title" class="inp" maxlength="200" required :disabled="busy"
            :aria-invalid="validationAttempted && missing.title || undefined" />
        </label>
        <label><span class="lb">沟通方式<i aria-hidden="true">*</i></span>
          <select v-model="registration.channel" class="sel" required :disabled="busy"
            :aria-invalid="validationAttempted && missing.channel || undefined">
            <option value="CHAT">消息</option><option value="PHONE">电话</option>
          </select>
        </label>
        <label><span class="lb">处理完成时间<i aria-hidden="true">*</i></span>
          <input v-model="registration.completedAt" type="datetime-local" class="inp" required :disabled="busy"
            :aria-invalid="validationAttempted && missing.completedAt || undefined" />
        </label>
        <label class="wide"><span class="lb">处理说明<i aria-hidden="true">*</i></span>
          <textarea v-model.trim="registration.description" class="ta" maxlength="500" required :disabled="busy"
            :aria-invalid="validationAttempted && missing.description || undefined" />
        </label>
        <div class="wide registration-attachment">
          <span class="lb">佐证附件<i aria-hidden="true">*</i></span>
          <input ref="evidenceInput" type="file" hidden :disabled="busy"
            accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,.mp3,.m4a,.wav" @change="selectEvidence" />
          <div class="registration-file">
            <button class="btn sm" type="button" :disabled="busy" :aria-label="file ? '更换佐证附件' : '选择佐证附件'"
              aria-describedby="registration-file-name" :aria-invalid="validationAttempted && missing.evidence || undefined"
              @click="evidenceInput?.click()">{{ file ? '更换文件' : '选择文件' }}</button>
            <span id="registration-file-name" class="small" :class="{ muted: !file }">{{ file?.name || '未选择文件' }}</span>
            <button v-if="file" class="btn sm" type="button" :disabled="busy" aria-label="移除佐证附件" @click="file = undefined">移除</button>
          </div>
          <small class="muted">上传聊天截图、通话记录截图或电话录音。</small>
        </div>
        <div class="wide">
          <p v-if="validationAttempted && !registrationComplete" class="small registration-hint" role="alert">请补齐标红的必填项。</p>
          <div class="registration-actions">
            <button class="btn pri" type="submit" :disabled="busy" :aria-disabled="!registrationComplete || undefined">
              {{ busy ? '提交中…' : '提交登记' }}
            </button>
            <small v-if="draftStatus !== 'idle'" class="muted" role="status">
              {{ { saving: '正在保存草稿…', saved: '草稿已自动保存到当前浏览器', failed: '草稿保存失败，请保留当前页面' }[draftStatus] }}
            </small>
          </div>
        </div>
      </form>
    </section>

    <section v-if="detail" ref="detailPanel" class="card report-detail">
      <header class="report-heading">
        <h3>{{ detail.report.title }}</h3>
        <button class="btn sm" type="button" :disabled="busy" @click="closeDetail">关闭详情</button>
      </header>
      <p class="small muted">{{ detail.report.requesterName }} · {{ detail.report.channel === 'PHONE' ? '电话' : '消息' }} · 完成于 {{ detail.report.completedAt }}</p>
      <p class="small muted">登记人 {{ detail.report.recordedBy }} · {{ labels[detail.report.status] }}</p>
      <p class="report-description">{{ detail.report.description }}</p>
      <div v-for="item in detail.evidence" :key="item.id" class="evidence">
        <p v-if="isImage(item)" class="evidence-name small muted">{{ item.fileName }}</p>
        <p v-if="evidencePreviews[item.id]?.status === 'loading'" class="small muted" role="status">附件加载中…</p>
        <div v-else-if="evidencePreviews[item.id]?.status === 'failed'" class="evidence-error">
          <span class="small" role="alert">{{ isImage(item) ? '图片预览失败' : '附件加载失败' }}</span>
          <button type="button" class="btn sm" @click="loadEvidence(item)">重试</button>
        </div>
        <template v-else-if="evidencePreviews[item.id]?.status === 'ready'">
          <img v-if="isImage(item)" :src="evidencePreviews[item.id]?.url" :alt="item.fileName" @error="imageFailed(item.id, $event)" />
          <template v-else>
            <audio v-if="item.contentType?.startsWith('audio/')" :src="evidencePreviews[item.id]?.url" controls />
            <a class="btn sm" :href="evidencePreviews[item.id]?.url" target="_blank" rel="noopener">打开 {{ item.fileName }}</a>
          </template>
        </template>
        <button v-else-if="!isImage(item)" type="button" class="btn sm" @click="loadEvidence(item)">查看证据 · {{ item.fileName }}</button>
      </div>
      <router-link v-if="detail.report.ticketNo && (auth.can('ticket.view.all') || (!staff && auth.can('ticket.view.own')))"
        :to="'/ticket/' + encodeURIComponent(detail.report.ticketNo)">查看已归档工单 {{ detail.report.ticketNo }}</router-link>
      <p v-if="detail.report.cancelReason" class="report-description">撤销原因 {{ detail.report.cancelReason }}</p>

      <form v-if="!staff && detail.report.status === 'PENDING'" class="report-form" @submit.prevent="finish(false)">
        <h3 class="wide">补填报修表单</h3>
        <label class="wide">问题标题<input v-model.trim="form.title" class="inp" maxlength="200" required /></label>
        <label>问题类型<select v-model="form.type" class="sel" @change="resetCategory"><option v-for="type in TICKET_TYPES" :key="type.v" :value="type.v">{{ type.s }}</option></select></label>
        <label>问题位置<input v-model.trim="form.location" class="inp" placeholder="楼栋、楼层和房间或工位" maxlength="64" required /></label>
        <label>一级分类<select v-model="form.cat1" class="sel" required @change="form.cat2 = ''"><option value="" disabled>请选择</option><option v-for="name in primary" :key="name">{{ name }}</option></select></label>
        <label>二级分类<select v-model="form.cat2" class="sel" required><option value="" disabled>请选择</option><option v-for="name in secondary" :key="name">{{ name }}</option></select></label>
        <label class="wide">问题描述<textarea v-model.trim="form.description" class="ta" maxlength="4000" required /></label>
        <div class="wide"><button class="btn pri" :disabled="busy || !form.cat2">确认补报</button></div>
      </form>
      <template v-if="staff && detail.report.status === 'PENDING'">
        <button v-if="!cancelling" class="btn danger sm" type="button" @click="cancelling = true">撤销登记</button>
        <form v-else class="report-form" @submit.prevent="finish(true)">
          <label class="wide">撤销原因<textarea v-model.trim="cancelReason" class="ta" maxlength="500" required /></label>
          <div class="wide staff-options">
            <button class="btn sm" type="button" :disabled="busy" @click="cancelling = false">返回</button>
            <button class="btn danger sm" :disabled="busy || !cancelReason">确认撤销</button>
          </div>
        </form>
      </template>
    </section>

    <p v-if="loading" class="empty" role="status">加载中…</p>
    <p v-else-if="!records.length" class="empty">暂无补报记录</p>
    <button v-for="row in records" :key="row.no" type="button" class="card report-row" @click="openDetail(row)">
      <span><strong>{{ row.title }}</strong><small class="muted">{{ staff ? row.requesterName + ' · ' : '' }}{{ row.completedAt }}</small></span>
      <span class="pill" :class="row.status === 'PENDING' ? 'b-orange' : 'b-gray'">{{ labels[row.status] }}</span>
    </button>
    <nav v-if="staff && pages > 1" class="report-pager" aria-label="补报登记分页">
      <button class="btn sm" type="button" :disabled="loading || page <= 1" @click="page--; load()">上一页</button>
      <span>{{ page }} / {{ pages }}</span>
      <button class="btn sm" type="button" :disabled="loading || page >= pages" @click="page++; load()">下一页</button>
    </nav>
  </div>
</template>

<style scoped>
.reports { display:grid; grid-template-columns:minmax(0,1fr); gap:12px; }
.report-heading, .staff-options, .report-pager { display:flex; flex-wrap:wrap; align-items:center; gap:8px; }
.report-heading { justify-content:space-between; }
.report-heading h2, .report-heading h3 { margin:0; overflow-wrap:anywhere; }
.report-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:12px; }
.report-form label { display:grid; gap:5px; font-size:13px; }
.report-form .lb { margin-bottom:0; }
.report-form .wide { grid-column:1 / -1; }
.report-form .inp, .report-form .sel, .report-form .ta { width:100%; min-width:0; }
.registration-form :deep([aria-invalid="true"]) { border-color:var(--red); box-shadow:0 0 0 2px var(--red-bg); }
.registration-form .btn[aria-disabled="true"] { background:var(--t3); border-color:var(--t3); cursor:not-allowed; }
.registration-attachment { display:grid; gap:5px; min-width:0; }
.registration-file, .registration-actions { display:flex; flex-wrap:wrap; align-items:center; gap:8px; }
.registration-file > span { overflow-wrap:anywhere; }
.registration-hint { margin:0 0 8px; color:#b32921; }
.report-detail > * + * { margin-top:10px; }
.report-detail { scroll-margin-top:70px; }
.report-description { white-space:pre-wrap; overflow-wrap:anywhere; }
.report-error { color:#b32921; background:#fff0ee; padding:12px; border-radius:10px; }
.report-row { display:flex; width:100%; align-items:center; justify-content:space-between; gap:10px; text-align:left; color:inherit; border:1px solid var(--line2); cursor:pointer; }
.report-row strong { overflow-wrap:anywhere; }
.report-row small { display:block; margin-top:4px; }
.report-row .pill { flex-shrink:0; }
.report-pager { justify-content:center; }
.evidence { min-width:0; }
.evidence-name { margin:0 0 6px; overflow-wrap:anywhere; }
.evidence img { display:block; max-width:100%; height:auto; border-radius:8px; }
.evidence-error { display:flex; flex-wrap:wrap; align-items:center; gap:8px; color:#b32921; }
.evidence audio { max-width:100%; }
@media (max-width:580px) { .report-form { grid-template-columns:minmax(0,1fr); } }
</style>
