<script setup lang="ts">
/**
 * 钉钉审批配置：总开关、四类业务各自的模板 processCode 与表单字段映射。
 * 保存走运行配置同一份 settings（revision 乐观锁），字段映射留空用默认名字，填 - 表示不传。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { adminApi, dingApprovalApi } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { toast } from '../../utils/toast'

interface FormField { key: string; label: string; defaultName: string }

const auth = useAuthStore()
const loading = ref(true)
const saving = ref(false)
const syncing = ref(false)
const lookupName = ref('')
const looking = ref(false)
const error = ref('')
const settings = ref<any>(null)
const fields = ref<FormField[]>([])
const status = ref<any>({ enabled: false, mock: false, pending: 0, processCodes: {} })
const active = ref('RECEIVE')

const TYPES = [
  { v: 'RECEIVE', s: '资产领用', hint: '常用：部门主管 → 资产管理员' },
  { v: 'BORROW', s: '资产借用', hint: '需在申请时约定归还日期' },
  { v: 'REPLACE', s: '资产更换', hint: '需在提交时预选替换设备，否则审批通过后无法自动执行' },
  { v: 'RETURN', s: '归还设备', hint: '单节点即可' },
  { v: 'PURCHASE', s: '设备购入', hint: '审批通过后进入采购流程：填 ERP 单号 → 入库选设备 → 待领取' }
]

const canEdit = computed(() => auth.can('settings.manage'))
const current = computed(() => TYPES.find(t => t.v === active.value)!)
const codes = computed<Record<string, string>>(() => settings.value?.approvalProcessCodes || {})
const mapping = computed<Record<string, string>>(() => settings.value?.approvalMappings?.[active.value] || {})
const enabledFor = computed(() => !!codes.value[active.value]?.trim())
const mock = computed(() => !!settings.value?.approvalMock)
const noDingCodes = computed(() => TYPES.filter(t => !codes.value[t.v]?.trim()).map(t => t.s))

function ensureShape(s: any) {
  if (!s.approvalProcessCodes) s.approvalProcessCodes = {}
  for (const t of TYPES) if (s.approvalProcessCodes[t.v] === undefined) s.approvalProcessCodes[t.v] = ''
  if (!s.approvalMappings) s.approvalMappings = {}
  for (const t of TYPES) {
    if (!s.approvalMappings[t.v]) s.approvalMappings[t.v] = {}
  }
  return s
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, st] = await Promise.all([adminApi.settings(), dingApprovalApi.status().catch(() => null)])
    settings.value = ensureShape(s)
    if (st) status.value = st
    await loadFields(active.value)
  } catch (e: any) {
    error.value = e.message || '配置加载失败'
  } finally {
    loading.value = false
  }
}

const fieldCache: Record<string, FormField[]> = {}
/** 不同业务的可映射字段不一样（采购没有资产编号/归还日期），按类型取 */
async function loadFields(type: string) {
  if (fieldCache[type]) { fields.value = fieldCache[type]; return }
  try {
    const list = await dingApprovalApi.fields(type)
    fieldCache[type] = list
    fields.value = list
  } catch (e: any) {
    toast(e.message)
  }
}

watch(active, type => { void loadFields(type) })

async function save() {
  if (!canEdit.value || saving.value) return
  saving.value = true
  try {
    const payload = JSON.parse(JSON.stringify(settings.value))
    // 清理空映射，保持配置干净
    for (const t of TYPES) {
      const m = payload.approvalMappings?.[t.v] || {}
      for (const k of Object.keys(m)) if (!String(m[k] || '').trim()) delete m[k]
      if (payload.approvalMappings) payload.approvalMappings[t.v] = m
    }
    const saved = await adminApi.saveSettings(payload)
    settings.value = ensureShape(saved)
    status.value = await dingApprovalApi.status().catch(() => status.value)
    toast('已保存，下一次提交的申请按新配置执行')
  } catch (e: any) {
    toast(e.message)
  } finally {
    saving.value = false
  }
}

async function syncNow() {
  if (syncing.value) return
  syncing.value = true
  try {
    const r = await dingApprovalApi.sync()
    status.value = await dingApprovalApi.status().catch(() => status.value)
    toast(r.handled ? `已同步，处理 ${r.handled} 笔` : '已同步，暂无状态变化')
  } catch (e: any) {
    toast(e.message)
  } finally {
    syncing.value = false
  }
}

/** 按钉钉模板名称查 processCode 并填入当前业务 */
async function lookupCode() {
  if (!lookupName.value.trim()) { toast('先填钉钉审批模板的名称'); return }
  looking.value = true
  try {
    const r = await dingApprovalApi.lookup(lookupName.value.trim())
    if (!r?.processCode) { toast('钉钉没有返回 processCode，请核对模板名称'); return }
    settings.value.approvalProcessCodes[active.value] = r.processCode
    toast('已填入 ' + r.processCode + '，记得点「保存配置」')
  } catch (e: any) {
    toast(e.message)
  } finally {
    looking.value = false
  }
}

function resetType() {
  if (!canEdit.value || !settings.value) return
  settings.value.approvalMappings[active.value] = {}
  toast('已恢复为默认控件名，记得保存')
}

onMounted(load)
</script>

<template>
  <div class="ding-page">
    <div class="page-h">
      <h2>钉钉审批</h2>
      <div class="row" style="gap:8px">
        <button class="btn sm" :disabled="syncing || !status.enabled" @click="syncNow">{{ syncing ? '同步中…' : '立即同步结果' }}</button>
        <button v-if="canEdit" class="btn pri sm" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存配置' }}</button>
      </div>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty" role="alert">{{ error }}</div>

    <template v-else>
      <section class="panel">
        <div class="setting-row">
          <div>
            <b>资产与采购申请走钉钉审批</b>
            <p class="small muted" style="margin:4px 0 0">开启后，配了 processCode 的业务提交时发起钉钉审批实例；结果由后端轮询回写并执行业务动作（采购单回写后进入待采购）。</p>
          </div>
          <input v-model="settings.approvalEnabled" type="checkbox" role="switch" :disabled="!canEdit" />
        </div>
        <div class="setting-row">
          <div>
            <b>本地联调模式</b>
            <p class="small muted" style="margin:4px 0 0">不真正调用钉钉，申请单得到 MOCK- 前缀实例号，可在「资产申请」页用「联调：模拟通过/驳回」验证链路。联调完请关闭。</p>
          </div>
          <input v-model="settings.approvalMock" type="checkbox" role="switch" :disabled="!canEdit" />
        </div>
        <div class="inline-fields">
          <label class="field"><span>结果轮询间隔（分钟）</span><input v-model.number="settings.approvalPollMinutes" class="inp" type="number" min="1" max="30" :disabled="!canEdit" /></label>
          <div class="stat">
            <span>在途钉钉审批</span><b>{{ status.pending || 0 }} 笔</b>
          </div>
          <div class="stat">
            <span>已接钉钉的业务</span><b>{{ TYPES.length - noDingCodes.length }} / {{ TYPES.length }}</b>
            <em v-if="noDingCodes.length">未接：{{ noDingCodes.join('、') }}</em>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="tabs">
          <button v-for="t in TYPES" :key="t.v" :class="{ on: active === t.v }" @click="active = t.v">
            {{ t.s }}
            <span v-if="codes[t.v]?.trim()" class="dot ok" title="已接入钉钉审批"></span>
            <span v-else class="dot" title="未配置，走系统内审批"></span>
          </button>
        </div>

        <div class="type-head">
          <div>
            <h3 style="margin:0">{{ current.s }}</h3>
            <p class="small muted" style="margin:4px 0 0">{{ current.hint }}</p>
          </div>
          <span class="chip" :class="enabledFor ? 'ok' : ''">{{ enabledFor ? '走钉钉审批' : '系统内审批' }}</span>
        </div>

        <label class="field" style="max-width:460px">
          <span>钉钉审批模板 processCode</span>
          <input v-model="settings.approvalProcessCodes[active]" class="inp" maxlength="64"
            :disabled="!canEdit" :placeholder="'留空则该业务继续走系统内审批'" />
        </label>
        <div class="lookup">
          <input v-model="lookupName" class="inp" maxlength="64" :disabled="!canEdit"
            placeholder="不知道 code？填钉钉模板名称，如「资产领用审批」" @keyup.enter="lookupCode" />
          <button class="btn sm" :disabled="!canEdit || looking" @click="lookupCode">{{ looking ? '查询中…' : '查询并填入' }}</button>
        </div>
        <p class="small muted" style="margin:6px 0 0">
          也可以直接去钉钉审批后台：表单管理 → 打开模板 → 地址栏里的 <code>processCode=PROC-…</code>。查询接口需要应用已开通「审批」权限。
        </p>

        <h4 style="margin-top:20px">表单字段映射</h4>
        <p class="small muted" style="margin:0 0 10px">
          左边是系统里的数据，右边填钉钉审批模板里对应控件的<b>控件名称</b>。留空表示使用默认名（见灰字），填 <code>-</code> 表示这个字段不传给钉钉。
        </p>
        <div class="map-table">
          <div class="map-head">
            <span>系统字段</span><span>钉钉控件名称</span><span>说明</span>
          </div>
          <div v-for="f in fields" :key="f.key" class="map-row">
            <span class="k">{{ f.label }}<i>{{ f.key }}</i></span>
            <input class="inp" :value="mapping[f.key] || ''" :disabled="!canEdit"
              :placeholder="'默认：' + f.defaultName"
              @input="settings.approvalMappings[active][f.key] = ($event.target as HTMLInputElement).value" />
            <span class="d">{{ f.key === 'expectReturn' ? '仅借用申请有值' : f.key === 'newAssetNo' ? '仅更换且已预选替换设备时有值' : '始终有值' }}</span>
          </div>
        </div>
        <div class="row" style="margin-top:12px;gap:10px">
          <button v-if="canEdit" class="btn sm" @click="resetType">恢复默认控件名</button>
          <span class="small muted">修改后点右上角「保存配置」生效；在途单据不受影响。</span>
        </div>

        <div v-if="mock" class="mock-note">
          当前是本地联调模式：不会真实调用钉钉。可在「资产申请」页对某张单点「联调：模拟通过 / 驳回」，再回来点「立即同步结果」。
        </div>
      </section>

      <section class="panel tips">
        <h4 style="margin-top:0">对接前必读</h4>
        <ul class="small">
          <li>开放平台需给应用勾选「审批」权限；本系统复用现有企业内部应用的 access_token，不用新建应用。</li>
          <li>钉钉审批模板里这些控件建议设为<b>只读</b>（由接口写入），避免审批人改内容导致与系统单据不一致。</li>
          <li>模板里不要再加系统没有的<b>必填</b>控件，否则建单会失败（失败会自动降级为系统内审批并记录原因）。</li>
          <li>更换业务：审批端选不了台账，需在提交申请时预选替换设备，否则审批通过后无法自动执行。</li>
          <li v-if="!status.enabled">钉钉审批总开关当前关闭，所有业务仍按系统内审批流转。</li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.ding-page { display:flex; flex-direction:column; gap:14px; }
.setting-row { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; padding:12px 0; border-bottom:1px solid var(--line2); }
.setting-row:first-child { padding-top:0; }
.inline-fields { display:flex; gap:32px; flex-wrap:wrap; margin-top:14px; align-items:flex-start; }
.inline-fields .field { max-width:220px; }
.stat { display:flex; flex-direction:column; gap:2px; font-size:12px; color:var(--t3); }
.stat b { font-size:16px; color:var(--t1); }
.stat em { font-style:normal; font-size:11px; color:var(--t4); max-width:260px; line-height:1.6; }
.tabs { display:flex; gap:6px; border-bottom:1px solid var(--line2); margin-bottom:14px; flex-wrap:wrap; }
.tabs button { border:0; background:transparent; padding:9px 14px; font:inherit; font-size:13px; color:var(--t3); cursor:pointer; border-bottom:2px solid transparent; display:flex; align-items:center; gap:6px; }
.tabs button.on { color:var(--brand); border-bottom-color:var(--brand); font-weight:600; }
.dot { width:6px; height:6px; border-radius:50%; background:#d5dae2; display:inline-block; }
.dot.ok { background:#1a7f4b; }
.type-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:14px; }
.chip { font-size:11px; padding:3px 10px; border-radius:999px; background:#f1f3f6; color:var(--t3); }
.chip.ok { background:#e9f7ef; color:#1a7f4b; }
.lookup { display:flex; gap:8px; align-items:center; max-width:620px; margin-top:10px; }
.lookup .inp { flex:1; }
.lookup + p code { background:#f1f3f6; padding:1px 5px; border-radius:4px; font-size:11px; }
.map-table { border:1px solid var(--line); border-radius:12px; overflow:hidden; }
.map-head, .map-row { display:grid; grid-template-columns:180px minmax(0,1fr) 220px; gap:12px; align-items:center; padding:9px 14px; }
.map-head { background:#fafbfc; font-size:11px; color:var(--t3); border-bottom:1px solid var(--line2); }
.map-row { border-bottom:1px solid var(--line2); }
.map-row:last-child { border-bottom:0; }
.map-row .k { font-size:12.5px; display:flex; align-items:baseline; gap:6px; }
.map-row .k i { font-style:normal; font-size:10px; color:var(--t4); font-family:ui-monospace,monospace; }
.map-row .d { font-size:11px; color:var(--t4); }
.mock-note { margin-top:14px; padding:10px 12px; border-radius:10px; background:#fff6e6; border:1px solid #ffe0b2; color:#8a5300; font-size:12px; }
.tips ul { margin:8px 0 0; padding-left:18px; line-height:2; }
.tips code { background:#f1f3f6; padding:1px 5px; border-radius:4px; font-size:11px; }
@media (max-width:760px) {
  .map-head { display:none; }
  .map-row { grid-template-columns:1fr; gap:6px; }
  .map-row .d { font-size:10.5px; }
}
</style>
