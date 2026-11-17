<script setup lang="ts">
import { type AssetFormValue, NEW_ASSET_STATUSES } from '../utils/asset-form'
import type { AssetCategory } from '../api'
import { watch } from 'vue'

const props = withDefaults(defineProps<{
  form: AssetFormValue
  categories: AssetCategory[]
  categoryLabel?: string
  statuses?: string[]
  disabled?: boolean
}>(), { categoryLabel: '编辑资产分类', statuses: () => NEW_ASSET_STATUSES, disabled: false })

watch([() => props.form.location, () => props.form.status, () => props.disabled], ([location, status, disabled]) => {
  if (!disabled && location.trim() && status === '待入库' && props.statuses.includes('闲置')) props.form.status = '闲置'
}, { immediate: true })
</script>

<template>
  <fieldset class="asset-fields" :disabled="disabled">
    <label class="field wide">设备名称<i>*</i><input v-model="form.name" class="inp" required maxlength="128" aria-label="设备名称" placeholder="如：办公笔记本" /></label>
    <label class="field">型号<input v-model="form.model" class="inp" maxlength="128" aria-label="设备型号" placeholder="如：ThinkPad T14s Gen 5" /></label>
    <label class="field">资产分类<i>*</i><select v-model="form.category" class="sel" required :aria-label="categoryLabel"><option value="" disabled>请选择分类</option><option v-for="c in categories" :key="c.id" :value="c.name">{{ c.name }}</option></select><slot name="category-actions" /></label>
    <label class="field">SN 序列号<input v-model="form.sn" class="inp" maxlength="64" aria-label="SN序列号" /></label>
    <label class="field">存放位置<input v-model="form.location" class="inp" maxlength="64" aria-label="存放位置" placeholder="如：A座 1F 备件库" /><span class="hint">待入库设备填写位置后，自动设为闲置。</span></label>
    <label class="field">购置日期<input v-model="form.buyDate" class="inp" type="date" aria-label="购置日期" /></label>
    <label class="field">维保到期<input v-model="form.warrantyEnd" class="inp" type="date" :min="form.buyDate || undefined" aria-label="维保到期" /></label>
    <label class="field">维保方式<input v-model="form.warranty" class="inp" maxlength="32" list="warranty-options" aria-label="维保方式" /><datalist id="warranty-options"><option>厂商保修</option><option>自保</option><option>已过保</option></datalist></label>
    <label class="field">设备状态<i>*</i><select v-model="form.status" class="sel" required :disabled="statuses.length === 1" aria-label="设备状态"><option v-for="state in statuses" :key="state" :value="state">{{ state }}</option></select></label>
    <p v-if="statuses.length === 1" class="hint wide">该设备已分配或借出，状态随资产申请流程更新。</p>
    <label class="field wide">备注<textarea v-model="form.remark" class="ta" maxlength="255" aria-label="设备备注" /></label>
  </fieldset>
</template>

<style scoped>
.asset-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:18px; min-width:0; border:0; margin:0; padding:0; }
.field { display:flex; flex-direction:column; gap:7px; font-size:12px; min-width:0; position:relative; }
.field i { color:#e35050; font-style:normal; position:absolute; margin-left:52px; top:0; }
.wide { grid-column:1/-1; }.hint { color:var(--t2); font-size:12px; line-height:1.8; margin:0; }
@media(max-width:520px) { .asset-fields { grid-template-columns:1fr; }.wide { grid-column:auto; } }
</style>
