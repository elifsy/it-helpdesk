<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { incomingNotificationApi } from '../../api'
import type { IncomingNotificationConfig, IncomingNotificationRules, NotificationRecipient, TicketNotificationRule } from '../../api'
import { TICKET_TYPES } from '../../config/ticket-types'
import { ROLE_NAMES } from '../../config/menus'
import { toast } from '../../utils/toast'
import PersonPicker from '../../components/PersonPicker.vue'

const ASSET_TYPES = [
  { value: 'RECEIVE', label: '设备领用' },
  { value: 'BORROW', label: '设备借用' },
  { value: 'REPLACE', label: '设备更换' },
  { value: 'RETURN', label: '设备归还' }
]
const LIMIT = 20

const config = ref<IncomingNotificationConfig>()
const draft = ref<IncomingNotificationRules>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const tab = ref<'ticket' | 'asset'>('ticket')
const type = ref<string>(TICKET_TYPES[0].v)
const cat1 = ref('')
const cat2 = ref('')
const assetType = ref(ASSET_TYPES[0].value)

const dirty = computed(() => JSON.stringify(draft.value) !== JSON.stringify(config.value?.rules))
const primaryCategories = computed(() => Object.keys(config.value?.categories[type.value] ?? {}))
const secondaryCategories = computed(() => config.value?.categories[type.value]?.[cat1.value] ?? [])
const matchesCategory = (rule: TicketNotificationRule) =>
  rule.type === type.value && rule.cat1 === cat1.value && (rule.cat2 ?? '') === cat2.value

const selectedIds = computed<number[]>(() => {
  if (!draft.value) return []
  return tab.value === 'ticket'
    ? draft.value.ticketRules.find(matchesCategory)?.recipientIds ?? []
    : draft.value.assetRecipients[assetType.value] ?? []
})
const parentIds = computed(() => draft.value?.ticketRules.find(rule =>
  rule.type === type.value && rule.cat1 === cat1.value && !rule.cat2)?.recipientIds ?? [])
const people = computed(() => new Map(config.value?.recipients.map(person => [person.id, person])))
const availablePeople = computed(() => (config.value?.recipients ?? [])
  .filter(person => !selectedIds.value.includes(person.id))
  .map(person => ({ ...person, description: ROLE_NAMES[person.role] })))
const selectionEnabled = computed(() => tab.value === 'asset' || !!cat1.value)

function chooseType(value: string) {
  type.value = value
  cat1.value = primaryCategories.value[0] ?? ''
  cat2.value = ''
}

function isCurrentCategory(rule: TicketNotificationRule) {
  const children = config.value?.categories[rule.type]?.[rule.cat1]
  return !!children && (!rule.cat2 || children.includes(rule.cat2))
}

function editRule(rule: TicketNotificationRule) {
  type.value = rule.type
  cat1.value = rule.cat1
  cat2.value = rule.cat2 ?? ''
}

function setSelected(ids: number[]) {
  if (!draft.value || !selectionEnabled.value) return
  if (tab.value === 'asset') {
    if (ids.length) draft.value.assetRecipients[assetType.value] = ids
    else delete draft.value.assetRecipients[assetType.value]
    return
  }
  const rules = draft.value.ticketRules
  const index = rules.findIndex(matchesCategory)
  if (index >= 0 && !ids.length) rules.splice(index, 1)
  else if (index >= 0) rules[index].recipientIds = ids
  else if (ids.length) rules.push({ type: type.value, cat1: cat1.value, cat2: cat2.value || null, recipientIds: ids })
}

function toggleRecipient(id: number) {
  const ids = selectedIds.value
  if (ids.includes(id)) setSelected(ids.filter(existing => existing !== id))
  else if (ids.length < LIMIT) setSelected([...ids, id])
}

function recipientLabel(person: NotificationRecipient) {
  return [ROLE_NAMES[person.role], person.department, person.employeeNo].filter(Boolean).join(' · ')
}

function selectedLabel(id: number) {
  const person = people.value.get(id)
  if (!person) return '不可用账号 ' + id
  const detail = recipientLabel(person)
  return detail ? `${person.name}（${detail}）` : person.name
}

function useRules(rules: IncomingNotificationRules) {
  draft.value = JSON.parse(JSON.stringify(rules)) as IncomingNotificationRules
}

async function load() {
  if (dirty.value && !window.confirm('重新加载会放弃未保存的修改，继续吗？')) return
  loading.value = true
  error.value = ''
  try {
    config.value = await incomingNotificationApi.config()
    useRules(config.value.rules)
    if (!primaryCategories.value.includes(cat1.value)) chooseType(type.value)
    if (!secondaryCategories.value.includes(cat2.value)) cat2.value = ''
  } catch (e: any) {
    error.value = e.message || '通知配置加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!config.value || !draft.value || !dirty.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const saved = await incomingNotificationApi.save(draft.value)
    config.value.rules = saved
    useRules(saved)
    toast('通知接收人已保存')
  } catch (e: any) {
    error.value = e.message || '通知配置保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="notification-settings">
    <header class="page-header">
      <div>
        <h2>通知接收人</h2>
        <p>来单时向指定人员发送钉钉通知。可选择在职且已绑定钉钉的 IT 或管理员。</p>
      </div>
      <div class="page-actions">
        <button type="button" class="btn" :disabled="loading || saving" @click="load">重新加载</button>
        <button type="button" class="btn primary" :disabled="!dirty || loading || saving" @click="save">
          {{ saving ? '保存中…' : '保存配置' }}
        </button>
      </div>
    </header>
    <p v-if="error" class="notification-error" role="alert">{{ error }}</p>
    <p v-if="loading" class="panel" role="status">正在加载通知配置…</p>
    <template v-else-if="config && draft">
      <fieldset class="panel rule-editor" :disabled="saving">
        <div class="rule-tabs" aria-label="通知业务">
          <button type="button" class="btn" :class="{ primary: tab === 'ticket' }" :aria-pressed="tab === 'ticket'" @click="tab = 'ticket'">报修工单</button>
          <button type="button" class="btn" :class="{ primary: tab === 'asset' }" :aria-pressed="tab === 'asset'" @click="tab = 'asset'">资产申请</button>
        </div>
        <div v-if="tab === 'ticket'" class="rule-fields">
          <label>
            问题类型
            <select class="inp" :value="type" @change="chooseType(($event.target as HTMLSelectElement).value)">
              <option v-for="option in TICKET_TYPES" :key="option.v" :value="option.v">{{ option.s }}</option>
            </select>
          </label>
          <label>
            一级分类
            <select v-model="cat1" class="inp" @change="cat2 = ''">
              <option v-if="!primaryCategories.length" value="">暂无分类</option>
              <option v-for="category in primaryCategories" :key="category">{{ category }}</option>
            </select>
          </label>
          <label>
            二级分类
            <select v-model="cat2" class="inp">
              <option value="">整个一级分类</option>
              <option v-for="category in secondaryCategories" :key="category">{{ category }}</option>
            </select>
          </label>
        </div>
        <div v-else class="rule-fields">
          <label>
            申请类型
            <select v-model="assetType" class="inp">
              <option v-for="option in ASSET_TYPES" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
        </div>

        <section v-if="selectionEnabled" class="recipient-section" aria-labelledby="recipient-heading">
          <h3 id="recipient-heading">接收人 <span v-if="selectedIds.length" class="recipient-count">已选 {{ selectedIds.length }} 人</span></h3>
          <div v-if="selectedIds.length" class="selected-people">
            <button v-for="id in selectedIds" :key="id" type="button" class="selected-person"
              :aria-label="`移除 ${selectedLabel(id)}`" @click="toggleRecipient(id)">
              {{ selectedLabel(id) }}<span aria-hidden="true"> ×</span>
            </button>
          </div>
          <p v-else-if="tab === 'ticket' && cat2 && parentIds.length" class="hint">
            当前使用一级分类的接收人。这里选人后，优先使用二级分类规则。
          </p>
          <p v-else-if="tab === 'ticket'" class="hint">未设置接收人时，通知有工单受理权限的 IT 和管理员。</p>
          <p v-else class="hint">未设置接收人时，沿用当前审批流程的通知。选择人员后，钉钉审批也会发送来单提醒。</p>
          <PersonPicker :key="JSON.stringify([tab, type, cat1, cat2, assetType])" class="person-search" label="搜索 IT 或管理员"
            :options="availablePeople" :disabled="saving || selectedIds.length >= LIMIT" clear-after-select
            @select="toggleRecipient($event.id)" />
          <p v-if="!config.recipients.length" class="hint">暂无在职且已绑定钉钉的 IT 或管理员。</p>
          <p v-if="selectedIds.length >= LIMIT" class="hint">每条规则最多选择 {{ LIMIT }} 人。</p>
        </section>
      </fieldset>

      <section v-if="tab === 'ticket' && draft.ticketRules.length" class="panel configured-rules">
        <h3>已配置的报修分类</h3>
        <ul>
          <li v-for="(rule, index) in draft.ticketRules" :key="JSON.stringify([rule.type, rule.cat1, rule.cat2])">
            <div>
              <strong>{{ TICKET_TYPES.find(option => option.v === rule.type)?.s ?? rule.type }} · {{ rule.cat1 }}{{ rule.cat2 ? ' / ' + rule.cat2 : '' }}</strong>
              <small>{{ rule.recipientIds.map(selectedLabel).join('、') }}</small>
            </div>
            <button v-if="isCurrentCategory(rule)" type="button" class="btn" :disabled="saving" @click="editRule(rule)">编辑</button>
            <button v-else type="button" class="btn" :disabled="saving" @click="draft.ticketRules.splice(index, 1)">移除失效分类</button>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.notification-settings { display: grid; gap: 16px; }
.page-header, .page-actions, .rule-tabs, .selected-people { display: flex; flex-wrap: wrap; gap: 8px; }
.page-header { align-items: center; justify-content: space-between; }
.page-header h2, h3 { margin: 0; }
.page-header p, .hint { margin: 6px 0 0; color: var(--muted, #656b75); font-size: 13px; }
.notification-error { margin: 0; color: #b42318; }
.rule-editor { margin: 0; min-width: 0; border: 0; }
.rule-fields { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr)); }
.rule-fields { margin: 16px 0; }
.rule-fields label { display: grid; gap: 6px; font-size: 13px; font-weight: 600; }
.rule-fields .inp { width: 100%; }
.recipient-section { border-top: 1px solid var(--line, #e4e7ec); padding-top: 16px; }
.recipient-count { color: var(--muted, #656b75); font-size: 13px; font-weight: normal; margin-left: 6px; }
.person-search { max-width: 420px; margin: 14px 0 10px; }
.configured-rules small { display: block; color: var(--muted, #656b75); margin-top: 3px; }
.selected-people { margin-top: 10px; }
.selected-person { font: inherit; color: #0668cc; background: #edf5ff; border: 0; border-radius: 16px; padding: 4px 10px; cursor: pointer; }
.configured-rules ul { padding: 0; margin: 10px 0 0; list-style: none; }
.configured-rules li { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px 0; border-top: 1px solid var(--line, #e4e7ec); }
.configured-rules li .btn { flex: none; }
</style>
