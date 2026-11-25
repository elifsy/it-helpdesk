<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ASSET_STATUS_CLASS } from '../utils/dict'
import { autoIconKey, iconClass } from '../utils/deviceIcons'
import DeviceIcon from './DeviceIcon.vue'

interface PickerAsset {
  no: string
  name: string
  model?: string | null
  category: string
  status: string
  sn?: string | null
  location?: string | null
  userId?: number | null
  borrowerId?: number | null
  /** 指定图标 key（采购目录可配）；为空时按分类+名称自动匹配 */
  icon?: string | null
}
const props = withDefaults(defineProps<{
  assets: PickerAsset[]
  categories: { id: number; name: string }[]
  modelValue: string
  category: string
  loading?: boolean
  categoryLabel?: string
  emptyText?: string
  /** 本人使用或借用的设备优先展示。 */
  myId?: number
  /** 统计口径后缀，如「台可选设备」/「个可申请型号」 */
  countSuffix?: string
  /** 是否显示卡片上的编号行（采购目录没有资产编号） */
  showNo?: boolean
  selectLabel?: string
  searchPlaceholder?: string
}>(), {
  loading: false, categoryLabel: '资产分类', emptyText: '该分类下暂无可选设备，可切换其他分类查看。',
  countSuffix: '台可选设备', showNo: true, selectLabel: '选择这台设备',
  searchPlaceholder: '搜索设备名称 / 型号 / 编号'
})
/** 是否为「我的设备」（本人领用或本人借用） */
function mine(asset: PickerAsset) {
  return props.myId != null && (asset.userId === props.myId || asset.borrowerId === props.myId)
}
const emit = defineEmits<{ 'update:modelValue': [value: string]; 'update:category': [value: string] }>()
const keyword = ref('')
const choices = ref<HTMLElement>()
const categoryCounts = computed(() => {
  const counts = new Map<string, number>()
  props.assets.forEach(asset => counts.set(asset.category, (counts.get(asset.category) || 0) + 1))
  return counts
})
const categoryOptions = computed(() => {
  const names = [...new Set([...props.categories.map(category => category.name), ...props.assets.map(asset => asset.category)])]
  return [{ name: '', label: '全部分类', count: props.assets.length }, ...names.map(name => ({ name, label: name, count: categoryCounts.value.get(name) || 0 }))]
})
const inCategory = computed(() => {
  const list = props.assets.filter(asset => !props.category || asset.category === props.category)
  // 我的设备排在前面，方便在长列表里直接找到
  return props.myId != null ? list.sort((a, b) => Number(mine(b)) - Number(mine(a))) : list
})
const shown = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return query ? inCategory.value.filter(asset => [asset.name, asset.model, asset.no, asset.sn, asset.location, asset.category]
    .some(value => String(value || '').toLowerCase().includes(query))) : inCategory.value
})
const selectedVisible = computed(() => shown.value.some(asset => asset.no === props.modelValue))
watch(inCategory, assets => { if (props.modelValue && !assets.some(asset => asset.no === props.modelValue)) emit('update:modelValue', '') })
watch(() => props.category, () => { keyword.value = '' })

function chooseCategory(category: string) {
  keyword.value = ''
  if (props.modelValue && !props.assets.some(asset => asset.no === props.modelValue && (!category || asset.category === category))) emit('update:modelValue', '')
  emit('update:category', category)
}
/** 图标 key：目录配置优先，否则按分类 + 名称自动匹配 */
function iconOf(asset: PickerAsset) {
  return asset.icon && asset.icon !== 'auto' ? asset.icon : autoIconKey(asset.category, asset.name)
}
function artClass(asset: PickerAsset) {
  return iconClass(iconOf(asset))
}
async function onChoiceKeydown(event: KeyboardEvent, index: number) {
  const count = shown.value.length
  if (!count) return
  const columns = Math.max(1, getComputedStyle(choices.value!).gridTemplateColumns.split(' ').length)
  const offsets: Record<string, number> = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: columns, ArrowUp: -columns }
  let target = index
  if (event.key === 'Home') target = 0
  else if (event.key === 'End') target = count - 1
  else if (event.key in offsets) target = ((index + offsets[event.key]) % count + count) % count
  else return
  event.preventDefault()
  emit('update:modelValue', shown.value[target].no)
  await nextTick()
  choices.value?.querySelectorAll<HTMLButtonElement>('.device-option')[target]?.focus()
}
</script>

<template>
  <div class="asset-picker" :data-selected-asset="modelValue" :data-selected-category="category">
    <aside class="picker-categories" :aria-label="categoryLabel">
      <div class="category-heading">{{ categoryLabel }}</div>
      <nav class="category-options" :aria-label="categoryLabel + '选项'">
        <button v-for="item in categoryOptions" :key="item.name" type="button" class="category-option" :class="{ active: category === item.name }"
          :data-category="item.name" :aria-pressed="category === item.name" :disabled="loading" @click="chooseCategory(item.name)">
          <span>{{ item.label }}</span><small>{{ item.count }}</small>
        </button>
      </nav>
    </aside>
    <section class="picker-main" aria-label="可选设备">
      <div class="picker-toolbar">
        <div><h4>{{ category || '全部设备' }}</h4><p>共 {{ shown.length }}{{ countSuffix }}</p></div>
        <label class="picker-search"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4 4" /></svg><input v-model="keyword" type="search" :placeholder="searchPlaceholder" aria-label="搜索可选设备" :disabled="loading" /></label>
      </div>
      <div class="picker-results">
        <div v-if="loading" class="picker-empty" role="status">正在加载可选设备…</div>
        <div v-else-if="!shown.length" class="picker-empty" role="status">
          <svg viewBox="0 0 64 64" aria-hidden="true"><rect x="13" y="12" width="38" height="31" rx="4" /><path d="M7 50h50M26 43v7m12-7v7M25 25h14m-14 7h8" /></svg>
          <b>{{ keyword ? '没有找到匹配的设备' : '暂无可选设备' }}</b><p>{{ keyword ? '试试其他关键词，或清除搜索查看全部设备。' : emptyText }}</p>
          <button v-if="keyword" type="button" class="btn sm" @click="keyword = ''">清除搜索</button>
        </div>
        <div v-else ref="choices" class="device-options" role="radiogroup" aria-label="选择一台设备">
          <button v-for="(asset, index) in shown" :key="asset.no" type="button" class="device-option" :class="{ selected: modelValue === asset.no }"
            role="radio" :aria-checked="modelValue === asset.no" :aria-label="(mine(asset) ? '我的设备，' : '') + asset.name + '，' + asset.no + '，' + asset.status"
            :tabindex="modelValue === asset.no || (!selectedVisible && index === 0) ? 0 : -1" :data-asset-no="asset.no"
            @click="emit('update:modelValue', asset.no)" @keydown="onChoiceKeydown($event, index)">
            <div class="device-art" :class="artClass(asset)">
              <span class="pill" :class="ASSET_STATUS_CLASS[asset.status] || 'b-gray'">{{ asset.status }}</span>
              <DeviceIcon :name="iconOf(asset)" />
              <span v-if="modelValue === asset.no" class="selected-mark" aria-hidden="true">✓</span>
              <span v-if="mine(asset)" class="mine-mark">我的设备</span>
            </div>
            <div class="device-info"><h5>{{ asset.name }}</h5><div v-if="showNo" class="device-no mono">{{ asset.no }}</div><p v-if="asset.model">型号：{{ asset.model }}</p><div class="device-category">{{ asset.category }}</div><p>{{ asset.location || '未标注位置' }}</p><p v-if="asset.sn" class="device-sn">SN：{{ asset.sn }}</p></div>
            <div class="device-select"><span>{{ modelValue === asset.no ? '已选择' : selectLabel }}</span><i aria-hidden="true">{{ modelValue === asset.no ? '✓' : '' }}</i></div>
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.asset-picker { display:grid; grid-template-columns:166px minmax(0,1fr); height:var(--asset-picker-height, min(58vh,540px)); min-height:280px; overflow:hidden; background:#fff; }
.picker-categories { display:flex; flex-direction:column; min-height:0; background:#f7f8fa; border-right:1px solid var(--line2); }
.category-heading { padding:20px 18px 12px; font-size:12px; color:var(--t3); font-weight:600; }
.category-options { overflow-y:auto; overscroll-behavior:contain; padding:0 9px 16px; }
.category-option { display:flex; align-items:center; justify-content:space-between; gap:8px; width:100%; border:0; border-radius:10px; background:transparent; padding:13px 11px; margin-bottom:4px; text-align:left; cursor:pointer; color:var(--t2); font-size:13px; line-height:1.5; }
.category-option span { overflow-wrap:anywhere; }.category-option small { flex-shrink:0; min-width:21px; padding:1px 5px; border-radius:6px; background:#e9ecf1; color:var(--t3); font-size:10px; text-align:center; }
.category-option:hover { background:#edf1f7; }.category-option.active { background:#e8f2ff; color:var(--brand); font-weight:700; }.category-option.active small { background:#d3e7ff; color:var(--brand); }
.category-option:focus-visible,.device-option:focus-visible { outline:2px solid var(--brand); outline-offset:-2px; }
.picker-main { display:flex; flex-direction:column; min-width:0; min-height:0; }
.picker-toolbar { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:12px; padding:19px 20px 16px; border-bottom:1px solid var(--line2); }
.picker-toolbar h4 { font-size:15px; margin:0; }.picker-toolbar p { font-size:11.5px; color:var(--t3); margin:5px 0 0; }
.picker-search { display:flex; align-items:center; gap:8px; flex:1; min-width:150px; max-width:250px; height:36px; padding:0 11px; border:1px solid var(--line); border-radius:10px; background:#fff; }.picker-search:focus-within { border-color:var(--brand); box-shadow:0 0 0 3px rgba(0,113,227,.08); }
.picker-search svg { width:16px; height:16px; flex-shrink:0; fill:none; stroke:var(--t3); stroke-width:1.7; }.picker-search input { min-width:0; width:100%; border:0; outline:0; font:inherit; font-size:12px; background:transparent; }
.picker-results { flex:1; min-height:0; overflow-y:auto; overscroll-behavior:contain; padding:18px 20px 22px; }
.device-options { display:grid; grid-template-columns:repeat(auto-fill,minmax(205px,1fr)); gap:14px; align-items:stretch; }
.device-option { display:flex; flex-direction:column; min-width:0; border:1px solid #e4e8ee; border-radius:14px; background:#fff; padding:0; text-align:left; cursor:pointer; overflow:hidden; transition:border-color .15s,box-shadow .15s; }
.device-option:hover { border-color:#94bff1; box-shadow:0 4px 14px rgba(0,74,148,.07); }.device-option.selected { border-color:var(--brand); box-shadow:0 0 0 1px var(--brand); }
/* 底色与描边色由全局 .art-xxx 提供（按图标族配色），这里只管排版 */
.device-art { position:relative; display:flex; align-items:center; justify-content:center; height:104px; flex-shrink:0; }
.device-art > svg { width:66px; height:66px; }.device-art .pill { position:absolute; top:10px; left:10px; font-size:10px; font-weight:500; white-space:nowrap; }
.selected-mark { position:absolute; top:10px; right:10px; width:21px; height:21px; border-radius:50%; background:var(--brand); color:#fff; display:grid; place-items:center; font-size:12px; }
.mine-mark { position:absolute; bottom:9px; left:10px; padding:3px 9px; border-radius:999px; background:#fff; color:var(--brand); font-size:10px; font-weight:600; letter-spacing:.02em; box-shadow:0 2px 8px rgba(0,74,148,.14); }
.device-info { padding:13px 13px 12px; flex:1; }.device-info h5 { margin:0 0 7px; font-size:13px; line-height:1.6; font-weight:650; overflow-wrap:anywhere; }.device-no { font-size:10.5px; color:var(--t2); overflow-wrap:anywhere; line-height:1.6; }
.device-category { display:inline-block; padding:2px 6px; margin-top:8px; border-radius:5px; background:#f3f5f8; font-size:10px; color:var(--t3); }.device-info p { font-size:11px; color:var(--t3); line-height:1.5; margin:7px 0 0; overflow-wrap:anywhere; }.device-info .device-sn { font-size:10px; }
.device-select { display:flex; align-items:center; justify-content:space-between; padding:10px 13px; border-top:1px solid var(--line2); font-size:11.5px; color:var(--t3); }.device-select i { width:15px; height:15px; border:1px solid #ccd4df; border-radius:50%; font-style:normal; display:grid; place-items:center; font-size:10px; }
.selected .device-select { color:var(--brand); background:#f4f9ff; font-weight:600; }.selected .device-select i { background:var(--brand); border-color:var(--brand); color:#fff; }
.picker-empty { min-height:230px; height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; gap:10px; color:var(--t3); padding:20px; font-size:12px; }.picker-empty svg { width:62px; height:62px; fill:none; stroke:#b9c9dc; stroke-width:1.6; }.picker-empty b { color:var(--t2); font-size:14px; }.picker-empty p { margin:0; line-height:1.8; max-width:300px; }
@media(max-width:600px) { .asset-picker { grid-template-columns:92px minmax(0,1fr); height:var(--asset-picker-height,min(59dvh,520px)); min-height:260px; }.category-heading { padding:16px 10px 10px; font-size:11px; }.category-options { padding:0 5px 10px; }.category-option { padding:12px 7px; gap:4px; font-size:11.5px; }.category-option small { min-width:17px; padding:1px 3px; font-size:9px; }.picker-toolbar { padding:13px 12px; gap:10px; }.picker-toolbar h4 { font-size:13px; }.picker-toolbar p { font-size:10px; }.picker-search { flex-basis:100%; min-width:0; max-width:none; height:33px; padding:0 8px; }.picker-search input { font-size:11px; }.picker-results { padding:12px; }.device-options { grid-template-columns:minmax(0,1fr); gap:12px; }.device-art { height:88px; }.device-art > svg { width:59px; height:59px; }.device-info { padding:11px; }.device-info h5 { font-size:12px; }.device-select { padding:9px 11px; font-size:11px; }.picker-empty { min-height:200px; padding:10px 2px; }.picker-empty b { font-size:12px; } }
@media(prefers-reduced-motion:reduce) { .device-option { transition:none; } }
</style>
