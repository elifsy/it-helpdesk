<script setup lang="ts">
import { computed, onUnmounted, ref, useId, watch } from 'vue'
import { kbApi } from '../api'

interface Article { id: number; title: string; summary?: string; category?: string }
const props = withDefaults(defineProps<{ modelValue: string; category?: string; placeholder?: string; managed?: boolean; status?: string }>(), {
  category: '', placeholder: '搜索标题 / 摘要', managed: false, status: ''
})
const emit = defineEmits<{ 'update:modelValue': [value: string]; search: [] }>()
const input = ref<HTMLInputElement>()
const root = ref<HTMLElement>()
const focused = ref(false)
const dismissed = ref(false)
const composing = ref(false)
const busy = ref(false)
const failed = ref(false)
const suggestions = ref<Article[]>([])
const active = ref(-1)
const listId = 'knowledge-suggestions-' + useId()
const value = computed({ get: () => props.modelValue, set: text => emit('update:modelValue', text) })
const query = computed(() => props.modelValue.trim())
const expanded = computed(() => focused.value && !dismissed.value && !!query.value)
let timer: ReturnType<typeof setTimeout> | undefined
let version = 0
let submittedQuery: string | null = null

function cancel() { clearTimeout(timer); version++; busy.value = false }
function schedule() {
  cancel(); active.value = -1; suggestions.value = []; failed.value = false
  if (submittedQuery !== null && submittedQuery !== query.value) submittedQuery = null
  if (!focused.value || composing.value || !query.value || submittedQuery === query.value) return
  dismissed.value = false; busy.value = true
  const request = version, keyword = query.value, category = props.category
  timer = setTimeout(async () => {
    try {
      const result = props.managed
        ? await kbApi.managedList({ keyword, status: props.status || undefined, page: 1, size: 50 })
        : await kbApi.list({ keyword, category: category || undefined })
      const rows = props.managed ? result.list : result
      if (request !== version) return
      const needle = keyword.normalize('NFKC').toLocaleLowerCase()
      const rank = (row: Article) => {
        const title = row.title.normalize('NFKC').toLocaleLowerCase()
        return title === needle ? 0 : title.startsWith(needle) ? 1 : title.includes(needle) ? 2 : 3
      }
      const unique = Array.from(new Map((Array.isArray(rows) ? rows : []).map((row: Article) => [row.id, row])).values()) as Article[]
      suggestions.value = unique.sort((a, b) => rank(a) - rank(b)).slice(0, 5)
    } catch { if (request === version) failed.value = true }
    finally { if (request === version) busy.value = false }
  }, 200)
}
watch([query, () => props.category, () => props.managed, () => props.status], schedule)
function focus() { focused.value = true; submittedQuery = null; schedule() }
function leave(event: FocusEvent) {
  if (event.relatedTarget instanceof Node && root.value?.contains(event.relatedTarget)) return
  focused.value = false; dismissed.value = true; cancel()
}
function submit() {
  cancel(); dismissed.value = true; active.value = -1; submittedQuery = query.value
  emit('search')
}
function choose(article: Article) {
  input.value?.focus()
  submittedQuery = article.title.trim()
  emit('update:modelValue', article.title)
  cancel(); dismissed.value = true; active.value = -1
  emit('search')
}
function keydown(event: KeyboardEvent) {
  if (composing.value || event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape') { event.preventDefault(); cancel(); dismissed.value = true; active.value = -1; return }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (!suggestions.value.length) return
    event.preventDefault(); dismissed.value = false
    const length = suggestions.value.length
    active.value = event.key === 'ArrowDown' ? (active.value + 1) % length : active.value < 0 ? length - 1 : (active.value + length - 1) % length
    root.value?.querySelectorAll<HTMLElement>('[role=option]')[active.value]?.scrollIntoView({ block: 'nearest' })
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (expanded.value && active.value >= 0 && suggestions.value[active.value]) choose(suggestions.value[active.value])
    else submit()
  }
}
function parts(text = '') {
  const needle = query.value.toLocaleLowerCase(), position = text.toLocaleLowerCase().indexOf(needle)
  if (!needle || position < 0) return [{ text, match: false }]
  return [{ text: text.slice(0, position), match: false }, { text: text.slice(position, position + needle.length), match: true },
    { text: text.slice(position + needle.length), match: false }]
}
onUnmounted(cancel)
</script>

<template>
  <form ref="root" class="row kb-search" role="search" @submit.prevent="submit" @focusout="leave">
    <div class="search-input-wrap">
      <input ref="input" v-model="value" class="inp" :placeholder="placeholder" aria-label="搜索知识库" autocomplete="off" maxlength="128"
        role="combobox" aria-autocomplete="list" :aria-expanded="expanded" :aria-controls="listId"
        :aria-activedescendant="expanded && active >= 0 ? listId + '-' + active : undefined"
        @focus="focus" @keydown="keydown" @compositionstart="composing = true; cancel()" @compositionend="composing = false; schedule()" />
      <div v-if="expanded" class="knowledge-suggestions">
        <div v-if="busy" class="suggestion-state" role="status">正在查找相关条目…</div>
        <div v-else-if="failed" class="suggestion-state" role="status">联想暂时不可用，可点击搜索重试</div>
        <div v-else-if="!suggestions.length" class="suggestion-state" role="status">没有相关条目，试试更短的关键词</div>
        <div :id="listId" role="listbox" aria-label="相关知识条目" :aria-busy="busy">
          <button v-for="(article, index) in suggestions" :id="listId + '-' + index" :key="article.id" type="button" role="option" tabindex="-1"
            class="suggestion-option" :class="{ active: active === index }" :aria-selected="active === index" @mousedown.prevent @click="choose(article)">
            <span class="suggestion-title"><span v-for="(part, i) in parts(article.title)" :key="i" :class="{ match: part.match }">{{ part.text }}</span></span>
            <span class="suggestion-summary"><small v-if="article.category">{{ article.category }}</small><span v-for="(part, i) in parts(article.summary)" :key="i" :class="{ match: part.match }">{{ part.text }}</span></span>
          </button>
        </div>
        <div v-if="!busy && suggestions.length" class="suggestion-hint">点击搜索该条目 · ↑↓ 选择，Enter 确认</div>
      </div>
    </div>
    <button class="btn" type="submit">搜索</button>
  </form>
</template>

<style scoped>
.kb-search { width:100%; max-width:560px; gap:8px; }
.search-input-wrap { position:relative; flex:1; min-width:0; }
.search-input-wrap .inp { width:100%; }
.kb-search > .btn { flex-shrink:0; white-space:nowrap; }
.knowledge-suggestions { position:absolute; top:calc(100% + 8px); left:0; right:0; z-index:45; overflow:hidden; border:1px solid var(--line); border-radius:14px; background:#fff; box-shadow:0 12px 36px rgba(29,29,31,.14); text-align:left; }
[role=listbox] { max-height:360px; overflow-y:auto; overscroll-behavior:contain; }
.suggestion-option { display:block; width:100%; padding:12px 14px; border:0; border-bottom:1px solid var(--line2); background:#fff; color:var(--t1); text-align:left; }
.suggestion-option:hover,.suggestion-option.active { background:var(--brand-l); }
.suggestion-title { display:block; font-size:13px; font-weight:600; overflow-wrap:anywhere; }
.suggestion-summary { display:block; margin-top:4px; font-size:11px; color:var(--t3); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.suggestion-summary small { margin-right:7px; color:var(--t2); }
.match { color:var(--brand); font-weight:600; }
.suggestion-state { padding:15px 14px; font-size:12px; color:var(--t3); line-height:1.6; }
.suggestion-hint { padding:8px 14px; background:#fafbfc; font-size:10px; color:var(--t3); }
@media(max-width:899px) {
  .knowledge-suggestions { right:-70px; max-width:calc(100vw - 32px); }
  .suggestion-option { padding:13px; min-height:56px; }.suggestion-title { font-size:14px; }
  [role=listbox] { max-height:40vh; }.suggestion-hint { display:none; }
}
</style>
