import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue'
import { controlSelector, describeControl, type TutorialControl } from '../config/tutorialControls'
import { tutorialWriteMessage } from '../utils/tutorialPractice'

const exclude = '.tutorial-guide,.tutorial-picker,.tutorial-highlight,.tutorial-trigger'
const closeButton = /^(关闭|取消|返回|×$)/

function visible(node: HTMLElement) {
  if (node.closest('[inert], [hidden]') || !node.isConnected) return false
  const rect = node.getBoundingClientRect()
  return !!rect.width && !!rect.height && getComputedStyle(node).visibility === 'visible'
}

function lessonScope() {
  const native = [...document.querySelectorAll<HTMLDialogElement>('dialog[open]:not(.tutorial-picker)')].filter(visible).pop()
  if (native) return native
  const modal = [...document.querySelectorAll<HTMLElement>('.mask')].filter(visible)
    .sort((a, b) => (Number(getComputedStyle(a).zIndex) || 0) - (Number(getComputedStyle(b).zIndex) || 0)).pop()
  if (modal) return modal
  const popover = [...document.querySelectorAll<HTMLElement>('.badge-account-pop,.notif-pop,.rail-summary.is-open,.mobile-nav,.report-detail,.reports .report-form')].filter(visible).pop()
  return popover || document.querySelector<HTMLElement>('.main')
}

function candidates(scope: HTMLElement) {
  const nodes = [...scope.querySelectorAll<HTMLElement>(controlSelector)]
  if (scope.matches('.main')) {
    nodes.push(...document.querySelectorAll<HTMLElement>('.tools button,.tools input,.account-badge button,.mobile-menu-button,.tabs button,.side button'))
  }
  const distinct = new Set<string>()
  return nodes.filter(visible)
    .filter(node => !node.closest(exclude))
    .map(describeControl)
    .filter(control => {
      const group = control.node.matches('.device-option,.asset-row,.wo,.ticket-card,.report-row,tr[data-tutorial-control]') ? 'record' : control.label
      const key = control.kind + ':' + group
      if (distinct.has(key)) return false
      distinct.add(key)
      return true
    })
    .sort((a, b) => Number(closeButton.test(a.label)) - Number(closeButton.test(b.label)))
}

export function useTutorialControls(active: Ref<boolean>, locate: (node?: HTMLElement, scroll?: boolean) => void) {
  const enabled = ref(false)
  const items = shallowRef<TutorialControl[]>([])
  const chosen = shallowRef<TutorialControl>()
  const nativeDialog = shallowRef<HTMLDialogElement>()
  const panelTitle = ref('')
  const feedback = ref('')
  const index = computed(() => items.value.findIndex(item => item.node === chosen.value?.node))
  let scope: HTMLElement | null = null
  let frame = 0
  let observer: MutationObserver | undefined

  function select(item: TutorialControl | undefined, scroll = true) {
    chosen.value = item
    feedback.value = ''
    void nextTick(() => { if (enabled.value && chosen.value === item) locate(item?.node, scroll) })
  }

  function refresh() {
    if (!active.value) return
    const nextScope = lessonScope()
    if (!nextScope) return
    const changed = nextScope !== scope
    scope = nextScope
    nativeDialog.value = nextScope instanceof HTMLDialogElement ? nextScope : undefined
    const heading = nextScope.querySelector('.dlg-h [id], .dlg-h h3, .picker-head h3, [id$="-title"]')
      ?? nextScope.querySelector('.dlg-h, h2, h3, .pop-h')
    panelTitle.value = nextScope.matches('.main') ? '' : (heading?.textContent || '')
      .replace(/[×›]+$/g, '').replace(/\s+/g,' ').trim().slice(0,64)
    items.value = candidates(nextScope)
    if (changed && !nextScope.matches('.main')) enabled.value = true
    if (!enabled.value) return
    const previous = chosen.value
    if (!previous || !visible(previous.node) || (changed && !nextScope.contains(previous.node))) select(items.value[0], false)
  }

  function schedule() {
    if (!active.value || frame) return
    frame = requestAnimationFrame(() => { frame = 0; refresh() })
  }

  function activate() {
    enabled.value = true
    refresh()
    if (chosen.value) locate(chosen.value.node)
  }

  function stepControl(delta: number) {
    const next = items.value[index.value + delta]
    if (next) select(next)
  }

  function focused(event: Event) {
    if (!active.value) return
    const node = event.target instanceof HTMLElement ? event.target.closest<HTMLElement>(controlSelector) : undefined
    if (!node || node.closest(exclude) || !node.matches('input,textarea,select')) return
    enabled.value = true
    select(describeControl(node), false)
  }

  function click(event: MouseEvent) {
    if (!active.value || !(event.target instanceof Element)) return
    const node = event.target.closest<HTMLElement>(controlSelector)
    if (!node || node.closest(exclude)) return
    enabled.value = true
    const item = describeControl(node)
    select(item, false)
    if (item.kind === 'write') {
      event.preventDefault()
      event.stopImmediatePropagation()
      feedback.value = tutorialWriteMessage
    }
    schedule()
  }

  function submit(event: SubmitEvent) {
    if (!active.value || !(event.target instanceof HTMLFormElement)) return
    const button = event.submitter instanceof HTMLElement ? event.submitter : event.target.querySelector<HTMLElement>('button:not([type]),[type=submit]')
    if (button && /^(查询|搜索|筛选)$/.test(describeControl(button).label) && !event.target.hasAttribute('action')) return
    event.preventDefault()
    event.stopImmediatePropagation()
    enabled.value = true
    if (button) select(describeControl(button),false)
    feedback.value = tutorialWriteMessage
  }

  function reset() {
    enabled.value = false
    items.value = []
    chosen.value = undefined
    nativeDialog.value = undefined
    panelTitle.value = ''
    feedback.value = ''
    scope = null
    cancelAnimationFrame(frame)
    frame = 0
    observer?.disconnect()
    observer = undefined
    document.removeEventListener('click',click,true)
    document.removeEventListener('focusin',focused,true)
    document.removeEventListener('submit',submit,true)
  }

  watch(active, on => {
    if (!on) { reset(); return }
    document.addEventListener('click',click,true)
    document.addEventListener('focusin',focused,true)
    document.addEventListener('submit',submit,true)
    observer = new MutationObserver(records => {
      if (records.some(record => !(record.target instanceof Element && record.target.closest(exclude)))) schedule()
    })
    observer.observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['open','class','disabled','hidden','aria-expanded']})
    schedule()
  }, { flush:'post' })
  onBeforeUnmount(reset)
  return { enabled, items, chosen, nativeDialog, panelTitle, feedback, index, activate, stepControl, schedule }
}
