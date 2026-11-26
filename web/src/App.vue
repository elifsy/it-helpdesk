<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref } from 'vue'
import { useAuthStore } from './stores/auth'
import router from './router'
import { canViewAllTickets, DEMO_USERS, homeForRoles, routeAllowed } from './config/menus'
import { authApi } from './api'
import { navigationFailure, reloadRoute } from './utils/navigation'
import FeatureTutorial from './components/FeatureTutorial.vue'
import { openTutorialPicker } from './config/tutorials'

const auth = useAuthStore()
const sessionView = computed(() => [auth.user?.id, auth.user?.role, canViewAllTickets(auth.roles, auth.permissions), auth.can('ticket.view.own')].join(':'))
const ready = ref(false)
const error = ref('')
const tutorial = ref<InstanceType<typeof FeatureTutorial>>()
provide(openTutorialPicker, () => tutorial.value?.openPicker())
let starting = false

async function start(expired = false) {
  if (starting) return
  starting = true
  ready.value = false
  error.value = ''
  if (expired) auth.logout()
  try {
    await auth.bootstrap()
    if (navigationFailure.value) throw new Error(navigationFailure.value.message)
    await router.isReady()
    if (!routeAllowed(auth.roles, router.currentRoute.value.fullPath, auth.permissions) || (router.currentRoute.value.path === '/home' && router.currentRoute.value.redirectedFrom?.path === '/' && auth.roles[0] !== 'USER'))
      await router.replace(homeForRoles(auth.roles, auth.permissions))
    ready.value = true
  } catch (e: any) { error.value = e.message || '登录失败，请重试' }
  finally { starting = false }
}
const onExpired = () => { void start(true) }
async function chooseDemo(name: string) {
  try { await auth.loginAsDemo(name); await start() }
  catch (e: any) { error.value = e.message }
}
let refreshing = false
async function refreshPermissions() {
  if (!ready.value || refreshing) return
  refreshing = true
  const token = auth.token
  try {
    const user = await authApi.me()
    if (token !== auth.token) return
    auth.user = user
    if (!routeAllowed(auth.roles, router.currentRoute.value.fullPath, auth.permissions)) await router.replace(homeForRoles(auth.roles, auth.permissions))
  } catch { /* 下次请求继续由后端校验 */ }
  finally { refreshing = false }
}
onMounted(() => { window.addEventListener('auth-expired', onExpired); window.addEventListener('permissions-changed', refreshPermissions); window.addEventListener('focus', refreshPermissions); void start() })
onUnmounted(() => { window.removeEventListener('auth-expired', onExpired); window.removeEventListener('permissions-changed', refreshPermissions); window.removeEventListener('focus', refreshPermissions) })
</script>

<template>
  <div v-if="!ready" class="boot">
    <img class="boot-logo" src="/icon-96.png?v=2" alt="IT 报修平台" width="72" height="72" />
    <div v-if="!error" class="spinner" />
    <p>{{ error ? (navigationFailure?.message || error) : '正在进入 IT 报修平台…' }}</p>
    <button v-if="error" class="btn pri" @click="navigationFailure ? reloadRoute(navigationFailure.path) : start()">重新尝试</button>
    <div v-if="error && auth.config?.demoEnabled" class="demo-choices">
      <button v-for="user in DEMO_USERS" :key="user.name" class="btn sm" @click="chooseDemo(user.name)">{{ user.name }} · {{ user.tag }}</button>
    </div>
  </div>
  <router-view v-else :key="sessionView" />
  <FeatureTutorial v-if="ready && auth.user && !auth.roles.includes('SUPER_ADMIN')" ref="tutorial" />
  <div v-if="ready && navigationFailure" class="navigation-error" role="alert">
    <span>{{ navigationFailure.message }}</span>
    <button class="btn sm pri" @click="reloadRoute(navigationFailure.path)">重新打开</button>
    <button class="btn sm" aria-label="关闭提示" @click="navigationFailure = null">×</button>
  </div>
</template>

<style scoped>
.navigation-error { position:fixed; z-index:210; top:76px; left:16px; right:16px; max-width:560px; margin:auto; display:flex; align-items:center; gap:10px; padding:14px; border-radius:14px; background:#fff; box-shadow:0 4px 24px #0002; font-size:13px; }
.navigation-error span { flex:1; }
.boot {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: #6e6e73;
  font-size: 13px;
  background: #f5f5f7;
}
.boot-logo {
  width: 72px;
  height: 72px;
  filter: drop-shadow(0 10px 18px rgba(0, 71, 151, 0.22));
}
.spinner {
  width: 26px;
  height: 26px;
  border: 3px solid #eef5ff;
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.demo-choices { display:flex; flex-wrap:wrap; justify-content:center; gap:8px; max-width:480px; }
@keyframes spin {
  to {
    transform: rotate(360deg)
  }
}
</style>
