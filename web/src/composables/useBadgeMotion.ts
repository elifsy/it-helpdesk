import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { createBadgeRibbon } from '../utils/badgeRibbon'

const axes = ['x', 'y', 'angle', 'tilt', 'yaw'] as const
const zero = () => ({ x: 0, y: 0, angle: 0, tilt: 0, yaw: 0 })
type Pose = ReturnType<typeof zero>
type Point = { x: number; y: number }
const clamp = (value: number, limit: number) => Math.max(-limit, Math.min(limit, value))
const elastic = (value: number, limit: number) => Math.tanh(value / limit) * limit
const rad = (value: number) => value * Math.PI / 180

function project(point: Point, pose: Pick<Pose, 'angle' | 'tilt' | 'yaw'>) {
  const cy = Math.cos(rad(pose.yaw)), sy = Math.sin(rad(pose.yaw))
  const cx = Math.cos(rad(pose.tilt)), sx = Math.sin(rad(pose.tilt))
  const cz = Math.cos(rad(pose.angle)), sz = Math.sin(rad(pose.angle))
  const x = point.x * cy, z = -point.x * sy
  const y = point.y * cx - z * sx
  return { x: x * cz - y * sz, y: x * sz + y * cz, z: point.y * sx + z * cx }
}

// Keep the grab point under the pointer and attach the ribbon to the moving clip.
export function useBadgeMotion(
  rig: Ref<HTMLDivElement | null>, ribbonSvg: Ref<SVGSVGElement | null>,
  onDrag: () => void, onTouchTap: () => void
) {
  const dragging = ref(false)
  let pointer: { id: number; target: HTMLElement; start: Point; pose: Pose; grip: Point; projected: Point; time: number } | undefined
  let layout: {
    width: number; height: number; centerX: number; centerY: number; originX: number; originY: number
    perspective: number; attachment: number; svgInverse: DOMMatrix | undefined
  } | undefined
  let media: MediaQueryList | undefined
  let observer: ResizeObserver | undefined
  let ribbon: ReturnType<typeof createBadgeRibbon> | undefined
  let frame = 0, reduced = false, suppressUntil = 0, lastTime = 0
  let pose = zero(), velocity = zero(), flipAngle = 0, flipTarget = 0
  let flipTween: { from: number; start: number } | undefined

  function measure() {
    const element = rig.value, parent = element?.parentElement
    const clip = element?.querySelector<HTMLElement>('.badge-metal-clip')
    if (!element || !parent || !clip || !ribbonSvg.value || !element.offsetWidth) return
    const slot = parent.getBoundingClientRect()
    const width = element.offsetWidth, height = element.offsetHeight
    layout = {
      width, height,
      centerX: slot.left + element.offsetLeft + width / 2,
      centerY: slot.top + element.offsetTop + height / 2,
      originX: slot.left + slot.width / 2,
      originY: slot.top + slot.height / 2,
      perspective: parseFloat(getComputedStyle(parent).perspective) || 700,
      attachment: -height / 2 + clip.offsetTop + clip.offsetHeight * .2,
      svgInverse: ribbonSvg.value.getScreenCTM()?.inverse()
    }
  }

  function paint() {
    if (!rig.value || !layout) return
    const style = rig.value.style
    for (const key of axes) {
      style.setProperty(`--badge-${key}`, `${pose[key].toFixed(3)}${key === 'x' || key === 'y' ? 'px' : 'deg'}`)
    }
    style.setProperty('--badge-flip', `${flipAngle.toFixed(3)}deg`)
    rig.value.classList.toggle('has-pose', axes.some(key => Math.abs(pose[key]) > .001))
    const attached = project({ x: 0, y: layout.attachment }, pose)
    const perspective = layout.perspective / (layout.perspective - attached.z)
    const screen = new DOMPoint(
      layout.originX + (layout.centerX + pose.x + attached.x - layout.originX) * perspective,
      layout.originY + (layout.centerY + pose.y + attached.y - layout.originY) * perspective
    )
    const end = layout.svgInverse ? screen.matrixTransform(layout.svgInverse) : { x: 44, y: 26 }
    ribbon?.draw(end, pose.angle, flipAngle + pose.yaw * .65)
  }

  function releaseCapture() {
    const captured = pointer
    pointer = undefined
    if (captured?.target.hasPointerCapture?.(captured.id)) captured.target.releasePointerCapture(captured.id)
  }

  function tick(time: number) {
    frame = 0
    const step = Math.min(2, Math.max(.4, (time - lastTime) / 16.67))
    lastTime = time
    if (!pointer) {
      for (const key of axes) {
        const spring = key === 'x' || key === 'y' ? .045 : .035
        velocity[key] = (velocity[key] - pose[key] * spring * step) * Math.pow(.82, step)
        pose[key] += velocity[key] * step
      }
      if (axes.every(key => Math.abs(pose[key]) + Math.abs(velocity[key]) < .045)) {
        pose = zero()
        velocity = zero()
      }
    }
    if (flipTween) {
      const progress = Math.min(1, (time - flipTween.start) / 620)
      const ease = progress * progress * (3 - 2 * progress)
      flipAngle = flipTween.from + (flipTarget - flipTween.from) * ease
      if (progress === 1) flipTween = undefined
    }
    paint()
    if (flipTween || (!pointer && axes.some(key => pose[key] || velocity[key]))) schedule()
  }

  function schedule() {
    if (!frame && !document.hidden) frame = requestAnimationFrame(tick)
  }

  function reset() {
    cancelAnimationFrame(frame)
    frame = 0
    if (pointer && dragging.value) suppressUntil = performance.now() + 350
    releaseCapture()
    dragging.value = false
    pose = zero()
    velocity = zero()
    flipTween = undefined
    flipAngle = flipTarget
    measure()
    paint()
  }

  function flip(value: boolean) {
    flipTarget = value ? 180 : 0
    measure()
    if (reduced) {
      flipTween = undefined
      flipAngle = flipTarget
      paint()
      return
    }
    lastTime = performance.now()
    flipTween = { from: flipAngle, start: lastTime }
    schedule()
  }

  function down(event: PointerEvent) {
    const target = event.currentTarget
    if (event.button !== 0 || pointer || !(target instanceof HTMLElement)) return
    measure()
    if (!layout) return
    const px = event.clientX - layout.centerX - pose.x
    const py = event.clientY - layout.centerY - pose.y
    const a = project({ x: 1, y: 0 }, pose), b = project({ x: 0, y: 1 }, pose)
    const determinant = a.x * b.y - a.y * b.x
    const grip = {
      x: clamp((px * b.y - py * b.x) / determinant, layout.width / 2),
      y: clamp((py * a.x - px * a.y) / determinant, layout.height / 2)
    }
    suppressUntil = 0
    pointer = {
      id: event.pointerId, target, start: { x: event.clientX, y: event.clientY },
      pose: { ...pose }, grip, projected: project(grip, pose), time: performance.now()
    }
    velocity = zero()
    target.setPointerCapture?.(event.pointerId)
  }

  function move(event: PointerEvent) {
    if (!pointer || !layout || pointer.id !== event.pointerId) return
    const dx = event.clientX - pointer.start.x, dy = event.clientY - pointer.start.y
    if (!dragging.value && Math.hypot(dx, dy) < 6) return
    if (!dragging.value) {
      dragging.value = true
      onDrag()
    }
    const small = layout.width < 65, limit = small ? 14 : 28
    const gx = pointer.grip.x / layout.width, gy = pointer.grip.y / layout.height
    const rotation = {
      angle: clamp(pointer.pose.angle + (gx * dy - gy * dx) * .62 + dx * .035, small ? 12 : 17),
      tilt: clamp(pointer.pose.tilt - dy * (.12 + Math.abs(gy) * .12), 9),
      yaw: clamp(pointer.pose.yaw + dx * (.13 + Math.abs(gx) * .12), 12)
    }
    const projected = project(pointer.grip, rotation)
    const x = clamp(pointer.pose.x + elastic(dx, limit) + pointer.projected.x - projected.x, limit)
    const y = clamp(pointer.pose.y + elastic(dy, limit * .65) + pointer.projected.y - projected.y, limit * .75)
    const cosine = Math.abs(Math.cos(rad(rotation.angle))), sine = Math.abs(Math.sin(rad(rotation.angle)))
    const half = (cosine * layout.width + sine * layout.height) / 2
    const halfHeight = (sine * layout.width + cosine * layout.height) / 2
    const next = {
      ...rotation,
      x: Math.max(6 - layout.centerX + half, Math.min(innerWidth - 6 - layout.centerX - half, x)),
      y: Math.max(6 - layout.centerY + halfHeight, Math.min(innerHeight - 6 - layout.centerY - halfHeight, y))
    }
    const now = performance.now(), elapsed = Math.max(8, now - pointer.time)
    for (const key of axes) {
      velocity[key] = clamp((next[key] - pose[key]) * 16 / elapsed, key === 'x' || key === 'y' ? 3 : 1.5)
    }
    pose = next
    pointer.time = now
    paint()
  }

  function up(event: PointerEvent) {
    if (pointer?.id !== event.pointerId) return
    const moved = dragging.value
    releaseCapture()
    dragging.value = false
    if (moved || event.pointerType === 'touch') suppressUntil = performance.now() + 350
    if (!moved && event.pointerType === 'touch') onTouchTap()
    if (moved && reduced) reset()
    else if (moved || axes.some(key => pose[key])) {
      lastTime = performance.now()
      schedule()
    }
  }

  function cancelled(event: PointerEvent) {
    if (pointer?.id === event.pointerId) {
      suppressUntil = performance.now() + 350
      reset()
    }
  }

  function preference() {
    reduced = !!media?.matches
    if (reduced) reset()
  }

  function visibility() {
    if (document.hidden) reset()
  }

  onMounted(() => {
    if (ribbonSvg.value) ribbon = createBadgeRibbon(ribbonSvg.value)
    media = matchMedia('(prefers-reduced-motion: reduce)')
    preference()
    if (rig.value && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => { measure(); paint() })
      observer.observe(rig.value)
    }
    measure()
    paint()
    media.addEventListener('change', preference)
    document.addEventListener('visibilitychange', visibility)
    window.addEventListener('resize', reset)
    window.addEventListener('blur', reset)
    window.addEventListener('pointermove', move, { passive: true, capture: true })
    window.addEventListener('pointerup', up, true)
    window.addEventListener('pointercancel', cancelled, true)
  })

  onBeforeUnmount(() => {
    cancelAnimationFrame(frame)
    releaseCapture()
    observer?.disconnect()
    ribbon?.dispose()
    media?.removeEventListener('change', preference)
    document.removeEventListener('visibilitychange', visibility)
    window.removeEventListener('resize', reset)
    window.removeEventListener('blur', reset)
    window.removeEventListener('pointermove', move, true)
    window.removeEventListener('pointerup', up, true)
    window.removeEventListener('pointercancel', cancelled, true)
  })
  return { dragging, down, cancelled, consumeClick: () => performance.now() < suppressUntil, reset, flip }
}
