<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type CSSProperties } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tutorialApi } from '../api'
import { tutorialsFor, type TutorialCourse } from '../config/tutorials'
import { workflowsFor, type WorkflowCourse } from '../config/workflows'
import { roleFromRoles, titleFor } from '../config/menus'
import { useAuthStore } from '../stores/auth'
import { useMissingReportStore } from '../stores/missingReports'
import { toast } from '../utils/toast'
import { tutorialPractice, tutorialWorkflow } from '../utils/tutorialPractice'
import { useTutorialControls } from '../composables/useTutorialControls'
import WorkflowTutorial from './WorkflowTutorial.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const reports = useMissingReportStore()
const courses = computed(() => tutorialsFor(auth.roles, auth.permissions))
const workflows = computed(() => workflowsFor(auth.roles, auth.permissions))
const workflow = ref<{ course: WorkflowCourse; startAtForm: boolean }>()
const current = computed(() => {
  const tab = route.path === '/admin/config' && typeof route.query.tab === 'string' ? route.query.tab : ''
  const path = route.path + (tab ? '?tab=' + tab : '')
  return courses.value.find(item => item.path === path)
})
const restricted = computed(() => route.path === '/new' || route.path === '/assets')
const reportCheckPending = computed(() => restricted.value && reports.loadedFor !== auth.user?.id)
const blocked = computed(() => restricted.value && reports.summary?.blocked)

const picker = ref<HTMLDialogElement>()
const pickerOpen = ref(false)
const guide = ref<HTMLElement>()
const active = ref<TutorialCourse>()
const stepIndex = ref(0)
const step = computed(() => active.value?.steps[stepIndex.value])
const seen = ref(new Set<string>())
const seenLoaded = ref(false)
const starting = ref(false)
const locating = ref(false)
const highlight = ref<CSSProperties>()
const viewportStyle = ref<CSSProperties>()
const placeAtTop = ref(false)
const { enabled: operationMode, items: controls, chosen: selectedControl, nativeDialog, panelTitle, feedback,
  index: controlIndex, activate: showControls, stepControl, schedule: refreshControls } =
  useTutorialControls(computed(() => !!active.value), locateControl)
const groups = computed(() => {
  const result = new Map<string, TutorialCourse[]>()
  for (const item of courses.value) {
    if (item.group === '我的服务') continue
    const items = result.get(item.group) ?? []
    items.push(item)
    result.set(item.group, items)
  }
  return [...result]
})

let accountRun = 0
let stepRun = 0
let frame = 0
let target: HTMLElement | undefined
let observer: ResizeObserver | undefined
let lastFocus: HTMLElement | undefined
const attempted = new Set<string>()
const editable = 'input, textarea, select, [contenteditable="true"]'

function visibleTarget(selector: string) {
  for (const node of document.querySelectorAll<HTMLElement>(selector)) {
    const rect = node.getBoundingClientRect()
    if (rect.width && rect.height && getComputedStyle(node).visibility === 'visible') return node
  }
}

function updateGeometry() {
  if (!active.value) return
  const view = window.visualViewport
  const width = document.documentElement.clientWidth
  const height = document.documentElement.clientHeight
  const left = view?.offsetLeft ?? 0
  const top = view?.offsetTop ?? 0
  const right = left + (view?.width ?? width)
  const bottom = top + (view?.height ?? height)
  viewportStyle.value = {
    '--tutorial-width': (right - left) + 'px',
    '--tutorial-height': (bottom - top) + 'px',
    '--tutorial-right': Math.max(0, width - right) + 'px',
    '--tutorial-bottom': Math.max(0, height - bottom) + 'px',
    '--tutorial-top': top + 'px',
  }
  const rect = target?.getBoundingClientRect()
  if (!rect || !rect.width || !rect.height) {
    highlight.value = undefined
    placeAtTop.value = false
    return
  }
  const x = Math.max(left + 2, rect.left - 4), y = Math.max(top + 2, rect.top - 4)
  const x2 = Math.min(right - 2, rect.right + 4), y2 = Math.min(bottom - 2, rect.bottom + 4)
  highlight.value = x2 > x && y2 > y ? { left: x + 'px', top: y + 'px', width: (x2 - x) + 'px', height: (y2 - y) + 'px' } : undefined
  const card = guide.value?.getBoundingClientRect()
  const location = (y + y2) / 2
  placeAtTop.value = !!highlight.value && (right - left < 600 || rect.right > right - (card?.width ?? 360))
    && location > top + (bottom - top) / 2
}

function scheduleGeometry() {
  if (!active.value) return
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(updateGeometry)
}

function setTarget(element?: HTMLElement) {
  observer?.disconnect()
  target = element
  if (element && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(scheduleGeometry)
    observer.observe(element)
  }
  updateGeometry()
}

function locateControl(element?: HTMLElement, scroll = true) {
  const rect = element?.getBoundingClientRect()
  if (scroll && rect && (rect.top < 66 || rect.top > window.innerHeight - 110)) {
    element?.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' })
  }
  setTarget(element)
}

function tryControl() {
  const item = selectedControl.value
  if (!item?.node.isConnected || item.node.matches(':disabled,[aria-disabled="true"]')) return
  if (item.kind === 'field') {
    locateControl(item.node)
    item.node.focus({ preventScroll: true })
  } else {
    item.node.click()
  }
}

async function move(index: number, scroll = true) {
  const course = active.value
  if (!course || index < 0 || locating.value) return
  if (index === course.steps.length) { showControls(); return }
  const run = ++stepRun
  stepIndex.value = index
  locating.value = true
  setTarget()
  await nextTick()
  const selector = course.steps[index].target
  let element = visibleTarget(selector)
  for (let attempt = 0; !element && attempt < 12; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 50))
    if (run !== stepRun) return
    element = visibleTarget(selector)
  }
  if (run !== stepRun) return
  element ||= visibleTarget('.main h2, .main .page-head, .main .report-heading, .main .home-heading') ?? visibleTarget('.tutorial-trigger')
  if (!operationMode.value) locateControl(element, scroll)
  locating.value = false
}

function closeGuide(restoreFocus = true) {
  stepRun++
  if (restoreFocus && guide.value?.contains(document.activeElement)) {
    const focus = lastFocus?.isConnected ? lastFocus : visibleTarget('.tutorial-trigger')
    focus?.focus({ preventScroll: true })
  }
  lastFocus = undefined
  active.value = undefined
  tutorialPractice.value = false
  locating.value = false
  setTarget()
  highlight.value = undefined
  cancelAnimationFrame(frame)
}

function showPicker() {
  if (!picker.value || picker.value.open) return
  picker.value.showModal()
  pickerOpen.value = true
}

function openPicker() {
  if ((!courses.value.length && !workflows.value.length) || starting.value || workflow.value) return
  closeGuide(false)
  showPicker()
}

function closeWorkflow(restoreFocus = true) {
  if (!workflow.value) return
  workflow.value = undefined
  tutorialWorkflow.value = undefined
  tutorialPractice.value = false
  if (restoreFocus) void nextTick(() => visibleTarget('.tutorial-trigger')?.focus({ preventScroll: true }))
}

async function launchWorkflow(course: WorkflowCourse) {
  if (workflow.value || !workflows.value.some(item => item.key === course.key)) return
  picker.value?.close()
  pickerOpen.value = false
  closeGuide(false)
  const startAtForm = course.key === 'repair' && route.path === '/new'
  const path = startAtForm ? '/new' : course.path
  if (current.value) attempted.add(current.value.key)
  attempted.add(course.key === 'repair' ? 'repair' : 'my-assets')
  tutorialWorkflow.value = course.key
  tutorialPractice.value = true
  workflow.value = { course, startAtForm }
  try {
    if (route.fullPath !== path) await router.push(path)
    if (route.path === path || (course.key === 'repair' && route.path === '/new')) return
  } catch { /* Restore the normal page if navigation failed. */ }
  if (workflow.value?.course.key !== course.key) return
  closeWorkflow()
  toast('当前无法打开流程练习，请稍后重试')
}

function onHistoryChange() {
  closeWorkflow(false)
}

function beginGuide(course: TutorialCourse, opener: HTMLElement | undefined, manual: boolean) {
  lastFocus = opener
  active.value = course
  tutorialPractice.value = true
  viewportStyle.value = undefined
  placeAtTop.value = false
  const editing = document.activeElement?.matches(editable)
  void move(0, manual || !editing)
}

async function practiceCurrentPage() {
  if (reportCheckPending.value || blocked.value) {
    toast(blocked.value ? '请先补报已完成事项，再练习本页按钮' : '正在检查待补报事项，请稍后打开教程')
    return
  }
  const opener = visibleTarget('.tutorial-trigger')
  picker.value?.close()
  pickerOpen.value = false
  if (current.value) attempted.add(current.value.key)
  beginGuide({
    key: current.value?.key || 'current-page',
    label: current.value?.label || titleFor(roleFromRoles(auth.roles), route.fullPath),
    path: route.fullPath, group: '我的服务', steps: []
  }, opener, true)
  await nextTick()
  guide.value?.focus({ preventScroll: true })
}

async function start(course: TutorialCourse, manual: boolean) {
  if (starting.value || workflow.value || !courses.value.some(item => item.key === course.key)) return
  const run = accountRun
  starting.value = true
  attempted.add(course.key)
  const opener = visibleTarget('.tutorial-trigger')
  picker.value?.close()
  pickerOpen.value = false
  try {
    if (current.value?.key !== course.key && manual) await router.push(course.path)
    if (run !== accountRun || current.value?.key !== course.key) return
    if (manual && reportCheckPending.value) {
      await new Promise<void>(resolve => {
        const stop = watch([reportCheckPending, current], () => {
          if (reportCheckPending.value && current.value?.key === course.key) return
          clearTimeout(timeout)
          stop()
          resolve()
        })
        const timeout = window.setTimeout(() => { stop(); resolve() }, 1500)
      })
    }
    if (run !== accountRun || current.value?.key !== course.key) return
    if (reportCheckPending.value || blocked.value) {
      if (manual) toast(blocked.value ? '请先补报已完成事项，再查看本功能教程' : '正在检查待补报事项，请稍后打开教程')
      return
    }
    if (!seen.value.has(course.key)) {
      const result = await tutorialApi.start(course.key)
      if (run !== accountRun) return
      seen.value.add(course.key)
      if (!manual && !result.shouldStart) return
    }
    if (current.value?.key !== course.key) return
    if (!manual && (pickerOpen.value || document.querySelector('dialog[open], .mask'))) return
    if (course.key === 'repair') {
      const repair = workflows.value.find(item => item.key === 'repair')
      if (repair) await launchWorkflow(repair)
      return
    }
    if (course.key === 'my-assets' && workflows.value.some(item => item.key !== 'repair')) {
      showPicker()
      return
    }
    beginGuide(current.value, opener, manual)
    await nextTick()
    if (manual && run === accountRun) guide.value?.focus({ preventScroll: true })
  } catch {
    if (manual && run === accountRun) toast('新手教程暂时无法开启，请稍后重试')
  } finally {
    if (run === accountRun) starting.value = false
  }
}

function onKey(event: KeyboardEvent) {
  if (!active.value || event.defaultPrevented || pickerOpen.value) return
  const fromGuide = event.target instanceof Node && guide.value?.contains(event.target)
  if (event.key === 'Escape') {
    if (!fromGuide && document.querySelector('dialog[open], .mask')) return
    event.preventDefault()
    closeGuide()
  } else if (fromGuide && (event.key === 'ArrowRight' || event.key === 'ArrowLeft')) {
    event.preventDefault()
    const delta = event.key === 'ArrowRight' ? 1 : -1
    if (operationMode.value) stepControl(delta)
    else void move(stepIndex.value + delta)
  }
}

watch([() => auth.user?.id, () => auth.user?.role], async ([id], _old, cleanup) => {
  const run = ++accountRun
  cleanup(() => { accountRun++ })
  closeWorkflow(false)
  closeGuide(false)
  picker.value?.close()
  pickerOpen.value = false
  starting.value = false
  seenLoaded.value = false
  seen.value = new Set()
  attempted.clear()
  if (id == null || !courses.value.length) return
  try {
    const modules = await tutorialApi.seen()
    if (run !== accountRun) return
    seen.value = new Set([...seen.value, ...modules])
    seenLoaded.value = true
  } catch { /* 手动入口仍然可以重试。 */ }
}, { immediate: true })

watch([current, blocked], ([course, restricted]) => {
  if (!active.value) return
  if (restricted) { closeGuide(); return }
  if (course && course.key !== active.value.key) attempted.add(course.key)
})
watch(() => route.fullPath, () => {
  if (!active.value) return
  if (operationMode.value || active.value.key !== current.value?.key) showControls()
  else void move(stepIndex.value, false)
  refreshControls()
}, { flush: 'post' })

watch([current, seenLoaded, starting, pickerOpen, reportCheckPending, blocked, workflow], ([course], _old, cleanup) => {
  if (!course || !seenLoaded.value || starting.value || pickerOpen.value || reportCheckPending.value || blocked.value
      || active.value || workflow.value || seen.value.has(course.key) || attempted.has(course.key)) return
  const wait = window.setTimeout(() => {
    if (!document.querySelector('dialog[open], .mask')) void start(course, false)
  }, 260)
  cleanup(() => window.clearTimeout(wait))
}, { immediate: true, flush: 'post' })

onMounted(() => {
  window.addEventListener('popstate', onHistoryChange)
  window.addEventListener('keydown', onKey)
  window.addEventListener('scroll', scheduleGeometry, true)
  window.addEventListener('resize', scheduleGeometry)
  window.visualViewport?.addEventListener('resize', scheduleGeometry)
  window.visualViewport?.addEventListener('scroll', scheduleGeometry)
})
onBeforeUnmount(() => {
  closeWorkflow(false)
  closeGuide(false)
  window.removeEventListener('popstate', onHistoryChange)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('scroll', scheduleGeometry, true)
  window.removeEventListener('resize', scheduleGeometry)
  window.visualViewport?.removeEventListener('resize', scheduleGeometry)
  window.visualViewport?.removeEventListener('scroll', scheduleGeometry)
})

defineExpose({ openPicker })
</script>

<template>
  <Teleport to="body">
    <dialog ref="picker" class="tutorial-picker" aria-labelledby="tutorial-picker-title" @close="pickerOpen = false">
      <div class="tutorial-picker-head">
        <h2 id="tutorial-picker-title">新手教程</h2>
        <button type="button" class="tutorial-close" aria-label="关闭教程列表" @click="picker?.close()">×</button>
      </div>
      <section v-if="workflows.length" class="tutorial-workflows">
        <h3>按业务流程练习</h3>
        <div class="workflow-options">
          <button v-for="item in workflows" :key="item.key" type="button" class="tutorial-course workflow-option"
            :data-workflow-course="item.key" @click="launchWorkflow(item)">
            <span><b>{{ item.label }}</b><small>{{ item.summary }}</small></span>
            <span class="course-action" aria-hidden="true">›</span>
          </button>
        </div>
      </section>
      <div v-if="groups.length" class="tutorial-groups">
        <section v-for="[group, items] in groups" :key="group">
          <h3>{{ group }}</h3>
          <button v-for="item in items" :key="item.key" type="button" class="tutorial-course"
            :class="{ 'is-current': item.key === current?.key }" :data-tutorial-course="item.key" @click="start(item, true)">
            <span><b>{{ item.label }}</b><small>{{ item.steps.length }} 步{{ item.key === current?.key ? ' · 当前页面' : '' }}</small></span>
            <span class="course-action">{{ seen.has(item.key) ? '再看一次' : '开始学习' }} ›</span>
          </button>
        </section>
      </div>
      <button v-if="!restricted" type="button" class="tutorial-current-page" @click="practiceCurrentPage">
        <span><b>练习本页按钮</b><small>查看当前页面的其他操作</small></span>
        <span class="course-action">开始练习 ›</span>
      </button>
      <p v-else-if="!groups.length && !workflows.length" class="empty">暂无可用教程</p>
    </dialog>
  </Teleport>
  <Teleport :to="nativeDialog || 'body'">
    <div v-if="active && highlight" class="tutorial-highlight" :style="highlight" aria-hidden="true"></div>
    <section v-if="active" ref="guide" class="tutorial-guide" :class="{ 'at-top': placeAtTop }" :style="viewportStyle"
      tabindex="-1" role="dialog" aria-labelledby="tutorial-step-title" aria-describedby="tutorial-step-description"
      :data-tutorial-module="active.key">
      <header>
        <span>{{ panelTitle || active.label }} · {{ operationMode ? '按钮教学' : '新手教程' }}</span>
        <span class="practice-tag">练习模式</span>
        <button type="button" class="tutorial-close" aria-label="关闭教程" @click="closeGuide()">×</button>
      </header>
      <div class="tutorial-progress" aria-live="polite">
        <span v-if="!operationMode">{{ stepIndex + 1 }} / {{ active.steps.length }}</span>
        <span v-else-if="controlIndex >= 0">{{ controlIndex + 1 }} / {{ controls.length }}</span>
        <h2 id="tutorial-step-title">{{ operationMode ? selectedControl?.label || '本页按钮' : step?.title }}</h2>
      </div>
      <div class="tutorial-copy" aria-live="polite">
        <p id="tutorial-step-description">{{ operationMode ? selectedControl?.description || '当前没有可练习的控件。可打开其他功能继续。' : step?.description }}</p>
        <p v-if="operationMode && selectedControl" class="practice-instruction">
          {{ selectedControl.kind === 'write' ? '可试点高亮按钮，业务提交已锁定。'
            : selectedControl.kind === 'field' ? '可点击高亮字段，练习填写或选择。' : '请点击页面上高亮的按钮，打开后的操作会继续引导。' }}
        </p>
        <p v-if="feedback" class="practice-feedback" role="status">{{ feedback }}</p>
      </div>
      <button v-if="!operationMode" class="tutorial-show-buttons" type="button" @click="showControls">查看本页按钮 ›</button>
      <button v-else-if="selectedControl" class="tutorial-show-buttons" type="button"
        :disabled="selectedControl.node.matches(':disabled,[aria-disabled=&quot;true&quot;]')" @click="tryControl">
        {{ selectedControl.kind === 'field' ? '定位到字段' : selectedControl.kind === 'write' ? '演示此操作' : '点开这一项' }} ›
      </button>
      <footer v-if="!operationMode">
        <button type="button" class="tutorial-skip" @click="closeGuide()">跳过教学</button>
        <div>
          <button v-if="stepIndex" type="button" class="btn sm" :disabled="locating" @click="move(stepIndex - 1)">上一步</button>
          <button type="button" class="btn sm pri" :disabled="locating" @click="move(stepIndex + 1)">
            {{ stepIndex === active.steps.length - 1 ? '练习功能按钮' : '下一步' }}
          </button>
        </div>
      </footer>
      <footer v-else>
        <button type="button" class="tutorial-skip" @click="closeGuide()">退出练习</button>
        <div>
          <button v-if="controlIndex > 0" type="button" class="btn sm" @click="stepControl(-1)">上一项</button>
          <button v-if="controls.length && controlIndex < controls.length - 1" type="button" class="btn sm pri"
            @click="stepControl(1)">下一项</button>
          <button v-else type="button" class="btn sm pri" @click="closeGuide()">完成练习</button>
        </div>
      </footer>
    </section>
  </Teleport>
  <WorkflowTutorial v-if="workflow" :course="workflow.course" :start-at-form="workflow.startAtForm" @close="closeWorkflow()" />
</template>

<style scoped>
.tutorial-picker { box-sizing:border-box; width:min(700px,calc(100% - 24px)); max-height:calc(100dvh - 24px); margin:auto; padding:20px; overflow:auto; border:1px solid var(--line); border-radius:18px; background:#fff; color:var(--t1); box-shadow:0 20px 64px #203b5833; }
.tutorial-picker::backdrop { background:#15233459; }
.tutorial-picker-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:14px; }
.tutorial-picker h2,.tutorial-guide h2 { margin:0; font-size:17px; }
.tutorial-workflows { margin-top:16px; }
.tutorial-workflows h3 { margin:0 0 8px; font-size:14px; }
.workflow-options { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.tutorial-course.workflow-option { min-height:61px; margin:0; background:#f6faff; border-color:#d8e7f8; }
.tutorial-course.workflow-option:hover { background:#eef5ff; border-color:#bed7f9; }
.workflow-option .course-action { font-size:19px; }
.workflow-option small { line-height:1.4; }
.tutorial-groups { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); align-items:start; gap:8px 16px; margin-top:4px; }
.tutorial-groups h3 { margin:13px 0 7px; color:var(--t2); font-size:12px; font-weight:600; }
.tutorial-course,.tutorial-current-page { display:flex; align-items:center; justify-content:space-between; gap:10px; width:100%; padding:10px 12px; margin:0 0 6px; border:1px solid var(--line); border-radius:10px; background:#fff; color:var(--t1); font:inherit; font-size:13px; text-align:left; cursor:pointer; }
.tutorial-course:hover,.tutorial-course.is-current,.tutorial-current-page { background:#eef5ff; border-color:#bed7f9; }
.tutorial-current-page { margin:12px 0 0; }
.tutorial-course b,.tutorial-course small,.tutorial-current-page b,.tutorial-current-page small { display:block; }
.tutorial-course small,.tutorial-current-page small { margin-top:3px; color:var(--t2); font-size:11px; }
.course-action { flex:none; color:var(--brand); font-size:12px; }
.tutorial-highlight { position:fixed; z-index:298; pointer-events:none; box-sizing:border-box; border:2px solid var(--brand); border-radius:11px; box-shadow:0 0 0 4px #0071e324; }
.tutorial-guide { position:fixed; z-index:299; right:calc(var(--tutorial-right,0px) + 12px); bottom:calc(var(--tutorial-bottom,0px) + 12px + env(safe-area-inset-bottom,0px)); display:flex; flex-direction:column; width:min(365px,calc(var(--tutorial-width,100vw) - 24px)); max-height:calc(var(--tutorial-height,100dvh) - 24px); padding:15px 17px; border:1px solid #cadcf2; border-radius:16px; background:#fff; color:var(--t1); box-shadow:0 16px 42px #223c592c; outline:none; }
.tutorial-guide.at-top { top:calc(var(--tutorial-top,0px) + 68px); bottom:auto; max-height:calc(var(--tutorial-height,100dvh) - 80px); }
.tutorial-guide header,.tutorial-guide footer,.tutorial-guide footer > div { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.tutorial-guide header,.tutorial-guide footer,.tutorial-progress { flex:none; }
.tutorial-guide header { color:var(--t2); font-size:12px; }
.tutorial-guide header > span:first-child { flex:1; min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.practice-tag { flex:none; padding:2px 7px; border-radius:8px; background:#eaf3ff; color:#07599f; font-size:11px; }
.tutorial-close { display:grid; place-items:center; width:30px; height:30px; border:0; border-radius:7px; background:transparent; color:var(--t2); font:inherit; font-size:22px; line-height:1; cursor:pointer; }
.tutorial-close:hover { background:#edf3fa; }
.tutorial-progress { display:flex; align-items:baseline; flex-wrap:wrap; gap:5px 10px; margin:8px 0; }
.tutorial-progress span { color:var(--brand); font-size:12px; font-weight:600; }
.tutorial-copy { min-height:0; overflow:auto; }
.tutorial-guide p { margin:0; color:var(--t2); font-size:13px; line-height:1.65; }
.tutorial-guide .practice-instruction { margin-top:6px; font-size:12px; color:#07599f; }
.tutorial-guide .practice-feedback { margin-top:6px; color:#8d4a04; font-size:12px; font-weight:600; }
.tutorial-show-buttons { flex:none; align-self:flex-start; padding:4px 0; margin-top:6px; border:0; background:none; color:var(--brand); font:inherit; font-size:12px; cursor:pointer; }
.tutorial-guide footer { padding-top:10px; margin-top:12px; border-top:1px solid var(--line); }
.tutorial-skip { padding:5px 0; color:var(--t2); border:0; border-radius:5px; background:none; font:inherit; font-size:12px; cursor:pointer; }
button:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }
@media (max-width:560px) {
  .tutorial-picker { padding:16px; }
  .tutorial-groups,.workflow-options { grid-template-columns:minmax(0,1fr); }
  .tutorial-guide { padding:12px 14px; bottom:calc(var(--tutorial-bottom,0px) + 72px + env(safe-area-inset-bottom,0px)); max-height:calc(var(--tutorial-height,100dvh) - 84px); }
  .tutorial-guide.at-top { bottom:auto; }
}
</style>
