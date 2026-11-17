import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { homeForRoles, routeAllowed } from '../config/menus'
import { assetOperationPath, legacyAssetEntry, parseAssetNo } from '../utils/asset-links'
import { navigationFailure, reloadRoute } from '../utils/navigation'

const legacyAsset = legacyAssetEntry(window.location.href)
if (legacyAsset) window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search + '#' + legacyAsset)

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/home' },
        { path: 'home', component: () => import('../views/employee/Home.vue') },
        { path: 'new', component: () => import('../views/employee/NewTicket.vue') },
        { path: 'tickets', component: () => import('../views/employee/MyTickets.vue') },
        { path: 'ticket/:no', component: () => import('../views/TicketDetail.vue') },
        { path: 'assets', component: () => import('../views/employee/Assets.vue') },
        { path: 'kb', component: () => import('../views/employee/Kb.vue') }
      ]
    },
    {
      path: '/admin',
      component: () => import('../layouts/AppLayout.vue'),
      children: [
        { path: '', redirect: '/admin/dashboard' },
        { path: 'dashboard', component: () => import('../views/admin/Dashboard.vue') },
        { path: 'tickets', component: () => import('../views/admin/TicketList.vue') },
        { path: 'ticket/:no', component: () => import('../views/TicketDetail.vue') },
        { path: 'assets', component: () => import('../views/admin/AssetList.vue') },
        { path: 'assets/:no', component: () => import('../views/admin/AssetDetail.vue') },
        { path: 'approvals', component: () => import('../views/admin/Approvals.vue') },
        { path: 'stats', component: () => import('../views/admin/Stats.vue') },
        { path: 'kb', component: () => import('../views/admin/KbAdmin.vue') },
        { path: 'config', component: () => import('../views/admin/Config.vue') }
      ]
    }
  ],
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to) => {
  if (to.path.startsWith('/asset=')) {
    const no = parseAssetNo('#' + to.path.slice(1) + to.fullPath.slice(to.path.length))
    if (no) return assetOperationPath(no)
    return '/home'
  }
  const auth = useAuthStore()
  if (!auth.user) return true
  if (!routeAllowed(auth.roles, to.fullPath, auth.permissions)) return homeForRoles(auth.roles, auth.permissions)
  return true
})

router.onError((error, to) => {
  if (/Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Loading chunk .* failed|Unable to preload CSS/i.test(error.message)) {
    try {
      const key = 'it_helpdesk_navigation_reload'
      const previous = JSON.parse(sessionStorage.getItem(key) || 'null')
      if (previous?.path !== to.fullPath || Date.now() - previous.at > 30000) {
        sessionStorage.setItem(key, JSON.stringify({ path: to.fullPath, at: Date.now() }))
        reloadRoute(to.fullPath)
        return
      }
    } catch { /* 无法自动恢复时，保留目标并显示重试入口。 */ }
  }
  navigationFailure.value = { path: to.fullPath, message: '页面暂时未能打开，请点击重新打开' }
})
router.afterEach((_to, _from, failure) => { if (!failure) navigationFailure.value = null })

export default router
