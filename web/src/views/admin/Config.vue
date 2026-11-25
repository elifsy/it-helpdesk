<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi, assetCategoryApi, authApi, metaApi, serviceContactApi, type JobSettings, type JobStatus, type ServiceContactMember, type ServiceContactPerson, type ServiceContactRoster } from '../../api'
import ContactLeaveStatus from '../../components/ContactLeaveStatus.vue'
import BadgeTitleSetting from '../../components/BadgeTitleSetting.vue'
import PersonPicker from '../../components/PersonPicker.vue'
import type { PersonOption } from '../../utils/people-search'
import { useAuthStore } from '../../stores/auth'
import { toast } from '../../utils/toast'
import RolePermissions from './RolePermissions.vue'
import { ROLE_NAMES, routeAllowed } from '../../config/menus'
import { TICKET_TYPES, type TicketCategoriesByType } from '../../config/ticket-types'

const route = useRoute()
const auth = useAuthStore()
const tab = computed(() => String(route.query.tab || ''))
const canViewSection = computed(() => routeAllowed(auth.roles, route.fullPath, auth.permissions))
const roleNames = ROLE_NAMES
const isSuperAdmin = computed(() => auth.roles.includes('SUPER_ADMIN'))
const roleOptions = computed(() => Object.entries(roleNames).filter(([role]) => isSuperAdmin.value || role !== 'SUPER_ADMIN'))
const settings = ref<any>(null)
const contactRoster = ref<ServiceContactRoster>()
const contactError = ref('')
const contactLoading = ref(false)
let contactLoadVersion = 0
const categoryRows = ref<Record<string, { name: string; children: string }[]>>({})
const categoryType = ref('INCIDENT')
const selectedHoliday = ref('')
const weekLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const currentCategoryRows = computed(() => categoryRows.value[categoryType.value] || [])
const baseline = ref('')
const snapshot = () => JSON.stringify({ settings: settings.value, categories: categoryRows.value })
const settingsDirty = computed(() => !!settings.value && snapshot() !== baseline.value)
const dirty = computed(() => tab.value === 'jobs' ? jobDirty.value : settingsDirty.value)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const users = ref<any[]>([])
const userTotal = ref(0)
const userPage = ref(1)
const keyword = ref('')
const status = ref('')
const departments = ref<any[]>([])
const engineers = ref<PersonOption[]>([])
const codes = ref<any[]>([])
const permissions = ref<any>({ roles: [], capabilities: [] })
const auditRows = ref<any[]>([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditKeyword = ref('')
const storage = ref<any>({})
const jobs = ref<JobStatus>()
const jobDraft = ref<JobSettings>()
const jobDirty = computed(() => !!jobDraft.value && JSON.stringify(jobDraft.value) !== JSON.stringify(jobs.value?.settings))
const jobRows = [
  { key: 'sla', label: '超时工单扫描', enabled: 'slaScanEnabled', cron: 'slaCron', nextRun: 'slaNextRun' },
  { key: 'contacts', label: '通讯录同步', enabled: 'contactSyncEnabled', cron: 'contactCron', nextRun: 'contactNextRun' }
] as const
const scanIntervals = [1, 5, 10, 15, 20, 30, 60].map(minutes => ({
  label: minutes === 60 ? '每小时整点' : '每 ' + minutes + ' 分钟',
  cron: minutes === 60 ? '0 0 * * * ?' : '0 */' + minutes + ' * * * ?'
}))
const scanInterval = computed(() => scanIntervals.find(item => item.cron === jobDraft.value?.slaCron)?.cron ?? '')
const contactTime = computed(() => {
  const match = /^0 (\d{1,2}) (\d{1,2}) \* \* [*?]$/.exec(jobDraft.value?.contactCron || '')
  return match ? [match[2], match[1]].map(part => part.padStart(2, '0')).join(':') : ''
})
const syncing = ref('')
const syncResult = ref<any>(null)
const dialog = ref<'' | 'user' | 'dept' | 'code'>('')
const edit = reactive<any>({})
const handoverEngineers = computed(() => engineers.value.filter(engineer => engineer.id !== edit.id))
let loadVersion = 0

const handoverCats = ref<{ id: number; name: string }[]>([])
const HANDOVER_TYPES = [
  { v: 'RECEIVE', s: '资产领用', stage: '待领取' },
  { v: 'BORROW', s: '资产借用', stage: '待领取' },
  { v: 'REPLACE', s: '资产更换', stage: '先待归还旧机，再待领取新机' },
  { v: 'RETURN', s: '归还设备', stage: '待归还' }
]

/** 某业务下某个分类是否勾选为「需要线下交接」 */
function hasHandover(type: string, name: string) {
  return (settings.value?.handoverCategories?.[type] || []).includes(name)
}
function toggleHandover(type: string, name: string) {
  if (!auth.can('settings.manage')) return
  if (!settings.value.handoverCategories) settings.value.handoverCategories = {}
  const list: string[] = settings.value.handoverCategories[type] || []
  settings.value.handoverCategories[type] = list.includes(name)
    ? list.filter((x) => x !== name) : [...list, name]
}

function applySettings(value: any) {
  if (value && !value.approvalProcessCodes) value.approvalProcessCodes = { RECEIVE: '', BORROW: '', REPLACE: '', RETURN: '' }
  if (value && !value.handoverCategories) value.handoverCategories = { RECEIVE: [], BORROW: [], REPLACE: [], RETURN: [] }
  if (value && !value.slaCalendar) value.slaCalendar = { weekdays: [1,2,3,4,5,6], start: '08:00', end: '17:00', holidays: [], pauseForInfo: true, responseHours: [1,2,4,8] }
  settings.value = value
  categoryRows.value = Object.fromEntries(TICKET_TYPES.map(type => [type.v,
    Object.entries(value.categoriesByType?.[type.v] || {}).map(([name, children]) => ({ name, children: (children as string[]).join('，') }))]))
  baseline.value = snapshot()
}

function addHoliday() {
  const date = selectedHoliday.value
  const holidays = settings.value.slaCalendar.holidays as string[]
  if (date && !holidays.includes(date)) settings.value.slaCalendar.holidays = [...holidays, date].sort()
  selectedHoliday.value = ''
}

async function refreshContacts() {
  const version = ++contactLoadVersion
  contactLoading.value = true
  contactError.value = ''
  try {
    const roster = await serviceContactApi.roster()
    if (version === contactLoadVersion) contactRoster.value = roster
  } catch (e: any) {
    if (version === contactLoadVersion) contactError.value = e.message || '请假状态暂不可查'
  } finally {
    if (version === contactLoadVersion) contactLoading.value = false
  }
}

function contactStatus(member: ServiceContactMember) {
  return contactRoster.value?.contacts.find(contact => contact.member.name === member.name
    && (contact.member.userId ?? null) === (member.userId ?? null))
}

function renameContact(member: ServiceContactMember, name: string) {
  if (member.name === name) return
  member.name = name
  member.userId = null
}

function bindContact(member: ServiceContactMember, person: ServiceContactPerson) {
  member.name = person.name
  member.userId = person.id
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
    if (!canViewSection.value) return
    if (tab.value === 'jobs') {
      const value = await adminApi.jobs()
      if (version === loadVersion) applyJobs(value)
      return
    }
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
    } else if (section === 'duty') void refreshContacts()
    else if (section === 'depts') departments.value = auth.can('dept.manage') || auth.can('user.manage') ? await adminApi.departments() : []
    else if (section === 'matrix') { /* 独立权限配置组件负责读取与保存 */ }
    else if (section === 'dict') {
      if (!settings.value.categories) applySettings({ ...settings.value, ...await adminApi.dictionary() })
    }
    else if (section === 'audit') await loadAudit()
    else if (section === 'storage') storage.value = await adminApi.storage()
  } catch (e: any) { if (version === loadVersion) error.value = e.message }
  finally { if (version === loadVersion) loading.value = false }
}
function applyJobs(value: JobStatus) {
  jobs.value = value
  jobDraft.value = { ...value.settings }
  if (!settingsDirty.value) settings.value = null
}
function changeScanInterval(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (jobDraft.value && value) jobDraft.value.slaCron = value
}
function changeContactTime(event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (!jobDraft.value || !value) return
  const [hours, minutes] = value.split(':').map(Number)
  jobDraft.value.contactCron = '0 ' + minutes + ' ' + hours + ' * * ?'
}
async function saveSettings() {
  saving.value = true
  error.value = ''
  try {
    if (tab.value === 'jobs') {
      if (!jobDraft.value || !isSuperAdmin.value) return
      const form = { ...jobDraft.value, slaCron: jobDraft.value.slaCron.trim(), contactCron: jobDraft.value.contactCron.trim() }
      if (!form.slaCron || !form.contactCron) throw new Error('请填写两个任务的 Cron 表达式')
      if (![form.slaReminderHours, form.confirmationReminderHours].every(hours => Number.isInteger(hours) && hours >= 1 && hours <= 720))
        throw new Error('两个提醒间隔均需填写 1–720 的整数小时')
      applyJobs(await adminApi.saveJobs(form))
      toast('定时任务配置已保存')
      return
    }
    if (settings.value.duty?.members.some((member: { position: string; name: string }) => !member.position || !member.name))
      throw new Error('请填写每一行的岗位和人员姓名，或移除空白行')
    const calendar = settings.value.slaCalendar
    if (calendar && (!calendar.weekdays.length || !calendar.start || !calendar.end || calendar.start >= calendar.end))
      throw new Error('请选择至少一个工作日，并设置有效的起止时间')
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
    if (tab.value === 'duty') void refreshContacts()
    toast('配置已保存，后续请求和任务将使用新配置')
  } catch (e: any) { error.value = e.message }
  finally { saving.value = false }
}
async function reloadSettings() {
  if (dirty.value && !window.confirm('放弃未保存的配置并重新加载？')) return
  try { if (tab.value !== 'jobs' && auth.can('settings.manage')) applySettings(await adminApi.settings()); await loadSection() }
  catch (e: any) { error.value = e.message }
}
function openEditor(kind: typeof dialog.value, row?: any) {
  Object.keys(edit).forEach(k => delete edit[k])
  if (kind === 'user') Object.assign(edit, row, {
    role: row.role || 'USER',
    deptId: row.deptId || 0, handoverId: null, skill: row.skill || '', location: row.location || ''
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
const jobRuns = computed(() => Object.fromEntries((jobs.value?.runs || []).map(run => [run.jobKey || run.job_key, run])))
function formatNextRun(value: string) {
  return new Date(value).toLocaleString('zh-CN', { timeZone: jobs.value?.timezone, hourCycle: 'h23' })
}
function formatBytes(bytes: number) { return bytes >= 1048576 ? (bytes / 1048576).toFixed(2) + ' MB' : Number(bytes || 0).toLocaleString() + ' B' }

onMounted(async () => {
  await loadSection()
  try { handoverCats.value = await assetCategoryApi.list() } catch { /* 无资产查看权限时忽略 */ }
})
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

    <template v-if="(settings || tab === 'jobs') && !loading && canViewSection">
      <template v-if="!tab">
        <section class="panel">
          <h3>运行开关</h3>
          <label class="setting-row"><span>提报前知识库推荐</span><input v-model="settings.kbRecommendEnabled" type="checkbox" role="switch" /></label>
          <BadgeTitleSetting v-if="isSuperAdmin" />
          <div class="setting-row"><span>登录方式</span><b>{{ auth.config?.demoEnabled ? '演示登录' : '钉钉免登' }}</b></div>
          <p class="hint">钉钉接入凭据与登录方式由部署配置控制。页面中的修改保存后生效。</p>
        </section>
        <section class="panel">
          <h3>基础参数</h3>
          <label class="field">二维码访问基址<input v-model="settings.qrcodeBaseUrl" class="inp" type="url" maxlength="255" /></label>
          <label class="field">统计缓存（秒，0 为关闭）<input v-model.number="settings.statsCacheSeconds" class="inp" type="number" min="0" max="300" /></label>
          <p class="hint">二维码基址需为员工手机可以访问的地址。当前配置版本：{{ settings.revision }}。</p>
        </section>
        <section class="panel">
          <div class="section-head"><h3>资产交接确认</h3></div>
          <p class="hint" style="margin:0 0 12px">
            勾选的分类：审批通过后先进入「待领取 / 待归还」，由 IT 或管理员在「资产申请」页确认交接，才真正改资产归属。
            不勾选的分类保持原逻辑——审批通过即执行。
          </p>
          <div v-for="t in HANDOVER_TYPES" :key="t.v" class="handover-row">
            <div class="hr-name">{{ t.s }}<small>{{ t.stage }}</small></div>
            <div class="hr-cats">
              <button v-for="c in handoverCats" :key="c.id" type="button" class="cat-chip"
                :class="{ on: hasHandover(t.v, c.name) }" :disabled="!auth.can('settings.manage')"
                @click="toggleHandover(t.v, c.name)">{{ c.name }}</button>
              <span v-if="!handoverCats.length" class="small muted">暂无可选资产分类</span>
            </div>
          </div>
          <p class="hint" style="margin:8px 0 0">一个都不勾＝该业务全部走「审批通过即执行」。</p>
        </section>
        <section class="panel">
          <div class="section-head"><h3>钉钉审批</h3><router-link class="btn sm" to="/admin/ding-approval">去配置</router-link></div>
          <p class="hint" style="margin:0">
            四类资产操作各自的模板 processCode 与表单字段映射，已单独拆到「系统管理 → 钉钉审批」页面维护；
            当前状态：{{ settings.approvalEnabled ? '已开启' : '未开启' }}{{ settings.approvalMock ? '（联调模式）' : '' }}。
          </p>
        </section>
      </template>

      <section v-else-if="tab === 'duty' && settings.duty" class="panel duty-settings">
        <div class="section-head">
          <h3>IT 服务联系人</h3>
          <div class="actions contact-actions">
            <button class="btn sm" type="button" :disabled="contactLoading || saving" @click="refreshContacts">刷新状态</button>
            <button class="btn sm" type="button" :disabled="saving || settings.duty.members.length >= 30"
              @click="settings.duty.members.push({ position: '', name: '', userId: null })">添加联系人</button>
          </div>
        </div>
        <p v-if="contactError" class="hint" role="status">{{ contactError }}</p>
        <p v-if="!settings.duty.members.length" class="hint">暂无联系人。</p>
        <div v-for="(member, index) in settings.duty.members" :key="index" class="duty-row">
          <div class="duty-fields">
            <label class="field">岗位
              <input v-model.trim="member.position" class="inp" maxlength="32" placeholder="例如 服务台" :disabled="saving" />
            </label>
            <PersonPicker label="人员姓名" :search="serviceContactApi.people" :manual-name="member.name" :disabled="saving"
              :selected="member.userId ? { id: member.userId, name: member.name } : undefined"
              @text-change="renameContact(member, $event)" @select="bindContact(member, $event)" />
          </div>
          <button class="btn sm contact-remove" type="button" :disabled="saving" :aria-label="`移除第 ${index + 1} 位联系人`"
            @click="settings.duty.members.splice(index, 1)">移除</button>
          <div v-if="member.name" class="contact-status">
            <span v-if="member.userId && contactStatus(member)?.status !== 'UNLINKED'" class="pill b-blue">已关联钉钉</span>
            <ContactLeaveStatus v-if="contactStatus(member)" :contact="contactStatus(member)" />
            <small v-else class="muted">{{ contactLoading ? '正在查询请假状态…' : '保存后查询请假状态' }}</small>
          </div>
        </div>
        <div class="field-grid">
          <label class="field">服务热线
            <input v-model.trim="settings.duty.hotline" class="inp" type="tel" maxlength="48" :disabled="saving" />
          </label>
          <label class="field">热线说明
            <input v-model.trim="settings.duty.hotlineNote" class="inp" maxlength="100" placeholder="选填" :disabled="saving" />
          </label>
        </div>
        <p class="hint">工作台显示联系人和当天请假情况，服务热线同步显示在员工首页。重名人员可按部门或工号关联。</p>
      </section>

      <section v-else-if="tab === 'users'" class="panel">
        <div class="section-head"><h3>用户与角色</h3><span class="muted">共 {{ userTotal }} 人</span></div>
        <form class="toolbar" @submit.prevent="userPage = 1; loadSection()">
          <input v-model="keyword" class="inp" placeholder="姓名、拼音、首字母、部门或工号" aria-label="搜索用户" />
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
            <td><button v-if="u.role !== 'SUPER_ADMIN' || isSuperAdmin" class="btn sm" @click="openEditor('user', u)">编辑</button></td>
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
        <h3>SLA 工作日历</h3><p class="hint">按北京时间计时。工作日历和目标时限在建单时确定。</p>
        <div class="week-select" role="group" aria-label="SLA 工作日">
          <label v-for="(label, index) in weekLabels" :key="label"><input v-model="settings.slaCalendar.weekdays" type="checkbox" :value="index + 1" />{{ label }}</label>
        </div>
        <div class="field-grid">
          <label class="field">工作开始<input v-model="settings.slaCalendar.start" class="inp" type="time" /></label>
          <label class="field">工作结束<input v-model="settings.slaCalendar.end" class="inp" type="time" /></label>
        </div>
        <label class="setting-row"><span>等待报修人补充信息时暂停计时</span><input v-model="settings.slaCalendar.pauseForInfo" type="checkbox" role="switch" /></label>
        <div class="field-grid">
          <label class="field">节假日<input v-model="selectedHoliday" class="inp" type="date" /></label>
          <button class="btn sm holiday-add" type="button" :disabled="!selectedHoliday || settings.slaCalendar.holidays.length >= 366" @click="addHoliday">添加节假日</button>
        </div>
        <div v-if="settings.slaCalendar.holidays.length" class="week-select holiday-list">
          <button v-for="date in settings.slaCalendar.holidays" :key="date" class="btn sm" type="button" :aria-label="'移除节假日 ' + date"
            @click="settings.slaCalendar.holidays = settings.slaCalendar.holidays.filter((day: string) => day !== date)">{{ date }} ×</button>
        </div>
        <h3 class="sla-target-heading">目标时限（工作小时）</h3>
        <div class="table-wrap"><table class="tb">
          <thead><tr><th>优先级</th><th>首次响应</th><th>解决</th></tr></thead>
          <tbody><tr v-for="p in [1,2,3,4]" :key="p"><td>P{{ p }}</td>
            <td><input v-model.number="settings.slaCalendar.responseHours[p-1]" class="inp" type="number" min="1" max="8760" :aria-label="'P' + p + ' 首次响应时限'" /></td>
            <td><input v-model.number="settings['p' + p + 'Hours']" class="inp" type="number" min="1" max="8760" :aria-label="'P' + p + ' 解决时限'" /></td>
          </tr></tbody>
        </table></div>
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

      <section v-else-if="tab === 'jobs' && jobs && jobDraft" class="panel">
        <h3>定时任务</h3>
        <p class="hint">按北京时间执行，保存后生效。已开始执行的任务会继续完成。</p>
        <div v-for="job in jobRows" :key="job.key" class="job">
          <label class="setting-row"><b>{{ job.label }}</b><input v-model="jobDraft[job.enabled]" :disabled="saving" type="checkbox" role="switch" /></label>
          <div class="field-grid job-fields">
            <label v-if="job.key === 'sla'" class="field">扫描频率
              <select class="sel" :value="scanInterval" :disabled="saving" @change="changeScanInterval">
                <option value="" disabled>自定义 Cron</option>
                <option v-for="item in scanIntervals" :key="item.cron" :value="item.cron">{{ item.label }}</option>
              </select>
            </label>
            <label v-else class="field">每日同步时间
              <input class="inp" type="time" :value="contactTime" :disabled="saving" @change="changeContactTime" />
            </label>
            <label class="field">Cron 表达式
              <input v-model="jobDraft[job.cron]" class="inp mono" :disabled="saving" :aria-label="job.label + ' Cron 表达式'" type="text" maxlength="120" spellcheck="false" autocomplete="off" />
            </label>
          </div>
          <p v-if="jobs[job.nextRun] && !jobDirty" class="hint">下次执行：{{ formatNextRun(jobs[job.nextRun]!) }}</p>
          <p v-if="jobRuns[job.key]" class="hint">最近执行：{{ jobRuns[job.key].finishedAt || jobRuns[job.key].finished_at }} · {{ jobRuns[job.key].status === 'SUCCESS' ? '成功' : '失败' }} · {{ jobRuns[job.key].message }}</p>
          <p v-else class="hint">暂无执行记录</p>
          <template v-if="job.key === 'sla'">
            <div class="field-grid job-fields">
              <label class="field">超时催办间隔（小时）
                <input v-model.number="jobDraft.slaReminderHours" class="inp" type="number" min="1" max="720" step="1" :disabled="saving" />
              </label>
              <label class="field">待确认提醒间隔（小时）
                <input v-model.number="jobDraft.confirmationReminderHours" class="inp" type="number" min="1" max="720" step="1" :disabled="saving" />
              </label>
            </div>
            <p class="hint">首次发现超时即提醒，之后按间隔催办。处理完成后，按待确认间隔提醒报修人。通知随扫描计划发送。</p>
          </template>
          <p v-if="job.key === 'contacts' && !jobs.dingTalkEnabled" class="hint">当前未启用钉钉接入，此任务会跳过。</p>
        </div>
        <div class="job" v-if="jobs.borrowDueNextRun">
          <div class="setting-row"><b>借用设备到期提醒</b></div>
          <p class="hint">每天 09:00 检查，提前 3 天和逾期后各提醒一次。</p>
          <p class="hint">下次执行：{{ formatNextRun(jobs.borrowDueNextRun) }}</p>
          <p v-if="jobRuns['borrow-due']" class="hint">最近执行：{{ jobRuns['borrow-due'].finishedAt || jobRuns['borrow-due'].finished_at }} · {{ jobRuns['borrow-due'].message }}</p>
        </div>
        <p class="hint">Cron 依次填写秒、分、时、日、月、星期，也可使用每日时间或扫描频率快捷填写。通讯录可在「部门与通讯录」手动同步。</p>
      </section>
    </template>
    <div v-else-if="!loading && !canViewSection" class="empty">未找到该管理分区</div>

    <div v-if="dialog" class="mask" @click.self="!saving && (dialog = '')">
      <form class="dlg editor" @submit.prevent="saveEntity">
        <div class="dlg-h">{{ dialog === 'user' ? '编辑用户 · ' + edit.name : dialog === 'dept' ? '部门信息' : '解决代码' }}<button class="close" type="button" :disabled="saving" @click="dialog = ''">×</button></div>
        <div class="dlg-b">
          <template v-if="dialog === 'user'">
            <label class="field">角色<select v-model="edit.role" class="sel" required><option v-for="[role, label] in roleOptions" :key="role" :value="role">{{ label }}</option></select></label>
            <label class="field">部门<select v-model="edit.deptId" class="sel"><option :value="0">未分配</option><option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
            <label class="field">状态<select v-model="edit.status" class="sel"><option value="ACTIVE">在职</option><option value="DISABLED">停用</option></select></label>
            <PersonPicker label="未结工单交接给" :options="handoverEngineers" :disabled="saving"
              :selected="handoverEngineers.find(e => e.id === edit.handoverId)"
              @select="edit.handoverId = $event.id" @clear="edit.handoverId = null" />
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
.duty-row { display:grid; grid-template-columns:minmax(0,1fr) auto; grid-template-areas:'fields remove' 'status status'; align-items:end; gap:7px 10px; }
.duty-fields { grid-area:fields; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); align-items:start; gap:10px; }
.duty-fields > .field { margin:0; gap:6px; color:var(--t2); font-weight:600; }
.duty-row + .duty-row { margin-top:12px; }
.duty-settings .section-head h3 { margin:0; }
.contact-actions { flex-wrap:wrap; }
.contact-remove { grid-area:remove; justify-self:end; margin-bottom:4px; }
.contact-status { grid-area:status; display:flex; flex-wrap:wrap; align-items:center; gap:6px 8px; min-width:0; }
@media (max-width:650px) {
  .duty-row { grid-template-areas:'fields fields' 'status remove'; align-items:center; }
  .contact-remove { margin-bottom:0; }
  .duty-settings .section-head { flex-wrap:wrap; }
}
.handover-row { display:flex; gap:14px; padding:10px 0; border-bottom:1px solid var(--line2); }
.handover-row:last-of-type { border-bottom:0; }
.hr-name { flex:0 0 96px; font-size:13px; font-weight:600; }
.hr-name small { display:block; font-weight:400; font-size:10.5px; color:var(--t4); margin-top:2px; }
.hr-cats { display:flex; flex-wrap:wrap; gap:7px; align-items:center; }
.cat-chip { border:1px solid var(--line); background:#fff; color:var(--t2); border-radius:999px; padding:4px 12px; font-size:11.5px; cursor:pointer; }
.cat-chip:hover { border-color:#94bff1; }
.cat-chip.on { background:var(--brand-l); border-color:#b6d7fb; color:var(--brand); font-weight:600; }
.cat-chip:disabled { opacity:.6; cursor:not-allowed; }

.code-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px 14px; }
.code-grid label { display:flex; flex-direction:column; gap:4px; font-size:11.5px; color:var(--t3); }
@media (max-width:600px) { .code-grid { grid-template-columns:1fr; } }
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
.job-fields { margin-top:12px; }
.week-select { display:flex; flex-wrap:wrap; gap:10px 14px; margin:10px 0 14px; }
.week-select label { display:flex; align-items:center; gap:5px; }
.holiday-add { align-self:end; justify-self:start; }
.sla-target-heading { margin-top:18px; }
.editor { max-width:560px; }.editor .dlg-b { max-height:65vh; overflow:auto; }.close { background:none; border:0; font-size:24px; cursor:pointer; float:right; }
fieldset { border:1px solid var(--line); padding:10px; border-radius:8px; }legend { padding:0 5px; font-size:13px; }.check { display:inline-flex; align-items:center; gap:7px; margin:6px 12px 6px 0; font-size:13px; }
@media(max-width:650px) { .field-grid { grid-template-columns:1fr; }.category-row { grid-template-columns:1fr auto; }.category-row .ta { grid-column:1 / -1; grid-row:2; }.panel { padding:15px; }.page-head { align-items:start; } }
</style>
