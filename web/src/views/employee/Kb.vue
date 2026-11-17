<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { kbApi } from '../../api'
import { toast } from '../../utils/toast'
import { useAuthStore } from '../../stores/auth'
import { useRouter } from 'vue-router'
import KnowledgeSearch from '../../components/KnowledgeSearch.vue'
const auth = useAuthStore()
const router = useRouter()

const keyword = ref('')
const category = ref('')
const list = ref<any[]>([])
const current = ref<any>(null)
const loading = ref(false)
let loadVersion = 0

const cats = computed(() => Array.from(new Set(list.value.map((k) => k.category))))

async function load() {
  const version = ++loadVersion
  loading.value = true
  try {
    const result = await kbApi.list({ keyword: keyword.value.trim() || undefined, category: category.value || undefined })
    if (version === loadVersion) list.value = result
  } catch (e: any) {
    if (version === loadVersion) toast(e.message)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function open(k: any) {
  try {
    current.value = await kbApi.detail(k.id)
  } catch (e: any) {
    toast(e.message)
  }
}

async function vote(helpful: boolean) {
  if (!current.value) return
  await kbApi.vote(current.value.id, helpful)
  toast(helpful ? '感谢反馈' : '已记录，我们会补充内容')
  current.value = null
}

onMounted(load)
onUnmounted(() => { loadVersion++ })
</script>

<template>
  <div>
    <div v-if="auth.can('kb.manage')" class="row" style="justify-content:flex-end;margin-bottom:10px">
      <button class="btn sm" @click="router.push('/admin/kb')">知识管理</button>
    </div>
    <KnowledgeSearch v-model="keyword" :category="category" placeholder="搜索：WiFi / 打印机 / VPN" @search="load" />

    <div class="cats">
      <span :class="{ on: !category }" @click="category = ''; load()">全部</span>
      <span v-for="c in cats" :key="c" :class="{ on: category === c }" @click="category = c; load()">{{ c }}</span>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!list.length" class="empty">没有匹配的知识条目</div>
    <div class="list">
      <div v-for="k in list" :key="k.id" class="wo" @click="open(k)">
        <div class="tt">{{ k.title }}</div>
        <div class="small muted" style="margin-top: 3px">{{ k.summary }}</div>
        <div class="small muted" style="margin-top: 5px">
          {{ k.category }} · 浏览 {{ k.views }} · 有帮助 {{ k.helpful }}
        </div>
      </div>
    </div>

    <div v-if="current" class="mask" @click.self="current = null">
      <div class="dlg">
        <div class="dlg-h">{{ current.title }}<span class="x" @click="current = null">×</span></div>
        <div class="dlg-b">
          <div class="small muted" style="margin-bottom: 10px">{{ current.category }} · {{ current.summary }}</div>
          <div style="white-space: pre-wrap; font-size: 13.5px; line-height: 1.9">{{ current.content }}</div>
        </div>
        <div v-if="auth.can('kb.vote')" class="dlg-f">
          <button class="btn" @click="vote(false)">没解决</button>
          <button class="btn pri" @click="vote(true)">已解决</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cats {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  padding: 12px 0;
  white-space: nowrap;
}
.cats span {
  padding: 4px 11px;
  border-radius: 20px;
  background: #fff;
  font-size: 12.5px;
  color: var(--t2);
  cursor: pointer;
  box-shadow: var(--shadow);
}
.cats span.on {
  background: var(--brand);
  color: #fff;
}
.wo {
  background: #fff;
  border-radius: var(--r);
  padding: 12px 14px;
  margin-bottom: 8px;
  cursor: pointer;
  box-shadow: var(--shadow);
}
.wo .tt {
  font-size: 13.5px;
  font-weight: 600;
}
@media (min-width: 900px) {
  .row {
    max-width: 560px;
  }
  .list {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
  .wo {
    margin-bottom: 0;
    padding: 16px 18px;
  }
  .wo .tt {
    font-size: 14px;
  }
}
</style>
