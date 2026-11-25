import axios from 'axios'
import { isPracticeRead, tutorialPractice, tutorialWriteMessage } from '../utils/tutorialPractice'

export const TOKEN_KEY = 'it_helpdesk_token'
export const DEMO_KEY = 'it_helpdesk_demo_user'

export class ApiError extends Error {
  constructor(message: string, public code?: number) { super(message) }
}

const http = axios.create({
  baseURL: '/api',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  if (tutorialPractice.value) {
    if (!isPracticeRead(config.method)) throw new ApiError(tutorialWriteMessage)
    config.headers.set('X-Tutorial-Practice', '1')
  }
  const token = localStorage.getItem(TOKEN_KEY)
  if (token && !['/auth/login', '/auth/config'].includes(config.url || '')) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const demo = localStorage.getItem(DEMO_KEY)
  if (demo) {
    config.headers['X-Demo-User'] = demo
  }
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        return Promise.reject(new ApiError(body.message || '请求失败', body.code))
      }
      return body.data
    }
    return body
  },
  (err) => {
    const msg = err?.response?.data?.message || err?.message || '网络异常'
    const code = err?.response?.data?.code || err?.response?.status
    if (code === 403 && !err?.config?.url?.startsWith('/auth/')) window.dispatchEvent(new Event('permissions-changed'))
    if (code === 401 && !err?.config?.url?.startsWith('/auth/')) {
      window.dispatchEvent(new Event('auth-expired'))
    }
    return Promise.reject(new ApiError(msg, code))
  }
)

export default http
