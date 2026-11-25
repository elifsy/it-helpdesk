<script setup lang="ts">
import { computed, ref } from 'vue'
import { assetApi, type AssetCategory } from '../api'
import { ASSET_WARRANTY_OPTIONS, NEW_ASSET_STATUSES } from '../utils/asset-form'
import { DEVICE_ICONS } from '../utils/deviceIcons'

const props = defineProps<{ categories: AssetCategory[] }>()
const emit = defineEmits<{ close: []; imported: [count: number] }>()
const categoryNames = computed(() => props.categories.map(category => category.name).join('、'))
const iconOptions = DEVICE_ICONS.map(icon => `${icon.label} ${icon.key}`).join('、')
const file = ref<File>()
const uploading = ref(false)
const downloading = ref(false)
const error = ref('')

function close() {
  if (!uploading.value && !downloading.value) emit('close')
}

function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  file.value = undefined
  error.value = ''
  if (!selected) return
  if (!/\.xlsx$/i.test(selected.name) || selected.size === 0 || selected.size > 5 * 1024 * 1024) {
    error.value = '请选择不超过 5 MB 的非空 .xlsx 文件'
    input.value = ''
    return
  }
  file.value = selected
}

async function downloadTemplate() {
  if (downloading.value || uploading.value) return
  downloading.value = true
  error.value = ''
  try {
    const blob = await assetApi.importTemplate()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '资产批量导入模板.xlsx'
    document.body.append(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 5000)
  } catch {
    error.value = '模板下载失败，请稍后重试'
  } finally {
    downloading.value = false
  }
}

async function importFile() {
  if (!file.value || uploading.value || downloading.value) return
  uploading.value = true
  error.value = ''
  try {
    const result = await assetApi.importFile(file.value)
    emit('imported', result.imported)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '导入失败，请重试'
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="mask" @click.self="close" @keydown.esc="close">
    <form class="dlg import-dialog" role="dialog" aria-modal="true" aria-labelledby="asset-import-title" @submit.prevent="importFile">
      <div class="dlg-h">
        <span id="asset-import-title">批量导入资产</span>
        <button type="button" class="x close-import" aria-label="关闭批量导入" :disabled="uploading || downloading" @click="close">×</button>
      </div>
      <div class="dlg-b">
        <button type="button" class="btn" :disabled="downloading || uploading" @click="downloadTemplate">
          {{ downloading ? '下载中…' : '下载 Excel 导入模板' }}
        </button>
        <p class="import-hint muted">设备名称、资产分类必填。可参考 Excel 中的「填写示例」，从「资产导入」第 2 行开始填写。</p>
        <details class="import-guide">
          <summary>填写说明</summary>
          <dl>
            <div><dt>编号</dt><dd>资产编号由系统生成。ERP资产编号选填，填写时须唯一。ERP资产编号与 SN 建议保留文本格式，避免丢失前导零。</dd></div>
            <div><dt>资产分类</dt><dd>请从模板下拉选项选择台账已有分类。<span v-if="categoryNames">当前可选分类有 {{ categoryNames }}。</span></dd></div>
            <div><dt>设备状态</dt><dd>可以选择 {{ NEW_ASSET_STATUSES.join('、') }}。使用人和状态均留空时默认为「待入库」，填写了存放位置则设为「闲置」。借出中的设备请通过资产借用流程登记。</dd></div>
            <div><dt>使用人 / 使用人工号</dt><dd>选填，优先填写通讯录工号。只填姓名时必须唯一，同名请核对部门并填写工号。填写后，设备状态可留空或填「在用」。</dd></div>
            <div><dt>购置日期和维保到期</dt><dd>可留空。填写时使用 Excel 日期单元格或 YYYY-MM-DD 格式，维保到期应等于或晚于购置日期。</dd></div>
            <div><dt>维保方式</dt><dd>可留空或填写文字，例如 {{ ASSET_WARRANTY_OPTIONS.join('、') }}。</dd></div>
            <div><dt>卡片图标</dt><dd>留空或填 auto 会自动匹配。也可以填写对应英文代码，例如 {{ iconOptions }}。</dd></div>
          </dl>
        </details>
        <label class="import-file">
          选择 Excel 文件
          <input type="file" class="inp" accept=".xlsx" :disabled="uploading || downloading" @change="chooseFile" />
          <span class="small muted">每次最多导入 500 台，文件上限 5 MB。</span>
        </label>
        <p v-if="error" class="import-error" role="alert">{{ error }}</p>
      </div>
      <div class="dlg-f">
        <button type="button" class="btn" :disabled="uploading || downloading" @click="close">取消</button>
        <button class="btn pri" :disabled="!file || uploading || downloading">{{ uploading ? '导入中…' : '开始导入' }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.import-dialog { max-width: 570px; display: flex; flex-direction: column; overflow: hidden; }
.import-dialog .dlg-b { min-height: 0; overflow-y: auto; }
.import-dialog .dlg-h, .import-dialog .dlg-f { flex-shrink: 0; }
.close-import { border: 0; background: none; padding: 0; }
.import-hint { margin: 12px 0; font-size: 12px; line-height: 1.6; }
.import-guide { margin: 0 0 18px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 12px; background: #f8f9fb; }
.import-guide summary { font-size: 13px; font-weight: 600; cursor: pointer; }
.import-guide dl { margin: 12px 0 0; display: grid; gap: 9px; font-size: 12px; line-height: 1.6; }
.import-guide dt { font-weight: 600; }
.import-guide dd { margin: 2px 0 0; color: var(--t2); overflow-wrap: anywhere; }
.import-file { display: grid; gap: 7px; font-size: 13px; }
.import-file .inp { width: 100%; height: auto; min-width: 0; }
.import-error { margin: 12px 0 0; padding: 10px 12px; background: #fff0ee; color: #b32921; border-radius: 10px; font-size: 13px; }
</style>
