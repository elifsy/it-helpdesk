<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { accountBadgeApi } from '../api'

const enabled = ref<boolean>()
const busy = ref(false)
const error = ref('')

async function load() {
  busy.value = true
  error.value = ''
  try { enabled.value = (await accountBadgeApi.getTitleSettings()).enabled }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取称号设置' }
  finally { busy.value = false }
}

async function save(event: Event) {
  const input = event.target as HTMLInputElement
  busy.value = true
  error.value = ''
  try {
    enabled.value = (await accountBadgeApi.setTitlesEnabled(input.checked)).enabled
    window.dispatchEvent(new Event('badge-titles-changed'))
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法保存称号设置' }
  finally {
    input.checked = !!enabled.value
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <label class="badge-title-setting">
      <span>工牌称号系统</span>
      <input v-if="enabled !== undefined" type="checkbox" role="switch" :checked="enabled" :disabled="busy" @change="save" />
      <span v-else-if="busy" class="muted small" role="status">读取中…</span>
    </label>
    <p v-if="error" class="small badge-title-error" role="alert">{{ error }}
      <button v-if="enabled === undefined" class="btn sm" type="button" @click="load">重试</button>
    </p>
  </div>
</template>

<style scoped>
.badge-title-setting { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 0; }
.badge-title-error { color:#b32921; }
</style>
