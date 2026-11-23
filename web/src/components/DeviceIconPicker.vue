<script setup lang="ts">
/**
 * 卡片图标选择器：自动 + DEVICE_ICONS 全量图标，单选并预览底色。
 * 采购目录编辑、资产台账新增/编辑共用这一个组件。
 */
import { computed } from 'vue'
import DeviceIcon from './DeviceIcon.vue'
import { DEVICE_ICONS, autoIconKey, iconClass } from '../utils/deviceIcons'

const props = withDefaults(defineProps<{
  modelValue?: string | null
  /** 「自动」预览时使用的上下文 */
  category?: string
  name?: string
  disabled?: boolean
  label?: string
}>(), { modelValue: '', category: '', name: '', disabled: false, label: '选择卡片图标' })

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

/** 未显式指定时实际会生效的图标 */
const effective = computed(() => props.modelValue || autoIconKey(props.category, props.name))

function choose(key: string) {
  if (!props.disabled) emit('update:modelValue', key)
}
</script>

<template>
  <div class="icon-grid" role="radiogroup" :aria-label="label">
    <button type="button" class="icon-opt" :class="{ on: !modelValue }"
      role="radio" :aria-checked="!modelValue" :disabled="disabled" @click="choose('')">
      <span class="icon-preview auto" :class="iconClass(effective)"><DeviceIcon :name="effective" /></span>
      <span>自动</span>
    </button>
    <button v-for="ic in DEVICE_ICONS" :key="ic.key" type="button" class="icon-opt"
      :class="{ on: modelValue === ic.key }" role="radio" :aria-checked="modelValue === ic.key"
      :disabled="disabled" @click="choose(ic.key)">
      <span class="icon-preview" :class="iconClass(ic.key)"><DeviceIcon :name="ic.key" /></span>
      <span>{{ ic.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.icon-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(76px, 1fr)); gap: 8px; }
.icon-opt { display: flex; flex-direction: column; align-items: center; gap: 5px; padding: 8px 4px 7px; border: 1px solid var(--line); border-radius: 11px; background: #fff; cursor: pointer; font: inherit; font-size: 11px; color: var(--t2); }
.icon-opt:hover:not(:disabled) { border-color: #94bff1; }
.icon-opt.on { border-color: var(--brand); box-shadow: 0 0 0 1px var(--brand); color: var(--brand); font-weight: 600; }
.icon-opt:disabled { opacity: .6; cursor: not-allowed; }
.icon-preview { width: 40px; height: 40px; border-radius: 10px; padding: 6px; }
.icon-preview.auto { border: 1px dashed #c9d3df; }
</style>
