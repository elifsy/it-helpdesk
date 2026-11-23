<script setup lang="ts">
/**
 * 购入申请流程（购物车）：选型号 → 加入申请单（可加多项、可改台数）→ 填事由 → 提交。
 * 选择界面与「领用/借用」完全同一套弹窗组件（AssetPicker），保证交互一致。
 * 提交后后台会把每一项按台数拆成明细，IT/管理员可以逐台配发、采购与交付。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { purchaseApi } from '../api'
import { toast } from '../utils/toast'
import AssetPicker from './AssetPicker.vue'

const emit = defineEmits<{ submitted: [no: string] }>()

interface CartLine { catalogId: number; name: string; category: string; spec: string; qty: number }

const MAX_QTY_PER_LINE = 10

const open = ref(false)
const step = ref(1)
const loading = ref(false)
const catalog = ref<any[]>([])
const pickId = ref('')
const pickCategory = ref('')
const cart = ref<CartLine[]>([])
const reason = ref('')
const submitting = ref(false)
const dialog = ref<HTMLElement>()
let overflow: string | null = null

/** 采购目录映射成选择器可用的条目（用 id 作为唯一键） */
const items = computed(() => catalog.value.map(c => ({
  no: String(c.id),
  name: c.name,
  category: c.category || '未分类',
  status: '可申请',
  icon: c.icon || null,
  model: c.spec || '',
  location: c.remark || '',
  sn: null,
  userName: null,
  borrower: null
})))
const picked = computed(() => catalog.value.find(c => String(c.id) === pickId.value) || null)
const totalQty = computed(() => cart.value.reduce((sum, c) => sum + c.qty, 0))

watch(open, async value => {
  if (value) {
    overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    dialog.value?.focus()
  } else if (overflow !== null) {
    document.body.style.overflow = overflow
    overflow = null
  }
})

async function show() {
  open.value = true
  step.value = 1
  pickId.value = ''
  pickCategory.value = ''
  cart.value = []
  reason.value = ''
  loading.value = true
  try {
    catalog.value = await purchaseApi.catalog()
  } catch (e: any) {
    toast(e.message)
  } finally {
    loading.value = false
  }
}

function close() {
  if (submitting.value) return
  open.value = false
}

/** 把当前选中的型号加入申请单 */
function addToCart() {
  if (!picked.value) { toast('请先选择需要购买的设备'); return }
  const exist = cart.value.find(c => c.catalogId === picked.value!.id)
  if (exist) {
    if (exist.qty >= MAX_QTY_PER_LINE) { toast(`单项最多申请 ${MAX_QTY_PER_LINE} 台`); return }
    exist.qty += 1
    toast(`「${exist.name}」已加到 ${exist.qty} 台`)
  } else {
    cart.value.push({
      catalogId: picked.value.id,
      name: picked.value.name,
      category: picked.value.category || '未分类',
      spec: picked.value.spec || '',
      qty: 1
    })
    toast('已加入申请单，可继续选择其它设备')
  }
  pickId.value = ''
}

function inc(line: CartLine) {
  if (line.qty >= MAX_QTY_PER_LINE) { toast(`单项最多申请 ${MAX_QTY_PER_LINE} 台`); return }
  line.qty += 1
}
function dec(line: CartLine) {
  if (line.qty <= 1) { remove(line); return }
  line.qty -= 1
}
function remove(line: CartLine) {
  cart.value = cart.value.filter(c => c.catalogId !== line.catalogId)
}

function toApply() {
  if (!cart.value.length) { toast('请先加入至少一项设备'); return }
  step.value = 2
}

async function submit() {
  if (!cart.value.length) { toast('请先加入至少一项设备'); return }
  if (reason.value.trim().length < 4) { toast('请填写购入事由（至少 4 个字）'); return }
  submitting.value = true
  try {
    const r = await purchaseApi.create({
      items: cart.value.map(c => ({ catalogId: c.catalogId, qty: c.qty })),
      reason: reason.value.trim()
    })
    open.value = false
    toast(`已提交 ${r.no}（共 ${totalQty.value} 台），等待审批`)
    emit('submitted', r.no)
  } catch (e: any) {
    toast(e.message)
  } finally {
    submitting.value = false
  }
}

defineExpose({ open: show })
</script>

<template>
  <div v-if="open" class="mask asset-picker-mask" @click.self="close">
    <div ref="dialog" class="dlg asset-selection-dialog" role="dialog" aria-modal="true" aria-labelledby="purchase-title" tabindex="-1">
      <div class="dlg-h">
        <div>
          <h3 id="purchase-title">购入申请</h3>
          <p>{{ step === 1 ? '第 1 步 / 共 2 步 · 选择设备，可加入多项' : '第 2 步 / 共 2 步 · 核对清单并填写事由' }}</p>
        </div>
        <button type="button" class="picker-close" aria-label="关闭购入申请" @click="close">×</button>
      </div>

      <div v-if="step === 1" class="asset-selection-body">
        <div v-if="loading" class="picker-empty" role="status">正在加载可申请购买的设备…</div>
        <div v-else-if="!items.length" class="picker-empty" role="status">
          <b>暂无可申请购买的设备</b>
          <p>可购清单由 IT / 管理员维护，请联系 IT 服务台添加型号。</p>
        </div>
        <div v-else class="pick-layout">
          <AssetPicker
            v-model="pickId"
            v-model:category="pickCategory"
            :assets="items"
            :categories="[]"
            category-label="设备分类"
            count-suffix="个可申请型号"
            select-label="加入申请单"
            search-placeholder="搜索型号 / 规格 / 备注"
            :show-no="false"
            empty-text="该分类下暂无可申请购买的设备，可切换其他分类查看。"
          />

          <!-- 加购界面常驻清单：加入后立刻看到，可直接改数量或移除 -->
          <aside class="cart-panel" aria-label="已加入申请单的设备">
            <div class="cart-head">
              <b>已加入申请单</b>
              <span>{{ cart.length }} 种 · {{ totalQty }} 台</span>
            </div>
            <div v-if="!cart.length" class="cart-empty">
              <b>还没有加入设备</b>
              <span>在左侧选好型号后点「加入申请单」</span>
            </div>
            <div v-else class="cart-body">
              <div v-for="(line, idx) in cart" :key="line.catalogId" class="panel-row">
                <span class="cart-idx">{{ idx + 1 }}</span>
                <div class="panel-main">
                  <b :title="line.name">{{ line.name }}</b>
                  <span class="small muted" :title="line.category + (line.spec ? ' · ' + line.spec : '')">
                    {{ line.category }}<template v-if="line.spec"> · {{ line.spec }}</template>
                  </span>
                  <div class="panel-ops">
                    <div class="qty">
                      <button type="button" aria-label="减少一台" @click="dec(line)">−</button>
                      <b>{{ line.qty }}</b>
                      <button type="button" aria-label="增加一台" @click="inc(line)">＋</button>
                    </div>
                    <button type="button" class="rm" aria-label="移除该项" @click="remove(line)">×</button>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="cart.length" class="cart-foot">
              <span class="small muted">共 {{ cart.length }} 种 · {{ totalQty }} 台</span>
              <button type="button" class="btn sm" @click="cart = []">清空</button>
            </div>
          </aside>
        </div>
      </div>

      <div v-else class="apply-body">
        <div class="cart">
          <div v-for="(line, idx) in cart" :key="line.catalogId" class="cart-row">
            <span class="cart-idx">{{ idx + 1 }}</span>
            <div class="cart-main">
              <b>{{ line.name }}</b>
              <span class="small muted">{{ line.category }}<template v-if="line.spec"> · {{ line.spec }}</template></span>
            </div>
            <div class="qty">
              <button type="button" aria-label="减少一台" @click="dec(line)">−</button>
              <b>{{ line.qty }}</b>
              <button type="button" aria-label="增加一台" @click="inc(line)">＋</button>
            </div>
            <button type="button" class="rm" aria-label="移除该项" @click="remove(line)">×</button>
          </div>
          <div class="cart-total">
            共 {{ cart.length }} 种 · {{ totalQty }} 台
            <button type="button" class="btn sm" @click="step = 1">继续添加</button>
          </div>
        </div>

        <div class="fld" style="margin-top: 16px">
          <label class="lb">购入事由<i>*</i></label>
          <textarea v-model="reason" class="ta" maxlength="500"
            placeholder="例：现有笔记本已到报废年限，新员工入职需要采购；请说明用途与必要性" />
          <p class="small muted" style="margin: 6px 0 0">
            审批通过后 IT 会逐台处理：库存有货的直接配发并通知领取，需要采购的填 ERP 单号、到货后单独交付。
          </p>
        </div>
      </div>

      <div class="dlg-f asset-selection-footer">
        <div class="selection-summary">
          <b>{{ step === 1 ? (picked ? picked.name : '请选择需要购买的设备') : `已选 ${cart.length} 种 · ${totalQty} 台` }}</b>
          <span>{{ step === 1 ? `已加入 ${cart.length} 种 · ${totalQty} 台` : '每一项会独立流转，可分批领取' }}</span>
        </div>
        <div class="picker-footer-actions">
          <button class="btn" :disabled="submitting" @click="close">取消</button>
          <template v-if="step === 1">
            <button class="btn" :disabled="!pickId" @click="addToCart">加入申请单</button>
            <button class="btn pri" :disabled="!cart.length" @click="toApply">下一步（{{ totalQty }}）</button>
          </template>
          <template v-else>
            <button class="btn" :disabled="submitting" @click="step = 1">上一步</button>
            <button class="btn pri" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '提交申请' }}</button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.asset-selection-dialog { outline:none; max-width:1140px; max-height:92vh; display:flex; flex-direction:column; overflow:hidden; }
.asset-selection-dialog .dlg-h { flex-shrink:0; padding:18px 22px; }
.dlg-h h3 { margin:0; font-size:16px; }
.dlg-h p { margin:6px 0 0; color:var(--t3); font-size:11.5px; font-weight:400; }
.picker-close { outline:none; border:0; border-radius:8px; background:transparent; color:var(--t3); font-size:26px; width:32px; height:32px; cursor:pointer; }
.picker-close:hover { color:var(--t1); background:#f2f4f7; }
.asset-selection-body { min-height:0; overflow:auto; }
.apply-body { padding:20px 22px; overflow:auto; }
/* 第 1 步：左侧选设备，右侧常驻购物车 */
.pick-layout {
  --picker-h: min(52vh, 470px);
  --asset-picker-height: var(--picker-h);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 252px;
}
.cart-panel {
  display: flex;
  flex-direction: column;
  height: var(--picker-h);
  border-left: 1px solid var(--line2);
  background: #fafbfc;
}
.cart-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; padding: 14px 14px 10px; }
.cart-head b { font-size: 12.5px; }
.cart-head span { font-size: 11px; color: var(--t3); }
.cart-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 16px; text-align: center; }
.cart-empty b { font-size: 12px; color: var(--t2); font-weight: 600; }
.cart-empty span { font-size: 11px; color: var(--t3); line-height: 1.6; }
.cart-body { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 10px 10px; }
.panel-row { display: flex; gap: 7px; padding: 10px; margin-bottom: 8px; border: 1px solid var(--line2); border-radius: 10px; background: #fff; }
.panel-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
.panel-main > b { font-size: 12px; line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.panel-main > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.panel-ops { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; }
.cart-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 14px; border-top: 1px solid var(--line2); background: #fff; }

.cart { border:1px solid var(--line2); border-radius:12px; overflow:hidden; }
.cart-row { display:flex; align-items:center; gap:10px; padding:11px 14px; border-bottom:1px solid var(--line2); }
.cart-row:last-of-type { border-bottom:0; }
.cart-idx { flex:0 0 20px; height:20px; border-radius:6px; background:#eef2f7; color:var(--t3); font-size:11px; display:grid; place-items:center; }
.cart-main { flex:1; min-width:0; display:flex; flex-direction:column; gap:2px; }
.cart-main b { font-size:13px; overflow-wrap:anywhere; }
.qty { display:flex; align-items:center; gap:2px; flex-shrink:0; border:1px solid var(--line); border-radius:9px; overflow:hidden; }
.qty button { width:28px; height:28px; border:0; background:#f7f8fa; color:var(--t2); font-size:14px; cursor:pointer; }
.qty button:hover { background:#eef2f7; }
.qty b { min-width:30px; text-align:center; font-size:12.5px; }
.rm { flex-shrink:0; border:0; background:transparent; color:var(--t4); font-size:17px; cursor:pointer; padding:0 4px; }
.rm:hover { color:#c9362b; }
.cart-total { display:flex; align-items:center; gap:10px; padding:10px 14px; background:#fafbfc; font-size:12px; color:var(--t3); border-top:1px solid var(--line2); }
.cart-total .btn { margin-left:auto; }
.picker-empty { min-height:230px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; text-align:center; color:var(--t3); font-size:12px; padding:20px; }
.picker-empty b { color:var(--t2); font-size:14px; }
.asset-selection-footer { flex-shrink:0; align-items:center; justify-content:space-between; gap:16px; padding:15px 22px 18px; border-top:1px solid var(--line2); }
.selection-summary { display:flex; flex-direction:column; gap:4px; min-width:0; }
.selection-summary b { font-size:12px; color:var(--t2); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.selection-summary span { font-size:10.5px; color:var(--t3); overflow-wrap:anywhere; }
.picker-footer-actions { display:flex; gap:9px; flex-shrink:0; }
.picker-footer-actions .btn { white-space:nowrap; }
@media(max-width:820px) {
  .pick-layout { grid-template-columns: 1fr; --picker-h: min(44dvh, 400px); }
  .cart-panel { height: auto; max-height: 190px; border-left: 0; border-top: 1px solid var(--line2); }
  .cart-body { display: flex; gap: 8px; overflow-x: auto; overflow-y: hidden; padding: 0 10px 10px; }
  .panel-row { flex: 0 0 176px; margin-bottom: 0; }
}
@media(max-width:600px) {
  .asset-picker-mask { padding:10px; }
  .asset-selection-dialog { max-height:94dvh; }
  .asset-selection-dialog .dlg-h { padding:14px; }
  .dlg-h h3 { font-size:14px; }
  .dlg-h p { max-width:240px; font-size:10.5px; line-height:1.5; }
  .apply-body { padding:14px; }
  .cart-row { padding:9px 10px; gap:7px; }
  .cart-idx { display:none; }
  .asset-selection-footer { padding:12px 14px 14px; gap:10px; }
  .selection-summary { max-width:calc(100% - 190px); }
  .selection-summary b { font-size:11px; }
  .selection-summary span { font-size:9px; }
  .picker-footer-actions { gap:7px; }
  .picker-footer-actions .btn { padding:0 12px; }
}
</style>
