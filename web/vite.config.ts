import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const env = (globalThis as any).process?.env || {}
// 后端地址可用环境变量覆盖，8080 被占用时改这里即可
const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'
const webPort = Number(env.VITE_WEB_PORT || 5173)

export default defineConfig({
  plugins: [vue()],
  base: './',
  server: {
    port: webPort,
    strictPort: true,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    // 保留旧的哈希资源，已经打开的页面更新后仍能加载详情模块。
    emptyOutDir: false,
    chunkSizeWarningLimit: 1800
  }
})
