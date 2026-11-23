<script setup lang="ts">
/**
 * 设备线稿图标：按 key 渲染 64×64 的 SVG，颜色跟随 currentColor。
 * 所有可选的 key 见 utils/deviceIcons.ts，卡片底色由外层的 .art-xxx 控制。
 */
import { computed } from 'vue'

const props = defineProps<{ name?: string | null }>()
const key = computed(() => props.name || 'generic')
</script>

<template>
  <svg viewBox="0 0 64 64" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <!-- 笔记本 -->
    <g v-if="key === 'laptop'">
      <rect x="15" y="11" width="34" height="26" rx="3" />
      <path d="M15 37 8 50h48l-7-13" />
      <path d="M27 44h10" />
    </g>
    <!-- 台式机 -->
    <g v-else-if="key === 'desktop'">
      <rect x="7" y="12" width="31" height="22" rx="3" />
      <path d="M7 40h31M19 34v6m-7 0h14" />
      <rect x="44" y="12" width="13" height="34" rx="3" />
      <path d="M49 19h3m-3 5h3m-3 5h3M48 39h5" />
    </g>
    <!-- 显示器 -->
    <g v-else-if="key === 'monitor'">
      <rect x="8" y="10" width="48" height="32" rx="4" />
      <path d="M25 53h14M32 42v11" />
    </g>
    <!-- 投影仪 -->
    <g v-else-if="key === 'projector'">
      <rect x="8" y="21" width="33" height="22" rx="4" />
      <circle cx="19" cy="32" r="4" />
      <path d="M41 26l12-7v26l-12-7M12 49h26" />
    </g>
    <!-- 键盘 -->
    <g v-else-if="key === 'keyboard'">
      <rect x="6" y="22" width="52" height="24" rx="4" />
      <path d="M12 29h3m5 0h3m5 0h3m5 0h3m5 0h3M12 35h3m5 0h3m5 0h3m5 0h3m5 0h3M21 41h22" />
    </g>
    <!-- 鼠标 -->
    <g v-else-if="key === 'mouse'">
      <rect x="22" y="9" width="20" height="36" rx="10" />
      <path d="M32 9v11M22 21h20" />
      <path d="M26 51h12" />
    </g>
    <!-- 耳机 -->
    <g v-else-if="key === 'headset'">
      <path d="M13 35v-5a19 19 0 0 1 38 0v5" />
      <rect x="8" y="33" width="11" height="15" rx="4" />
      <rect x="45" y="33" width="11" height="15" rx="4" />
      <path d="M45 48v4a7 7 0 0 1-7 7h-6" />
    </g>
    <!-- 打印机 -->
    <g v-else-if="key === 'printer'">
      <rect x="11" y="25" width="42" height="21" rx="4" />
      <path d="M18 25V11h28v14M20 37h24v15H20zM43 30h3M25 43h14m-14 5h10" />
    </g>
    <!-- 服务器 -->
    <g v-else-if="key === 'server'">
      <rect x="15" y="8" width="34" height="48" rx="5" />
      <path d="M15 24h34M15 40h34m-27-8h2m-2-16h2m-2 32h2m7-32h10m-10 16h10m-10 16h10" />
    </g>
    <!-- 网络设备 -->
    <g v-else-if="key === 'network'">
      <rect x="9" y="27" width="46" height="21" rx="5" />
      <path d="M17 27V12m30 15V12M18 39h2m7 0h2m7 0h2m7 0h2M24 19a12 12 0 0 1 16 0m-12 5a6 6 0 0 1 8 0" />
    </g>
    <!-- 手机 -->
    <g v-else-if="key === 'phone'">
      <rect x="21" y="7" width="22" height="50" rx="5" />
      <path d="M28 13h8M29 50h6" />
    </g>
    <!-- 平板 -->
    <g v-else-if="key === 'tablet'">
      <rect x="13" y="7" width="38" height="50" rx="4" />
      <path d="M28 50h8" />
    </g>
    <!-- 其他设备 -->
    <g v-else>
      <path d="M12 22 32 12l20 10v21L32 53 12 43V22Z" />
      <path d="M12 22l20 10 20-10M32 32v21" />
    </g>
  </svg>
</template>

<style scoped>
svg { width: 100%; height: 100%; display: block; }
</style>
