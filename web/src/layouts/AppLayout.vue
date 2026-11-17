<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  adminApi,
  authApi,
  assetRequestApi,
  kbApi,
  metaApi,
  notifyApi,
  statsApi,
  ticketApi
} from '../api'
import { useAuthStore } from '../stores/auth'
import {
  DEMO_USERS,
  MENUS,
  ROLE_META,
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

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const workRole = computed<WorkRole>(() => roleFromRoles(auth.roles))
const meta = computed(() => ROLE_META[workRole.value])
const groups = computed(() => MENUS[workRole.value].map(g => ({ ...g, items: g.items.filter(m => routeAllowed(auth.roles, m.path, auth.permissions) && (!m.permission || auth.can(m.permission))) })).filter(g => g.items.length))
const tabs = computed(() => TABS[workRole.value].filter(t => routeAllowed(auth.roles, t.path, auth.permissions)))
const title = computed(() => titleFor(workRole.value, route.fullPath))
const personalPage = computed(() => ['/home', '/new', '/tickets', '/assets', '/kb'].includes(route.path) || route.path.startsWith('/ticket/'))
const inDingTalk = isDingTalk()

/* ---------- 角标 / 通知 ---------- */
const badges = ref({ pending: 0, approvals: 0 })
const unread = ref(0)
const notifies = ref<any[]>([])
const showNotif = ref(false)
const showUserMenu = ref(false)
const showMobileMenu = ref(false)
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
watch(() => route.fullPath, () => { showMobileMenu.value = false })
function navigationKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); showMobileMenu.value = false }
  if (event.key !== 'Tab') return
  const controls = mobileNav.value?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href]')
  if (!controls?.length) return
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
function resizeNavigation() { if (window.innerWidth >= 900) showMobileMenu.value = false }
const copyAccountText = ref('')
const accountLoading = ref(false)
const accountError = ref('')

async function refreshAccount() {
  if (accountLoading.value) return
  accountLoading.value = true
  accountError.value = ''
  try { auth.user = await authApi.me() }
  catch (e: any) { accountError.value = e.message || '账号资料加载失败，请重试' }
  finally { accountLoading.value = false }
}

function toggleUserMenu() {
  showUserMenu.value = !showUserMenu.value
  if (showUserMenu.value) {
    copyAccountText.value = ''
    void refreshAccount()
  }
}

async function copyDingTalkId() {
  const id = auth.user?.dingUserId
  if (!id) return
  try {
    await navigator.clipboard.writeText(id)
    copyAccountText.value = ''
    toast('钉钉 UserId 已复制')
  } catch {
    copyAccountText.value = '请选中上方 UserId 并复制'
  }
}

function signInAgain() {
  auth.logout()
  window.location.reload()
}

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
const adminJobs = ref<any>({})

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
      const [o, c, s, runtime, jobs] = await Promise.all([
        auth.can("stats.view") ? statsApi.overview() : Promise.resolve({}),
        metaApi.categories(),
        metaApi.solutionCodes(),
        auth.can("settings.manage") ? adminApi.settings() : Promise.resolve({}),
        auth.can("settings.manage") ? adminApi.jobs() : Promise.resolve({})
      ])
      ov.value = o || {}
      catsMeta.value = c || {}
      codes.value = Array.isArray(s) ? s : []
      adminRuntime.value = runtime
      adminJobs.value = jobs
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
  try {
    notifies.value = await notifyApi.list()
  } catch {
    notifies.value = []
  }
}
async function readOne(n: any) {
  if (n.isRead) return
  n.isRead = 1
  unread.value = Math.max(0, unread.value - 1)
  await notifyApi.read(n.id)
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
onMounted(() => { void loadAll(); window.addEventListener('admin-data-changed', onAdminChange); window.addEventListener('resize', resizeNavigation) })
onUnmounted(() => {
  window.removeEventListener('admin-data-changed', onAdminChange)
  window.removeEventListener('resize', resizeNavigation)
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
        <button class="bell" @click="openNotif">◔<i v-if="unread > 0" /></button>
        <button class="avatar" aria-label="查看当前账号" :aria-expanded="showUserMenu" :style="{ background: meta.avatar, color: meta.color }" @click="toggleUserMenu">
          {{ auth.user?.name?.slice(0, 1) || meta.initial }}
        </button>
      </div>

      <!-- 消息通知 -->
      <div v-if="showNotif" class="pop notif-pop">
        <div class="pop-h">消息通知 <button @click="showNotif = false">×</button></div>
        <div v-if="!notifies.length" class="empty">暂无消息</div>
        <div v-for="n in notifies" :key="n.id" class="notif" @click="readOne(n)">
          <div class="row"><b>{{ n.title }}</b><span class="sp" /><i v-if="!n.isRead" class="dot" /></div>
          <div class="small muted">{{ n.content }}</div>
          <div class="small muted">{{ n.createdAt }}</div>
        </div>
      </div>

      <!-- 演示身份 / 工作区切换 -->
      <div v-if="showUserMenu" class="pop user-pop">
        <div class="pop-h">当前账号 <button aria-label="关闭账号菜单" @click="showUserMenu = false">×</button></div>
        <div class="me-line">
          <b>{{ auth.user?.name }}</b>
          <span>{{ auth.user?.deptName }}</span>
          <small>{{ (auth.roles || []).join(' · ') }}</small>
        </div>
        <div class="ding-account" :aria-busy="accountLoading">
          <span>钉钉 UserId</span>
          <template v-if="auth.user?.dingUserId">
            <code>{{ auth.user.dingUserId }}</code>
            <button class="link" @click="copyDingTalkId">复制 UserId</button>
          </template>
          <span v-else>{{ accountLoading ? '正在读取…' : '当前账号尚未关联钉钉' }}</span>
          <small v-if="copyAccountText">{{ copyAccountText }}</small>
          <div v-if="accountError" class="account-error" role="alert">
            <small>{{ accountError }}</small>
            <button class="link" :disabled="accountLoading" @click="refreshAccount">重新读取</button>
          </div>
        </div>
        <template v-if="auth.config?.demoEnabled">
          <div class="pop-sub">演示身份</div>
          <button
            v-for="u in DEMO_USERS"
            :key="u.name"
            class="user-item"
            :class="{ on: auth.user?.name === u.name }"
            @click="switchUser(u.name)"
          >
            <span>{{ u.name }}</span><small>{{ u.tag }}</small>
          </button>
        </template>
        <template v-else>
          <button class="user-item" @click="signInAgain">重新登录</button>
        </template>
      </div>
    </header>

    <div class="layout">
      <!-- 侧栏：唯一主导航 -->
      <aside class="side">
        <template v-for="(g, i) in groups" :key="g.title">
          <div class="grp" :class="{ first: i === 0 }">{{ g.title }}</div>
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
        </template>
        <div class="me">
          <b>{{ auth.user?.name }}</b>
          <span>{{ auth.user?.deptName }}</span>
          <small v-if="workRole === 'emp'">热线 内线 8300</small>
          <small v-else-if="workRole === 'it' || workRole === 'viewer'">当日待受理 {{ badges.pending }} 单</small>
          <small v-else>管理更改已留痕</small>
        </div>
      </aside>

      <!-- 主栏 -->
      <main class="main"><router-view /></main>

      <!-- 右栏：不打断主任务的辅助信息，数据均来自真实接口 -->
      <aside class="rail">
        <template v-if="personalPage || workRole === 'emp'">
          <div class="panel">
            <h4>我的申请</h4>
            <div v-if="!reqs.length" class="empty sm">暂无申请</div>
            <div v-for="r in reqs" :key="r.id" class="line">
              <span>{{ REQ_TYPE[r.type]?.name || r.typeName || r.type }}</span><span class="sp" />
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
            <p class="sub">{{ adminRuntime.contactSyncEnabled && adminJobs.dingTalkEnabled ? '自动同步已启用' : '自动同步未运行' }}；样例试跑不写库</p>
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

        <!-- <div class="hotline"><span>客服热线</span><b>内线 8300</b><small>系统故障时使用</small></div> -->
      </aside>
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
.shell {
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
.bell, .avatar {
  width: 30px; height: 30px; border-radius: 50%; border: 0; cursor: pointer;
  display: grid; place-items: center; position: relative; font-size: 14px;
}
.bell { background: #fff; color: #6e6e73; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05); }
.bell i { position: absolute; right: 1px; top: 1px; width: 7px; height: 7px; border-radius: 50%; background: #ff3b30; border: 1.5px solid #fff; }
.avatar { font-size: 11px; font-weight: 600; }
.pop {
  position: absolute; top: 54px; right: 0; width: 300px; background: #fff; border-radius: 16px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.16); z-index: 90; overflow: hidden;
}
.pop-h { display: flex; align-items: center; padding: 13px 16px; font-size: 13px; font-weight: 700; border-bottom: 1px solid #f2f2f4; }
.pop-h button { margin-left: auto; border: 0; background: none; font-size: 18px; color: #86868b; cursor: pointer; line-height: 1; }
.notif-pop { max-height: 380px; overflow-y: auto; }
.notif { padding: 11px 16px; border-bottom: 1px solid #f2f2f4; cursor: pointer; font-size: 12.5px; }
.notif:last-child { border-bottom: 0; }
.notif .dot { width: 7px; height: 7px; border-radius: 50%; background: #ff3b30; }
.me-line { padding: 13px 16px; display: flex; flex-direction: column; gap: 2px; font-size: 12px; }
.me-line span { color: #6e6e73; }
.me-line small { color: #a1a1a6; font-size: 10.5px; }
.pop-sub { padding: 8px 16px; font-size: 10px; letter-spacing: 0.1em; color: #a1a1a6; background: #fafafb; }
.user-item { width: 100%; display: flex; align-items: center; gap: 8px; padding: 10px 16px; border: 0; background: transparent; cursor: pointer; font-size: 12.5px; text-align: left; }
.user-item:hover { background: #f5f8ff; }
.user-item.on { color: #0071e3; font-weight: 600; }
.user-item small { margin-left: auto; color: #a1a1a6; font-size: 10.5px; }
.ding-account { display: grid; gap: 8px; padding: 12px 16px; border-top: 1px solid #eef0f4; font-size: 12px; }
.ding-account > span, .ding-account small { color: #6e6e73; }
.ding-account code { overflow-wrap: anywhere; user-select: text; font-size: 13px; line-height: 1.5; color: #1d1d1f; }
.ding-account .link { justify-self: start; }
.account-error { display: grid; gap: 6px; }

/* ---------- 三栏栅格 ---------- */
.layout {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr) 300px;
  gap: 30px;
  padding: 38px 40px 70px;
  max-width: 1440px;
  margin: 0 auto;
  align-items: start;
}
.side { position: sticky; top: 100px; }
.grp { font-size: 10.5px; letter-spacing: 0.12em; color: #a1a1a6; padding: 0 14px 9px; text-transform: uppercase; }
.grp:not(.first) { margin-top: 20px; }
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
.main { min-width: 0; }
.rail { position: sticky; top: 100px; display: flex; flex-direction: column; gap: 16px; }
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
.hotline { background: #fff; border-radius: 20px; padding: 16px 20px; display: flex; align-items: center; gap: 9px; box-shadow: 0 2px 14px rgba(0, 0, 0, 0.035); font-size: 11.5px; color: #86868b; }
.hotline b { color: #0071e3; font-size: 14px; }
.hotline small { margin-left: auto; font-size: 10px; }

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
  .top { padding: 0 26px; gap: 22px; }
  .search { width: 160px; }
  .layout { grid-template-columns: 188px minmax(0, 1fr); gap: 24px; padding: 30px 26px 60px; }
  .rail { grid-column: 2; position: static; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 6px; align-items: start; }
}
@media (max-width: 899px) {
  .shell { min-height:100vh; min-height:100dvh; }
  .top { height:calc(56px + env(safe-area-inset-top,0px)); padding: env(safe-area-inset-top,0px) 16px 0; gap:10px; background:rgba(255,255,255,.96); }
  .mobile-menu-button { display:grid; place-items:center; flex:0 0 36px; width:36px; height:40px; padding:0; border:0; background:transparent; color:var(--t1); font-size:23px; }
  .logo { gap:7px; font-size:15px; }
  .tools { gap:8px; }
  .bell,.avatar { width:38px; height:38px; }
  .avatar { font-size:14px; }
  .ctx, .search { display: none; }
  .layout { display: block; padding: 0 0 calc(88px + env(safe-area-inset-bottom,0px)); }
  .side { display: none; }
  .main { padding: 20px 16px 0; }
  .rail { display: none; }
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
</style>
