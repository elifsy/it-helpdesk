<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { attachmentApi, metaApi, missingReportApi, type MissingReport, type MissingReportRequester } from '../api'
import { TICKET_TYPES, type TicketCategoriesByType } from '../config/ticket-types'
import { useAuthStore } from '../stores/auth'
import { useMissingReportStore } from '../stores/missingReports'
import { toast } from '../utils/toast'

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
const keyword = ref('')
const people = ref<MissingReportRequester[]>([])
const requester = ref<MissingReportRequester>()
const searched = ref(false)
const searching = ref(false)
const file = ref<File>()
const registration = reactive({ title: '', description: '', channel: 'CHAT', completedAt: '' })
const cancelReason = ref('')
const cancelling = ref(false)
const categories = ref<TicketCategoriesByType>({})
const form = reactive({ title: '', description: '', location: '', type: 'INCIDENT', cat1: '', cat2: '' })
const primary = computed(() => Object.keys(categories.value[form.type] || {}))
const secondary = computed(() => categories.value[form.type]?.[form.cat1] || [])
const evidenceLinks = ref<Record<number, string>>({})
const evidenceLoading = ref<number>()
const labels = { PENDING: '待补报', SUBMITTED: '已补报', CANCELLED: '已撤销' }
let listVersion = 0
let detailVersion = 0

function clearEvidence() {
  Object.values(evidenceLinks.value).forEach(url => URL.revokeObjectURL(url))
  evidenceLinks.value = {}
  evidenceLoading.value = undefined
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
    Object.assign(form, { title: result.report.title, description: result.report.description, location: '', type: 'INCIDENT' })
    resetCategory()
    await nextTick()
    if (version === detailVersion) detailPanel.value?.scrollIntoView({ block: 'start' })
    if (!staff.value && result.report.status === 'PENDING' && !Object.keys(categories.value).length)
      categories.value = await metaApi.categoriesByType()
  } catch (e: any) { if (version === detailVersion) error.value = e.message || '详情加载失败' }
}

async function preview(id: number, storedName: string) {
  const version = detailVersion
  evidenceLoading.value = id
  error.value = ''
  try {
    const blob = await attachmentApi.blob(storedName)
    if (version === detailVersion) evidenceLinks.value[id] = URL.createObjectURL(blob)
  } catch (e: any) { if (version === detailVersion) error.value = e.message || '证据读取失败' }
  finally { if (version === detailVersion) evidenceLoading.value = undefined }
}

async function search() {
  const term = keyword.value.trim()
  if (!term || searching.value) return
  searching.value = true
  searched.value = false
  error.value = ''
  try {
    people.value = await missingReportApi.search(term)
    searched.value = true
  } catch (e: any) { error.value = e.message || '搜索失败' }
  finally { searching.value = false }
}

function selectEvidence(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0]
}

async function register() {
  if (busy.value || !requester.value || !file.value) return
  busy.value = true
  error.value = ''
  try {
    const completedAt = registration.completedAt.replace('T', ' ')
    await missingReportApi.register({ ...registration, requesterId: requester.value.id,
      completedAt: completedAt.length === 16 ? completedAt + ':00' : completedAt }, file.value)
    registrationOpen.value = false
    requester.value = undefined
    file.value = undefined
    keyword.value = ''
    people.value = []
    searched.value = false
    Object.assign(registration, { title: '', description: '', channel: 'CHAT', completedAt: '' })
    page.value = 1
    await load()
    toast('已登记待补报事项')
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
onUnmounted(() => { listVersion++; closeDetail() })
</script>

<template>
  <div class="reports">
    <header class="report-heading">
      <h2>{{ staff ? '紧急事项补报' : '我的待补报事项' }}</h2>
      <button v-if="staff" class="btn pri" type="button" :disabled="busy" @click="registrationOpen = !registrationOpen">
        {{ registrationOpen ? '收起登记' : '登记已完成事项' }}
      </button>
      <router-link v-else class="btn sm" to="/home">返回首页</router-link>
    </header>
    <p v-if="error" class="report-error" role="alert">{{ error }}</p>

    <section v-if="staff && registrationOpen" class="card">
      <h3>登记已完成事项</h3>
      <form class="report-search" @submit.prevent="search">
        <label>搜索需求人<input v-model="keyword" class="inp" placeholder="姓名或工号" maxlength="64" /></label>
        <button class="btn sm" :disabled="searching || !keyword.trim()">搜索</button>
      </form>
      <div v-if="people.length" class="staff-options">
        <button v-for="person in people" :key="person.id" class="btn sm" type="button"
          :class="{ pri: requester?.id === person.id }" :aria-pressed="requester?.id === person.id" @click="requester = person">
          {{ [person.name, person.department, person.employeeNo].filter(Boolean).join(' · ') }}
        </button>
      </div>
      <p v-else-if="searched" class="small muted">暂无匹配的在职人员。</p>
      <p v-if="requester" class="small muted">需求人 {{ requester.name }}{{ requester.department ? ' · ' + requester.department : '' }}</p>
      <form class="report-form" @submit.prevent="register">
        <label class="wide">事项标题<input v-model.trim="registration.title" class="inp" maxlength="200" required /></label>
        <label>沟通方式<select v-model="registration.channel" class="sel"><option value="CHAT">消息</option><option value="PHONE">电话</option></select></label>
        <label>处理完成时间<input v-model="registration.completedAt" type="datetime-local" class="inp" required /></label>
        <label class="wide">处理说明<textarea v-model.trim="registration.description" class="ta" maxlength="500" required /></label>
        <label class="wide">佐证附件<input type="file" class="inp" accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,.mp3,.m4a,.wav" required @change="selectEvidence" />
          <small class="muted">上传聊天截图、通话记录截图或电话录音。</small>
        </label>
        <div class="wide"><button class="btn pri" :disabled="busy || !requester || !file">提交登记</button></div>
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
        <button v-if="!evidenceLinks[item.id]" type="button" class="btn sm" :disabled="evidenceLoading !== undefined" @click="preview(item.id, item.storedName)">
          {{ evidenceLoading === item.id ? '读取中…' : '查看证据 · ' + item.fileName }}
        </button>
        <template v-else>
          <img v-if="item.contentType.startsWith('image/')" :src="evidenceLinks[item.id]" :alt="item.fileName" />
          <audio v-else-if="item.contentType.startsWith('audio/')" :src="evidenceLinks[item.id]" controls />
          <a class="btn sm" :href="evidenceLinks[item.id]" target="_blank" rel="noopener">打开 {{ item.fileName }}</a>
        </template>
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
.reports { display:grid; gap:12px; }
.report-heading, .report-search, .staff-options, .report-pager { display:flex; flex-wrap:wrap; align-items:center; gap:8px; }
.report-heading { justify-content:space-between; }
.report-heading h2, .report-heading h3 { margin:0; overflow-wrap:anywhere; }
.report-search { margin:12px 0; align-items:end; }
.report-search label { flex:1; min-width:150px; }
.report-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:12px; }
.report-form label, .report-search label { display:grid; gap:5px; font-size:13px; }
.report-form .wide { grid-column:1 / -1; }
.report-form .inp, .report-form .sel, .report-form .ta { width:100%; min-width:0; }
.report-detail > * + * { margin-top:10px; }
.report-detail { scroll-margin-top:70px; }
.report-description { white-space:pre-wrap; overflow-wrap:anywhere; }
.report-error { color:#b32921; background:#fff0ee; padding:12px; border-radius:10px; }
.report-row { display:flex; width:100%; align-items:center; justify-content:space-between; gap:10px; text-align:left; color:inherit; border:1px solid var(--line2); cursor:pointer; }
.report-row strong { overflow-wrap:anywhere; }
.report-row small { display:block; margin-top:4px; }
.report-row .pill { flex-shrink:0; }
.report-pager { justify-content:center; }
.evidence img { display:block; max-width:100%; max-height:400px; object-fit:contain; }
.evidence audio { max-width:100%; }
@media (max-width:580px) { .report-form { grid-template-columns:minmax(0,1fr); } }
</style>
