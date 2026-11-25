<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import { accountBadgeApi, type AccountBadge, type BadgeAvatarSource } from '../api'
import logo from '../assets/badge/company-logo.webp'
import { useBadgeMotion } from '../composables/useBadgeMotion'

const props = defineProps<{ name: string; roleLabel: string; department?: string }>()
const menuOpen = defineModel<boolean>({ required: true })
const route = useRoute()
const root = ref<HTMLElement | null>(null)
const rig = ref<HTMLDivElement | null>(null)
const ribbon = ref<SVGSVGElement | null>(null)
const faceButton = ref<HTMLButtonElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const id = useId()
const ribbonId = 'badge-ribbon-' + id
const menuId = 'badge-menu-' + id
const profile = ref<AccountBadge>()
const loading = ref(false)
const error = ref('')
const avatarFailed = ref(false)
const scrolled = ref(false)
const source = computed(() => profile.value?.avatarSource || 'DINGTALK')
const initial = computed(() => Array.from(props.name || '员')[0])
const cardLabel = computed(() => {
  if (menuOpen.value) return '公司标志，点击翻回工牌正面'
  return [props.name + '的工牌', props.roleLabel, profile.value?.title, '点击翻面'].filter(Boolean).join('，')
})
const motion = useBadgeMotion(rig, ribbon, () => { menuOpen.value = false }, () => toggleMenu(faceButton.value))
const { dragging } = motion
let opener: HTMLButtonElement | null = null

async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const next = await accountBadgeApi.get()
    if (next.avatarUrl !== profile.value?.avatarUrl) avatarFailed.value = false
    profile.value = next
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '工牌加载失败'
  } finally { loading.value = false }
}

async function chooseAvatar(next: BadgeAvatarSource) {
  if (loading.value || next === source.value) return
  loading.value = true
  error.value = ''
  try {
    profile.value = await accountBadgeApi.setAvatar(next)
    avatarFailed.value = false
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '头像设置保存失败'
  } finally { loading.value = false }
}

function toggleMenu(button: HTMLButtonElement | null) {
  if (!menuOpen.value) opener = button
  menuOpen.value = !menuOpen.value
}

function toggleFromCard(event: MouseEvent) {
  const pointerType = 'pointerType' in event ? event.pointerType : ''
  if (pointerType === 'touch' || (event.detail > 0 && motion.consumeClick())) return
  toggleMenu(faceButton.value)
}

async function focusMenu(button: HTMLButtonElement | null) {
  if (!menuOpen.value) toggleMenu(button)
  await nextTick()
  closeButton.value?.focus()
}

function closeMenu(restoreFocus = false) {
  menuOpen.value = false
  if (restoreFocus) (opener && opener.offsetWidth ? opener : faceButton.value)?.focus()
}

function outside(event: PointerEvent) {
  if (menuOpen.value && event.target instanceof Node && !root.value?.contains(event.target)) closeMenu()
}

function escape(event: KeyboardEvent) {
  if (event.key === 'Escape' && menuOpen.value) {
    event.preventDefault()
    closeMenu(true)
  }
}

function updateScroll() {
  scrolled.value = window.scrollY > 24
}

watch(menuOpen, open => {
  motion.flip(open)
  if (open) void load()
}, { flush: 'post' })
watch(scrolled, motion.reset, { flush: 'post' })
watch(() => route.fullPath, () => closeMenu())

onMounted(() => {
  updateScroll()
  void load()
  window.addEventListener('scroll', updateScroll, { passive: true })
  window.addEventListener('badge-titles-changed', load)
  document.addEventListener('pointerdown', outside)
  document.addEventListener('keydown', escape)
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', updateScroll)
  window.removeEventListener('badge-titles-changed', load)
  document.removeEventListener('pointerdown', outside)
  document.removeEventListener('keydown', escape)
})
</script>

<template>
  <section ref="root" class="account-badge" :class="{ 'is-scrolled': scrolled, 'is-dragging': dragging }" aria-label="我的工牌">
    <button class="badge-account-trigger" type="button" :aria-controls="menuOpen ? menuId : undefined" :aria-expanded="menuOpen"
      @click="toggleMenu($event.currentTarget as HTMLButtonElement)" @keydown.down.prevent="focusMenu($event.currentTarget as HTMLButtonElement)">
      我的账户 <span aria-hidden="true" :class="{ 'is-flipped': menuOpen }">⌄</span>
    </button>
    <div class="badge-slot">
      <svg ref="ribbon" class="badge-strap" viewBox="0 0 88 60" preserveAspectRatio="xMidYMin meet" aria-hidden="true">
        <defs>
          <pattern :id="ribbonId" width="2" height="2" patternUnits="userSpaceOnUse" patternTransform="rotate(18)">
            <path d="M0 .3H2" stroke="#d5f1ef" stroke-opacity=".3" stroke-width=".38" />
            <path d="M.4 0V2" stroke="#042c39" stroke-opacity=".34" stroke-width=".48" />
          </pattern>
        </defs>
        <path data-ribbon-shadow fill="none" stroke="#183f50" stroke-opacity=".12" stroke-width="14" transform="translate(.5 .6)" />
        <g data-ribbon-faces />
        <path data-ribbon-texture :fill="'url(#' + ribbonId + ')'" />
        <path data-ribbon-edge fill="none" stroke="#91c4c7" stroke-opacity=".7" stroke-width=".65" />
        <path data-ribbon-edge fill="none" stroke="#093f4c" stroke-opacity=".9" stroke-width=".65" />
        <path v-for="side in 2" :key="side" data-ribbon-seam fill="none" stroke="#b2d2d1" stroke-opacity=".6" stroke-width=".4" stroke-dasharray="1.2 1.2" />
        <g data-ribbon-label><text text-anchor="middle" dominant-baseline="central" fill="#f4fbfa" font-family="Arial,sans-serif" font-size="5.5" font-weight="700" textLength="10" lengthAdjust="spacingAndGlyphs">TZME</text></g>
      </svg>
      <div ref="rig" class="badge-suspension" :class="{ 'has-award': !!profile?.title }"
        @lostpointercapture="motion.cancelled" @click="toggleFromCard" @dragstart.prevent>
        <span class="badge-metal-clip" aria-hidden="true" />
        <button ref="faceButton" type="button" class="badge-flip" :aria-controls="menuOpen ? menuId : undefined"
          :aria-expanded="menuOpen" :aria-label="cardLabel"
          :title="menuOpen ? '点击翻回正面' : '拖动工牌，点击翻面'" @pointerdown="motion.down" @keydown.down.prevent="focusMenu(faceButton)">
          <span class="badge-turn">
            <span class="badge-face badge-front" aria-hidden="true">
              <span class="badge-slot-hole"></span>
              <span class="badge-portrait">
                <span v-if="source === 'VIRTUAL'" class="badge-virtual"></span>
                <img v-else-if="profile?.avatarUrl && !avatarFailed" :src="profile.avatarUrl" alt="钉钉头像"
                  referrerpolicy="no-referrer" @error="avatarFailed = true" />
                <span v-else class="badge-initial">{{ initial }}</span>
              </span>
              <strong class="badge-name">{{ name }}</strong>
              <span class="badge-role">{{ roleLabel }}</span>
              <span v-if="profile?.title && profile.titleKey" class="badge-award" :data-title="profile.titleKey">
                <span class="badge-crest"><strong>{{ profile.title }}</strong></span>
                <span class="badge-count">累计接单 {{ profile.acceptedTickets }} 单</span>
              </span>
            </span>
            <span class="badge-face badge-back" aria-hidden="true">
              <img :src="logo" alt="重钢机械公司 Logo" />
            </span>
          </span>
        </button>
      </div>
    </div>

    <div v-if="menuOpen" :id="menuId" class="badge-account-pop" aria-label="我的账户" :aria-busy="loading">
      <div class="badge-account-head">
        <span class="badge-portrait badge-account-avatar" aria-hidden="true">
          <span v-if="source === 'VIRTUAL'" class="badge-virtual"></span>
          <img v-else-if="profile?.avatarUrl && !avatarFailed" :src="profile.avatarUrl" alt=""
            referrerpolicy="no-referrer" @error="avatarFailed = true" />
          <span v-else class="badge-initial">{{ initial }}</span>
        </span>
        <div class="badge-account-identity">
          <strong>{{ name }}</strong>
          <small>{{ department ? department + ' · ' : '' }}{{ roleLabel }}</small>
        </div>
        <button ref="closeButton" type="button" class="badge-close" aria-label="关闭账号菜单" @click="closeMenu(true)">×</button>
      </div>
      <div class="badge-avatar-options" role="group" aria-label="工牌头像来源">
        <span class="badge-avatar-label">工牌头像</span>
        <button type="button" class="btn sm" :class="{ pri: source === 'DINGTALK' }" :aria-pressed="source === 'DINGTALK'"
          :disabled="loading || !profile" @click="chooseAvatar('DINGTALK')">钉钉头像</button>
        <button type="button" class="btn sm" :class="{ pri: source === 'VIRTUAL' }" :aria-pressed="source === 'VIRTUAL'"
          :disabled="loading || !profile" @click="chooseAvatar('VIRTUAL')">虚拟形象</button>
      </div>
      <p v-if="error" class="badge-error" role="alert">
        {{ error }} <button v-if="!profile" type="button" class="btn sm" @click="load">重试</button>
      </p>
      <slot name="shortcuts" />
      <div v-if="profile?.title && profile.acceptedTickets != null" class="badge-title-progress">
        <div class="badge-title-head"><strong>称号进度</strong><span>{{ profile.title }}</span></div>
        <template v-if="profile.nextTitle && profile.nextTitleAt">
          <progress :value="profile.acceptedTickets" :max="profile.nextTitleAt"
            :aria-label="'晋级至' + profile.nextTitle + '的接单进度'"></progress>
          <div class="badge-title-detail">
            <span>累计接单 {{ profile.acceptedTickets }} / {{ profile.nextTitleAt }}</span>
            <span>下一称号 · {{ profile.nextTitle }}</span>
          </div>
        </template>
        <div v-else class="badge-title-detail">
          <span>累计接单 {{ profile.acceptedTickets }} 单</span>
          <span v-if="profile.titleKey === 'legend'">已达最高称号</span>
        </div>
      </div>
      <slot />
    </div>
  </section>
</template>

<style scoped>
.account-badge { position:relative; display:flex; align-items:center; gap:12px; align-self:stretch; flex:none; color:#315a6c; }
.badge-account-trigger { display:flex; align-items:center; gap:5px; padding:6px 0 6px 4px; border:0; border-radius:5px; background:none; color:#315a6c; font:inherit; font-size:12px; cursor:pointer; white-space:nowrap; }
.badge-account-trigger span { transition:transform .25s; }
.badge-account-trigger .is-flipped { transform:rotate(180deg); }
.badge-slot { position:relative; width:var(--account-badge-width); height:100%; perspective:700px; }
.badge-strap { position:absolute; top:0; left:0; width:100%; height:60px; overflow:visible; pointer-events:none; }
.badge-suspension { position:absolute; top:34px; left:0; width:100%; height:var(--badge-height,138px); transform-style:preserve-3d; }
.badge-suspension.has-award { height:var(--badge-award-height,155px); }
.badge-suspension.has-pose { transform:translate3d(var(--badge-x,0px),var(--badge-y,0px),0) rotateZ(var(--badge-angle,0deg)) rotateX(var(--badge-tilt,0deg)) rotateY(var(--badge-yaw,0deg)); }
.badge-metal-clip { position:absolute; z-index:2; top:-12px; left:50%; translate:-50%; width:8px; height:19px; border:1px solid #899da4; border-radius:4px; background:linear-gradient(100deg,#a7bbc4,#fff 25%,#9eafb8 50%,#f4f8fa 78%,#6f8791); box-shadow:1px 2px 2px #17374420,inset 1px 0 1px #fff; transform:rotateY(var(--badge-flip,0deg)); pointer-events:none; }
.badge-flip { display:block; position:relative; width:100%; height:100%; padding:0; border:0; border-radius:8px; background:none; color:#153e51; font:inherit; cursor:grab; touch-action:none; user-select:none; transform-style:preserve-3d; }
.badge-flip:focus-visible,.badge-account-trigger:focus-visible { outline:2px solid #147d90; outline-offset:3px; }
.is-dragging .badge-flip { cursor:grabbing; }
.badge-turn { position:relative; display:block; width:100%; height:100%; transform-style:preserve-3d; transform:rotateY(var(--badge-flip,0deg)); pointer-events:none; }
.badge-face { position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; border:1px solid #b9cdd5; border-radius:8px; box-shadow:2px 7px 12px #173c501f,inset 0 0 0 3px #ffffffe6,inset 0 0 0 4px #e8f0f4; backface-visibility:hidden; -webkit-backface-visibility:hidden; overflow:hidden; }
.badge-front { padding:16px 6px 8px; justify-content:center; gap:6px; background:linear-gradient(145deg,#faffff,#f3f8fb 60%,#fff); }
.has-award .badge-front { justify-content:flex-start; gap:var(--badge-award-gap,3px); }
.badge-front::before { content:'Z'; position:absolute; right:6px; bottom:4px; color:#147d9012; font:italic 47px/1 Arial,sans-serif; }
.badge-back { justify-content:center; transform:rotateY(180deg); background:#fff; }
.badge-back img { width:72%; max-width:64px; max-height:70%; object-fit:contain; }
.badge-slot-hole { position:absolute; top:3px; left:calc(50% - 9px); height:3px; width:18px; border:1px solid #a6bdc5; border-radius:3px; background:#d8e6ea; }
.badge-portrait { display:grid; place-items:center; flex:none; position:relative; width:48px; height:48px; border:1px solid #d4e4ee; border-radius:9px; overflow:hidden; background:#f0f5fa; }
.has-award .badge-portrait { width:var(--badge-award-portrait,44px); height:var(--badge-award-portrait,44px); }
.badge-portrait img,.badge-virtual,.badge-initial { width:100%; height:100%; }
.badge-portrait img { display:block; object-fit:cover; }
.badge-virtual { background:url('../assets/badge/virtual-avatars.webp') no-repeat 0 0 / 300% 200%; }
.badge-initial { display:grid; place-items:center; color:#155585; font-size:17px; font-weight:700; background:linear-gradient(145deg,#e3f3ff,#b1d7f7); }
.badge-name { position:relative; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:14px; line-height:1.3; }
.badge-role { position:relative; padding:0 6px; border-radius:12px; background:#e3f2f2; color:#096273; font-size:9px; font-weight:700; line-height:1.6; white-space:nowrap; }
.badge-award { display:grid; position:relative; align-self:stretch; justify-items:center; margin-top:auto; }
.badge-award[data-title="steel"] { --offset:7.5%; --ink:#effbff; --bevel:#68b2ea; --outline:#142957; }
.badge-award[data-title="jade"] { --offset:37.2%; --ink:#e0fff0; --bevel:#68dabc; --outline:#113632; }
.badge-award[data-title="sapphire"] { --offset:66.2%; --ink:#f7f1ff; --bevel:#a99afe; --outline:#29165e; }
.badge-award[data-title="legend"] { --offset:95.6%; --ink:#fff6bc; --bevel:#f5b944; --outline:#6d290e; }
.badge-crest { display:grid; place-items:center; width:100%; max-width:96px; aspect-ratio:1024 / 350; background:url('../assets/badge/titles.webp') no-repeat center var(--offset) / 100% auto; }
.badge-crest strong { color:var(--ink); font-size:9px; font-weight:900; line-height:1; white-space:nowrap; paint-order:stroke fill; -webkit-text-stroke:.055em var(--outline); text-shadow:0 .065em 0 var(--bevel),0 .12em .1em var(--outline); }
.badge-count { font-size:7px; line-height:1.2; color:#52677a; }
.badge-account-pop { position:absolute; z-index:89; top:calc(100% - 4px); right:calc(var(--account-badge-width) + 12px); width:270px; max-height:calc(100vh - 76px); max-height:calc(100dvh - 76px); overflow:auto; background:#fff; border-radius:16px; color:#1d1d1f; box-shadow:0 16px 40px #0002; }
.badge-account-head { display:flex; align-items:center; gap:9px; padding:12px 14px; border-bottom:1px solid #eef0f4; }
.badge-account-head .badge-account-avatar { width:36px; height:36px; border-radius:9px; }
.badge-account-head .badge-account-avatar .badge-initial { font-size:15px; }
.badge-account-identity { display:grid; flex:1; min-width:0; gap:2px; font-size:13px; overflow-wrap:anywhere; }
.badge-account-identity small { color:#6e6e73; font-size:11px; }
.badge-close { border:0; padding:2px 6px; background:none; color:#86868b; font-size:18px; line-height:1; cursor:pointer; }
.badge-avatar-options { display:flex; align-items:center; gap:7px; flex-wrap:wrap; padding:10px 14px; }
.badge-avatar-label { color:#6e6e73; font-size:11px; margin-right:2px; }
.badge-title-progress { margin:0 14px 12px; padding:10px 11px; display:grid; gap:7px; border:1px solid #e4eaf3; border-radius:11px; background:#f5f8fd; }
.badge-title-head,.badge-title-detail { display:flex; justify-content:space-between; align-items:baseline; gap:4px 8px; flex-wrap:wrap; }
.badge-title-head { font-size:12px; }
.badge-title-head span { color:var(--brand); font-weight:600; }
.badge-title-detail { font-size:10.5px; color:#505d6c; }
.badge-title-progress progress { width:100%; height:6px; border:0; border-radius:6px; overflow:hidden; background:#dce6f4; }
.badge-title-progress progress::-webkit-progress-bar { background:#dce6f4; }
.badge-title-progress progress::-webkit-progress-value { border-radius:6px; background:var(--brand); }
.badge-title-progress progress::-moz-progress-bar { border-radius:6px; background:var(--brand); }
.badge-error { margin:0 16px 10px; color:#b32921; font-size:12px; overflow-wrap:anywhere; }
.is-scrolled .badge-suspension { width:52px; height:48px; top:6px; left:calc((var(--account-badge-width) - 52px) / 2); }
.is-scrolled .badge-strap,.is-scrolled .badge-slot-hole,.is-scrolled .badge-role,.is-scrolled .badge-award { display:none; }
.is-scrolled .badge-metal-clip { top:-5px; height:8px; width:5px; }
.is-scrolled .badge-front { padding:4px 2px; gap:2px; }
.is-scrolled .badge-portrait { width:23px; height:23px; border-radius:6px; }
.is-scrolled .badge-initial,.is-scrolled .badge-name { font-size:9px; }
@media (max-width:1199px) {
  .badge-suspension { --badge-height:120px; --badge-award-height:139px; --badge-award-portrait:36px; top:29px; }
  .badge-front { padding-top:13px; gap:4px; }
  .badge-portrait { width:42px; height:42px; }
  .badge-name { font-size:13px; }
}
@media (max-width:899px) {
  .badge-account-trigger { display:none; }
  .badge-suspension { --badge-height:66px; --badge-award-height:75px; --badge-award-gap:1px; --badge-award-portrait:20px; top:10px; }
  .badge-metal-clip { top:-6px; width:5px; height:10px; }
  .badge-face { border-radius:6px; box-shadow:1px 4px 8px #173c501f,inset 0 0 0 2px #ffffffd9; }
  .badge-front { padding:4px 2px 2px; gap:2px; }
  .badge-front::before,.badge-slot-hole,.badge-count { display:none; }
  .badge-portrait { width:22px; height:22px; border-radius:5px; }
  .badge-name,.badge-initial { font-size:8px; }
  .badge-role,.badge-crest strong { font-size:6px; }
  .badge-role { padding:0 3px; }
  .badge-account-pop { top:91px; right:0; width:min(270px, calc(100vw - 32px)); max-height:calc(100dvh - 172px); }
  .is-scrolled .badge-account-pop { top:calc(100% + 3px); }
  .is-scrolled .badge-suspension { width:100%; left:0; top:4px; }
}
@media (prefers-reduced-motion:reduce) { .badge-account-trigger span { transition:none; } }
</style>
