<script setup lang="ts">
/**
 * 资产申请流程（选设备 → 填事由 → 提交），员工首页与「资产服务」页共用。
 * 外观与交互完全一致：外部只负责渲染四张 qcard 与调用 open(type)。
 */
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { assetRequestApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { eligibleAssets, hasAssetHolder } from '../utils/assets'
import { REQ_TYPE } from '../utils/dict'
import { toast } from '../utils/toast'
import AssetPicker from './AssetPicker.vue'
import { practiceAssets, practiceCategories } from '../config/workflows'
import { tutorialWorkflow } from '../utils/tutorialPractice'

const props = defineProps<{
  assets: any[]
  categories: { id: number; name: string }[]
  loading?: boolean
  reload?: () => Promise<any> | any
  /** 本人已提交、仍待审批的设备编号：不可再次选择 */
  pendingNos?: string[]
}>()

const emit = defineEmits<{ submitted: [no: string]; closed: [] }>()

const auth = useAuthStore()
const myId = computed(() => auth.user?.id)
const isExample = !!tutorialWorkflow.value && tutorialWorkflow.value !== 'repair'
const flowAssets = computed(() => isExample ? practiceAssets(auth.user?.name || '', myId.value) : props.assets)
const flowCategories = computed(() => isExample ? practiceCategories : props.categories)
const flowLoading = computed(() => !isExample && !!props.loading)
const flowPending = computed(() => isExample ? [] : props.pendingNos || [])

const pickType = ref('')
const pickCategory = ref('')
const pickNo = ref('')
const showPick = ref(false)
const showApply = ref(false)
const submitting = ref(false)
const pickerDialog = ref<HTMLElement>()
let pickerOverflow: string | null = null
const form = reactive({ reason: '', expectReturn: '' })
/* 更换申请：提交时预选替换设备（钉钉审批端选不了台账） */
const spareNo = ref('')
const spareCategory = ref('')
const showSpare = ref(false)
const spareAssets = computed(() => flowAssets.value.filter(a =>
  ['闲置', '待入库'].includes(a.status) && !hasAssetHolder(a) && a.no !== pickNo.value))
const spareSelected = computed(() => flowAssets.value.find(a => a.no === spareNo.value))

const pickAssets = computed(() => eligibleAssets(flowAssets.value, pickType.value, myId.value, '', flowPending.value))
const pickList = computed(() => eligibleAssets(flowAssets.value, pickType.value, myId.value, pickCategory.value, flowPending.value))
const selectedAsset = computed(() => flowAssets.value.find(a => a.no === pickNo.value))

watch(pickList, list => { if (!list.some(a => a.no === pickNo.value)) pickNo.value = '' })
watch(showPick, async open => {
  if (open) {
    pickerOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    pickerDialog.value?.querySelector<HTMLButtonElement>('.picker-close')?.focus()
  } else if (pickerOverflow !== null) {
    document.body.style.overflow = pickerOverflow
    pickerOverflow = null
  }
})
onUnmounted(() => { if (pickerOverflow !== null) document.body.style.overflow = pickerOverflow })

/** 打开申请流程；no 为已选设备编号（可空） */
function open(type: string, no?: string) {
  if (!auth.can('request.create') || !['RECEIVE', 'BORROW', 'REPLACE', 'RETURN'].includes(type)) return
  if (no && flowPending.value.includes(no)) {
    toast('该设备你已提交过申请，审批完成前不能重复提交')
    return
  }
  const asset = flowAssets.value.find(a => a.no === no)
  pickType.value = type
  pickCategory.value = asset?.category || ''
  const list = eligibleAssets(flowAssets.value, type, myId.value, pickCategory.value)
  pickNo.value = list.some(a => a.no === no) ? no! : ''
  form.reason = ''
  form.expectReturn = new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10)
  spareNo.value = ''
  spareCategory.value = ''
  showSpare.value = false
  showApply.value = false
  showPick.value = true
}

function nextStep() {
  if (!pickList.value.some(a => a.no === pickNo.value)) { toast('请先选择该分类下可申请的设备'); return }
  showPick.value = false
  showApply.value = true
}

async function reselect() {
  await props.reload?.()
  showApply.value = false
  showPick.value = true
}

function closeFlow() {
  if (submitting.value) return
  showPick.value = false
  showApply.value = false
  emit('closed')
}

async function submitApply() {
  const asset = selectedAsset.value
  if (!asset || !pickList.value.some(a => a.no === asset.no)) { toast('请选择可申请的设备'); return }
  if (form.reason.trim().length < 4) { toast('请填写申请事由（至少 4 个字）'); return }
  if (pickType.value === 'BORROW' && !form.expectReturn) { toast('请选择归还日期'); return }
  if (pickType.value === 'REPLACE' && !spareNo.value) { toast('更换申请请先选择替换设备'); return }
  submitting.value = true
  try {
    const result = await assetRequestApi.create({
      type: pickType.value,
      assetNo: asset.no,
      category: asset.category,
      reason: form.reason,
      expectReturn: pickType.value === 'BORROW' ? form.expectReturn : undefined,
      newAssetNo: pickType.value === 'REPLACE' ? spareNo.value : undefined
    })
    submitting.value = false
    showPick.value = false
    showApply.value = false
    toast('已提交：' + result.no + '，等待审批')
    emit('submitted', result.no)
    emit('closed')
    await props.reload?.()
  } catch (e: any) {
    toast(e.message)
  } finally {
    submitting.value = false
  }
}

defineExpose({ open, close: closeFlow })
</script>

<template>
  <!-- 选设备 -->
  <div v-if="showPick" class="mask asset-picker-mask" @click.self="closeFlow" @keydown.esc="closeFlow">
    <div ref="pickerDialog" class="dlg asset-selection-dialog" role="dialog" aria-modal="true" aria-labelledby="asset-selection-title">
      <div class="dlg-h">
        <div>
          <h3 id="asset-selection-title">{{ REQ_TYPE[pickType].name }} · 选择设备</h3>
          <p>{{ pickType === 'REPLACE' ? '选择自己名下需要更换的原设备' : pickType === 'RETURN' ? '选择自己借用中需要归还的设备' : '先选择左侧分类，再点击设备卡片' }}</p>
        </div>
        <button type="button" class="picker-close" aria-label="关闭设备选择" @click="closeFlow">×</button>
      </div>
      <div class="asset-selection-body">
        <AssetPicker
          v-model="pickNo"
          v-model:category="pickCategory"
          :assets="pickAssets"
          :categories="flowCategories"
          :loading="flowLoading"
          :my-id="myId"
          :category-label="pickType === 'REPLACE' ? '原设备分类' : '资产分类'"
          :empty-text="'该分类下暂无可' + (pickType === 'RECEIVE' ? '领用' : pickType === 'BORROW' ? '借用' : pickType === 'REPLACE' ? '更换的本人' : '归还的本人') + '设备，可切换其他分类查看。'"
        />
      </div>
      <div class="picker-help">{{ REQ_TYPE[pickType].desc }}，选择后填写申请事由，提交 IT 审批。</div>
      <div class="dlg-f asset-selection-footer">
        <div class="selection-summary">
          <b>{{ pickNo ? selectedAsset?.name : '请选择一台设备' }}</b>
          <span>{{ pickNo || '点击右侧卡片选择，选中后继续' }}</span>
        </div>
        <div class="picker-footer-actions">
          <button class="btn" @click="closeFlow">取消</button>
          <button class="btn pri" data-workflow-step="asset-next" :disabled="flowLoading || !pickNo || !pickList.length" @click="nextStep">下一步</button>
        </div>
      </div>
    </div>
  </div>

  <!-- 更换：选择替换设备 -->
  <div v-if="showSpare" class="mask asset-picker-mask" @click.self="showSpare = false" @keydown.esc="showSpare = false">
    <div class="dlg asset-selection-dialog" role="dialog" aria-modal="true" aria-labelledby="spare-title">
      <div class="dlg-h">
        <div>
          <h3 id="spare-title">选择替换设备</h3>
          <p>只列出闲置且无人使用的设备，选中后作为更换的新机</p>
        </div>
        <button type="button" class="picker-close" aria-label="关闭替换设备选择" @click="showSpare = false">×</button>
      </div>
      <div class="asset-selection-body">
        <AssetPicker v-model="spareNo" v-model:category="spareCategory" :assets="spareAssets"
          :categories="flowCategories" :loading="flowLoading" category-label="替换设备分类"
          empty-text="当前没有闲置可替换的设备，请先归还或新增备机。" />
      </div>
      <div class="dlg-f asset-selection-footer">
        <div class="selection-summary">
          <b>{{ spareSelected ? spareSelected.name : '请选择替换设备' }}</b>
          <span>{{ spareNo || '选中后点「确定」' }}</span>
        </div>
        <div class="picker-footer-actions">
          <button class="btn" @click="showSpare = false">取消</button>
          <button class="btn pri" data-workflow-step="asset-spare-confirm" :disabled="!spareNo" @click="showSpare = false">确定</button>
        </div>
      </div>
    </div>
  </div>

  <!-- 申请表单 -->
  <div v-if="showApply" v-show="!showSpare" class="mask" @click.self="closeFlow">
    <div class="dlg">
      <div class="dlg-h">{{ REQ_TYPE[pickType].name }} · 填写申请<span class="x" @click="closeFlow">×</span></div>
      <div class="dlg-b">
        <div class="card" style="background: #fafbfc; margin-bottom: 12px; box-shadow: none">
          <b style="font-size: 13px">{{ selectedAsset?.name }} · {{ pickNo }}</b>
          <div class="small muted" style="margin-top: 4px">资产分类：{{ selectedAsset?.category }}</div>
          <button type="button" class="btn sm" style="margin-top: 8px" :disabled="submitting" @click="reselect">重新选择分类与设备</button>
          <div class="small muted" style="margin-top: 4px">{{ REQ_TYPE[pickType].desc }}</div>
        </div>
        <div class="fld">
          <label class="lb">申请事由<i>*</i></label>
          <textarea v-model="form.reason" class="ta" data-workflow-step="asset-reason" aria-label="申请事由" placeholder="例：新岗位需要一台办公笔记本，申请领用。" />
        </div>
        <div v-if="pickType === 'BORROW'" class="fld" style="margin-bottom: 0">
          <label class="lb">预计归还日期<i>*</i></label>
          <input v-model="form.expectReturn" type="date" data-workflow-step="asset-date" aria-label="预计归还日期" class="inp" />
        </div>
        <div v-if="pickType === 'REPLACE'" class="fld" style="margin-bottom: 0">
          <label class="lb">替换设备<i>*</i></label>
          <div class="row" style="gap:8px;align-items:center">
            <button type="button" class="btn" data-workflow-step="asset-spare-open" style="flex:1;justify-content:flex-start" @click="showSpare = true">
              {{ spareSelected ? spareSelected.name + ' · ' + spareNo : '选择一台闲置设备作为替换机' }}
            </button>
            <button v-if="spareNo" type="button" class="btn sm" @click="spareNo = ''">清除</button>
          </div>
          <div class="small muted" style="margin-top:6px">审批端无法挑选台账设备，需在此预选；通过后自动把使用人换到新机，旧机回库并转移未结工单。</div>
        </div>
      </div>
      <div class="dlg-f">
        <button class="btn" :disabled="submitting" @click="closeFlow">取消</button>
        <button class="btn pri" data-workflow-step="asset-submit" :disabled="submitting || !pickNo || !auth.can('request.create')" @click="submitApply">{{ submitting ? '提交中…' : '提交申请' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.asset-selection-dialog { max-width:1000px; max-height:92vh; display:flex; flex-direction:column; overflow:hidden; }
.asset-selection-dialog .dlg-h { flex-shrink:0; padding:18px 22px; }
.dlg-h h3 { margin:0; font-size:16px; }
.dlg-h p { margin:6px 0 0; color:var(--t3); font-size:11.5px; font-weight:400; }
.picker-close { flex:none; border:0; border-radius:8px; background:transparent; color:var(--t3); font-size:26px; width:32px; height:32px; cursor:pointer; }
.picker-close:hover { color:var(--t1); background:#f2f4f7; }
.asset-selection-body { display:flex; flex-direction:column; min-height:0; overflow:hidden; }
.asset-selection-body > .asset-picker { min-height:0; }
.picker-help { flex-shrink:0; padding:12px 22px; background:#fafbfc; color:var(--t3); font-size:11.5px; line-height:1.7; border-top:1px solid var(--line2); }
.asset-selection-footer { flex-shrink:0; align-items:center; justify-content:space-between; gap:16px; padding:15px 22px 18px; border-top:1px solid var(--line2); }
.selection-summary { display:flex; flex-direction:column; flex:1 1 110px; gap:4px; min-width:0; }
.selection-summary b { font-size:12px; color:var(--t2); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.selection-summary span { font-size:10.5px; color:var(--t3); overflow-wrap:anywhere; }
.picker-footer-actions { display:flex; gap:9px; flex-shrink:0; }
.picker-footer-actions .btn { white-space:nowrap; }
@media(max-width:899px) {
  .asset-picker-mask {
    --picker-top: max(10px, env(safe-area-inset-top, 0px));
    --picker-bottom: max(10px, env(safe-area-inset-bottom, 0px));
    padding:10px;
    padding:var(--picker-top) max(10px, env(safe-area-inset-right, 0px)) var(--picker-bottom) max(10px, env(safe-area-inset-left, 0px));
  }
  .asset-selection-dialog { max-height:calc(100vh - 20px); max-height:calc(100dvh - var(--picker-top) - var(--picker-bottom)); }
}
@media(max-width:600px) {
  .asset-selection-dialog .dlg-h { padding:14px; }
  .dlg-h h3 { font-size:14px; }
  .dlg-h p { max-width:240px; font-size:10.5px; line-height:1.5; }
  .picker-help { padding:10px 14px; font-size:10px; }
  .asset-selection-footer { padding:12px 14px 14px; gap:10px; flex-wrap:wrap; }
  .selection-summary b { font-size:11px; }
  .selection-summary span { font-size:9px; }
  .picker-footer-actions { gap:7px; }
  .picker-footer-actions .btn { padding:0 14px; }
}
</style>
