<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  adminApi,
  assetRequestApi,
  kbApi,
  metaApi,
  notifyApi,
  serviceContactApi,
  statsApi,
  ticketApi,
  type ServiceContactRoster,
  type Notification
} from '../api'
import ContactLeaveStatus from '../components/ContactLeaveStatus.vue'
import AccountBadge from '../components/AccountBadge.vue'
import { useAuthStore } from '../stores/auth'
import { useMissingReportStore } from '../stores/missingReports'
import {
  DEMO_USERS,
  MENUS,
  ROLE_META,
  ROLE_NAMES,
  TABS,
  roleFromRoles,
  homeForRoles,
  routeAllowed,
  titleFor,
  type WorkRole
} from '../config/menus'
import { REQ_STATUS_NAME, REQ_TYPE } from '../utils/dict'
import { isDingTalk } from '../utils/dingtalk'
import { toast, toastText } from '../utils/toast'
import { openTutorialPicker, tutorialsFor } from '../config/tutorials'
import { tutorialPractice, tutorialWorkflow } from '../utils/tutorialPractice'

const route = useRoute()
const reportState = useMissingReportStore()
const router = useRouter()
const auth = useAuthStore()
const openTutorials = inject(openTutorialPicker)
const hasTutorials = computed(() => !!auth.user && tutorialsFor(auth.roles, auth.permissions).length > 0)

const workRole = computed<WorkRole>(() => roleFromRoles(auth.roles))
const meta = computed(() => {
  const value = ROLE_META[workRole.value]
  return auth.roles.includes('SUPER_ADMIN') ? { ...value, label: ROLE_NAMES.SUPER_ADMIN } : value
})
const groups = computed(() => MENUS[workRole.value].map(g => ({ ...g, items: g.items.filter(m => routeAllowed(auth.roles, m.path, auth.permissions) && (!m.permission || auth.can(m.permission))) })).filter(g => g.items.length))
const tabs = computed(() => TABS[workRole.value].filter(t => routeAllowed(auth.roles, t.path, auth.permissions)))

/* ---------- 侧栏分组折叠（IT / 管理员的导航项较多，允许按组收起） ---------- */
const canFoldNav = computed(() => workRole.value !== 'emp')
const foldKey = computed(() => 'it_helpdesk_nav_folded_' + workRole.value)
const folded = ref<Record<string, boolean>>({})
/** 首次进入（没有本地记录）时，只展开当前页面所在的分组，其余先折叠，避免导航过长 */
function defaultFolded() {
  const map: Record<string, boolean> = {}
  const active = groups.value.find(g => g.items.some(m => isOn(m.path)))
  for (const g of groups.value) map[g.title] = !!active && g.title !== active.title
  return map
}
function loadFolded() {
  const raw = localStorage.getItem(foldKey.value)
  if (raw) {
    try { folded.value = JSON.parse(raw) || {}; return } catch { /* 损坏则回退默认 */ }
  }
  folded.value = defaultFolded()
}
function persistFolded() {
  try { localStorage.setItem(foldKey.value, JSON.stringify(folded.value)) } catch { /* 隐私模式忽略 */ }
}
watch(foldKey, loadFolded, { immediate: true })
function toggleGroup(title: string) {
  folded.value = { ...folded.value, [title]: !folded.value[title] }
  persistFolded()
}
function groupActive(items: { path: string }[]) {
  return items.some(m => isOn(m.path))
}
const allFolded = computed(() => canFoldNav.value && groups.value.length > 0 && groups.value.every(g => folded.value[g.title]))
function toggleAllGroups() {
  const next: Record<string, boolean> = {}
  if (!allFolded.value) for (const g of groups.value) next[g.title] = true
  folded.value = next
  persistFolded()
}
/** 进入某个页面时，自动把它所在的分组展开，避免被折叠挡住在用的入口 */
watch(() => route.path, () => {
  if (!canFoldNav.value) return
  const hit = groups.value.find(g => g.items.some(m => isOn(m.path)))
  if (hit && folded.value[hit.title]) folded.value = { ...folded.value, [hit.title]: false }
}, { immediate: true })
const title = computed(() => titleFor(workRole.value, route.fullPath))
const personalPage = computed(() => ['/home', '/new', '/tickets', '/assets', '/kb', '/missing-reports'].includes(route.path) || route.path.startsWith('/ticket/'))
const isHome = computed(() => route.path === '/home')
const contactRoster = ref<ServiceContactRoster>()
watch([isHome, () => auth.user?.id], async ([home, userId], _, onCleanup) => {
  contactRoster.value = undefined
  if (!home || !userId) return
  let current = true
  onCleanup(() => { current = false })
  try {
    const roster = await serviceContactApi.display()
    if (current) contactRoster.value = roster
  } catch {
    if (!current) return
    try {
      const { duty } = await metaApi.runtime()
      if (current && duty) contactRoster.value = { contacts: [], hotline: duty.hotline, hotlineNote: duty.hotlineNote }
    } catch {
      // 联系方式加载失败时，首页其他功能仍可使用。
    }
  }
}, { immediate: true })
const reportRestrictedPage = computed(() => !tutorialWorkflow.value && (route.path === '/new' || route.path === '/assets'))
watch([() => auth.user?.id, () => route.path], ([id]) => {
  if (!id || ['/home', '/new', '/assets', '/missing-reports'].includes(route.path)) void reportState.refresh(id)
}, { immediate: true })
const inDingTalk = isDingTalk()

/* ---------- 角标 / 通知 ---------- */
const badges = ref({ pending: 0, approvals: 0 })
const unread = ref(0)
const notifies = ref<Notification[]>([])
const showNotif = ref(false)
const showUserMenu = ref(false)
const showMobileMenu = ref(false)
const showRail = ref(false)
const railTrigger = ref<HTMLButtonElement>()
const railSummary = ref<HTMLElement>()
const mobileNav = ref<HTMLElement | null>(null)
let navigationOverflow: string | null = null
let navigationFocus: HTMLElement | null = null
watch(showMobileMenu, async open => {
  if (open) {
    navigationFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    navigationOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    showUserMenu.value = false
    showNotif.value = false
    await nextTick()
    if (showMobileMenu.value) mobileNav.value?.querySelector<HTMLButtonElement>('button')?.focus()
  } else {
    if (navigationOverflow !== null) document.body.style.overflow = navigationOverflow
    navigationOverflow = null
    navigationFocus?.focus()
  }
})
watch(() => route.fullPath, () => { showMobileMenu.value = false; showRail.value = false })
function navigationKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); showMobileMenu.value = false }
  if (event.key !== 'Tab') return
  const controls = mobileNav.value?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href]')
  if (!controls?.length) return
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
function resizeNavigation() {
  if (window.innerWidth >= 900) showMobileMenu.value = false
  if (!railTrigger.value?.getClientRects().length) showRail.value = false
}
function closeRail(restoreFocus = false) {
  showRail.value = false
  if (restoreFocus) railTrigger.value?.focus()
}
async function toggleRail() {
  if (showRail.value) { closeRail(); return }
  showNotif.value = false
  showUserMenu.value = false
  showRail.value = true
  await nextTick()
  if (showRail.value) railSummary.value?.querySelector<HTMLButtonElement>('.rail-close')?.focus()
}
function dismissRail(event: PointerEvent) {
  if (showRail.value && event.target instanceof Node
      && !railSummary.value?.contains(event.target) && !railTrigger.value?.contains(event.target)) closeRail()
}
function railKey(event: KeyboardEvent) {
  if (showRail.value && event.key === 'Escape') {
    event.preventDefault()
    closeRail(true)
  }
}
const canOpenMyTickets = computed(() => routeAllowed(auth.roles, '/tickets', auth.permissions))
const canOpenMyRequests = computed(() => routeAllowed(auth.roles, '/assets', auth.permissions)
  && (auth.can('request.view.own') || auth.can('request.view.all')))
const ticketsToConfirm = ref(0)
const requestsToApprove = ref(0)

watch([showUserMenu, () => auth.user?.id, canOpenMyTickets, canOpenMyRequests],
  ([open, userId, showTickets, showRequests], _previous, onCleanup) => {
    ticketsToConfirm.value = 0
    requestsToApprove.value = 0
    if (!open || userId == null) return
    showNotif.value = false
    closeRail()
    let current = true
    onCleanup(() => { current = false })
    if (showTickets) {
      void ticketApi.list({ mine: true, status: 'PENDING_CONFIRM', page: 1, size: 1 }).then(page => {
        if (current) ticketsToConfirm.value = page.total
      }).catch(() => {})
    }
    if (showRequests) {
      void assetRequestApi.list({ mine: true, status: 'PENDING' }).then(rows => {
        if (current) requestsToApprove.value = rows.length
      }).catch(() => {})
    }
  })

/* ---------- 顶栏搜索（本地过滤，走真实工单数据） ---------- */
const kw = ref('')
const allTickets = ref<any[]>([])
const searchFocus = ref(false)
const searchResults = computed(() => {
  const k = kw.value.trim().toLowerCase()
  if (!k) return []
  return allTickets.value
    .filter((t) => `${t.no} ${t.title}`.toLowerCase().includes(k))
    .slice(0, 6)
})
function openTicket(no: string) {
  kw.value = ''
  searchFocus.value = false
  router.push(auth.can('ticket.view.all') && !personalPage.value ? `/admin/ticket/${no}` : `/ticket/${no}`)
}
function onSearchBlur() {
  window.setTimeout(() => (searchFocus.value = false), 180)
}

/* ---------- 右栏真实数据 ---------- */
const reqs = ref<any[]>([])
const kb = ref<any[]>([])
const ov = ref<any>({})
const engineers = ref<any[]>([])
const cats = ref<any[]>([])
const catsMeta = ref<Record<string, string[]>>({})
const codes = ref<any[]>([])
const syncText = ref('')
const syncing = ref(false)
const adminRuntime = ref<any>({})

async function loadBadges() {
  try {
    const [nv, t, ar] = await Promise.all([
      notifyApi.unread(),
      auth.can("ticket.view.own") || auth.can("ticket.view.all") ? ticketApi.list({ status: 'NEW', page: 1, size: 1 }) : Promise.resolve({ total: 0 }),
      auth.can('request.view.own') || auth.can('request.view.all') ? assetRequestApi.list({ status: 'PENDING' }) : Promise.resolve([])
    ])
    unread.value = nv?.count || 0
    badges.value.pending = t?.total || 0
    badges.value.approvals = Array.isArray(ar) ? ar.length : 0
  } catch {
    /* 角标失败不影响主流程 */
  }
}

async function loadRail() {
  try {
    if (personalPage.value || workRole.value === 'emp') {
      const [r, k] = await Promise.all([
        auth.can("request.view.own") || auth.can("request.view.all") ? assetRequestApi.list({ mine: true }) : Promise.resolve([]),
        auth.can("kb.view") ? kbApi.list({}) : Promise.resolve([])
      ])
      reqs.value = (Array.isArray(r) ? r : []).slice(0, 3)
      kb.value = (Array.isArray(k) ? k : []).slice(0, 3)
    } else if (workRole.value === 'it' || workRole.value === 'viewer' || workRole.value === 'admin') {
      const [o, e, c] = await Promise.all([
        auth.can("stats.view") ? statsApi.overview() : Promise.resolve({}),
        auth.can("stats.view") ? statsApi.engineers(30) : Promise.resolve([]),
        auth.can("stats.view") ? statsApi.category(30) : Promise.resolve([])
      ])
      ov.value = o || {}
      engineers.value = (Array.isArray(e) ? e : []).slice(0, 4)
      cats.value = (Array.isArray(c) ? c : []).slice(0, 3)
    } else {
      const [o, c, s, runtime] = await Promise.all([
        auth.can("stats.view") ? statsApi.overview() : Promise.resolve({}),
        metaApi.categories(),
        metaApi.solutionCodes(),
        auth.can("settings.manage") ? adminApi.settings() : Promise.resolve({})
      ])
      ov.value = o || {}
      catsMeta.value = c || {}
      codes.value = Array.isArray(s) ? s : []
      adminRuntime.value = runtime
    }
  } catch {
    /* 右栏降级为空，不阻塞主内容 */
  }
}

async function loadAll() {
  await Promise.all([loadBadges(), loadRail()])
  try {
    const r = auth.can("ticket.view.own") || auth.can("ticket.view.all") ? await ticketApi.list({ mine: personalPage.value || undefined, page: 1, size: 50 }) : { list: [] }
    allTickets.value = r?.list || []
  } catch {
    allTickets.value = []
  }
}

async function openNotif() {
  showNotif.value = true
  showUserMenu.value = false
  closeRail()
  try {
    notifies.value = await notifyApi.list()
  } catch {
    notifies.value = []
  }
}
async function readOne(n: Notification) {
  if (n.isRead) return
  try {
    await notifyApi.read(n.id)
    if (n.isRead) return
    n.isRead = 1
    unread.value = Math.max(0, unread.value - 1)
  } catch {
    toast('暂时无法标记该通知为已读')
  }
}
function openNotification(n: Notification) {
  showNotif.value = false
  void readOne(n)
}

async function switchUser(name: string) {
  showUserMenu.value = false
  try {
    await auth.loginAsDemo(name)
    await loadAll()
    router.push(homeForRoles(auth.roles, auth.permissions))
  } catch (e: any) { syncText.value = e.message; window.alert(e.message) }
}

async function trySync() {
  syncing.value = true
  try {
    const r = await adminApi.syncDingTalk('dry')
    syncText.value = `样例试跑：人员新增 ${r?.users?.created ?? 0} / 更新 ${r?.users?.updated ?? 0} / 停用 ${r?.disabled ?? 0}`
  } catch (e: any) {
    syncText.value = e?.message || '试跑失败'
  } finally {
    syncing.value = false
  }
}

function badgeOf(key?: string) {
  if (key === 'pending') return badges.value.pending
  if (key === 'approvals') return badges.value.approvals
  return 0
}
function isOn(path: string) {
  const [p, q] = path.split('?')
  if (route.path !== p) return false
  if (!q) return !route.query.tab && !route.query.scan
  return Object.entries(Object.fromEntries(new URLSearchParams(q))).every(
    ([k, v]) => String(route.query[k] ?? '') === v
  )
}
function tabOn(path: string) {
  return isOn(path)
}

const onAdminChange = () => { void loadRail() }
watch(() => auth.permissions.join(','), loadAll)
onMounted(() => {
  void loadAll()
  window.addEventListener('admin-data-changed', onAdminChange)
  window.addEventListener('resize', resizeNavigation)
  document.addEventListener('pointerdown', dismissRail)
  document.addEventListener('keydown', railKey)
})
onUnmounted(() => {
  window.removeEventListener('admin-data-changed', onAdminChange)
  window.removeEventListener('resize', resizeNavigation)
  document.removeEventListener('pointerdown', dismissRail)
  document.removeEventListener('keydown', railKey)
  if (navigationOverflow !== null) document.body.style.overflow = navigationOverflow
})
watch([workRole, personalPage], loadAll)
</script>

<template>
  <div class="shell">
    <!-- 顶栏：无横向导航，只有品牌 + 当前位置 + 全局工具 -->
    <header class="top">
      <button class="mobile-menu-button" aria-label="打开全部功能" :aria-expanded="showMobileMenu" @click="showMobileMenu = true">☰</button>
      <div class="logo">
        <img class="brand-ic" src="/icon-48.png" alt="" width="26" height="26" />
        <span>IT 服务台</span>
      </div>
      <div class="ctx">
        <span class="role-tag">{{ meta.label }}</span>
        <span class="sep">/</span>
        <b>{{ title }}</b>
      </div>

      <div class="tools">
        <div class="search">
          <span>⌕</span>
          <input
            v-model="kw"
            placeholder="搜索工单号、故障描述"
            @focus="searchFocus = true"
            @blur="onSearchBlur"
            @keydown.enter="searchResults.length && openTicket(searchResults[0].no)"
          />
          <div v-if="searchFocus && searchResults.length" class="search-pop">
            <div
              v-for="t in searchResults"
              :key="t.no"
              class="search-item"
              @mousedown.prevent="openTicket(t.no)"
            >
              <b>{{ t.title }}</b><span class="mono">{{ t.no }}</span>
            </div>
          </div>
        </div>
        <button ref="railTrigger" type="button" class="rail-trigger"
          :aria-expanded="showRail" aria-controls="workspace-summary" @click="toggleRail">辅助信息</button>
        <button v-if="hasTutorials" type="button" class="tutorial-trigger" aria-label="新手教程" title="新手教程"
          aria-haspopup="dialog" @click="openTutorials?.()">
          <span class="tutorial-icon" aria-hidden="true">?</span><span class="tutorial-label">新手教程</span>
        </button>
        <button class="bell" aria-label="通知" @click="openNotif">◔<i v-if="unread > 0" /></button>
      </div>
      <AccountBadge v-if="auth.user" :key="auth.user.id" v-model="showUserMenu" :name="auth.user.name"
        :role-label="meta.label" :department="auth.user.deptName">
        <template #shortcuts>
          <nav v-if="canOpenMyTickets || canOpenMyRequests" class="account-shortcuts" aria-label="账户快捷入口">
            <router-link v-if="canOpenMyTickets" to="/tickets" class="account-shortcut" @click="showUserMenu = false">
              <span class="account-shortcut-icon" aria-hidden="true">≡</span>
              <span class="account-shortcut-label">我的工单</span>
              <small v-if="ticketsToConfirm">{{ ticketsToConfirm }} 单待确认</small>
              <span class="account-shortcut-arrow" aria-hidden="true">›</span>
            </router-link>
            <router-link v-if="canOpenMyRequests" to="/assets?tab=requests" class="account-shortcut" @click="showUserMenu = false">
              <span class="account-shortcut-icon" aria-hidden="true">⇄</span>
              <span class="account-shortcut-label">我的申请</span>
              <small v-if="requestsToApprove">{{ requestsToApprove >= 200 ? '200+' : requestsToApprove }} 笔待审批</small>
              <span class="account-shortcut-arrow" aria-hidden="true">›</span>
            </router-link>
          </nav>
        </template>
        <template v-if="auth.config?.demoEnabled">
          <div class="pop-sub">演示身份</div>
          <button
            v-for="u in DEMO_USERS"
            :key="u.name"
            class="user-item"
            :class="{ on: auth.user.name === u.name }"
            @click="switchUser(u.name)"
          >
            <span>{{ u.name }}</span><small>{{ u.tag }}</small>
          </button>
        </template>
      </AccountBadge>

      <!-- 消息通知 -->
      <div v-if="showNotif" class="pop notif-pop">
        <div class="pop-h">消息通知 <button @click="showNotif = false">×</button></div>
        <div v-if="!notifies.length" class="empty">暂无消息</div>
        <div v-for="n in notifies" :key="n.id" class="notif">
          <div class="row"><b>{{ n.title }}</b><span class="sp" /><i v-if="!n.isRead" class="dot" /></div>
          <div class="small muted">{{ n.content }}</div>
          <div class="small muted">{{ n.createdAt }}</div>
          <router-link v-if="n.action" :to="n.action.path" class="btn sm notif-action" @click="openNotification(n)">{{ n.action.label }}</router-link>
          <button v-else-if="!n.isRead" type="button" class="link notif-action" @click="readOne(n)">标为已读</button>
        </div>
      </div>

    </header>

    <div class="layout">
      <!-- 侧栏：唯一主导航 -->
      <aside class="side">
        <button v-if="canFoldNav" type="button" class="nav-fold-all" @click="toggleAllGroups">
          {{ allFolded ? '展开全部分组' : '收起全部分组' }}
        </button>
        <template v-for="(g, i) in groups" :key="g.title">
          <button
            type="button"
            class="grp"
            :class="{ first: i === 0 && !canFoldNav, folded: !!folded[g.title], active: groupActive(g.items) }"
            :aria-expanded="canFoldNav ? !folded[g.title] : undefined"
            :title="canFoldNav ? (folded[g.title] ? '展开该分组' : '收起该分组') : g.title"
            @click="canFoldNav && toggleGroup(g.title)"
          >
            <span>{{ g.title }}</span>
            <span v-if="canFoldNav" class="gcount">{{ g.items.length }}</span>
            <span v-if="canFoldNav" class="chev" aria-hidden="true">›</span>
          </button>
          <div v-show="!canFoldNav || !folded[g.title]" class="grp-items">
            <button
              v-for="m in g.items"
              :key="m.path"
              class="mi"
              :class="{ on: isOn(m.path) }"
              @click="router.push(m.path)"
            >
              <span class="ic">{{ m.icon }}</span>
              <span>{{ m.label }}</span>
              <span v-if="badgeOf(m.badge)" class="n">{{ badgeOf(m.badge) }}</span>
            </button>
          </div>
        </template>
        <div class="me">
          <b>{{ auth.user?.name }}</b>
          <span>{{ auth.user?.deptName }}</span>
          <small v-if="workRole === 'emp'"></small>
          <small v-else-if="workRole === 'it' || workRole === 'viewer'">当日待受理 {{ badges.pending }} 单</small>
          <small v-else>管理更改已留痕</small>
        </div>
      </aside>

      <div class="work-area">
        <!-- 主栏 -->
        <main class="main">
          <div v-if="reportRestrictedPage && reportState.loadedFor !== auth.user?.id" class="empty" role="status">正在检查待补报事项…</div>
          <section v-else-if="reportRestrictedPage && reportState.summary?.blocked" class="card report-block">
            <h2>请先补报已完成事项</h2>
            <p>你有 {{ reportState.summary.pending }} 条事项待补报。待补报数量降至 {{ reportState.summary.limit }} 条及以下后，可恢复报修和资产服务。</p>
            <router-link class="btn pri" to="/missing-reports">查看并补报</router-link>
          </section>
          <template v-else>
            <div v-if="reportRestrictedPage && reportState.summary?.pending" class="card report-notice">
              <span class="pill b-orange">{{ reportState.summary.pending }} 条待补报</span>
              <router-link to="/missing-reports">查看已完成事项</router-link>
            </div>
            <router-view v-slot="{ Component }">
              <KeepAlive :max="1">
                <component :is="Component" v-if="!tutorialPractice" :key="route.matched[route.matched.length - 1]?.path" />
              </KeepAlive>
              <component :is="Component" v-if="tutorialPractice" :key="tutorialWorkflow || 'page-practice'" />
            </router-view>
          </template>
        </main>

        <!-- 右栏：不打断主任务的辅助信息，数据均来自真实接口 -->
        <aside class="rail" :class="{ 'home-rail': isHome }">
          <div id="workspace-summary" ref="railSummary" class="rail-summary" :class="{ 'is-open': showRail }" aria-label="辅助信息" role="region">
            <div class="rail-pop-header">
              <b>辅助信息</b>
              <button type="button" class="rail-close" aria-label="关闭辅助信息" @click="closeRail(true)">×</button>
            </div>
            <template v-if="personalPage || workRole === 'emp'">
              <div class="panel">
                <h4>我的申请</h4>
                <div v-if="!reqs.length" class="empty sm">暂无申请</div>
                <div v-for="r in reqs" :key="r.id" class="line">
                  <span>{{ REQ_TYPE[r.type]?.name || r.typeName || r.type }}</span>
                  <span v-if="r.status === 'PENDING' && r.dingInstanceId" class="tag">钉钉</span>
                  <span class="sp" />
                  <span class="tag" :class="r.status === 'APPROVED' || r.status === 'COMPLETED' ? 'g' : ''">
                    {{ REQ_STATUS_NAME[r.status] || r.statusName || r.status }}
                  </span>
                </div>
                <button class="link" @click="router.push('/assets?tab=requests')">查看全部 ›</button>
              </div>
              <div class="panel">
                <h4>常见问题</h4>
                <div v-if="!kb.length" class="empty sm">暂无知识条目</div>
                <button v-for="k in kb" :key="k.id" class="kb" @click="router.push('/kb')">
                  <span class="ic">▤</span>
                  <span><b>{{ k.title }}</b><small>{{ k.category }} · 浏览 {{ k.views || 0 }}</small></span>
                </button>
              </div>
            </template>

            <template v-else-if="workRole === 'it' || workRole === 'viewer' || workRole === 'admin'">
              <div class="panel center">
                <h4 class="left">SLA 达成率</h4>
                <div class="ring" :style="{ background: `conic-gradient(#0071e3 0 ${ov.slaRate || 0}%, #eef1f6 ${ov.slaRate || 0}% 100%)` }">
                  <i>{{ ov.slaRate || 0 }}%</i>
                </div>
                <p class="sub">近 30 天 {{ ov.total || 0 }} 单</p>
              </div>
              <div class="panel">
                <h4>工程师工作量</h4>
                <div v-if="!engineers.length" class="empty sm">暂无数据</div>
                <div v-for="e in engineers" :key="e.name" class="line">
                  <span>{{ e.name }}</span><span class="sp" /><span class="v">{{ e.total }} 单</span>
                </div>
              </div>
              <div class="panel">
                <h4>故障类型 TOP 3</h4>
                <div v-if="!cats.length" class="empty sm">暂无数据</div>
                <div v-for="c in cats" :key="c.name" class="line">
                  <span>{{ c.name }}</span><span class="sp" /><span class="v">{{ c.value ?? c.total ?? c.count ?? 0 }}</span>
                </div>
              </div>
            </template>

            <!-- <template v-else>
              <div class="panel">
                <h4>通讯录同步</h4>
                <div class="line"><span>工单总量</span><span class="sp" /><span class="v">{{ ov.total || 0 }} 单</span></div>
                <div class="line"><span>故障分类</span><span class="sp" /><span class="v">{{ Object.keys(catsMeta).length }} 类</span></div>
                <div class="line"><span>解决代码</span><span class="sp" /><span class="v">{{ codes.length }} 项</span></div>
                <div v-if="auth.can('contact.sync')" class="pills">
                  <button class="pri" :disabled="syncing" @click="trySync">{{ syncing ? '试跑中…' : '立即试跑' }}</button>
                </div>
                <p v-if="syncText" class="sub" style="margin-top: 10px; color: #0071e3">{{ syncText }}</p>
              </div>
              <div class="panel">
                <h4>运行参数</h4>
                <div class="line"><span>二维码基址</span><span class="sp" /><span class="v mono" style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" :title="adminRuntime.qrcodeBaseUrl">{{ adminRuntime.qrcodeBaseUrl || '—' }}</span></div>
                <div class="line"><span>统计缓存</span><span class="sp" /><span class="v">{{ adminRuntime.statsCacheSeconds === 0 ? '已关闭' : (adminRuntime.statsCacheSeconds ?? '—') + ' 秒' }}</span></div>
                <div class="line"><span>故障兜底热线</span><span class="sp" /><span class="v">内线 8300</span></div>
              </div>
              <div class="panel">
                <h4>配置提示</h4>
                <p class="sub">配置保存后生效。<br />SLA 时限的修改仅影响新建工单。</p>
              </div>
            </template> -->

          </div>
          <section v-if="isHome && contactRoster && (contactRoster.contacts.length || contactRoster.hotline)" class="panel service-contacts">
            <h4>IT 服务联系人</h4>
            <ul v-if="contactRoster.contacts.length" class="service-contact-list">
              <li v-for="(contact, index) in contactRoster.contacts" :key="index">
                <span>{{ contact.member.position }} {{ contact.member.name }}</span>
                <ContactLeaveStatus :contact="contact" />
              </li>
            </ul>
            <p v-if="contactRoster.hotline" class="small muted service-hotline">
              热线 <strong>{{ contactRoster.hotline }}</strong><span v-if="contactRoster.hotlineNote">（{{ contactRoster.hotlineNote }}）</span>
            </p>
          </section>
        </aside>
      </div>
    </div>

    <div v-if="showMobileMenu" class="mobile-nav-mask" @click.self="showMobileMenu = false" @keydown="navigationKey">
      <section ref="mobileNav" class="mobile-nav" role="dialog" aria-modal="true" aria-labelledby="mobile-nav-title">
        <div class="mobile-nav-head"><b id="mobile-nav-title">全部功能</b><button aria-label="关闭全部功能" @click="showMobileMenu = false">×</button></div>
        <div class="mobile-nav-account"><b>{{ auth.user?.name }}</b><span>{{ meta.label }}<template v-if="auth.user?.deptName"> · {{ auth.user.deptName }}</template></span></div>
        <div class="mobile-nav-groups">
          <template v-for="group in groups" :key="group.title">
            <h3>{{ group.title }}</h3>
            <button v-for="item in group.items" :key="item.path" class="mi" :class="{ on: isOn(item.path) }" @click="showMobileMenu = false; router.push(item.path)">
              <span class="ic">{{ item.icon }}</span><span>{{ item.label }}</span><span v-if="badgeOf(item.badge)" class="n">{{ badgeOf(item.badge) }}</span>
            </button>
          </template>
        </div>
      </section>
    </div>

    <!-- 手机端底部导航 -->
    <nav class="tabs" aria-label="手机底部导航">
      <button
        v-for="t in tabs"
        :key="t.path"
        :class="{ on: tabOn(t.path) }"
        :aria-current="tabOn(t.path) ? 'page' : undefined"
        @click="router.push(t.path)"
      >
        <span>{{ t.icon }}</span>{{ t.label }}
      </button>
    </nav>

    <div class="toast" :class="{ show: !!toastText }">{{ toastText }}</div>
  </div>
</template>

<style scoped>
.report-block { max-width:680px; padding:20px; }
.report-block p { margin:12px 0 16px; line-height:1.6; }
.report-notice { display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin-bottom:12px; }
.shell {
  --account-badge-width: 112px;
  min-height: 100vh;
  background: #f5f5f7;
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #1d1d1f;
}

/* ---------- 顶栏 ---------- */
.top {
  height: 64px;
  position: sticky;
  top: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  gap: 34px;
  padding: 0 40px;
  background: rgba(250, 250, 252, 0.78);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}
.logo { display: flex; align-items: center; gap: 9px; font-size: 15px; font-weight: 600; white-space: nowrap; }
.logo .brand-ic {
  width: 26px; height: 26px; border-radius: 8px; display: block;
  box-shadow: 0 2px 6px rgba(34, 112, 75, 0.22);
}
.logo i {
  width: 23px; height: 23px; border-radius: 7px; background: #1d1d1f; color: #fff;
  display: grid; place-items: center; font-size: 9px; font-weight: 700; font-style: normal;
}
.ctx { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #86868b; white-space: nowrap; }
.role-tag { font-size: 11px; font-weight: 600; color: #0071e3; background: #eef5ff; padding: 4px 11px; border-radius: 980px; }
.ctx .sep { color: #c7c7cc; }
.ctx b { color: #1d1d1f; font-weight: 600; font-size: 13.5px; }
.tools { margin-left: auto; display: flex; align-items: center; gap: 12px; position: relative; }
.tutorial-trigger { display:flex; align-items:center; justify-content:center; column-gap:5px; min-height:34px; padding:0 7px; border:0; border-radius:8px; background:none; color:#315a6c; font:inherit; font-size:12px; white-space:nowrap; cursor:pointer; }
.tutorial-trigger:hover { background:#eaf3ff; color:var(--brand); }
.tutorial-trigger:focus-visible { outline:2px solid var(--brand); outline-offset:1px; }
.tutorial-icon { display:grid; place-items:center; width:15px; height:15px; border:1px solid currentColor; border-radius:50%; font-weight:700; line-height:1; }
.search {
  width: 222px; height: 32px; border-radius: 980px; background: #ececee;
  display: flex; align-items: center; gap: 8px; padding: 0 13px; color: #86868b; position: relative;
}
.search input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent; font-size: 11.5px; color: #1d1d1f; }
.search input::placeholder { color: #a1a1a6; }
.search-pop {
  position: absolute; left: 0; right: 0; top: 38px; background: #fff; border-radius: 14px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.14); padding: 6px; z-index: 80;
}
.search-item { display: flex; flex-direction: column; gap: 2px; padding: 9px 11px; border-radius: 9px; cursor: pointer; }
.search-item:hover { background: #f2f5fa; }
.search-item b { font-size: 12px; font-weight: 600; }
.search-item .mono { font-size: 10px; color: #86868b; }
.bell {
  width: 30px; height: 30px; border-radius: 50%; border: 0; cursor: pointer;
  display: grid; place-items: center; position: relative; font-size: 14px;
}
.bell { background: #fff; color: #6e6e73; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05); }
.bell i { position: absolute; right: 1px; top: 1px; width: 7px; height: 7px; border-radius: 50%; background: #ff3b30; border: 1.5px solid #fff; }
.pop {
  position: absolute; top: 54px; right: 0; width: 300px; background: #fff; border-radius: 16px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.16); z-index: 90; overflow: hidden;
}
.pop-h { display: flex; align-items: center; padding: 13px 16px; font-size: 13px; font-weight: 700; border-bottom: 1px solid #f2f2f4; }
.pop-h button { margin-left: auto; border: 0; background: none; font-size: 18px; color: #86868b; cursor: pointer; line-height: 1; }
.notif-pop { max-height: 380px; overflow-y: auto; }
.notif { padding: 11px 16px; border-bottom: 1px solid #f2f2f4; font-size: 12.5px; }
.notif-action { margin-top: 6px; }
.notif:last-child { border-bottom: 0; }
.notif .dot { width: 7px; height: 7px; border-radius: 50%; background: #ff3b30; }
.pop-sub { padding: 8px 16px; font-size: 10px; letter-spacing: 0.1em; color: #a1a1a6; background: #fafafb; }
.user-item { width: 100%; display: flex; align-items: center; gap: 8px; padding: 10px 16px; border: 0; background: transparent; cursor: pointer; font-size: 12.5px; text-align: left; }
.user-item:hover { background: #f5f8ff; }
.user-item.on { color: #0071e3; font-weight: 600; }
.user-item small { margin-left: auto; color: #a1a1a6; font-size: 10.5px; }
.account-shortcuts { display:grid; gap:6px; padding:0 14px 12px; }
.account-shortcut { display:flex; align-items:center; gap:7px; padding:7px 9px; border:1px solid #e9edf3; border-radius:10px; color:inherit; text-decoration:none; }
.account-shortcut:hover { background:#f5f8ff; border-color:#d6e6fb; }
.account-shortcut:focus-visible { outline:2px solid var(--brand); outline-offset:1px; }
.account-shortcut-icon { display:grid; place-items:center; flex:none; width:24px; height:24px; border-radius:7px; background:#edf5ff; color:var(--brand); font-size:15px; }
.account-shortcut-label { flex:1; font-size:12px; font-weight:600; }
.account-shortcut small { color:#505d6c; font-size:10.5px; }
.account-shortcut-arrow { color:#687789; font-size:16px; line-height:1; }

/* ---------- 三栏栅格 ---------- */
.layout {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  gap: 30px;
  padding: 38px 40px 70px;
  max-width: min(1440px, calc(100% - var(--account-badge-width) - 14px));
  margin-left: auto;
  margin-right: max(calc(var(--account-badge-width) + 14px), calc((100% - 1440px) / 2));
  align-items: start;
}
.side { position: sticky; top: 100px; }
.nav-fold-all { display:block; width:100%; border:0; background:transparent; text-align:right; padding:0 14px 10px; font-family:inherit; font-size:10.5px; letter-spacing:.04em; color:#a1a1a6; cursor:pointer; }
.nav-fold-all:hover { color:#0071e3; }
.grp { display:flex; align-items:center; gap:6px; width:100%; border:0; background:transparent; font-family:inherit; text-align:left;
  font-size: 12.5px; font-weight: 600; letter-spacing: 0.02em; color: #48484c; padding: 0 14px 10px; cursor:default; }
.grp:not(.first) { margin-top: 20px; }
.side .grp[aria-expanded] { cursor:pointer; }
.side .grp[aria-expanded]:hover { color:#1d1d1f; }
.side .grp[aria-expanded]:hover .chev { color:#0071e3; }
.grp .gcount { font-size:10.5px; letter-spacing:0; color:#a3a3a9; font-weight:500; }
.grp .chev { margin-left:auto; font-size:13px; line-height:1; color:#b4b4ba; transform:rotate(90deg); transition:transform .15s ease; }
.grp.folded .chev { transform:rotate(0deg); }
.grp.active { color:#0071e3; }
.grp.active .gcount { color:#0071e3; }
.grp-items { display:block; }
.mi {
  width: 100%; display: flex; align-items: center; gap: 10px; padding: 9px 14px; border: 0;
  border-radius: 12px; font-size: 13.5px; color: #6e6e73; margin-bottom: 3px;
  background: transparent; text-align: left; cursor: pointer; font-family: inherit;
}
.mi:hover { background: rgba(0, 0, 0, 0.035); }
.mi.on { background: #fff; color: #0071e3; font-weight: 600; box-shadow: 0 2px 10px rgba(0, 0, 0, 0.04); }
.mi .ic { width: 17px; text-align: center; font-size: 14px; }
.mi .n { margin-left: auto; font-size: 10px; background: #ff3b30; color: #fff; min-width: 17px; height: 17px; border-radius: 9px; display: grid; place-items: center; padding: 0 4px; font-weight: 600; }
.side .me { margin: 22px 14px 0; padding-top: 16px; border-top: 1px solid #e8e8ed; display: flex; flex-direction: column; gap: 2px; font-size: 11.5px; color: #86868b; }
.side .me b { color: #1d1d1f; font-size: 12.5px; }
.side .me small { color: #0071e3; margin-top: 4px; }
.work-area { display:grid; grid-template-columns:minmax(0,1fr) 300px; align-items:start; gap:30px; min-width:0; }
.main { min-width: 0; }
.rail { position:sticky; top:100px; }
.rail,.rail-summary { display:flex; flex-direction:column; gap:16px; min-width:0; }
.rail-trigger,.rail-pop-header { display:none; }
.panel { background: #fff; border-radius: 22px; padding: 22px; box-shadow: 0 2px 14px rgba(0, 0, 0, 0.035); }
.panel h4 { font-size: 14px; font-weight: 600; margin: 0 0 5px; }
.panel h4.left { text-align: left; }
.panel .sub { font-size: 11.5px; color: #86868b; line-height: 1.65; margin: 0; }
.pills { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 15px; }
.pills button { border: 1px solid #d2d2d7; color: #0071e3; background: #fff; font-size: 12px; font-weight: 500; padding: 8px 16px; border-radius: 980px; }
.pills button.pri { background: #0071e3; border-color: #0071e3; color: #fff; }
.pills button:disabled { opacity: 0.55; }
.line { display: flex; align-items: center; gap: 9px; padding: 11px 0; border-bottom: 1px solid #f2f2f4; font-size: 12.5px; }
.line:last-child { border-bottom: 0; }
.line .v { font-size: 11.5px; color: #86868b; }
.tag { font-size: 10.5px; color: #0071e3; background: #eef5ff; padding: 2px 8px; border-radius: 980px; }
.tag.g { color: #1a8a4a; background: #e8f8ee; }
.link { margin-top: 10px; border: 0; background: none; color: #0071e3; font-size: 11.5px; padding: 0; }
.kb { width: 100%; display: flex; gap: 9px; padding: 10px 0; border: 0; border-bottom: 1px solid #f2f2f4; background: none; text-align: left; }
.kb:last-child { border-bottom: 0; }
.kb .ic { width: 26px; height: 26px; border-radius: 8px; background: #eef5ff; color: #0071e3; display: grid; place-items: center; font-size: 12px; flex: 0 0 26px; }
.kb b { font-size: 12.5px; font-weight: 500; display: block; }
.kb small { font-size: 10.5px; color: #86868b; display: block; margin-top: 2px; }
.empty.sm { padding: 14px 0; font-size: 11.5px; }
.center { text-align: center; }
.ring { width: 128px; height: 128px; border-radius: 50%; margin: 14px auto 12px; display: grid; place-items: center; }
.ring i { width: 98px; height: 98px; border-radius: 50%; background: #fff; display: grid; place-items: center; font-style: normal; font-size: 22px; font-weight: 600; color: #0071e3; }
.service-contacts { overflow-wrap: anywhere; }
.service-contact-list { list-style: none; padding: 0; margin: 10px 0 0; display: grid; gap: 8px; }
.service-contact-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; font-size: 13px; }
.service-hotline { margin: 10px 0 0; }
.service-hotline strong { color: var(--brand); }

.tabs, .mobile-menu-button { display: none; }
.mobile-nav-mask { position:fixed; inset:0; z-index:120; background:rgba(29,29,31,.4); display:flex; }
.mobile-nav { width: min(86vw, 340px); height:100%; display:flex; flex-direction:column; background:#fff; box-shadow:12px 0 40px rgba(0,0,0,.12); padding-top:env(safe-area-inset-top,0px); padding-bottom:env(safe-area-inset-bottom,0px); }
.mobile-nav-head { display:flex; align-items:center; justify-content:space-between; padding:12px 20px; border-bottom:1px solid var(--line); font-size:17px; }
.mobile-nav-head button { width:40px; height:40px; border:0; background:transparent; color:var(--t2); font-size:26px; }
.mobile-nav-account { display:grid; gap:4px; padding:18px 20px; background:#f6f9ff; font-size:15px; }
.mobile-nav-account span { font-size:12px; color:var(--t2); }
.mobile-nav-groups { min-height:0; overflow-y:auto; padding:6px 12px 22px; overscroll-behavior:contain; }
.mobile-nav-groups h3 { padding:18px 12px 8px; font-size:12px; color:var(--t3); }
.mobile-nav-groups .mi { min-height:44px; font-size:15px; }
.toast {
  position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%) translateY(10px);
  background: rgba(29, 33, 41, 0.92); color: #fff; padding: 10px 18px; border-radius: 980px;
  font-size: 12.5px; opacity: 0; pointer-events: none; transition: 0.2s; z-index: 200;
}
.toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }

/* 主栏内：页面自身的 PC 双栏收成单列，右栏由布局统一提供 */
.main :deep(.home),
.main :deep(.detail) { grid-template-columns: minmax(0, 1fr) !important; }
.main :deep(.home > .col-side) { display: none; }
.main :deep(.page-h) { margin-top: 0; }

/* 全页布局按屏幕宽度切换，兼容不支持容器查询的钉钉内嵌浏览器。 */
@media (max-width: 1199px) {
  .shell { --account-badge-width: 100px; }
  .search { width: 160px; }
}
@media (min-width:900px) and (max-width:1599px), (min-width:900px) and (max-height:849px) {
  .top { padding:0 26px; gap:20px; }
  .ctx { min-width:0; }
  .ctx b { min-width:0; overflow:hidden; text-overflow:ellipsis; }
  .role-tag { flex:none; }
  .layout { grid-template-columns:176px minmax(0,1fr); gap:20px; padding:24px 26px 20px; }
  .side { top:88px; max-height:calc(100vh - 108px); max-height:calc(100dvh - 108px); overflow-y:auto; scrollbar-width:thin; }
  .work-area { display:block; }
  .rail { position:static; }
  .rail-trigger {
    display:block; flex:none; padding:6px 11px; border:1px solid var(--line); border-radius:999px;
    background:#fff; color:#536277; font:inherit; font-size:12px; white-space:nowrap; cursor:pointer;
  }
  .rail-trigger:is(:hover,[aria-expanded=true]) { color:var(--brand); border-color:#b6d7fb; background:var(--brand-l); }
  .rail-summary { display:none; }
  .rail-summary.is-open {
    position:fixed; z-index:75; top:73px; right:calc(var(--account-badge-width) + 42px);
    display:flex; gap:10px; width:320px; max-height:calc(100vh - 89px); max-height:calc(100dvh - 89px);
    overflow:auto; overscroll-behavior:contain; padding:10px; border:1px solid var(--line);
    border-radius:18px; background:#f8f9fc; box-shadow:0 12px 32px #18284124;
  }
  .rail-pop-header { display:flex; align-items:center; justify-content:space-between; padding:0 4px 0 6px; font-size:13px; }
  .rail-close { width:28px; height:28px; border:0; border-radius:8px; background:transparent; color:var(--t2); font-size:19px; cursor:pointer; }
  .rail-close:hover { background:#e9eef6; }
  .rail-summary .panel { flex:none; border:1px solid var(--line2); border-radius:12px; padding:14px; box-shadow:none; }
  .rail-summary .ring { width:84px; height:84px; margin:9px auto; }
  .rail-summary .ring i { width:66px; height:66px; font-size:17px; }
  .rail-summary .line { padding:7px 0; }
  .rail .service-contacts { margin-top:16px; }
  .main :deep(.page-h), .main :deep(.page-h > .row), .main :deep(.filters) { flex-wrap:wrap; }
}
@media (max-width: 899px) {
  .shell { --account-badge-width: 48px; min-height:100vh; min-height:100dvh; }
  .top { height:calc(56px + env(safe-area-inset-top,0px)); padding: env(safe-area-inset-top,0px) 16px 0; gap:10px; background:rgba(255,255,255,.96); }
  .mobile-menu-button { display:grid; place-items:center; flex:0 0 36px; width:36px; height:40px; padding:0; border:0; background:transparent; color:var(--t1); font-size:23px; }
  .logo { gap:7px; font-size:15px; }
  .tools { gap:8px; }
  .tutorial-label { display:none; }
  .tutorial-trigger { width:32px; padding:0; }
  .tutorial-icon { width:17px; height:17px; }
  .bell { width:38px; height:38px; }
  .ctx, .search { display: none; }
  .layout { display: block; max-width:none; margin:0; padding: 0 0 calc(88px + env(safe-area-inset-bottom,0px)); }
  .side { display: none; }
  .work-area { display:block; }
  .main { padding: 32px 16px 0; }
  .rail { display: none; }
  .rail.home-rail { position:static; display:block; }
  .rail-summary { display:none; }
  .rail .service-contacts { margin:12px 16px 0; }
  .tabs {
    display: flex; position: fixed; left:0; right:0; bottom:0; height:calc(64px + env(safe-area-inset-bottom,0px)); padding-bottom:env(safe-area-inset-bottom,0px); z-index:70;
    background: rgba(255, 255, 255, 0.96); backdrop-filter: blur(18px);
    border-top: 1px solid rgba(0, 0, 0, 0.06);
  }
  .tabs button {
    flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
    min-width:0; gap:4px; font-size:11px; color:#86868b; border:0; background:none; font-family:inherit;
  }
  .tabs button span { font-size:21px; line-height:1; }
  .tabs button.on { color: #0071e3; font-weight: 600; }
  .pop { position:fixed; top:calc(62px + env(safe-area-inset-top,0px)); right:12px; left:12px; width:auto; max-height:calc(100vh - 150px); overflow-y:auto; }
  .toast { bottom:calc(84px + env(safe-area-inset-bottom,0px)); max-width:calc(100vw - 32px); text-align:center; }
  .main :deep(.page-h) { flex-wrap:wrap; gap:12px; }
  .main :deep(.page-h > .row) { flex:1 1 100%; min-width:0; }
  .main :deep(.page-h .inp) { width:100% !important; min-width:0; flex:1; }
  .main :deep(.kpis) { grid-template-columns: repeat(2, minmax(0, 1fr)) !important; }
  .main :deep(.grid2) { grid-template-columns: minmax(0, 1fr) !important; }
}
@media (max-width:350px) {
  .top { gap:8px; padding-inline:12px; }
  .tools { gap:5px; }
  .tutorial-trigger { width:29px; }
}
</style>
