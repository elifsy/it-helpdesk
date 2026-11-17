<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi, authApi, metaApi } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { toast } from '../../utils/toast'
import RolePermissions from './RolePermissions.vue'
import { ROLE_NAMES, CONFIG_PERMISSIONS } from '../../config/menus'
import { TICKET_TYPES, type TicketCategoriesByType } from '../../config/ticket-types'

const route = useRoute()
const auth = useAuthStore()
const tab = computed(() => String(route.query.tab || ''))
const canViewSection = computed(() => (CONFIG_PERMISSIONS[tab.value] || []).some(auth.can))
const roleNames = ROLE_NAMES
const settings = ref<any>(null)
const categoryRows = ref<Record<string, { name: string; children: string }[]>>({})
const categoryType = ref('INCIDENT')
const currentCategoryRows = computed(() => categoryRows.value[categoryType.value] || [])
const baseline = ref('')
const snapshot = () => JSON.stringify({ settings: settings.value, categories: categoryRows.value })
const dirty = computed(() => !!settings.value && snapshot() !== baseline.value)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const users = ref<any[]>([])
const userTotal = ref(0)
const userPage = ref(1)
const keyword = ref('')
const status = ref('')
const departments = ref<any[]>([])
const engineers = ref<any[]>([])
const codes = ref<any[]>([])
const permissions = ref<any>({ roles: [], capabilities: [] })
const auditRows = ref<any[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditKeyword = ref('')
const storage = ref<any>({})
const jobs = ref<any>({ runs: [] })
const syncing = ref('')
const syncResult = ref<any>(null)
const dialog = ref<'' | 'user' | 'dept' | 'code'>('')
const edit = reactive<any>({})
let loadVersion = 0

function applySettings(value: any) {
  settings.value = value
  categoryRows.value = Object.fromEntries(TICKET_TYPES.map(type => [type.v,
    Object.entries(value.categoriesByType?.[type.v] || {}).map(([name, children]) => ({ name, children: (children as string[]).join('，') }))]))
  baseline.value = snapshot()
}
async function loadUsers() {
  const r = await adminApi.users({ keyword: keyword.value, status: status.value, page: userPage.value })
  users.value = r.list
  userTotal.value = r.total
}
async function loadAudit() {
  const r = await adminApi.audit({ keyword: auditKeyword.value, page: auditPage.value })
  auditRows.value = r.list
  auditTotal.value = r.total
}
async function loadSection() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    if (!settings.value) {
      if (auth.can("settings.manage")) applySettings(await adminApi.settings())
      else { settings.value = {}; baseline.value = snapshot() }
    }
    if (version !== loadVersion) return
    const section = tab.value
    if (section === 'users') {
      const [d, e] = await Promise.all([adminApi.departments(), metaApi.engineers(), loadUsers()])
      departments.value = d
      engineers.value = e
    } else if (section === 'depts') departments.value = auth.can('dept.manage') || auth.can('user.manage') ? await adminApi.departments() : []
    else if (section === 'matrix') { /* 独立权限配置组件负责读取与保存 */ }
    else if (section === 'dict') {
      if (!settings.value.categories) applySettings({ ...settings.value, ...await adminApi.dictionary() })
    }
    else if (section === 'audit') await loadAudit()
    else if (section === 'storage') storage.value = await adminApi.storage()
    else if (section === 'jobs' || !section) jobs.value = await adminApi.jobs()
  } catch (e: any) { if (version === loadVersion) error.value = e.message }
  finally { if (version === loadVersion) loading.value = false }
}
async function saveSettings() {
  saving.value = true
  error.value = ''
  try {
    const categoriesByType: TicketCategoriesByType = Object.create(null)
    for (const type of TICKET_TYPES) {
      const cats: Record<string, string[]> = Object.create(null)
      for (const row of categoryRows.value[type.v] || []) {
        const name = row.name.trim()
        if (!name || Object.prototype.hasOwnProperty.call(cats, name)) throw new Error(`${type.s}的分类名称不能为空或重复`)
        cats[name] = row.children.split(/[，,\n]/).map(s => s.trim()).filter(Boolean)
      }
      categoriesByType[type.v] = cats
    }
    if (auth.can('settings.manage')) applySettings(await adminApi.saveSettings({ ...settings.value, categoriesByType }))
    else applySettings({ ...settings.value, ...await adminApi.saveCategories({ revision: settings.value.revision, categoriesByType }) })
    window.dispatchEvent(new Event('admin-data-changed'))
    toast('配置已保存，后续请求和任务将使用新配置')
  } catch (e: any) { error.value = e.message }
  finally { saving.value = false }
}
async function reloadSettings() {
  if (dirty.value && !window.confirm('放弃未保存的配置并重新加载？')) return
  try { if (auth.can("settings.manage")) applySettings(await adminApi.settings()); await loadSection() }
  catch (e: any) { error.value = e.message }
}
function openEditor(kind: typeof dialog.value, row?: any) {
  Object.keys(edit).forEach(k => delete edit[k])
  if (kind === 'user') Object.assign(edit, row, {
    role: row.role || 'USER',
    deptId: row.deptId || 0, handoverId: '', skill: row.skill || '', location: row.location || ''
  })
  if (kind === 'dept') Object.assign(edit, { id: null, name: '', parentId: 0, sort: 0 }, row || {})
  if (kind === 'code') Object.assign(edit, { id: null, category: '', code: '', name: '', sort: 0 }, row || {})
  dialog.value = kind
}
async function saveEntity() {
  saving.value = true
  try {
    if (dialog.value === 'user') {
      await adminApi.updateUser(edit.id, {
        role: edit.role, status: edit.status, deptId: Number(edit.deptId), skill: edit.skill,
        location: edit.location, handoverId: edit.handoverId ? Number(edit.handoverId) : null
      })
      if (edit.id === auth.user?.id) auth.user = await authApi.me()
    } else if (dialog.value === 'dept') {
      await adminApi.saveDepartment(edit.id, { name: edit.name, parentId: Number(edit.parentId), sort: Number(edit.sort) })
    } else {
      await adminApi.saveSolution(edit.id, { category: edit.category, code: edit.code, name: edit.name, sort: Number(edit.sort) })
    }
    dialog.value = ''
    window.dispatchEvent(new Event('admin-data-changed'))
    toast('已保存')
    await loadSection()
  } catch (e: any) { toast(e.message) }
  finally { saving.value = false }
}
async function removeEntity(kind: 'dept' | 'code', row: any) {
  if (!window.confirm('确认删除“' + row.name + '”？')) return
  saving.value = true
  try {
    if (kind === 'dept') await adminApi.deleteDepartment(row.id)
    else await adminApi.deleteSolution(row.id)
    window.dispatchEvent(new Event('admin-data-changed'))
    toast('已删除')
    await loadSection()
  } catch (e: any) { toast(e.message) }
  finally { saving.value = false }
}
async function sync(mode: 'dry' | 'demo' | 'live') {
  if (mode !== 'dry' && !window.confirm(mode === 'demo' ? '将样例通讯录写入当前数据库，确认继续？' : '同步真实通讯录，并停用已离职人员，确认继续？')) return
  syncing.value = mode
  try {
    syncResult.value = await adminApi.syncDingTalk(mode)
    toast(mode === 'dry' ? '样例试跑完成，未写入数据' : '同步完成')
    if (mode !== 'dry') await loadSection()
  } catch (e: any) { toast(e.message) }
  finally { syncing.value = '' }
}
function deptName(id: number) { return departments.value.find(d => d.id === id)?.name || (id ? String(id) : '根目录') }
function jobRun(key: string) { return jobs.value.runs?.find((r: any) => (r.jobKey || r.job_key) === key) }
function formatBytes(bytes: number) { return bytes >= 1048576 ? (bytes / 1048576).toFixed(2) + ' MB' : Number(bytes || 0).toLocaleString() + ' B' }

onMounted(loadSection)
watch(tab, () => { dialog.value = ''; void loadSection() })
</script>

<template>
  <div class="management">
    <div class="page-head">
      <div><h2>系统管理</h2><p class="muted">组织、权限与服务运行配置</p></div>
      <button class="btn sm" :disabled="loading || saving" @click="reloadSettings">重新加载</button>
    </div>
    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <div v-if="dirty" class="save-bar">
      <span>有尚未保存的配置</span>
      <button class="btn pri sm" :disabled="saving || loading" @click="saveSettings">{{ saving ? '保存中…' : '保存配置' }}</button>
    </div>
    <div v-if="loading" class="empty" role="status">正在加载…</div>

    <template v-if="settings && !loading && canViewSection">
      <template v-if="!tab">
        <section class="panel">
          <h3>运行开关</h3>
          <label class="setting-row"><span>提报前知识库推荐</span><input v-model="settings.kbRecommendEnabled" type="checkbox" role="switch" /></label>
          <label class="setting-row"><span>超时工单定时扫描</span><input v-model="settings.slaScanEnabled" type="checkbox" role="switch" /></label>
          <label class="setting-row"><span>通讯录每日同步</span><input v-model="settings.contactSyncEnabled" type="checkbox" role="switch" /></label>
          <div class="setting-row"><span>登录方式</span><b>{{ auth.config?.demoEnabled ? '演示登录' : '钉钉免登' }}</b></div>
          <p class="hint">钉钉接入凭据与登录方式由部署配置控制。页面中的修改保存后生效。</p>
        </section>
        <section class="panel">
          <h3>基础参数</h3>
          <label class="field">二维码访问基址<input v-model="settings.qrcodeBaseUrl" class="inp" type="url" maxlength="255" /></label>
          <label class="field">统计缓存（秒，0 为关闭）<input v-model.number="settings.statsCacheSeconds" class="inp" type="number" min="0" max="300" /></label>
          <p class="hint">二维码基址需为员工手机可以访问的地址。当前配置版本：{{ settings.revision }}。</p>
        </section>
      </template>

      <section v-else-if="tab === 'users'" class="panel">
        <div class="section-head"><h3>用户与角色</h3><span class="muted">共 {{ userTotal }} 人</span></div>
        <form class="toolbar" @submit.prevent="userPage = 1; loadSection()">
          <input v-model="keyword" class="inp" placeholder="姓名或工号" aria-label="姓名或工号" />
          <select v-model="status" class="sel" aria-label="用户状态"><option value="">全部状态</option><option value="ACTIVE">在职</option><option value="DISABLED">停用</option></select>
          <button class="btn" type="submit">查询</button>
        </form>
        <p class="hint">人员由钉钉通讯录同步。停用或移除处理权限时，有未结工单的用户必须指定交接人；角色为单选，修改立即作用于后续请求。</p>
        <div class="table-wrap"><table class="tb">
          <thead><tr><th>姓名 / 工号</th><th>部门</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
          <tbody><tr v-for="u in users" :key="u.id">
            <td><b>{{ u.name }}</b><div class="muted small">{{ u.employeeNo || '—' }}</div></td>
            <td>{{ u.deptName || '未分配' }}</td>
            <td>{{ roleNames[u.role] || u.role }}</td>
            <td>{{ u.status === 'ACTIVE' ? '在职' : '停用' }}</td>
            <td><button class="btn sm" @click="openEditor('user', u)">编辑</button></td>
          </tr><tr v-if="!users.length"><td colspan="5" class="empty">没有匹配的用户</td></tr></tbody>
        </table></div>
        <div class="pagination"><button class="btn sm" :disabled="userPage <= 1" @click="userPage--; loadSection()">上一页</button><span>第 {{ userPage }} 页</span><button class="btn sm" :disabled="userPage * 20 >= userTotal" @click="userPage++; loadSection()">下一页</button></div>
      </section>

      <template v-else-if="tab === 'depts'">
        <section v-if="auth.can('dept.manage')" class="panel">
          <div class="section-head"><h3>部门</h3><button class="btn pri sm" @click="openEditor('dept')">新增部门</button></div>
          <p class="hint">钉钉同步的部门信息会在下次同步时更新。删除本地部门前需迁移成员和下级部门。</p>
          <div class="table-wrap"><table class="tb">
            <thead><tr><th>名称</th><th>上级</th><th>来源</th><th>排序</th><th>操作</th></tr></thead>
            <tbody><tr v-for="d in departments" :key="d.id">
              <td>{{ d.name }}</td><td>{{ deptName(d.parentId) }}</td><td>{{ d.dingDeptId ? '钉钉' : '本地' }}</td><td>{{ d.sort }}</td>
              <td class="actions"><button class="btn sm" @click="openEditor('dept', d)">编辑</button><button v-if="!d.dingDeptId" class="btn sm danger" :disabled="saving" @click="removeEntity('dept', d)">删除</button></td>
            </tr><tr v-if="!departments.length"><td colspan="5" class="empty">暂无部门</td></tr></tbody>
          </table></div>
        </section>
        <section v-if="auth.can('contact.sync')" class="panel">
          <h3>通讯录同步</h3>
          <p class="hint">真实同步更新姓名、部门、联系方式和在职状态，保留本地角色；离职人员的未结工单会转派。</p>
          <div class="toolbar">
            <button class="btn" :disabled="!!syncing" @click="sync('dry')">样例只读试跑</button>
            <button v-if="auth.config?.demoEnabled" class="btn" :disabled="!!syncing" @click="sync('demo')">写入样例通讯录</button>
            <button class="btn pri" :disabled="!!syncing || !!auth.config?.demoEnabled" @click="sync('live')">{{ syncing === 'live' ? '同步中…' : '同步真实通讯录' }}</button>
          </div>
          <div v-if="syncResult" class="sync-result">模式 {{ syncResult.mode }} · 部门新增 {{ syncResult.departments?.created }} · 人员新增 {{ syncResult.users?.created }}、更新 {{ syncResult.users?.updated }}、停用 {{ syncResult.disabled }} · 转派工单 {{ syncResult.transferredTickets }} 张</div>
        </section>
      </template>

      <RolePermissions v-else-if="tab === 'matrix'" />

      <section v-else-if="tab === 'sla'" class="panel">
        <h3>SLA 解决时限</h3><p class="hint">按自然小时计算。修改仅影响新建工单，已有工单保留原时限。</p>
        <div class="field-grid">
          <label v-for="p in [1, 2, 3, 4]" :key="p" class="field">P{{ p }}（小时）<input v-model.number="settings['p' + p + 'Hours']" class="inp" type="number" min="1" max="8760" /></label>
        </div>
        <p class="hint">需满足 P1 ≤ P2 ≤ P3 ≤ P4。信息安全类始终按 P1 处理。</p>
        <h3>自动定级矩阵</h3>
        <div class="table-wrap"><table class="tb">
          <thead><tr><th>工作影响 / 范围</th><th>本人</th><th>部门</th><th>整层</th><th>全公司</th></tr></thead>
          <tbody><tr><td>完全无法工作</td><td>P3</td><td>P2</td><td>P1</td><td>P1</td></tr><tr><td>能凑合</td><td>P4</td><td>P3</td><td>P2</td><td>P2</td></tr><tr><td>不影响</td><td>P4</td><td>P4</td><td>P3</td><td>P3</td></tr></tbody>
        </table></div>
      </section>

      <template v-else-if="tab === 'dict'">
        <section class="panel">
          <div class="section-head"><h3>问题分类</h3><button class="btn sm" @click="currentCategoryRows.push({ name: '', children: '' })">添加分类</button></div>
          <div class="toolbar" aria-label="按问题类型配置分类">
            <button v-for="type in TICKET_TYPES" :key="type.v" class="btn sm" :class="{ pri: categoryType === type.v }" :aria-pressed="categoryType === type.v" :data-category-type="type.v" @click="categoryType = type.v">{{ type.s }}</button>
          </div>
          <p class="hint">每种问题类型分别维护可选分类。子分类用逗号或换行分隔；故障报修中的信息安全分类必须保留。修改不会覆盖历史工单。</p>
          <div v-for="(row, index) in currentCategoryRows" :key="categoryType + index" class="category-row">
            <input v-model="row.name" class="inp" maxlength="32" placeholder="一级分类" aria-label="一级分类" />
            <textarea v-model="row.children" class="ta" placeholder="子分类，用逗号分隔" aria-label="子分类" />
            <button class="btn sm danger" :disabled="categoryType === 'INCIDENT' && row.name === '信息安全'" @click="currentCategoryRows.splice(index, 1)">移除</button>
          </div>
        </section>
      </template>

      <section v-else-if="tab === 'audit'" class="panel">
        <h3>审计日志</h3>
        <form class="toolbar" @submit.prevent="auditPage = 1; loadSection()"><input v-model="auditKeyword" class="inp" placeholder="操作人、动作或对象" aria-label="审计查询" /><button class="btn">查询</button></form>
        <div class="table-wrap"><table class="tb"><thead><tr><th>时间</th><th>操作人</th><th>动作 / 对象</th><th>详情</th></tr></thead>
          <tbody><tr v-for="a in auditRows" :key="a.id"><td class="nowrap">{{ a.createdAt || a.created_at }}</td><td>{{ a.actorName || a.actor_name }}</td><td><b>{{ a.action }}</b><div class="muted small">{{ a.target }}</div></td><td><details><summary>{{ (a.detail || '').slice(0, 55) }}{{ a.detail?.length > 55 ? '…' : '' }}</summary><p class="audit-detail">{{ a.detail }}</p></details></td></tr><tr v-if="!auditRows.length"><td colspan="4" class="empty">暂无审计记录</td></tr></tbody>
        </table></div>
        <div class="pagination"><button class="btn sm" :disabled="auditPage <= 1" @click="auditPage--; loadSection()">上一页</button><span>第 {{ auditPage }} 页 · 共 {{ auditTotal }} 条</span><button class="btn sm" :disabled="auditPage * 20 >= auditTotal" @click="auditPage++; loadSection()">下一页</button></div>
      </section>

      <section v-else-if="tab === 'storage'" class="panel">
        <h3>附件与存储</h3>
        <div class="storage-stats"><div><b>{{ storage.fileCount || 0 }}</b><span>已登记附件</span></div><div><b>{{ formatBytes(storage.totalBytes) }}</b><span>已登记文件大小</span></div></div>
        <p class="hint">存储方式：{{ storage.type }}。目录：<span class="mono">{{ storage.directory }}</span></p>
        <div class="field-grid">
          <label class="field">单个文件上限（MB，1–10）<input v-model.number="settings.maxUploadMb" class="inp" type="number" min="1" max="10" /></label>
          <label class="field">每个业务的附件上限（1–20）<input v-model.number="settings.maxAttachments" class="inp" type="number" min="1" max="20" /></label>
        </div>
        <p class="hint">支持 JPG、PNG、GIF、WebP 和 PDF。修改不会删除已有附件；所有文件访问都校验登录身份和业务权限。</p>
      </section>

      <section v-else-if="tab === 'jobs'" class="panel">
        <h3>定时任务</h3>
        <div v-for="job in [{ key: 'sla', label: '超时工单扫描', setting: 'slaScanEnabled', cron: jobs.slaCron }, { key: 'contacts', label: '通讯录每日同步', setting: 'contactSyncEnabled', cron: jobs.contactCron }]" :key="job.key" class="job">
          <label class="setting-row"><b>{{ job.label }}</b><input v-model="settings[job.setting]" type="checkbox" role="switch" /></label>
          <p class="hint">计划：<span class="mono">{{ job.cron }}</span></p>
          <p v-if="jobRun(job.key)" class="hint">最近执行：{{ jobRun(job.key).finishedAt || jobRun(job.key).finished_at }} · {{ jobRun(job.key).status === 'SUCCESS' ? '成功' : '失败' }} · {{ jobRun(job.key).message }}</p>
          <p v-else class="hint">暂无执行记录</p>
          <p v-if="job.key === 'contacts' && !jobs.dingTalkEnabled" class="hint">当前未启用钉钉接入，此任务会跳过。</p>
        </div>
        <p class="hint">开关保存后作用于下一次调度。通讯录手动同步入口位于“部门与通讯录”。</p>
      </section>
    </template>
    <div v-else-if="!loading && !canViewSection" class="empty">未找到该管理分区</div>

    <div v-if="dialog" class="mask" @click.self="!saving && (dialog = '')">
      <form class="dlg editor" @submit.prevent="saveEntity">
        <div class="dlg-h">{{ dialog === 'user' ? '编辑用户 · ' + edit.name : dialog === 'dept' ? '部门信息' : '解决代码' }}<button class="close" type="button" :disabled="saving" @click="dialog = ''">×</button></div>
        <div class="dlg-b">
          <template v-if="dialog === 'user'">
            <label class="field">角色<select v-model="edit.role" class="sel" required><option v-for="(label, role) in roleNames" :key="role" :value="role">{{ label }}</option></select></label>
            <label class="field">部门<select v-model="edit.deptId" class="sel"><option :value="0">未分配</option><option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
            <label class="field">状态<select v-model="edit.status" class="sel"><option value="ACTIVE">在职</option><option value="DISABLED">停用</option></select></label>
            <label class="field">未结工单交接给<select v-model="edit.handoverId" class="sel"><option value="">不交接（有未结工单时必须选择）</option><option v-for="e in engineers.filter(e => e.id !== edit.id)" :key="e.id" :value="e.id">{{ e.name }}</option></select></label>
            <label class="field">技能<input v-model="edit.skill" class="inp" maxlength="128" /></label>
            <label class="field">位置<input v-model="edit.location" class="inp" maxlength="64" /></label>
          </template>
          <template v-else-if="dialog === 'dept'">
            <label class="field">部门名称<input v-model="edit.name" class="inp" required maxlength="64" /></label>
            <label class="field">上级部门<select v-model="edit.parentId" class="sel"><option :value="0">根目录</option><option v-for="d in departments.filter(d => d.id !== edit.id)" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
            <label class="field">排序<input v-model.number="edit.sort" class="inp" type="number" min="0" max="9999" required /></label>
          </template>
          <template v-else>
            <label class="field">分类<input v-model="edit.category" class="inp" required maxlength="32" /></label>
            <label class="field">代码标识<input v-model="edit.code" class="inp" required maxlength="64" :readonly="!!edit.id" /></label>
            <label class="field">显示名称<input v-model="edit.name" class="inp" required maxlength="64" /></label>
            <label class="field">排序<input v-model.number="edit.sort" class="inp" type="number" min="0" max="9999" required /></label>
          </template>
        </div>
        <div class="dlg-f"><button class="btn" type="button" :disabled="saving" @click="dialog = ''">取消</button><button class="btn pri" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.page-head,.section-head,.toolbar,.save-bar,.pagination,.actions { display:flex; align-items:center; gap:10px; }
.page-head,.section-head { justify-content:space-between; margin-bottom:16px; }
h2 { font-size:26px; letter-spacing:-.03em; margin-bottom:5px; } h3 { font-size:16px; margin-bottom:12px; }
.panel { padding:20px; background:#fff; border-radius:16px; margin-bottom:16px; box-shadow:var(--shadow); }
.setting-row { display:flex; align-items:center; justify-content:space-between; padding:13px 0; border-bottom:1px solid var(--line2); gap:14px; font-size:13px; }
input[type=checkbox] { accent-color:#0071e3; width:18px; height:18px; cursor:pointer; }
.field { display:flex; flex-direction:column; gap:7px; margin:14px 0; font-size:13px; }
.field-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 18px; }
.hint { font-size:12px; color:var(--t2); line-height:1.8; margin:12px 0; overflow-wrap:anywhere; }
.toolbar { margin-bottom:14px; flex-wrap:wrap; }.toolbar .inp { flex:1; min-width:140px; }.toolbar .sel { width:auto; }
.save-bar { justify-content:space-between; background:#eaf3ff; border:1px solid #bcd8fa; border-radius:12px; padding:12px 16px; margin-bottom:16px; font-size:13px; }
.error { background:#fff0ee; color:#b32921; padding:14px; border-radius:10px; margin-bottom:14px; font-size:13px; }
.table-wrap { overflow-x:auto; }.tb th,.tb td { padding:11px 9px; font-size:12px; }.tb { min-width:540px; }
.pagination { justify-content:center; margin-top:16px; font-size:12px; }.danger { color:#b32921; }.nowrap { white-space:nowrap; }
.category-row { display:grid; grid-template-columns:120px minmax(0,1fr) auto; gap:9px; align-items:start; margin-top:10px; }.category-row .ta { min-height:60px; }
.matrix { min-width:760px; }.matrix td:not(:first-child),.matrix th:not(:first-child) { text-align:center; }.yes { color:#14813d; font-weight:700; }
.audit-detail { white-space:pre-wrap; overflow-wrap:anywhere; max-width:480px; margin:8px 0; }summary { cursor:pointer; }
.storage-stats { display:flex; gap:36px; padding:14px 0; }.storage-stats b { display:block; font-size:25px; }.storage-stats span { display:block; font-size:12px; color:var(--t2); margin-top:6px; }
.sync-result { background:#f3f6fa; padding:14px; border-radius:9px; font-size:12px; line-height:1.8; }.job { padding:8px 0 16px; }
.editor { max-width:560px; }.editor .dlg-b { max-height:65vh; overflow:auto; }.close { background:none; border:0; font-size:24px; cursor:pointer; float:right; }
fieldset { border:1px solid var(--line); padding:10px; border-radius:8px; }legend { padding:0 5px; font-size:13px; }.check { display:inline-flex; align-items:center; gap:7px; margin:6px 12px 6px 0; font-size:13px; }
@media(max-width:650px) { .field-grid { grid-template-columns:1fr; }.category-row { grid-template-columns:1fr auto; }.category-row .ta { grid-column:1 / -1; grid-row:2; }.panel { padding:15px; }.page-head { align-items:start; } }
</style>
