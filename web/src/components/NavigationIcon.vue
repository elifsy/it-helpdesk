<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ destination: string }>()
const path = computed(() => props.destination.split('?')[0])
const configTab = computed(() => new URLSearchParams(props.destination.split('?')[1]).get('tab') || '')
</script>

<template>
  <svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.8"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <g v-if="path === '/home'">
      <path class="surface" d="M5.5 14.5 14 6.7a3 3 0 0 1 4 0l8.5 7.8v10A3.5 3.5 0 0 1 23 28H9a3.5 3.5 0 0 1-3.5-3.5z" />
      <path d="M13 28v-7a3 3 0 0 1 6 0v7" />
    </g>
    <g v-else-if="path === '/new'">
      <path class="surface" d="M21.5 5.6a7 7 0 0 0-8.6 8.6l-7 7a3.5 3.5 0 1 0 5 5l7-7a7 7 0 0 0 8.6-8.6l-4 4-3.4-.8-.8-3.4z" />
      <path d="m8.8 23.4.2-.2" />
    </g>
    <g v-else-if="path === '/tickets'">
      <path class="surface" d="M8.5 4.5h10l6.5 6.5v13.5a3 3 0 0 1-3 3H8.5a3 3 0 0 1-3-3v-17a3 3 0 0 1 3-3Z" />
      <path d="M18.5 4.7v4.5a2 2 0 0 0 2 2H25M10.5 16h9M10.5 21h6.5" />
    </g>
    <g v-else-if="path === '/assets'">
      <rect class="surface" x="4.5" y="5.5" width="23" height="16.5" rx="3.5" />
      <path d="M5 17h22M16 22v5m-5 0h10" />
    </g>
    <g v-else-if="path === '/kb'">
      <path class="surface" d="M16 8.6c-3.2-3.2-7.3-3.5-10.5-2.3a2 2 0 0 0-1.3 1.9v14.6c0 1 1 1.6 1.9 1.3 3.7-1.1 7.2-.8 9.9 2 2.7-2.8 6.2-3.1 9.9-2 .9.3 1.9-.3 1.9-1.3V8.2a2 2 0 0 0-1.3-1.9C23.3 5.1 19.2 5.4 16 8.6Z" />
      <path d="M16 8.6v17.5M8.5 12.5c1.6-.3 3.1 0 4.3.8m6.4 0c1.2-.8 2.7-1.1 4.3-.8" />
    </g>
    <g v-else-if="path === '/admin/dashboard'">
      <rect class="surface" x="5" y="5" width="22" height="22" rx="4" />
      <path d="M10.5 20.5v-4m5.5 4v-8m5.5 8v-11" />
    </g>
    <g v-else-if="path === '/admin/tickets'">
      <rect class="surface" x="6.5" y="7.5" width="19" height="20" rx="3.5" />
      <rect class="overlay" x="11.5" y="4" width="9" height="6.5" rx="2" />
      <path d="M12 16h8m-8 5h6" />
    </g>
    <g v-else-if="path === '/admin/missing-reports' || path === '/missing-reports'">
      <path class="surface" d="M10 4.5h8l6.5 6.5v13.5a3 3 0 0 1-3 3H10a3 3 0 0 1-3-3v-17a3 3 0 0 1 3-3Z" />
      <path d="M18 5v4a2 2 0 0 0 2 2h4m-8 4v8m-4-4h8" />
    </g>
    <g v-else-if="path === '/admin/assets'">
      <rect class="surface" x="4.5" y="5" width="23" height="22" rx="3.5" />
      <path d="M4.5 16h23m-16-6h9m-9 11h9" />
    </g>
    <g v-else-if="path === '/admin/approvals'">
      <rect class="surface" x="6.5" y="7.5" width="19" height="20" rx="3.5" />
      <rect class="overlay" x="11.5" y="4" width="9" height="6.5" rx="2" />
      <path d="m11.5 19 3 3 6-6" />
    </g>
    <g v-else-if="path === '/admin/purchase'">
      <path class="surface" d="M7.5 10.5h17l2 13.5a3 3 0 0 1-3 3.5h-15a3 3 0 0 1-3-3.5Z" />
      <path d="M12.5 12v-4a3.5 3.5 0 0 1 7 0v4m-3.5 4v7m-3.5-3.5h7" />
    </g>
    <g v-else-if="path === '/admin/stats'">
      <rect class="surface" x="5" y="5" width="22" height="22" rx="4" />
      <path d="m9.5 21 4.5-5 4.2 2.5 4.3-6.5" />
    </g>
    <g v-else-if="path === '/admin/kb'">
      <path class="surface" d="M8.5 4.5h10l6.5 6.5v13.5a3 3 0 0 1-3 3H8.5a3 3 0 0 1-3-3v-17a3 3 0 0 1 3-3Z" />
      <path d="M18.5 5v4a2 2 0 0 0 2 2H25m-14 6h4m-4 4h2" />
      <path class="overlay" d="m17 22 1-4 7-7 3 3-7 7Z" />
    </g>
    <g v-else-if="path === '/admin/ding-approval'">
      <path d="M11 10h4a3 3 0 0 1 3 3v7.5m0-7a3.5 3.5 0 0 1 3.5-3.5H23" />
      <rect class="surface" x="4" y="7" width="7" height="6" rx="2" />
      <rect class="surface" x="23" y="7" width="5" height="6" rx="1.7" />
      <rect class="surface" x="14" y="21" width="8" height="6" rx="2" />
    </g>
    <g v-else-if="path === '/admin/incoming-notifications'">
      <path class="surface" d="M16 5.5a7 7 0 0 0-7 7V17a5 5 0 0 1-2 4l-1.5 1.5h21L25 21a5 5 0 0 1-2-4v-4.5a7 7 0 0 0-7-7Z" />
      <path d="M13 25a3 3 0 0 0 6 0M16 3.8v1.7" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'users'">
      <circle class="surface" cx="13" cy="10" r="4.5" />
      <path class="surface" d="M5 25.5v-2a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v2Z" />
      <path d="M21.5 6.8a3.8 3.8 0 0 1 0 7.5m2 3.7a4.5 4.5 0 0 1 4 4.5v3" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'depts'">
      <rect class="surface" x="12" y="5" width="8" height="6" rx="2" />
      <path d="M16 11v6m-9 4v-2a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2m-9-4v4" />
      <rect class="surface" x="4" y="21" width="6" height="6" rx="1.5" />
      <rect class="surface" x="13" y="21" width="6" height="6" rx="1.5" />
      <rect class="surface" x="22" y="21" width="6" height="6" rx="1.5" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'duty'">
      <path d="M6.5 18v-2a9.5 9.5 0 0 1 19 0v2m0 5.5V25a3 3 0 0 1-3 3H17" />
      <rect class="surface" x="4.5" y="16" width="6" height="8" rx="2.4" />
      <rect class="surface" x="21.5" y="16" width="6" height="8" rx="2.4" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'sla'">
      <circle class="surface" cx="16" cy="18" r="9.5" />
      <path d="M13 4.5h6m-3 0v4m6.5 2 2-2M16 13v5l3 2.5" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'dict'">
      <rect class="surface" x="7" y="5" width="19" height="22" rx="3" />
      <path d="M11 5v22m4-15h6m-6 5h6m-6 5h4M6 10H4m2 6H4m2 6H4" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'audit'">
      <rect class="surface" x="7" y="5.5" width="18" height="21" rx="3" />
      <path d="M11.5 11h9m-9 5h9m-9 5h5" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'storage'">
      <path class="surface" d="M5 9a3 3 0 0 1 3-3h5l3.5 3h7.5a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3Z" />
      <path d="M5 14h22m-16 5h6" />
    </g>
    <g v-else-if="path === '/admin/config' && configTab === 'jobs'">
      <circle class="surface" cx="16" cy="16" r="11" />
      <path d="M16 9.5V16l4 3m-4-14v2m0 18v2m-11-11h2m18 0h2" />
    </g>
    <g v-else-if="path === '/admin/config' && !configTab">
      <path class="surface" d="m13 5-.8 3-2 1.2-3-.7-2.5 4.3 2.1 2.2v2.3l-2.1 2.2 2.5 4.3 3-.7 2 1.2.8 3h5l.8-3 2-1.2 3 .7 2.5-4.3-2.1-2.2V15l2.1-2.2-2.5-4.3-3 .7-2-1.2-.8-3Z" />
      <circle class="overlay" cx="15.5" cy="16.2" r="3.8" />
    </g>
    <g v-else>
      <rect class="surface" x="5" y="5" width="22" height="22" rx="4" />
      <path d="M9.5 12.5h13m-13 7h13" />
      <circle class="overlay" cx="14" cy="12.5" r="2.2" />
      <circle class="overlay" cx="19" cy="19.5" r="2.2" />
    </g>
  </svg>
</template>

<style scoped>
svg { display:block; width:30px; height:30px; }
.surface { fill:var(--tab-fill, transparent); transition:fill .24s ease-out; }
.overlay { fill:var(--tab-detail, #fff); transition:fill .24s ease-out; }
@media (prefers-reduced-motion:reduce) {
  .surface, .overlay { transition:none; }
}
</style>
