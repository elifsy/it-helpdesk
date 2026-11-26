<script setup lang="ts">
import PendingReportBadges from '../components/PendingReportBadges.vue'
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { metaApi, attachmentApi, ticketApi } from '../api'
import { useAuthStore } from '../stores/auth'
import {
  IMPACT_NAME,
  RECORD_KIND,
  TICKET_TYPE_NAME,
  WORK_NAME,
  remainText,
  statusName
} from '../utils/dict'
import { toast } from '../utils/toast'
import { ticketQueueText } from '../utils/ticketQueue'
import AttachmentFolder from '../components/AttachmentFolder.vue'
import PersonPicker from '../components/PersonPicker.vue'
import type { PersonOption } from '../utils/people-search'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const detail = ref<any>(null)
const loading = ref(true)
const detailError = ref('')
const attachmentLoading = ref(false)
const attachmentError = ref('')
const engineers = ref<PersonOption[]>([])
const categoriesByType = ref<Record<string, Record<string, string[]>>>({})
const busy = ref(false)
const attachments = ref<any[]>([])
const pendingAttachments = shallowRef<File[]>([])
const uploadError = ref('')
const uploading = ref(false)
const runtime = ref({ maxAttachments: 3, maxUploadMb: 5, kbRecommendEnabled: true, requireSolutionCode: true })
const attachmentSlots = computed(() => Math.max(0, runtime.value.maxAttachments - attachments.value.length))
let loadVersion = 0
let attachmentVersion = 0
let uploadVersion = 0
let progressVersion = 0
let progressTimer: ReturnType<typeof setInterval> | undefined
const progressRefreshing = ref(false)
const progressError = ref('')
function releaseAttachments() {
  attachments.value.forEach(a => { if (a.url) URL.revokeObjectURL(a.url) })
  attachments.value = []
}
onUnmounted(() => {
  loadVersion++; uploadVersion++; progressVersion++; releaseAttachments()
  clearInterval(progressTimer)
  document.removeEventListener('visibilitychange', refreshVisibleProgress)
})

const dlg = ref<'' | 'dispatch' | 'record' | 'finish' | 'confirm' | 'rate' | 'request-info' | 'provide-info' | 'resume-info'>('')
const form = reactive({
  assigneeId: null as number | null,
  kind: 'REMOTE',
  content: '',
  costMin: 0,
  cat1: '',
  cat2: '',
  note: '',
  score: 5,
  tags: '',
  solved: true,
  remark: ''
})

const t = computed(() => detail.value?.ticket)
const canUpload = computed(() => auth.can('attachment.upload') && (t.value?.creatorId === auth.user?.id
  || auth.can('ticket.handle') || auth.can('ticket.accept') || auth.can('ticket.dispatch')))
const actionPermissions: Record<string, string> = { ACCEPT: 'ticket.accept', REJECT: 'ticket.accept', DISPATCH: 'ticket.dispatch', RECORD: 'ticket.handle', FINISH: 'ticket.handle', CONFIRM: 'ticket.confirm', RATE: 'ticket.confirm', CANCEL: 'ticket.confirm', REQUEST_INFO: 'ticket.handle', PROVIDE_INFO: 'ticket.confirm', RESUME_INFO: 'ticket.handle' }
const actions = computed<string[]>(() => (detail.value?.actions || []).filter((action: string) => auth.can(actionPermissions[action]) && (action !== 'ACCEPT' || auth.can('ticket.handle'))))
const infoTitle = computed(() => dlg.value === 'request-info' ? '请报修人补充信息' : dlg.value === 'resume-info' ? '恢复处理' : '补充工单信息')
const cat1Options = computed(() => Object.keys(categoriesByType.value[t.value?.type] || {}))
const cat2Options = computed(() => (categoriesByType.value[t.value?.type] || {})[form.cat1] || [])
function onCat1Change() {
  form.cat2 = cat2Options.value[0] || ''
}

async function load() {
  const version = ++loadVersion
  progressVersion++
  progressRefreshing.value = false
  progressError.value = ''
  loading.value = true
  detailError.value = ''
  attachmentError.value = ''
  releaseAttachments()
  try {
    const no = String(route.params.no)
    const d = await ticketApi.detail(no)
    if (version !== loadVersion) return
    detail.value = d
    void loadAttachments(no, version)
  } catch (e: any) {
    if (version === loadVersion) detailError.value = e.message || '工单详情加载失败，请重试'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function loadAttachments(no = String(route.params.no), version = loadVersion) {
  const request = ++attachmentVersion
  const isCurrent = () => version === loadVersion && request === attachmentVersion
  attachmentLoading.value = true
  attachmentError.value = ''
  try {
    const atts = await attachmentApi.list('TICKET', no)
    const loaded = await Promise.all((atts || []).map(async (a: any) => {
      try { return { ...a, url: URL.createObjectURL(await attachmentApi.blob(a.storedName)) } }
      catch { return { ...a, url: '' } }
    }))
    if (!isCurrent()) { loaded.forEach(a => { if (a.url) URL.revokeObjectURL(a.url) }); return }
    releaseAttachments()
    attachments.value = loaded
    if (loaded.some(a => !a.url)) attachmentError.value = '部分附件加载失败，请重试'
  } catch {
    if (isCurrent()) attachmentError.value = '附件加载失败，请重试'
  } finally {
    if (isCurrent()) attachmentLoading.value = false
  }
}

async function refreshProgress() {
  if (loading.value || progressRefreshing.value || busy.value || uploading.value || dlg.value || !ticketQueueText(t.value)) return
  const no = String(route.params.no)
  const version = loadVersion
  const refresh = ++progressVersion
  progressRefreshing.value = true
  progressError.value = ''
  try {
    const latest = await ticketApi.detail(no)
    if (version === loadVersion && refresh === progressVersion && String(route.params.no) === no) detail.value = latest
  } catch {
    if (version === loadVersion && refresh === progressVersion) progressError.value = '进度更新失败，请点击刷新进度重试'
  } finally {
    if (version === loadVersion && refresh === progressVersion) progressRefreshing.value = false
  }
}
function refreshVisibleProgress() {
  if (document.visibilityState === 'visible') void refreshProgress()
}

function isImage(a: any) {
  return /^image\//.test(a?.contentType || '')
}

async function uploadAttachments() {
  const no = t.value?.no
  const list = pendingAttachments.value
  if (!no || !canUpload.value || uploading.value || attachmentLoading.value || !list.length) return
  if (list.length > attachmentSlots.value) {
    uploadError.value = `当前还可上传 ${attachmentSlots.value} 个附件，请先移除多选的文件。`
    return
  }
  const version = ++uploadVersion
  uploading.value = true
  uploadError.value = ''
  const failed: File[] = []
  try {
    for (const file of list) {
      if (version !== uploadVersion || !canUpload.value) return
      try { await attachmentApi.upload(file, 'TICKET', no) }
      catch { failed.push(file) }
    }
    if (version !== uploadVersion) return
    pendingAttachments.value = failed
    if (failed.length) uploadError.value = `${failed.length} 个附件上传失败，已留在文件夹中，可以重试。`
    else toast('附件已上传')
    await loadAttachments(no)
  } finally {
    if (version === uploadVersion) uploading.value = false
  }
}

async function init() {
  const jobs: Promise<any>[] = [load(), metaApi.runtime().then(r => { runtime.value = r })]
  if (auth.can("ticket.dispatch") || auth.can("ticket.handle")) {
    jobs.push(
      auth.can("ticket.dispatch") ? metaApi.engineers().then((r) => (engineers.value = r)) : Promise.resolve(),
      metaApi.categoriesByType().then((r: any) => { categoriesByType.value = r || {} })
    )
  }
  await Promise.allSettled(jobs)
}

onMounted(() => {
  void init()
  progressTimer = setInterval(refreshVisibleProgress, 30000)
  document.addEventListener('visibilitychange', refreshVisibleProgress)
})
watch([() => route.params.no, () => auth.user?.id], () => {
  uploadVersion++
  uploading.value = false
  pendingAttachments.value = []
  uploadError.value = ''
  detail.value = null
  void load()
})

function openDlg(kind: typeof dlg.value) {
  dlg.value = kind
  if (kind === 'confirm') {
    form.solved = true
    form.score = 5
  }
  if (kind === 'rate') {
    form.score = 5
  }
  if (kind === 'finish') {
    form.note = ''
    // 关单时确认问题分类：默认带出工单原分类，IT 可纠正
    form.cat1 = t.value?.cat1 || cat1Options.value[0] || ''
    const list = categoriesByType.value[t.value?.type]?.[form.cat1] || []
    form.cat2 = list.includes(t.value?.cat2) ? t.value.cat2 : (list[0] || '')
  }
  if (kind === 'record') {
    form.content = ''
    form.costMin = 0
  }
}

async function doAction(a: string) {
  try {
    busy.value = true
    if (a === 'ACCEPT') {
      await ticketApi.accept(t.value.no)
      toast('已受理，已由您接单处理')
      await load()
    } else if (a === 'REJECT') {
      const reason = window.prompt('驳回原因（会通知报修人）')
      if (!reason) return
      await ticketApi.reject(t.value.no, reason)
      toast('已驳回')
      await load()
    } else if (a === 'CANCEL') {
      if (!window.confirm('确认撤销这张工单？')) return
      await ticketApi.cancel(t.value.no)
      toast('已撤销')
      await load()
    } else if (a === 'DISPATCH') {
      form.assigneeId = null
      dlg.value = 'dispatch'
    } else if (a === 'RECORD') {
      openDlg('record')
    } else if (a === 'FINISH') {
      openDlg('finish')
    } else if (a === 'REQUEST_INFO' || a === 'PROVIDE_INFO' || a === 'RESUME_INFO') {
      form.note = ''
      openDlg(a === 'REQUEST_INFO' ? 'request-info' : a === 'PROVIDE_INFO' ? 'provide-info' : 'resume-info')
    } else if (a === 'CONFIRM') {
      openDlg('confirm')
    } else if (a === 'RATE') {
      openDlg('rate')
    }
  } catch (e: any) {
    toast(e.message)
  } finally {
    busy.value = false
  }
}

async function submitInfo() {
  const note = form.note.trim()
  if (!note || busy.value || !t.value) return
  busy.value = true
  const requesting = dlg.value === 'request-info'
  try {
    await (requesting ? ticketApi.requestInfo : ticketApi.provideInfo)(t.value.no, note)
    dlg.value = ''
    toast(requesting ? '已通知报修人补充信息' : '工单已恢复处理')
    await load()
  } catch (e: any) { toast(e.message) }
  finally { busy.value = false }
}

async function submitDispatch() {
  if (!form.assigneeId) {
    toast('请选择工程师')
    return
  }
  await ticketApi.dispatch(t.value.no, Number(form.assigneeId))
  dlg.value = ''
  toast('已派单')
  await load()
}

async function submitRecord() {
  if (!form.content.trim()) {
    toast('请填写处理内容')
    return
  }
  await ticketApi.addRecord(t.value.no, {
    kind: form.kind,
    content: form.content,
    costMin: Number(form.costMin) || 0
  })
  dlg.value = ''
  toast('已记录')
  await load()
}

async function submitFinish() {
  if (!form.cat1) {
    toast('请选择问题分类')
    return
  }
  if (form.cat2 && !cat2Options.value.includes(form.cat2)) {
    toast('请选择当前类型下的子分类')
    return
  }
  if (!form.note.trim()) {
    toast('请填写处理结论')
    return
  }
  await ticketApi.finish(t.value.no, { cat1: form.cat1, cat2: form.cat2, note: form.note })
  dlg.value = ''
  toast('已提交，等待员工确认')
  await load()
}

async function submitConfirm() {
  await ticketApi.confirm(t.value.no, {
    solved: form.solved,
    score: form.solved ? form.score : undefined,
    tags: form.tags,
    remark: form.remark
  })
  dlg.value = ''
  toast(form.solved ? '已确认解决，工单关闭' : '已反馈，工程师会继续跟进')
  await load()
}

async function submitRate() {
  await ticketApi.rate(t.value.no, { score: form.score, tags: form.tags })
  dlg.value = ''
  toast('感谢评价')
  await load()
}
</script>

<template>
  <div v-if="loading" class="empty">加载中…</div>
  <div v-else-if="detailError" class="empty detail-error" role="alert">
    <p>{{ detailError }}</p>
    <button class="btn pri" @click="load">重新加载</button>
    <router-link class="btn" :to="auth.can('ticket.view.all') ? '/admin/tickets' : '/tickets'">返回工单列表</router-link>
  </div>
  <div v-else-if="!t" class="empty">工单不存在</div>
  <div v-else class="detail">
    <div class="col-main">
      <div class="card status-card">
        <div class="row">
          <span class="pill" :class="'p' + t.priority[1].toLowerCase()">{{ t.priority }}</span>
          <b style="font-size: 15px">{{ statusName(t.status) }}</b>
          <span class="sp" />
          <span v-if="t.overdue" class="pill b-red">已超时</span>
          <span v-else-if="t.remainMinutes != null" class="small muted">{{ remainText(t.remainMinutes, t.workTime, t.slaPausedAt) }}</span>
        </div>
        <div class="ttl">{{ t.title }}</div>
        <div class="mono" style="margin-top: 4px">{{ t.no }}</div>
        <div v-if="ticketQueueText(t)" class="queue-panel">
          <div class="row">
            <b class="queue-heading">接单与排队</b><span class="sp" />
            <button type="button" class="btn sm" :disabled="progressRefreshing || busy || uploading || !!dlg" @click="refreshProgress">{{ progressRefreshing ? '更新中…' : '刷新进度' }}</button>
          </div>
          <p class="queue-summary" role="status" aria-live="polite">{{ ticketQueueText(t) }}</p>
          <p v-if="t.queueAhead != null" class="queue-note">按接单先后统计当前 IT 尚未完成的工单，每 30 秒更新。</p>
          <p v-else-if="!t.assigneeId && t.status !== 'PENDING_CONFIRM'" class="queue-note">IT 接单后显示前方待处理工单数量。</p>
          <p v-if="progressError" class="queue-error" role="alert">{{ progressError }}</p>
        </div>
      </div>

      <div class="card" style="margin-top: 10px">
        <div class="kv"><span>分类</span><b>{{ t.cat1 }} · {{ t.cat2 || '—' }}</b></div>        <div v-if="t.categoryNote" class="kv category-note"><span>分类补充</span><b>{{ t.categoryNote }}</b></div>
        <div class="kv"><span>类型</span><b>{{ TICKET_TYPE_NAME[t.type] || t.type }}</b></div>
        <div class="kv"><span>影响</span><b>{{ IMPACT_NAME[t.impact] }} · {{ WORK_NAME[t.workImpact] }}</b></div>
        <div class="kv"><span>报修人</span><b>{{ t.creatorName }}（{{ t.deptName }}）<PendingReportBadges :counts="t.pendingByType" :repair-count="t.pendingReportCount" /></b></div>
        <div class="kv"><span>故障位置</span><b class="ticket-location">{{ t.location || '—' }}</b></div>
        <div class="kv"><span>工程师</span><b>{{ t.assigneeName || '待接单' }}</b></div>
        <div class="kv"><span>提交</span><b>{{ t.createdAt }}</b></div>
        <div v-if="t.responseDueAt" class="kv"><span>首次响应时限</span><b>{{ t.responseDueAt }}</b></div>
        <div v-if="t.responseWorkMinutes != null" class="kv"><span>首次响应耗时</span><b>{{ t.responseWorkMinutes }} 分钟</b></div>
        <div v-if="t.dueAt" class="kv"><span>解决时限</span><b>{{ t.dueAt }}</b></div>
        <div v-if="t.resolveWorkMinutes != null" class="kv"><span>解决耗时</span><b>{{ t.resolveWorkMinutes }} 分钟</b></div>
        <div v-if="t.slaPausedAt || t.workTime" class="kv"><span>SLA 计时</span><b>{{ t.slaPausedAt ? '已停表 · 等待补充信息' : '按工作时间' }}</b></div>
        <div v-if="t.rating" class="kv"><span>评价</span><b>{{ t.rating }} 星 {{ t.ratingTags || '' }}</b></div>
      </div>

      <div class="desc">{{ t.description }}</div>

      <div class="card" style="margin-top: 10px">
        <div class="row">
          <b style="font-size: 13.5px">附件</b>
          <span class="small muted" v-if="attachments.length">（{{ attachments.length }}）</span>
        </div>
        <div v-if="attachmentLoading" class="small muted" style="margin-top: 9px">附件加载中…</div>
        <div v-else-if="attachmentError" class="attachment-error small" role="alert">
          {{ attachmentError }} <button class="btn sm" @click="loadAttachments()">重新加载附件</button>
        </div>
        <div v-else-if="!attachments.length" class="small muted" style="margin-top: 9px">还没有附件</div>
        <div v-if="attachments.length" class="atts">
          <a
            v-for="a in attachments"
            :key="a.id"
            class="att"
            :href="a.url || undefined"
            target="_blank"
            rel="noopener"
          >
            <img v-if="isImage(a) && a.url" :src="a.url" alt="" />
            <div v-else class="pdf">PDF</div>
            <div class="nm">{{ a.fileName }}</div>
          </a>
        </div>
        <div v-if="canUpload && (attachmentSlots || pendingAttachments.length)" class="attachment-upload">
          <AttachmentFolder v-model="pendingAttachments" :max-files="attachmentSlots" :max-file-mb="runtime.maxUploadMb"
            :disabled="uploading || attachmentLoading" />
          <div class="attachment-upload-actions">
            <small class="muted">还可上传 {{ attachmentSlots }} 个附件，单个 ≤{{ runtime.maxUploadMb }} MB</small>
            <button v-if="pendingAttachments.length" type="button" class="btn pri sm" :disabled="uploading || attachmentLoading" @click="uploadAttachments">
              {{ uploading ? '上传中…' : '上传附件' }}
            </button>
          </div>
          <p v-if="uploadError && pendingAttachments.length" class="small attachment-error" role="alert">{{ uploadError }}</p>
        </div>
        <p v-else-if="canUpload && !attachmentLoading" class="small muted attachment-upload">附件已达上传上限。</p>
      </div>

      <div v-if="actions.length" class="ops">
        <button
          v-for="a in actions"
          :key="a"
          class="btn"
          :class="{ pri: ['ACCEPT', 'FINISH', 'CONFIRM', 'RATE'].includes(a), danger: ['REJECT', 'CANCEL'].includes(a) }"
          :disabled="busy"
          @click="doAction(a)"
        >
          {{
            {
              ACCEPT: '受理',
              REJECT: '驳回',
              DISPATCH: '派单',
              RECORD: '添加处理记录',
              FINISH: '提交完成',
              REQUEST_INFO: '要求补充信息',
              PROVIDE_INFO: '补充信息',
              RESUME_INFO: '恢复处理',
              CONFIRM: '确认结果',
              RATE: '评价',
              CANCEL: '撤销工单'
            }[a] || a
          }}
        </button>
      </div>

      <div class="sec-t"><h3>流转时间轴</h3></div>
      <div class="card" style="padding: 15px 16px">
        <div class="tl">
          <div v-for="e in detail.events" :key="e.id" class="tli">
            <span class="dot" />
            <div class="tt">{{ e.remark || e.action }}</div>
            <div class="tm">
              {{ e.createdAt }} · {{ e.operator }}<span v-if="e.toStatus"> · {{ statusName(e.toStatus) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <aside class="col-side">
      <div v-if="detail.asset" class="card">
        <div class="row">
          <b style="font-size: 13.5px">关联设备</b>
          <span class="sp" />
          <a @click="router.push({ path: '/assets', query: { asset: detail.asset.no } })">查看设备 ›</a>
        </div>
        <div style="margin-top: 7px; font-size: 13px">{{ detail.asset.name }}</div>
        <div class="mono">{{ detail.asset.no }} · {{ detail.asset.status }}</div>
      </div>

      <div class="sec-t" :style="detail.asset ? 'margin-top:16px' : 'margin-top:0'">
        <h3>处理记录</h3>
      </div>
      <div v-if="!detail.records?.length" class="empty">暂无处理记录</div>
      <div v-for="r in detail.records" :key="r.id" class="card" style="margin-bottom: 8px">
        <div class="row">
          <span class="pill b-blue">{{ RECORD_KIND[r.kind] || r.kind }}</span>
          <span class="sp" />
          <span class="small muted">{{ r.createdAt }}</span>
        </div>
        <div style="margin-top: 6px; font-size: 13px">{{ r.content }}</div>
        <div class="small muted" style="margin-top: 4px">{{ r.operator }} · 耗时 {{ r.costMin }} 分钟</div>
      </div>
    </aside>

    <!-- 派单 -->
    <div v-if="dlg === 'request-info' || dlg === 'provide-info' || dlg === 'resume-info'" class="mask" @click.self="!busy && (dlg = '')">
      <form class="dlg" @submit.prevent="submitInfo">
        <div class="dlg-h">{{ infoTitle }}<button type="button" class="btn sm" :disabled="busy" aria-label="关闭" @click="dlg = ''">×</button></div>
        <div class="dlg-b"><label class="fld"><span class="lb">{{ dlg === 'request-info' ? '需补充的内容' : '补充内容或恢复说明' }}<i>*</i></span>
          <textarea v-model="form.note" class="ta" maxlength="400" required :disabled="busy" />
        </label></div>
        <div class="dlg-f"><button class="btn" type="button" :disabled="busy" @click="dlg = ''">取消</button>
          <button class="btn pri" :disabled="busy || !form.note.trim()">{{ dlg === 'request-info' ? '发送补充要求' : '提交并恢复处理' }}</button></div>
      </form>
    </div>

    <div v-if="dlg === 'dispatch'" class="mask" @click.self="dlg = ''">
      <div class="dlg">
        <div class="dlg-h">派单<span class="x" @click="dlg = ''">×</span></div>
        <div class="dlg-b">
          <PersonPicker label="选择工程师" :options="engineers" :selected="engineers.find(e => e.id === form.assigneeId)"
            @select="form.assigneeId = $event.id" @clear="form.assigneeId = null" />
        </div>
        <div class="dlg-f">
          <button class="btn" @click="dlg = ''">取消</button>
          <button class="btn pri" :disabled="!form.assigneeId" @click="submitDispatch">确认派单</button>
        </div>
      </div>
    </div>

    <!-- 处理记录 -->
    <div v-if="dlg === 'record'" class="mask" @click.self="dlg = ''">
      <div class="dlg">
        <div class="dlg-h">添加处理记录<span class="x" @click="dlg = ''">×</span></div>
        <div class="dlg-b">
          <div class="fld">
            <label class="lb">处理方式</label>
            <select v-model="form.kind" class="sel">
              <option v-for="(v, k) in RECORD_KIND" :key="k" :value="k">{{ v }}</option>
            </select>
          </div>
          <div class="fld">
            <label class="lb">处理内容<i>*</i></label>
            <textarea v-model="form.content" class="ta" placeholder="例：远程排查发现无线网卡驱动异常，已重装驱动" />
          </div>
          <div class="fld" style="margin-bottom: 0">
            <label class="lb">耗时（分钟）</label>
            <input v-model="form.costMin" type="number" class="inp" min="0" />
          </div>
        </div>
        <div class="dlg-f">
          <button class="btn" @click="dlg = ''">取消</button>
          <button class="btn pri" @click="submitRecord">保存</button>
        </div>
      </div>
    </div>

    <!-- 关单 -->
    <div v-if="dlg === 'finish'" class="mask" @click.self="dlg = ''">
      <div class="dlg">
        <div class="dlg-h">提交完成<span class="x" @click="dlg = ''">×</span></div>
        <div class="dlg-b">
          <div class="fld">
            <label class="lb">问题分类<i>*</i></label>
            <div class="row">
              <select v-model="form.cat1" class="sel" style="flex: 1" aria-label="问题大类" @change="onCat1Change">
                <option v-for="c in cat1Options" :key="c" :value="c">{{ c }}</option>
              </select>
              <select v-model="form.cat2" class="sel" style="flex: 1" aria-label="问题子分类">
                <option v-for="c in cat2Options" :key="c" :value="c">{{ c }}</option>
              </select>
            </div>
            <div class="small muted" style="margin-top: 5px">默认带出报修时选择的分类，处理中如判断有偏差可在此纠正；统计与知识沉淀以该分类为准</div>
          </div>
          <div class="fld" style="margin-bottom: 0">
            <label class="lb">处理结论<i>*</i></label>
            <textarea v-model="form.note" class="ta" placeholder="例：重装无线网卡驱动并重启，连接正常，网速达标" />
            <p class="small muted" style="margin:6px 0 0">请写清原因和处理步骤。报修人确认解决、工单关闭后，将自动生成知识草稿，整理发布后供大家查询。</p>
          </div>
        </div>
        <div class="dlg-f">
          <button class="btn" @click="dlg = ''">取消</button>
          <button class="btn pri" @click="submitFinish">提交完成</button>
        </div>
      </div>
    </div>

    <!-- 员工确认 -->
    <div v-if="dlg === 'confirm'" class="mask" @click.self="dlg = ''">
      <div class="dlg">
        <div class="dlg-h">问题解决了吗<span class="x" @click="dlg = ''">×</span></div>
        <div class="dlg-b">
          <div class="opts" style="margin-bottom: 14px">
            <div class="opt" :class="{ sel: form.solved }" @click="form.solved = true">已解决</div>
            <div class="opt" :class="{ sel: !form.solved }" @click="form.solved = false">还没好</div>
          </div>
          <template v-if="form.solved">
            <label class="lb">给这次服务打个分</label>
            <div class="stars">
              <span v-for="i in 5" :key="i" :class="{ on: i <= form.score }" @click="form.score = i">★</span>
            </div>
            <input v-model="form.tags" class="inp" style="margin-top: 10px" placeholder="标签（选填）：响应快、专业" />
          </template>
          <textarea v-else v-model="form.remark" class="ta" placeholder="哪里还没好？工程师会继续跟进" />
        </div>
        <div class="dlg-f">
          <button class="btn" @click="dlg = ''">取消</button>
          <button class="btn pri" @click="submitConfirm">提交</button>
        </div>
      </div>
    </div>

    <!-- 评价 -->
    <div v-if="dlg === 'rate'" class="mask" @click.self="dlg = ''">
      <div class="dlg">
        <div class="dlg-h">评价这次服务<span class="x" @click="dlg = ''">×</span></div>
        <div class="dlg-b">
          <div class="stars">
            <span v-for="i in 5" :key="i" :class="{ on: i <= form.score }" @click="form.score = i">★</span>
          </div>
          <input v-model="form.tags" class="inp" style="margin-top: 10px" placeholder="标签（选填）" />
        </div>
        <div class="dlg-f">
          <button class="btn" @click="dlg = ''">取消</button>
          <button class="btn pri" @click="submitRate">提交评价</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ticket-location { min-width:0; overflow-wrap:anywhere; }
.category-note b { min-width:0; overflow-wrap:anywhere; }
.detail-error .btn { margin:6px; }
.attachment-error { margin-top:9px; color:var(--red, #c9362b); }
.attachment-upload { margin:10px 0 0; }
.attachment-upload-actions { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; margin-top:8px; }
.queue-panel { margin-top:16px; padding:13px 14px; border-radius:12px; background:var(--brand-l, #eef6ff); }
.queue-heading { font-size:12px; color:var(--t2); }.queue-panel .btn { flex-shrink:0; white-space:nowrap; }
.queue-summary { margin:9px 0 0; color:var(--brand); font-size:14px; font-weight:600; line-height:1.7; overflow-wrap:anywhere; }
.queue-note { margin:6px 0 0; color:var(--t3); font-size:11.5px; line-height:1.6; }
.queue-error { margin:6px 0 0; color:var(--red); font-size:12px; }
.status-card {
  border-left: 3px solid var(--brand);
}
.status-card .ttl {
  font-size: 15px;
  font-weight: 700;
  margin-top: 9px;
}
.kv {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  padding: 4px 0;
}
.kv span {
  width: 68px;
  flex: 0 0 68px;
  color: var(--t3);
}
.kv b {
  font-weight: 600;
  color: var(--t2);
  word-break: break-all;
}
.desc {
  margin-top: 10px;
  background: #fff;
  border-radius: var(--r);
  padding: 13px 15px;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  box-shadow: var(--shadow);
}
.ops {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.stars {
  font-size: 30px;
  color: var(--t4);
  letter-spacing: 4px;
  cursor: pointer;
}
.stars span.on {
  color: #ffb400;
}
.atts {
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  margin-top: 10px;
}
.att {
  width: 96px;
  text-decoration: none;
  color: var(--t2);
  font-size: 11.5px;
}
.att img,
.att .pdf {
  width: 96px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafbfc;
  color: var(--t3);
  font-size: 12px;
  font-weight: 700;
}
.att .nm {
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------------- PC 两栏 ---------------- */
@media (min-width: 900px) {
  .detail {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 360px;
    gap: 18px;
    align-items: start;
  }
  .col-side {
    position: sticky;
    top: 84px;
  }
  .status-card .ttl {
    font-size: 18px;
  }
  .kv {
    font-size: 13px;
    padding: 5px 0;
  }
  .desc {
    padding: 16px 18px;
    font-size: 13.5px;
  }
  .ops .btn {
    height: 36px;
    padding: 0 18px;
  }
}
</style>
