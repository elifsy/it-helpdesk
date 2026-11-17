<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { assetApi, attachmentApi, kbApi, metaApi, ticketApi } from '../../api'
import { toast } from '../../utils/toast'
import { statusName } from '../../utils/dict'
import { TICKET_TYPES, type TicketCategoriesByType } from '../../config/ticket-types'
import { useAuthStore } from '../../stores/auth'
import AssetPicker from '../../components/AssetPicker.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const type = ref('INCIDENT')
const categoriesByType = ref<TicketCategoriesByType>({})
const categories = computed(() => categoriesByType.value[type.value] || {})
const loadingCategories = ref(true)
const categoryError = ref('')
const locationInput = ref<HTMLInputElement>()
const locationError = ref('')
const assets = ref<any[]>([])
const openTicket = ref<any>(null)
const checkingOpen = ref(false)
const showAssetPicker = ref(false)
const pickNo = ref('')
const pickCategory = ref('')
const kbList = ref<any[]>([])
const showKb = ref(false)
const resolvingKbId = ref<number | null>(null)
const kbResolveError = ref('')
const resolvedKbIds = new Set<number>()
const submitting = ref(false)
const triedKb = ref(0)
const kbHitIds = ref<number[]>([])
const files = ref<File[]>([])
const previews = ref<string[]>([])
const runtime = ref({ maxAttachments: 3, maxUploadMb: 5, kbRecommendEnabled: true, requireSolutionCode: true })
let active = true
onUnmounted(() => { active = false; previews.value.forEach(URL.revokeObjectURL) })

const form = reactive({
  title: '',
  cat1: '',
  cat2: '',
  categoryNote: '',
  impact: 'SELF',
  workImpact: 'SLOW',
  assetNo: '',
  location: '',
  description: ''
})

const cat2List = computed(() => categories.value[form.cat1] || [])
const selectedAsset = computed(() => assets.value.find((a: any) => a.no === form.assetNo) || null)
const showCategoryNote = computed(() => form.cat1 === '其他' || form.cat2 === '其他')
watch(showCategoryNote, shown => { if (!shown) form.categoryNote = '' })
watch(() => form.location, value => { if (value.trim()) locationError.value = '' })
const types = TICKET_TYPES
let recommendationVersion = 0
function clearRecommendations() {
  recommendationVersion++
  kbList.value = []; kbHitIds.value = []; triedKb.value = 0; showKb.value = false
  kbResolveError.value = ''
}
function resetCategory() {
  form.cat1 = Object.keys(categories.value)[0] || ''
  onCat1Change()
}
watch(type, resetCategory)
watch(() => form.cat2, clearRecommendations)

/** 同一人对同一设备只允许一张未结工单：选中设备后先查一遍 */
async function checkOpenTicket() {
  openTicket.value = null
  if (!form.assetNo) return
  checkingOpen.value = true
  try {
    const page = await ticketApi.list({ mine: true, assetNo: form.assetNo, page: 1, size: 5 })
    openTicket.value = (page.list || []).find((x: any) => !['CLOSED', 'REJECTED', 'CANCELLED'].includes(x.status)) || null
  } catch {
    /* 校验失败不阻塞填写，提交时后端仍会拦截 */
  } finally {
    checkingOpen.value = false
  }
}
watch(() => form.assetNo, () => { void checkOpenTicket() })

/* 关联设备：与资产申请一致的设备选择弹窗 */
function openAssetPicker() {
  if (submitting.value) return
  pickNo.value = form.assetNo
  pickCategory.value = selectedAsset.value?.category || ''
  showAssetPicker.value = true
}
function closeAssetPicker() {
  showAssetPicker.value = false
}
function confirmAsset() {
  form.assetNo = pickNo.value
  showAssetPicker.value = false
}
function clearAsset() {
  form.assetNo = ''
}

onMounted(async () => {
  try {
    const [c, a, r] = await Promise.all([metaApi.categoriesByType(), assetApi.list({}), metaApi.runtime()])
    runtime.value = r
    categoriesByType.value = c
    assets.value = a
    if (typeof route.query.asset === 'string') {
      if (a.some((asset: any) => asset.no === route.query.asset)) form.assetNo = route.query.asset
      else toast('该设备不存在或当前无权查看，请重新选择关联设备')
    }
    resetCategory()
  } catch (e: any) {
    categoryError.value = '问题分类加载失败，请刷新页面后重试'
    toast(e.message)
  } finally { loadingCategories.value = false }
})

function onCat1Change() {
  form.cat2 = cat2List.value[0] || ''
  form.categoryNote = ''
  clearRecommendations()
}

async function checkKb() {
  if (!form.description.trim()) {
    toast('先简单描述一下问题，再找解决办法')
    return
  }
  try {
    const version = ++recommendationVersion
    const list = await kbApi.recommend({ cat1: form.cat1, desc: form.description })
    if (version !== recommendationVersion) return
    kbList.value = list || []
    kbHitIds.value = kbList.value.map((x) => x.id)
    triedKb.value = 1
    kbResolveError.value = ''
    showKb.value = true
  } catch (e: any) {
    toast(e.message)
  }
}

function closeKnowledge() {
  if (resolvingKbId.value === null) showKb.value = false
}

async function resolveWithKnowledge(article: any) {
  if (resolvingKbId.value !== null || submitting.value || !auth.can('kb.vote')) return
  resolvingKbId.value = article.id
  kbResolveError.value = ''
  const version = recommendationVersion
  try {
    if (!resolvedKbIds.has(article.id)) {
      await kbApi.vote(article.id, true)
      resolvedKbIds.add(article.id)
    }
    if (!active || version !== recommendationVersion) return
    await router.replace('/home')
    showKb.value = false
    toast('问题已解决，无需提交工单')
  } catch (e: any) {
    if (active && version === recommendationVersion) kbResolveError.value = e.message || '反馈保存失败，请重试'
  } finally {
    resolvingKbId.value = null
  }
}

async function submit() {
  if (resolvingKbId.value !== null || submitting.value) return
  if (openTicket.value) {
    toast('该设备已有进行中的工单 ' + openTicket.value.no + '，请勿重复提交')
    return
  }
  if (!cat2List.value.includes(form.cat2)) { toast('请选择当前问题类型对应的分类'); return }
  if (!form.location.trim()) {
    locationError.value = '请填写故障位置，例如楼栋、楼层和房间/工位'
    locationInput.value?.focus()
    return
  }
  if (!form.description.trim() || form.description.trim().length < 4) {
    toast('请把问题描述清楚一些')
    return
  }
  submitting.value = true
  try {
    const t = await ticketApi.create({
      ...form,
      location: form.location.trim(),
      title: form.title.trim() || `${form.cat1} · ${form.cat2 || '问题'}`,
      categoryNote: showCategoryNote.value ? form.categoryNote.trim() || null : null,
      type: type.value,
      kbHit: kbHitIds.value.join(','),
      triedKb: triedKb.value,
      source: '钉钉工作台'
    })
    let uploadFailed = 0
    for (const f of files.value) {
      try {
        await attachmentApi.upload(f, 'TICKET', t.no)
      } catch {
        uploadFailed++
      }
    }
    if (uploadFailed > 0) {
      toast(`工单已提交（${t.no}），${uploadFailed} 个附件上传失败，可到详情页重新上传`)
    } else if (files.value.length) {
      toast(`提交成功：${t.no}（${t.priority}），已上传 ${files.value.length} 个附件`)
    } else {
      toast(`提交成功：${t.no}（${t.priority}）`)
    }
    router.replace(`/ticket/${t.no}`)
  } catch (e: any) {
    toast(e.message)
  } finally {
    submitting.value = false
  }
}


function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  for (const f of Array.from(input.files || [])) {
    if (files.value.length >= runtime.value.maxAttachments) {
      toast(`最多上传 ${runtime.value.maxAttachments} 个附件`)
      break
    }
    if (f.size > runtime.value.maxUploadMb * 1024 * 1024) {
      toast(`${f.name} 超过 ${runtime.value.maxUploadMb}MB`)
      continue
    }
    files.value.push(f)
    previews.value.push(URL.createObjectURL(f))
  }
  input.value = ''
}

function removeFile(i: number) {
  URL.revokeObjectURL(previews.value[i])
  files.value.splice(i, 1)
  previews.value.splice(i, 1)
}
</script>

<template>
  <div class="new-ticket">
    <div class="card">
      <div class="lb">问题类型<i>*</i></div>
      <div class="opts">
        <button type="button"
          v-for="t in types"
          :key="t.v"
          class="opt"
          :class="{ sel: type === t.v }"
          :aria-pressed="type === t.v"
          :data-ticket-type="t.v"
          :disabled="submitting"
          @click="type = t.v"
        >
          {{ t.l }}<small>{{ t.s }}</small>
        </button>
      </div>
    </div>

    <div class="card" style="margin-top: 11px">
      <div class="fld">
        <label class="lb">问题分类<i>*</i></label>
        <div class="row">
          <select v-model="form.cat1" class="sel" style="flex: 1" aria-label="问题大类" :disabled="loadingCategories || submitting || !!categoryError" @change="onCat1Change">
            <option v-for="(v, k) in categories" :key="k" :value="k">{{ k }}</option>
          </select>
          <select v-model="form.cat2" class="sel" style="flex: 1" aria-label="问题子分类" :disabled="loadingCategories || submitting || !!categoryError">
            <option v-for="c in cat2List" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>
        <div v-if="showCategoryNote" class="category-note">
          <label for="ticket-category-note" class="lb">分类补充 <small class="muted">（选填）</small></label>
          <input id="ticket-category-note" v-model="form.categoryNote" class="inp" maxlength="200" placeholder="可填写具体分类，例如：会议室音视频" />
        </div>
        <p v-if="categoryError" class="small" role="alert">{{ categoryError }}</p>
        <div v-else class="small muted" style="margin-top: 5px">{{ loadingCategories ? '正在加载分类…' : `当前为${types.find(t => t.v === type)?.s}分类` }}<template v-if="type === 'INCIDENT'">，选「信息安全」会自动按 P1 处理</template></div>
      </div>

      <div class="fld">
        <label class="lb">影响范围<i>*</i></label>
        <div class="opts">
          <div
            v-for="o in [
              { v: 'SELF', l: '只影响我' },
              { v: 'DEPT', l: '影响部门' },
              { v: 'FLOOR', l: '影响整层' },
              { v: 'ALL', l: '全公司' }
            ]"
            :key="o.v"
            class="opt"
            :class="{ sel: form.impact === o.v }"
            @click="form.impact = o.v"
          >
            {{ o.l }}
          </div>
        </div>
      </div>

      <div class="fld">
        <label class="lb">还能不能干活<i>*</i></label>
        <div class="opts">
          <div
            v-for="o in [
              { v: 'FINE', l: '不受影响' },
              { v: 'SLOW', l: '能凑合' },
              { v: 'BLOCKED', l: '完全干不了' }
            ]"
            :key="o.v"
            class="opt"
            :class="{ sel: form.workImpact === o.v }"
            @click="form.workImpact = o.v"
          >
            {{ o.l }}
          </div>
        </div>
        <div class="small muted" style="margin-top: 5px">优先级由这两项自动计算，不需要你判断紧急程度</div>
      </div>

      <div class="fld">
        <label for="fault-location" class="lb">故障位置<i>*</i></label>
        <input id="fault-location" ref="locationInput" v-model="form.location" class="inp" type="text" required maxlength="64"
          placeholder="例如：A座3楼财务办公室，靠窗第2个工位"
          :aria-invalid="!!locationError" aria-describedby="fault-location-help fault-location-error" :disabled="submitting" />
        <p id="fault-location-help" class="small muted" style="margin:5px 0 0">请写清楼栋、楼层和房间/工位，方便工程师找到现场。</p>
        <p v-if="locationError" id="fault-location-error" class="location-error small" role="alert">{{ locationError }}</p>
      </div>

      <div class="fld">
        <label class="lb">关联设备 <small class="muted">（选填）</small></label>
        <div class="asset-pick">
          <button type="button" class="asset-pick-btn" :disabled="submitting" aria-haspopup="dialog" @click="openAssetPicker">
            <span v-if="selectedAsset" class="asset-pick-val">
              <b>{{ selectedAsset.name }}</b>
              <span class="mono">{{ selectedAsset.no }}</span>
            </span>
            <span v-else class="asset-pick-empty">未关联设备，点击选择</span>
            <span class="asset-pick-arrow" aria-hidden="true">›</span>
          </button>
          <button v-if="form.assetNo" type="button" class="btn sm" :disabled="submitting" @click="clearAsset">清除</button>
        </div>
        <div v-if="openTicket" class="dup-warning" role="alert">
          <span>该设备已有进行中的工单 <b class="mono">{{ openTicket.no }}</b>（{{ statusName(openTicket.status) }}），同一台设备同时只能有一张工单。</span>
          <router-link class="btn sm" :to="'/ticket/' + encodeURIComponent(openTicket.no)">查看该工单</router-link>
        </div>
        <p v-else-if="form.assetNo && checkingOpen" class="small muted" style="margin:6px 0 0">正在检查该设备是否有未结工单…</p>
      </div>

      <div class="fld" style="margin-bottom: 4px">
        <label class="lb">问题描述<i>*</i></label>
        <textarea
          v-model="form.description"
          class="ta"
          placeholder="例：打印机显示脱机，重启过仍然不行，影响部门打印"
        />
        <button v-if="runtime.kbRecommendEnabled" class="btn sm" style="margin-top: 8px" :disabled="resolvingKbId !== null || submitting" @click="checkKb">先看看有没有解决办法</button>
      </div>

      <div class="fld" style="margin-bottom: 4px">
        <label class="lb">现场截图 <small class="muted">（选填，最多 {{ runtime.maxAttachments }} 个，单个 ≤{{ runtime.maxUploadMb }}MB）</small></label>
        <div class="thumbs">
          <div v-for="(src, i) in previews" :key="i" class="thumb">
            <img :src="src" alt="" />
            <span class="del" @click="removeFile(i)">×</span>
          </div>
          <label v-if="files.length < runtime.maxAttachments" class="picker">
            ＋
            <input type="file" accept="image/*,application/pdf" multiple hidden @change="onPickFiles" />
          </label>
        </div>
        <div class="small muted" style="margin-top: 6px">拍照或截图能省一轮来回沟通，工程师一眼就能判断</div>
      </div>
    </div>

    <button class="btn pri block" style="margin-top: 14px" :disabled="submitting || resolvingKbId !== null || loadingCategories || !!categoryError || !form.cat2 || !!openTicket" @click="submit">
      {{ submitting ? '提交中…' : (openTicket ? '该设备已有进行中的工单' : '提交报修') }}
    </button>

    <!-- 关联设备：与资产申请同一套设备选择弹窗 -->
    <div v-if="showAssetPicker" class="mask asset-pick-mask" @click.self="closeAssetPicker" @keydown.esc="closeAssetPicker">
      <div class="dlg asset-pick-dialog" role="dialog" aria-modal="true" aria-labelledby="asset-pick-title">
        <div class="dlg-h asset-pick-head">
          <div>
            <h3 id="asset-pick-title">选择关联设备</h3>
            <p>选中后工程师能直接看到型号、位置与履历；本人名下设备会标「我的设备」</p>
          </div>
          <button type="button" class="asset-pick-close" aria-label="关闭设备选择" @click="closeAssetPicker">×</button>
        </div>
        <div class="asset-pick-body">
          <AssetPicker
            v-model="pickNo"
            v-model:category="pickCategory"
            :assets="assets"
            :categories="[]"
            :my-name="auth.user?.name"
            empty-text="当前没有可关联的设备。"
          />
        </div>
        <div class="dlg-f asset-pick-foot">
          <span class="small muted">{{ pickNo ? '已选择 ' + pickNo : '尚未选择设备' }}</span>
          <div class="asset-pick-actions">
            <button type="button" class="btn" @click="closeAssetPicker">取消</button>
            <button type="button" class="btn pri" :disabled="!pickNo" @click="confirmAsset">确定关联</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="showKb" class="mask" @click.self="closeKnowledge">
      <div class="dlg knowledge-recommendations" role="dialog" aria-modal="true" aria-labelledby="knowledge-recommendations-title" :aria-busy="resolvingKbId !== null">
        <div class="dlg-h"><span id="knowledge-recommendations-title">提交前先看看</span><button type="button" class="x" aria-label="关闭知识推荐" :disabled="resolvingKbId !== null" @click="closeKnowledge">×</button></div>
        <div class="dlg-b">
          <p v-if="kbList.length && auth.can('kb.vote')" class="small muted kb-resolution-hint">按步骤处理后，点击对应条目的“已解决”即可结束本次报修，无需提交工单。</p>
          <div v-if="kbResolveError" class="kb-resolve-error small" role="alert">{{ kbResolveError }}，可重试或继续报修。</div>
          <div v-if="!kbList.length" class="empty">暂时没有匹配的知识条目，可以直接提交</div>
          <div v-for="k in kbList" :key="k.id" class="kbi" :data-kb-id="k.id">
            <b>{{ k.title }}</b>
            <div class="small muted" style="margin-top: 4px">{{ k.summary }}</div>
            <div class="row kb-item-actions" style="margin-top: 8px">
              <a @click="kbApi.detail(k.id).then((d) => (k.content = d.content)); k.expanded = !k.expanded">
                {{ k.expanded ? '收起' : '查看步骤' }}
              </a>
              <span class="sp" />
              <span class="small muted">有帮助 {{ k.helpful }} 人</span>
              <button v-if="auth.can('kb.vote')" type="button" class="btn pri sm" :data-kb-resolve="k.id" :disabled="resolvingKbId !== null" @click="resolveWithKnowledge(k)">{{ resolvingKbId === k.id ? '记录中…' : '已解决' }}</button>
            </div>
            <div v-if="k.expanded" class="small" style="margin-top: 8px; white-space: pre-wrap">{{ k.content }}</div>
          </div>
        </div>
        <div class="dlg-f">
          <button type="button" class="btn" :disabled="resolvingKbId !== null" @click="closeKnowledge">还是不行，继续报修</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.opts > button.opt { line-height:inherit; text-align:left; }
.category-note { margin-top:12px; }
.location-error { margin:5px 0 0; color:#c9362b; }
.dup-warning {
  display:flex; align-items:center; gap:10px; flex-wrap:wrap;
  margin-top:8px; padding:10px 12px; border-radius:12px;
  background:#fff6e6; border:1px solid #ffe0b2; color:#8a5300; font-size:12px; line-height:1.6;
}
.dup-warning span { flex:1 1 220px; }
.dup-warning .mono { color:#8a5300; }
/* 关联设备：按钮 + 选择弹窗 */
.asset-pick { display:flex; align-items:center; gap:8px; }
.asset-pick-btn {
  flex:1; min-width:0; display:flex; align-items:center; gap:10px; text-align:left;
  padding:12px 14px; border-radius:12px; border:1px solid var(--line); background:#fff;
  font:inherit; font-size:13px; cursor:pointer; transition:.15s;
}
.asset-pick-btn:hover { border-color:#94bff1; box-shadow:0 4px 14px rgba(0,74,148,.07); }
.asset-pick-btn:focus-visible { outline:2px solid var(--brand); outline-offset:1px; }
.asset-pick-val { display:flex; align-items:center; gap:8px; min-width:0; }
.asset-pick-val b { font-size:13px; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.asset-pick-val .mono { color:var(--t3); font-size:11.5px; white-space:nowrap; }
.asset-pick-empty { color:var(--t3); }
.asset-pick-arrow { margin-left:auto; color:var(--t4); font-size:18px; }
.asset-pick-dialog { max-width:1000px; max-height:92vh; display:flex; flex-direction:column; overflow:hidden; }
.asset-pick-head { flex-shrink:0; padding:18px 22px; display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.asset-pick-head h3 { margin:0; font-size:16px; }
.asset-pick-head p { margin:6px 0 0; color:var(--t3); font-size:11.5px; }
.asset-pick-close { border:0; border-radius:8px; background:transparent; color:var(--t3); font-size:26px; width:32px; height:32px; cursor:pointer; flex:0 0 auto; }
.asset-pick-close:hover { color:var(--t1); background:#f2f4f7; }
.asset-pick-body { min-height:0; overflow:auto; }
.asset-pick-foot { flex-shrink:0; align-items:center; justify-content:space-between; gap:14px; padding:14px 22px 18px; border-top:1px solid var(--line2); }
.asset-pick-actions { display:flex; gap:9px; }
@media (max-width:600px) {
  .asset-pick-mask { padding:10px; }
  .asset-pick-dialog { max-height:94dvh; }
  .asset-pick-head { padding:14px; }
  .asset-pick-head h3 { font-size:14px; }
  .asset-pick-head p { font-size:10.5px; }
  .asset-pick-foot { padding:12px 14px 14px; }
}
.kb-item-actions { flex-wrap:wrap; }
.kb-resolution-hint { margin:0 0 8px; line-height:1.7; }
.kb-resolve-error { margin:0 0 8px; color:#c9362b; }
.knowledge-recommendations .x { border:0; padding:0; background:transparent; }
.knowledge-recommendations .x:disabled { cursor:wait; opacity:.5; }
.kbi {
  padding: 11px 0;
  border-bottom: 1px solid var(--line2);
}
.kbi:last-child {
  border-bottom: none;
}
.thumbs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.thumb {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--line);
  background: #fafbfc;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb .del {
  position: absolute;
  right: 2px;
  top: 2px;
  width: 17px;
  height: 17px;
  line-height: 15px;
  text-align: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}
.picker {
  width: 72px;
  height: 72px;
  border: 1px dashed var(--t4);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--t3);
  cursor: pointer;
  font-size: 20px;
}
.picker:hover {
  border-color: var(--brand);
  color: var(--brand);
}
@media (min-width: 900px) {
  .new-ticket {
    max-width: 780px;
  }
  .new-ticket :deep(.card) {
    padding: 18px 20px;
  }
  .new-ticket :deep(.opts) {
    gap: 10px;
  }
  .new-ticket :deep(.opt) {
    padding: 14px 16px;
    font-size: 13.5px;
  }
  .new-ticket :deep(.ta) {
    min-height: 140px;
  }
}
</style>
