import { defineStore } from 'pinia'
import { authApi, type LoginResult } from '../api'
import { ApiError, DEMO_KEY, TOKEN_KEY } from '../api/http'
import { isDingTalk, requestAuthCode } from '../utils/dingtalk'

interface AuthState {
  token: string
  user: LoginResult | null
  loading: boolean
  config: { demoEnabled: boolean; corpId: string } | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null,
    loading: false,
    config: null
  }),
  getters: {
    roles: (s) => s.user?.roles || [],
    permissions: (s) => s.user?.permissions || [],
    can: (s) => (permission: string) => (s.user?.permissions || []).includes(permission),
    canManage: (s) => (s.user?.permissions || []).includes('role.manage'),
    canCreateAsset: (s) => (s.user?.permissions || []).includes('asset.create'),
    isEmployeeOnly: (s) => !(s.user?.permissions || []).includes('ticket.view.all'),
    isIt: (s) => (s.user?.permissions || []).some(r => ['ticket.handle', 'ticket.dispatch'].includes(r)),
    canApprove: (s) => (s.user?.permissions || []).includes('request.approve')
  },
  actions: {
    async bootstrap() {
      this.loading = true
      try {
        this.config = await authApi.config()
        if (this.token) {
          try {
            this.user = await authApi.me()
            return
          } catch (e) {
            if (!(e instanceof ApiError) || e.code !== 401) throw e
            this.logout()
          }
        }
        if (this.config.demoEnabled) {
          await this.loginAsDemo(localStorage.getItem(DEMO_KEY) || '张伟')
        } else if (isDingTalk()) {
          const code = await requestAuthCode(this.config.corpId)
          await this.applyLogin({ authCode: code })
        } else {
          throw new Error('请在钉钉工作台中打开此应用')
        }
      } finally {
        this.loading = false
      }
    },
    async loginAsDemo(name: string) {
      if (!this.config?.demoEnabled) throw new Error('当前环境未启用演示登录')
      await this.applyLogin({ demoUser: name })
      localStorage.setItem(DEMO_KEY, name)
    },
    async applyLogin(payload: { authCode?: string; demoUser?: string }) {
      const res = await authApi.login(payload)
      this.token = res.token
      this.user = res
      localStorage.setItem(TOKEN_KEY, res.token)
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
