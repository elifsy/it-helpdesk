<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, shallowRef, useId, watch } from 'vue'

const props = withDefaults(defineProps<{
  disabled?: boolean
  invalid?: boolean
  maxFiles: number
  maxFileMb: number
  kind?: 'attachment' | 'evidence' | 'excel'
}>(), { kind: 'attachment' })
const files = defineModel<File[]>({ required: true })
const input = ref<HTMLInputElement>()
const folderButton = ref<HTMLButtonElement>()
const toggleButton = ref<HTMLButtonElement>()
const filePanel = ref<HTMLElement>()
const dragDepth = ref(0)
const dragging = computed(() => dragDepth.value > 0 && !props.disabled)
const incomingCount = ref(0)
const dropSequence = ref(0)
const hovered = ref(false)
const panelFocused = ref(false)
const pinned = ref<boolean>()
const expanded = computed(() => files.value.length > 0 && !dragging.value && !incomingCount.value
  && (pinned.value ?? (hovered.value || panelFocused.value)))
const error = ref('')
const imageExtensions = ['.jpg', '.jpeg', '.png', '.gif', '.webp']
const attachments = [...imageExtensions, '.pdf']
const formats = {
  attachment: { extensions: attachments, label: '选择附件', warning: '请选择图片或 PDF 文件。' },
  evidence: { extensions: [...attachments, '.mp3', '.m4a', '.wav'], label: '选择佐证附件', warning: '请选择图片、PDF、MP3、M4A 或 WAV 文件。' },
  excel: { extensions: ['.xlsx'], label: '选择 Excel 文件', warning: '请选择 .xlsx 文件。' }
}
const format = computed(() => formats[props.kind])
const singleFile = computed(() => props.kind === 'excel')
const previews = shallowRef(new Map<File, string>())
const id = useId()
const fileStatusId = 'attachment-status-' + id
const filePanelId = 'attachment-files-' + id
const megabyte = 1024 * 1024
// Leave room for the report JSON and multipart headers within the 20 MB request limit.
const maxEvidenceBytes = 20 * megabyte - 16 * 1024

watch(files, list => {
  const next = new Map<File, string>()
  for (const file of list) {
    if (file.type.startsWith('image/') || imageExtensions.some(ext => file.name.toLowerCase().endsWith(ext))) {
      next.set(file, next.get(file) ?? previews.value.get(file) ?? URL.createObjectURL(file))
    }
  }
  for (const [file, url] of previews.value) if (!next.has(file)) URL.revokeObjectURL(url)
  previews.value = next
  if (incomingCount.value > list.length) incomingCount.value = 0
}, { immediate: true })
onUnmounted(() => { for (const url of previews.value.values()) URL.revokeObjectURL(url) })

function addFiles(selection: FileList | null, fromDrop: boolean) {
  if (props.disabled || !selection?.length) return
  if (singleFile.value && selection.length > 1) {
    error.value = '每次请选择一个 Excel 文件。'
    return
  }
  const next = singleFile.value ? [] : [...files.value]
  const previousCount = next.length
  const warnings = new Set<string>()
  let totalBytes = next.reduce((sum, file) => sum + file.size, 0)
  for (const file of Array.from(selection)) {
    if (!file.size) warnings.add('文件为空，请重新选择。')
    else if (!format.value.extensions.some(ext => file.name.toLowerCase().endsWith(ext))) {
      warnings.add(format.value.warning)
    } else if (file.size > props.maxFileMb * megabyte) {
      warnings.add(`单个附件不能超过 ${props.maxFileMb} MB。`)
    } else if (next.length >= props.maxFiles) {
      warnings.add(`最多可添加 ${props.maxFiles} 个附件。`)
    } else if (props.kind === 'evidence' && totalBytes + file.size > maxEvidenceBytes) {
      warnings.add('附件合计需小于 20 MB，请减小文件后再添加。')
    } else {
      next.push(file)
      totalBytes += file.size
    }
  }
  error.value = [...warnings].join(' ')
  const added = next.length - previousCount
  if (added || singleFile.value) {
    files.value = next
    incomingCount.value = fromDrop ? added : 0
    if (fromDrop && added) dropSequence.value++
  }
}

function chooseFile(event: Event) {
  const picker = event.target as HTMLInputElement
  addFiles(picker.files, false)
  picker.value = ''
}

async function removeFile(index: number) {
  const hadFocus = filePanel.value?.contains(document.activeElement)
  files.value = files.value.filter((_, i) => i !== index)
  error.value = ''
  if (!files.value.length) {
    panelFocused.value = false
    pinned.value = undefined
  }
  if (!hadFocus) return
  await nextTick()
  const buttons = filePanel.value?.querySelectorAll('button')
  const next = buttons?.[Math.min(index, files.value.length - 1)]
  const target = next ?? toggleButton.value ?? folderButton.value
  target?.focus()
}

function leaveFolder() {
  hovered.value = false
  if (pinned.value === false) pinned.value = undefined
}

function closePanel(event: KeyboardEvent) {
  if (!expanded.value) return
  event.stopPropagation()
  pinned.value = false
  toggleButton.value?.focus()
}

function paperPosition(index: number) {
  const count = files.value.length - incomingCount.value
  const offset = index - (count - 1) / 2
  return {
    '--paper-left': `${24 + offset * Math.min(15, 44 / Math.max(count - 1, 1))}px`,
    '--paper-angle': `${offset * Math.min(10, 30 / Math.max(count - 1, 1))}deg`,
    '--paper-top': `${17 - index % 3 * 3}px`
  }
}

function enter(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  if (!props.disabled) dragDepth.value++
}

function over(event: DragEvent) {
  const transfer = event.dataTransfer
  if (!transfer?.types.includes('Files')) return
  event.preventDefault()
  transfer.dropEffect = props.disabled ? 'none' : 'copy'
}

function drop(event: DragEvent) {
  dragDepth.value = 0
  addFiles(event.dataTransfer?.files ?? null, true)
}
</script>

<template>
  <div class="evidence-picker">
    <div class="evidence-drop" :class="{ dragging }" @dragenter="enter" @dragover="over"
      @dragleave="dragDepth = Math.max(0, dragDepth - 1)" @drop.prevent="drop">
      <input ref="input" type="file" hidden :multiple="!singleFile" :accept="format.extensions.join(',')" :disabled="disabled" @change="chooseFile" />
      <div class="evidence-folder" :class="{ expanded }" @pointerenter="hovered = $event.pointerType === 'mouse'"
        @pointerleave="leaveFolder" @keydown.esc="closePanel">
        <button ref="folderButton" class="evidence-card" type="button" :disabled="disabled" :aria-invalid="invalid || undefined"
          :aria-label="format.label" :aria-describedby="fileStatusId" @click="input?.click()">
          <span :key="dropSequence" class="folder-art" :class="{ inserting: incomingCount }" aria-hidden="true">
            <span class="folder-shadow" />
            <span class="folder-back" />
            <span v-for="paper in files.length - incomingCount" :key="paper" class="folder-paper stored" :style="paperPosition(paper - 1)" />
            <span v-for="paper in incomingCount" :key="'incoming-' + paper" class="folder-paper incoming"
              :style="{ animationDelay: (paper - 1) * 24 + 'ms' }" @animationend="paper === incomingCount && (incomingCount = 0)" />
            <span class="folder-face" />
          </span>
          <span :id="fileStatusId" class="evidence-status" aria-live="polite">
            <strong v-if="dragging">{{ singleFile ? '松开选择文件' : '松开添加附件' }}</strong>
            <strong v-else-if="incomingCount">{{ singleFile ? '正在选择文件…' : '正在添加附件…' }}</strong>
            <template v-else-if="files.length">
              <strong class="evidence-added"><i aria-hidden="true" />{{ singleFile ? '已选择 Excel 文件' : '已添加 ' + files.length + ' 个附件' }}</strong>
              <small>{{ singleFile ? '点击文件夹可更换文件' : '点击文件夹可继续添加' }}</small>
            </template>
            <template v-else>
              <strong class="evidence-desktop">拖拽文件到这里</strong>
              <small class="evidence-desktop">或点击文件夹选择</small>
              <strong class="evidence-mobile">点击文件夹{{ format.label }}</strong>
            </template>
          </span>
        </button>
        <button v-if="files.length" ref="toggleButton" class="evidence-toggle" type="button"
          :aria-controls="filePanelId" :aria-expanded="expanded" @click="pinned = !expanded">
          {{ expanded ? '收起' : '查看' }}{{ singleFile ? '文件' : '附件' }}<span aria-hidden="true">{{ expanded ? '⌃' : '⌄' }}</span>
        </button>
        <Transition name="file-sheets">
          <div v-if="expanded" :id="filePanelId" ref="filePanel" class="evidence-file-panel" @focusin="panelFocused = true"
            @focusout="panelFocused = filePanel?.contains($event.relatedTarget as Node) ?? false">
            <ul class="evidence-files" aria-label="已选择的文件">
              <li v-for="(file, index) in files" :key="index" class="evidence-file" :style="{ '--sheet-delay': Math.min(index, 5) * 30 + 'ms' }">
                <img v-if="previews.has(file)" class="file-preview" :src="previews.get(file)" alt="" />
                <span v-else class="file-icon" aria-hidden="true" />
                <span class="evidence-file-name" :title="file.name">{{ file.name }}</span>
                <button class="file-remove" type="button" :disabled="disabled" :aria-label="'移除 ' + file.name" title="移除附件" @click="removeFile(index)">×</button>
              </li>
            </ul>
          </div>
        </Transition>
      </div>
    </div>
    <p v-if="error" class="evidence-picker-error small" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.evidence-picker { display:grid; gap:8px; min-width:0; }
.evidence-drop {
  display:grid;
  place-items:center;
  min-width:0;
  padding:16px;
  border:1px solid var(--line);
  border-radius:16px;
  background:radial-gradient(ellipse at 50% 0%, #fff 4%, #f7f8f9 60%, #edefef 100%);
  transition:background-color .18s ease, border-color .18s ease;
}
.evidence-folder {
  position:relative;
  display:grid;
  justify-items:center;
  gap:8px;
  width:min(100%, 340px);
  min-width:0;
}
.evidence-card {
  display:grid;
  justify-items:center;
  align-content:center;
  gap:7px;
  width:min(100%, 244px);
  min-width:0;
  min-height:167px;
  padding:16px 14px 12px;
  border:2px solid #fff;
  border-radius:13px;
  background:linear-gradient(150deg, #ffffff94, #f6f7f9b3);
  box-shadow:0 10px 24px #3c556116, inset 0 0 16px #ffffff9e;
  color:var(--t1);
  font:inherit;
  text-align:center;
  cursor:pointer;
  transition:border-color .18s ease, box-shadow .18s ease, background-color .18s ease;
}
.evidence-card:hover:not(:disabled, [aria-invalid="true"]), .dragging .evidence-card {
  border-color:#9aceff;
  box-shadow:0 10px 30px #1686dc26, inset 0 0 12px #ffffffa8;
}
.evidence-card:focus-visible { outline:2px solid var(--brand); outline-offset:3px; }
.evidence-card:disabled { opacity:.65; cursor:default; }
.dragging.evidence-drop { border-color:#72b6ec; background:#eaf5ff; }
.folder-art, .evidence-status { pointer-events:none; }
.folder-art {
  position:relative;
  isolation:isolate;
  width:92px;
  height:94px;
  perspective:180px;
  transition:transform .22s ease;
}
.evidence-card:hover:not(:disabled) .folder-art { transform:translateY(-2px); }
.dragging .folder-art { transform:translateY(-5px) scale(1.08); }
.folder-shadow, .folder-back, .folder-paper, .folder-face { position:absolute; }
.folder-shadow {
  inset:auto 2px 2px;
  height:12px;
  border-radius:50%;
  background:#47768552;
  filter:blur(6px);
}
.folder-back {
  inset:22px 8px 7px;
  border-radius:6px 7px 8px 8px;
  background:linear-gradient(110deg, #b7eaff62, transparent 68%),
    linear-gradient(180deg, #49afe0, #137faf 34%, #288dbb65 72%, #419fc936);
  box-shadow:inset 0 1px #bdeeffb8, 0 3px 6px #3375992b;
}
.folder-back::before {
  position:absolute;
  top:-7px;
  right:0;
  width:47%;
  height:15px;
  border-radius:6px 8px 0 0;
  background:linear-gradient(130deg, #61c3eb, #2f9cd7);
  content:'';
}
.folder-paper {
  width:43px;
  height:43px;
  border:1px solid #edf3f5;
  border-radius:4px;
  background:linear-gradient(147deg, #fff 40%, #e6eff4);
  box-shadow:0 2px 7px #18577636;
}
.folder-paper::before {
  position:absolute;
  top:8px;
  left:7px;
  width:7px;
  height:7px;
  border-radius:2px;
  background:linear-gradient(140deg, #6fcbac, #2ca27d);
  content:'';
}
.folder-paper::after {
  position:absolute;
  top:8px;
  left:17px;
  width:16px;
  height:2px;
  border-radius:2px;
  background:#738993;
  box-shadow:0 5px #bfd1d8, -10px 11px #d2e0e6;
  content:'';
}
.folder-paper.stored {
  top:var(--paper-top);
  left:var(--paper-left);
  transform:rotate(var(--paper-angle));
  transition:transform .22s ease;
}
.dragging .folder-paper.stored { transform:translateY(-5px) rotate(var(--paper-angle)); }
.expanded .folder-paper.stored { transform:translateY(-12px) rotate(var(--paper-angle)); }
.folder-paper.incoming {
  top:16px;
  left:21px;
  animation:paper-into-folder .68s cubic-bezier(.19,.65,.27,1) both;
}
.folder-face {
  inset:41px 7px 7px;
  border:1px solid #e1f8ffd6;
  border-radius:4px 5px 8px 8px;
  background:linear-gradient(120deg, #effdffad, #cfedff29 62%, #65bcdb36),
    linear-gradient(180deg, #dbf8ff40, #71c5e681 64%, #3aa0cc85);
  -webkit-backdrop-filter:blur(4px) saturate(1.2);
  backdrop-filter:blur(4px) saturate(1.2);
  box-shadow:inset 0 1px #fffdf7db, inset 0 -1px 2px #65b0d051, 0 4px 8px #2670952b;
  transform-origin:center bottom;
  transition:transform .2s ease;
}
.dragging .folder-face, .expanded .folder-face { transform:rotateX(-22deg) scaleX(1.025); }
.inserting .folder-face { animation:folder-open .68s ease both; }
.evidence-status {
  display:grid;
  gap:3px;
  justify-items:center;
  width:100%;
  min-height:35px;
  color:var(--t2);
  font-size:13px;
}
.evidence-status strong { font-weight:600; color:var(--t1); }
.evidence-status small { color:var(--t3); font-size:11.5px; }
.evidence-status .evidence-mobile { display:none; }
.evidence-added { display:flex; align-items:center; gap:6px; }
.evidence-added i {
  display:grid;
  place-items:center;
  width:14px;
  height:14px;
  border-radius:50%;
  background:#17895c;
}
.evidence-added i::before {
  width:6px;
  height:3px;
  border:solid #fff;
  border-width:0 0 1.5px 1.5px;
  transform:translateY(-1px) rotate(-45deg);
  content:'';
}
.evidence-toggle {
  display:flex;
  align-items:center;
  gap:5px;
  padding:5px 10px;
  border:0;
  border-radius:8px;
  background:transparent;
  color:var(--brand);
  font:inherit;
  font-size:12px;
  cursor:pointer;
}
.evidence-toggle:hover { background:#e6f1fb; }
.evidence-file-panel {
  position:absolute;
  z-index:8;
  bottom:calc(100% - 16px);
  width:100%;
  padding:5px;
  border:1px solid #cee2ee;
  border-radius:14px;
  background:#f2f8fc;
  box-shadow:0 13px 28px #345d7633;
  transform-origin:center bottom;
}
.evidence-files {
  display:grid;
  gap:6px;
  max-height:min(240px, 40vh);
  margin:0;
  padding:2px;
  overflow:auto;
  overscroll-behavior:contain;
  list-style:none;
}
.evidence-file {
  display:grid;
  grid-template-columns:auto minmax(0,1fr) auto;
  align-items:center;
  gap:8px;
  padding:4px 6px 4px 10px;
  border:1px solid #dfebf2;
  border-radius:9px;
  background:linear-gradient(155deg, #fff, #fafdff);
  color:var(--t1);
  animation:paper-out-of-folder .28s ease var(--sheet-delay) both;
}
.file-icon {
  width:17px;
  height:21px;
  border:1px solid #a8cddd;
  border-radius:4px;
  background:linear-gradient(145deg, #fff, #def1f8);
  box-shadow:0 2px 3px #34647a14;
}
.file-preview { display:block; width:32px; height:32px; border-radius:5px; object-fit:cover; }
.file-icon::after {
  display:block;
  width:7px;
  height:1px;
  margin:6px auto 0;
  background:#4b8197;
  box-shadow:0 4px #8fb7c7, 0 8px #b4cfdb;
  content:'';
}
.evidence-file-name { overflow-wrap:anywhere; font-size:12px; }
.file-remove {
  display:grid;
  place-items:center;
  width:32px;
  height:32px;
  border:0;
  border-radius:8px;
  background:transparent;
  color:var(--t2);
  font:inherit;
  font-size:20px;
  cursor:pointer;
}
.file-remove:hover:not(:disabled) { background:var(--red-bg); color:#b32921; }
.file-remove:disabled { opacity:.5; cursor:default; }
.evidence-toggle:focus-visible, .file-remove:focus-visible { outline:2px solid var(--brand); outline-offset:1px; }
.evidence-picker-error { margin:0; color:#b32921; }
.file-sheets-enter-active, .file-sheets-leave-active { transition:opacity .22s ease, transform .22s ease; }
.file-sheets-enter-from, .file-sheets-leave-to { opacity:0; transform:translateY(28px) scale(.94); }
@keyframes paper-out-of-folder {
  from { opacity:0; transform:translateY(12px); }
  to { opacity:1; transform:none; }
}
@keyframes paper-into-folder {
  0% { opacity:0; transform:translate(12px, -42px) rotate(-20deg) scale(.87); }
  20% { opacity:1; }
  68% { opacity:1; transform:translate(0, 15px) rotate(-7deg); }
  100% { opacity:0; transform:translate(0, 32px) rotate(-4deg) scale(.96); }
}
@keyframes folder-open {
  0%, 100% { transform:none; }
  43% { transform:rotateX(-28deg) scaleX(1.03); }
}
@media (pointer:coarse) {
  .evidence-status .evidence-mobile { display:block; }
  .evidence-status .evidence-desktop { display:none; }
}
@media (hover:none), (max-width:600px) {
  .evidence-file-panel { position:static; }
  .file-sheets-enter-from, .file-sheets-leave-to { transform:translateY(-10px); }
  .evidence-toggle { min-height:40px; }
  .file-remove { width:40px; height:40px; }
}
@media (prefers-reduced-motion:reduce) {
  .evidence-drop, .evidence-card, .folder-art, .folder-paper.stored, .folder-face,
  .file-sheets-enter-active, .file-sheets-leave-active { transition:none; }
  .folder-paper.incoming, .inserting .folder-face { animation-duration:1ms; }
  .folder-paper.incoming { animation-delay:0ms !important; }
  .evidence-file { animation:none; }
}
</style>
