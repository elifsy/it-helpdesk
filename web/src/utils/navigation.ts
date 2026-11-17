import { ref } from 'vue'

// 初次路由也可能在 App 挂载前失败，需要保留错误供启动页读取。
export const navigationFailure = ref<{ path: string; message: string } | null>(null)

export function reloadRoute(path: string) {
  if (!path.startsWith('/') || path.startsWith('//')) return
  window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search + '#' + path)
  window.location.reload()
}
