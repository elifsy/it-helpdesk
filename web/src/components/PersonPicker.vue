<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, useId, watch, type CSSProperties } from 'vue'
import { matchingPeople, personDetails, type PersonOption } from '../utils/people-search'

const props = withDefaults(defineProps<{
  label: string
  options?: readonly PersonOption[]
  search?: (query: string) => Promise<PersonOption[]>
  selected?: PersonOption | null
  manualName?: string
  clearAfterSelect?: boolean
  disabled?: boolean
  required?: boolean
  invalid?: boolean
  placeholder?: string
}>(), { placeholder: '姓名、拼音、首字母、部门或工号' })
const emit = defineEmits<{
  select: [person: PersonOption]
  clear: []
  'text-change': [value: string]
}>()

const inputId = 'person-' + useId()
const listId = inputId + '-options'
const input = ref<HTMLInputElement>()
const panel = ref<HTMLElement>()
const displayName = () => props.selected?.name ?? props.manualName ?? ''
const text = ref(displayName())
const open = ref(false)
const active = ref(-1)
const busy = ref(false)
const failed = ref(false)
const composing = ref(false)
const remoteResults = ref<PersonOption[]>([])
const placement = ref<CSSProperties>()
const query = computed(() => text.value.trim())
const expanded = computed(() => open.value && !props.disabled && (!props.search || !!query.value))
const visible = computed(() => (
  props.search ? remoteResults.value : matchingPeople(props.options ?? [], text.value)
).slice(0, 20))
let pending: ReturnType<typeof setTimeout> | undefined
let version = 0

function cancelSearch() {
  clearTimeout(pending)
  version++
  busy.value = false
}

function updateResults() {
  active.value = -1
  cancelSearch()
  failed.value = false
  remoteResults.value = []
  if (!props.search || !expanded.value || composing.value) return
  const search = props.search
  const value = query.value
  const request = version
  busy.value = true
  pending = setTimeout(async () => {
    try {
      const rows = await search(value)
      if (request === version) remoteResults.value = rows
    } catch {
      if (request === version) failed.value = true
    } finally {
      if (request === version) busy.value = false
    }
  }, 180)
}

function show() {
  open.value = true
  updateResults()
}

function hide() {
  open.value = false
  active.value = -1
  cancelSearch()
  text.value = displayName()
}

function type(event: Event) {
  text.value = (event.target as HTMLInputElement).value
  if (composing.value) return
  if (props.selected && text.value !== props.selected.name) emit('clear')
  emit('text-change', text.value.trim())
  show()
}

function clear() {
  emit('clear')
  emit('text-change', '')
  input.value?.focus()
  text.value = ''
  show()
}

function choose(person: PersonOption) {
  if (props.disabled) return
  emit('select', person)
  hide()
  text.value = props.clearAfterSelect ? '' : person.name
}

function keydown(event: KeyboardEvent) {
  if (composing.value || event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape' && expanded.value) {
    event.preventDefault()
    event.stopPropagation()
    hide()
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) show()
    if (!visible.value.length) return
    const last = visible.value.length - 1
    active.value = event.key === 'ArrowDown' ? (active.value >= last ? 0 : active.value + 1)
      : (active.value <= 0 ? last : active.value - 1)
    void nextTick(() => panel.value?.querySelector('[data-active]')?.scrollIntoView({ block: 'nearest' }))
  } else if (event.key === 'Enter' && expanded.value) {
    event.preventDefault()
    const person = visible.value[active.value] ?? (visible.value.length === 1 ? visible.value[0] : undefined)
    if (person) choose(person)
  }
}

function position() {
  if (!expanded.value || !input.value) return
  const rect = input.value.getBoundingClientRect()
  const viewport = window.visualViewport
  const top = viewport?.offsetTop ?? 0
  const bottom = top + (viewport?.height ?? window.innerHeight)
  const below = bottom - rect.bottom - 8
  const above = rect.top - top - 8
  const flip = below < 220 && above > below
  placement.value = {
    left: rect.left + 'px',
    width: rect.width + 'px',
    top: (flip ? rect.top - 6 : rect.bottom + 6) + 'px',
    maxHeight: Math.min(300, Math.max(80, flip ? above : below)) + 'px',
    transform: flip ? 'translateY(-100%)' : undefined
  }
}

watch(expanded, (visible, _, cleanup) => {
  if (!visible) return
  const viewport = window.visualViewport
  position()
  window.addEventListener('resize', position)
  window.addEventListener('scroll', position, true)
  viewport?.addEventListener('resize', position)
  viewport?.addEventListener('scroll', position)
  cleanup(() => {
    window.removeEventListener('resize', position)
    window.removeEventListener('scroll', position, true)
    viewport?.removeEventListener('resize', position)
    viewport?.removeEventListener('scroll', position)
  })
}, { flush: 'post' })
watch(visible, () => { active.value = -1 })
watch([() => props.selected?.id, () => displayName()], () => {
  if (!open.value) text.value = displayName()
})
watch(() => props.disabled, disabled => { if (disabled) hide() })
onUnmounted(cancelSearch)
</script>

<template>
  <div class="person-picker">
    <label :for="inputId" :class="{ lb: required }">{{ label }}<i v-if="required" aria-hidden="true">*</i></label>
    <div class="person-control">
      <input :id="inputId" ref="input" class="inp" type="text" autocomplete="off" spellcheck="false" maxlength="64"
        :value="text" :placeholder="placeholder" :disabled="disabled" role="combobox" aria-autocomplete="list"
        :aria-required="required || undefined" :aria-invalid="invalid || undefined"
        :aria-expanded="expanded" :aria-controls="expanded ? listId : undefined"
        :aria-activedescendant="expanded && active >= 0 ? listId + '-' + active : undefined"
        @focus="!open && show()" @click="!open && show()" @blur="hide" @input="type" @keydown="keydown"
        @compositionstart="composing = true; cancelSearch()"
        @compositionend="composing = false; type($event)" />
      <button v-if="text && !disabled" class="person-clear" type="button" :aria-label="'清空' + label"
        @mousedown.prevent @click="clear">×</button>
    </div>
    <small v-if="selected && personDetails(selected)" class="person-selection-detail">{{ personDetails(selected) }}</small>
    <Teleport to="body">
      <div v-if="expanded" ref="panel" class="person-dropdown" :style="placement">
        <p v-if="busy" class="person-state" role="status">正在搜索…</p>
        <p v-else-if="failed" class="person-state" role="status">搜索失败，请重试</p>
        <p v-else-if="!visible.length" class="person-state" role="status">没有找到匹配的人员</p>
        <div :id="listId" role="listbox" :aria-label="label" :aria-busy="busy">
          <button v-for="(person, index) in visible" :id="listId + '-' + index" :key="person.id" type="button"
            class="person-choice" role="option" tabindex="-1" :aria-selected="person.id === selected?.id"
            :data-active="active === index ? '' : undefined"
            @mousedown.prevent @click="choose(person)">
            <strong>{{ person.name }}</strong>
            <small v-if="personDetails(person)">{{ personDetails(person) }}</small>
          </button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.person-picker { display:grid; min-width:0; gap:6px; }
.person-picker > label { color:var(--t2, #3c4655); font-size:13px; font-weight:600; margin:0; }
.person-control { position:relative; }
.person-control .inp { width:100%; padding-right:36px; }
.person-clear { position:absolute; right:3px; top:50%; transform:translateY(-50%); width:32px; height:32px; border:0; border-radius:8px; background:transparent; color:#657183; font-size:19px; cursor:pointer; }
.person-clear:is(:hover,:focus-visible) { background:#edf5ff; color:#0065c8; }
.person-selection-detail { color:#52677a; font-size:11px; overflow-wrap:anywhere; }
.person-dropdown { position:fixed; z-index:250; overflow:auto; overscroll-behavior:contain; padding:4px; border:1px solid #dbe2eb; border-radius:12px; background:#fff; box-shadow:0 12px 28px #16243f24; }
.person-state { margin:0; padding:10px; color:#676e7a; font-size:12px; }
.person-choice { width:100%; min-height:42px; display:grid; align-content:center; justify-items:start; gap:2px; padding:7px 10px; border:0; border-radius:8px; background:none; color:#1d1d1f; text-align:left; font:inherit; cursor:pointer; overflow-wrap:anywhere; }
.person-choice:is(:hover,[data-active],[aria-selected=true]) { background:#edf5ff; color:#0968c8; }
.person-choice strong { font-size:13px; }
.person-choice small { font-size:11px; color:#52677a; }
</style>
