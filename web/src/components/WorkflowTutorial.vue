<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch, type CSSProperties } from 'vue'
import type { WorkflowCourse } from '../config/workflows'

const props = defineProps<{ course: WorkflowCourse; startAtForm?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const index = ref(props.startAtForm ? 1 : 0)
const current = computed(() => props.course.steps[index.value])
const completed = ref(false)
const notice = ref('')
const panel = ref<HTMLElement>()
const target = shallowRef<HTMLElement>()
const viewport = ref({ left:0, top:0, right:window.innerWidth, bottom:window.innerHeight })
type Bounds = { left:number; top:number; right:number; bottom:number }
const hole = ref<Bounds>()
let frame = 0
let observer: MutationObserver | undefined
let resize: ResizeObserver | undefined
let advanceIndex: number | undefined
let disposed = false
const focusable = 'button:not(:disabled),input:not(:disabled):not([hidden]),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]'

function rectStyle(left:number,top:number,right:number,bottom:number): CSSProperties {
  return { left:left+'px', top:top+'px', width:Math.max(0,right-left)+'px', height:Math.max(0,bottom-top)+'px' }
}
const masks = computed(() => {
  const { left,top,right,bottom } = viewport.value
  const gap = completed.value ? undefined : hole.value
  return gap ? [
    rectStyle(left,top,right,gap.top),
    rectStyle(left,gap.top,gap.left,gap.bottom),
    rectStyle(gap.right,gap.top,right,gap.bottom),
    rectStyle(left,gap.bottom,right,bottom)
  ] : [rectStyle(left,top,right,bottom)]
})
const ring = computed(() => hole.value && rectStyle(hole.value.left,hole.value.top,hole.value.right,hole.value.bottom))
const placement = computed<CSSProperties>(() => {
  const {left,top,right,bottom} = viewport.value
  const space=right-left
  const gap=completed.value ? undefined : hole.value
  const width=Math.min(340, Math.max(150, space-24))
  let x=right-width-12, y=top+12
  let available=bottom-top-24
  if (space<520) x=left+(space-width)/2
  if (gap && space>=720 && right-gap.right>=width+20) {
    x=gap.right+12
  } else if (gap && space>=720 && gap.left-left>=width+20) {
    x=gap.left-width-12
  } else if (gap) {
    const above=gap.top-top-24, below=bottom-gap.bottom-24
    if (below>=Math.min(220,above)) {
      y=gap.bottom+12
      available=below
    } else {
      available=above
    }
  } else if (completed.value) {
    x=left+(space-width)/2
    y=top+Math.max(12,(bottom-top-235)/2)
    available=bottom-y-12
  }
  return { left:x+'px',top:y+'px',width:width+'px',maxHeight:Math.max(130,available)+'px' }
})

function findTarget() {
  for (const node of document.querySelectorAll<HTMLElement>(current.value.target)) {
    if (node.getClientRects().length && getComputedStyle(node).visibility==='visible') return node
  }
}

function geometry() {
  const view=window.visualViewport
  const left=view?.offsetLeft ?? 0, top=view?.offsetTop ?? 0
  const right=left+(view?.width ?? document.documentElement.clientWidth)
  const bottom=top+(view?.height ?? document.documentElement.clientHeight)
  viewport.value={left,top,right,bottom}
  if (completed.value) { hole.value=undefined; return }
  const node=findTarget()
  if (node!==target.value) {
    resize?.disconnect()
    target.value=node
    if (node) {
      resize?.observe(node)
      const box=node.getBoundingClientRect()
      if (box.top<top+56 || box.bottom>bottom-48) node.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'})
    }
  }
  const box=node?.getBoundingClientRect()
  if (!box || !box.width || !box.height) { hole.value=undefined; return }
  const bounds={left:Math.max(left,box.left-4),top:Math.max(top,box.top-4),right:Math.min(right,box.right+4),bottom:Math.min(bottom,box.bottom+4)}
  hole.value=bounds.right>bounds.left && bounds.bottom>bounds.top ? bounds : undefined
}

function schedule() {
  if (!frame && !disposed) frame=requestAnimationFrame(() => { frame=0; geometry() })
}

function next(to?: string) {
  if (completed.value) return
  const destination=to ? props.course.steps.findIndex(s=>s.id===to) : index.value+1
  if (destination<0 || destination>=props.course.steps.length) return
  index.value=destination
}

function continueForm() {
  const step=current.value
  if (step.mode!=='continue' || !target.value || !hole.value) return
  if (step.minLength) {
    const field=target.value as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
    if (field.value.trim().length<step.minLength) {
      notice.value=step.minLength===1 ? '请先填写或选择高亮项。' : '请填写至少 '+step.minLength+' 个字。'
      field.focus()
      return
    }
  }
  next()
}

function inside(node: Node | null) {
  return !!node && (!!panel.value?.contains(node) || (!completed.value && !!target.value?.contains(node)))
}
function stop(event: Event) {
  event.preventDefault()
  event.stopImmediatePropagation()
}
function handleClick(event: MouseEvent) {
  const node=event.target
  if (!(node instanceof Element) || !inside(node)) { stop(event); return }
  if (panel.value?.contains(node) || completed.value || !target.value?.contains(node)) return
  const step=current.value
  if (step.trigger && !node.closest(step.trigger)) return
  const control=node.closest('button,input,select,textarea,[role=button]')
  if (control?.matches(':disabled,[aria-disabled=true]')) return
  if (step.mode==='finish') {
    stop(event)
    completed.value=true
    void nextTick(() => panel.value?.focus({preventScroll:true}))
    schedule()
  } else if (step.mode==='click' && advanceIndex!==index.value) {
    const from=index.value
    advanceIndex=from
    void nextTick(() => {
      advanceIndex=undefined
      if (!disposed && index.value===from) next()
    })
  }
}

function handleKey(event: KeyboardEvent) {
  if (event.key==='Escape') { stop(event); emit('close'); return }
  if (event.key==='Tab') {
    const insidePanel=[...panel.value?.querySelectorAll<HTMLElement>(focusable) || []]
    const item=completed.value ? undefined : target.value
    const insideTarget=item?.matches(focusable) ? [item] : [...item?.querySelectorAll<HTMLElement>(focusable) || []]
    const controls=[...insideTarget,...insidePanel].filter(el=>el.getClientRects().length)
    const position=controls.indexOf(document.activeElement as HTMLElement)
    const nextPosition=position<0 ? (event.shiftKey ? controls.length-1 : 0) : (position+controls.length+(event.shiftKey?-1:1))%controls.length
    stop(event)
    controls[nextPosition]?.focus()
    return
  }
  if (!inside(event.target as Node)) stop(event)
}

watch(index, async () => {
  notice.value=''
  target.value=undefined
  hole.value=undefined
  await nextTick()
  geometry()
  panel.value?.focus({preventScroll:true})
})

onMounted(() => {
  document.addEventListener('click',handleClick,true)
  document.addEventListener('keydown',handleKey,true)
  window.addEventListener('scroll',schedule,true)
  window.addEventListener('resize',schedule)
  window.visualViewport?.addEventListener('resize',schedule)
  window.visualViewport?.addEventListener('scroll',schedule)
  if (typeof ResizeObserver!=='undefined') resize=new ResizeObserver(schedule)
  observer=new MutationObserver(records => {
    if (records.some(record => !(record.target instanceof Element && record.target.closest('.workflow-guidance')))) schedule()
  })
  observer.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['class','hidden','disabled']})
  geometry()
  panel.value?.focus({preventScroll:true})
})
onBeforeUnmount(() => {
  disposed=true
  cancelAnimationFrame(frame)
  observer?.disconnect()
  resize?.disconnect()
  document.removeEventListener('click',handleClick,true)
  document.removeEventListener('keydown',handleKey,true)
  window.removeEventListener('scroll',schedule,true)
  window.removeEventListener('resize',schedule)
  window.visualViewport?.removeEventListener('resize',schedule)
  window.visualViewport?.removeEventListener('scroll',schedule)
})
</script>

<template>
  <Teleport to="body">
    <div class="workflow-guidance" :data-workflow="course.key">
      <div v-for="(mask,n) in masks" :key="n" class="workflow-shade" :style="mask" aria-hidden="true" @wheel.prevent @touchmove.prevent></div>
      <div v-if="hole && !completed" class="workflow-outline" :style="ring" aria-hidden="true"></div>
      <section ref="panel" class="workflow-coach" :style="placement" tabindex="-1"
        aria-labelledby="workflow-title" :data-workflow-current="completed ? 'completed' : current.id">
        <header>
          <span>{{ course.label }} · 流程练习</span>
          <button type="button" aria-label="退出流程练习" @click="emit('close')">×</button>
        </header>
        <div class="workflow-coach-copy">
          <div class="workflow-title-line" aria-live="polite">
            <b v-if="!completed">{{ index + 1 }} / {{ course.steps.length }}</b>
            <h2 id="workflow-title">{{ completed ? '练习完成' : current.title }}</h2>
          </div>
          <p>{{ completed ? course.outcome : current.description }}</p>
          <p v-if="notice" class="workflow-message" role="status">{{ notice }}</p>
          <p v-else-if="!hole && !completed" class="workflow-message" role="status">正在定位本步骤…</p>
          <p v-else-if="completed" class="workflow-message">本次仅为模拟操作，业务记录没有提交。</p>
        </div>
        <footer v-if="completed || current.skipTo || current.mode === 'continue'">
          <button v-if="completed" class="btn sm pri" type="button" @click="emit('close')">完成教程</button>
          <button v-if="!completed && current.skipTo" class="btn sm" type="button" @click="next(current.skipTo)">跳过可选项</button>
          <button v-if="!completed && current.mode === 'continue'" class="btn sm pri" type="button" :disabled="!hole" @click="continueForm">继续</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.workflow-guidance { position:fixed; inset:0; z-index:1100; pointer-events:none; }
.workflow-shade { position:fixed; background:rgba(13,22,37,.66); pointer-events:auto; touch-action:none; }
.workflow-outline { position:fixed; pointer-events:none; outline:2px solid #52aaff; outline-offset:-2px; border-radius:9px; }
.workflow-coach { position:fixed; display:flex; flex-direction:column; padding:14px 16px; pointer-events:auto; border:1px solid #bcd8f7; border-radius:15px; background:#fff; color:var(--t1); box-shadow:0 12px 36px #0a223444; outline:none; }
.workflow-coach header,.workflow-coach footer { display:flex; flex:none; align-items:center; justify-content:space-between; gap:8px; }
.workflow-coach header { color:var(--t2); font-size:12px; }
.workflow-coach header button { width:28px; height:28px; border:0; border-radius:7px; background:none; color:var(--t2); font-size:22px; line-height:1; cursor:pointer; }
.workflow-coach header button:hover { background:#edf5ff; }
.workflow-coach-copy { overflow:auto; min-height:0; overscroll-behavior:contain; }
.workflow-title-line { display:flex; align-items:baseline; gap:8px; margin-top:6px; }
.workflow-title-line b { flex:none; color:var(--brand); font-size:12px; }
.workflow-title-line h2 { margin:0; font-size:17px; line-height:1.4; }
.workflow-coach p { margin:7px 0 0; color:var(--t2); font-size:13px; line-height:1.55; }
.workflow-coach .workflow-message { color:#13558b; font-size:12px; }
.workflow-coach footer { justify-content:flex-end; margin-top:10px; }
.workflow-coach button:focus-visible { outline:2px solid var(--brand); outline-offset:2px; }
@media(max-width:480px) {
  .workflow-coach { padding:10px 13px; }
  .workflow-title-line { margin-top:3px; }
  .workflow-coach p { margin-top:5px; }
}
</style>
