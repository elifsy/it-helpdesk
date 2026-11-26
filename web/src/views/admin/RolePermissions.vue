<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '../../api'
import { toast } from '../../utils/toast'

interface Capability { key: string; name: string; group: string; readOnly: boolean; requires: string[] }
interface Policy { revision: number; selfServiceVersion: number; selfServicePermissions: string[]; roles: string[]; roleNames: Record<string, string>; capabilities: Capability[]; assignments: Record<string, string[]>; defaults: Record<string, string[]> }
const policy = ref<Policy | null>(null)
const baseline = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dirty = computed(() => !!policy.value && JSON.stringify(policy.value.assignments) !== baseline.value)
const groups = computed(() => Array.from(new Set(policy.value?.capabilities.map(c => c.group) || [])))
function apply(value: Policy) { policy.value = value; baseline.value = JSON.stringify(value.assignments) }
async function load() {
  if (dirty.value && !window.confirm('放弃未保存的权限修改并重新加载？')) return
  loading.value = true
  error.value = ''
  try { apply(await adminApi.permissions()) }
  catch (e: any) { error.value = e.message }
  finally { loading.value = false }
}
function locked(role: string, capability: Capability) {
  return capability.key === 'role.manage'
    || (role === 'USER' && (capability.key === 'ticket.view.all' || capability.requires.includes('ticket.view.all')))
    || (role === 'VIEWER' && !capability.readOnly && !policy.value?.selfServicePermissions.includes(capability.key))
}
function toggle(role: string, key: string, enabled: boolean) {
  const data = policy.value!
  const assigned = new Set(data.assignments[role])
  function add(permission: string) {
    if (assigned.has(permission)) return
    data.capabilities.find(c => c.key === permission)?.requires.forEach(add)
    assigned.add(permission)
  }
  if (enabled) add(key)
  else {
    assigned.delete(key)
    let removed = true
    while (removed) {
      removed = false
      for (const capability of data.capabilities) {
        if (assigned.has(capability.key) && capability.requires.some(dependency => !assigned.has(dependency))) {
          assigned.delete(capability.key)
          removed = true
        }
      }
    }
  }
  data.assignments[role] = data.capabilities.filter(c => assigned.has(c.key)).map(c => c.key)
}
function reset(role: string) {
  if (!window.confirm('恢复“' + policy.value!.roleNames[role] + '”的默认权限？保存后生效。')) return
  policy.value!.assignments[role] = [...policy.value!.defaults[role]]
}
async function save() {
  saving.value = true
  error.value = ''
  try {
    apply(await adminApi.savePermissions({ revision: policy.value!.revision, selfServiceVersion: policy.value!.selfServiceVersion, assignments: policy.value!.assignments }))
    window.dispatchEvent(new Event('permissions-changed'))
    window.dispatchEvent(new Event('admin-data-changed'))
    toast('角色权限已保存，后续请求立即按新配置校验')
  } catch (e: any) { error.value = e.message }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section class="role-permissions">
    <div class="permission-head">
      <div><h3>角色权限配置</h3><p>勾选每种角色可以使用的功能，保存后应用到该角色的全部账号。</p></div>
      <button class="btn sm" :disabled="saving || loading" @click="load">重新加载权限</button>
    </div>
    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <div v-if="loading" class="empty">正在加载权限…</div>
    <template v-else-if="policy">
      <div class="role-cards">
        <div v-for="role in policy.roles" :key="role" class="role-card">
          <b>{{ policy.roleNames[role] }}</b>
          <span>{{ policy.assignments[role].length }} 项权限<span v-if="role === 'VIEWER'"> · 本人自助 / 管理只读</span></span>
          <button class="reset" :disabled="saving" @click="reset(role)">恢复默认</button>
        </div>
      </div>
      <p class="permission-note">普通用户仅可查看和办理本人工单。各角色默认拥有本人的报修、资产申请与知识反馈功能；可查看用户办理本人业务，对管理数据仅查看。勾选操作权限会补齐依赖，取消查看权限会取消依赖操作；管理员的权限配置入口始终保留。</p>
      <div class="table-wrap">
        <table class="tb permission-matrix">
          <thead><tr><th>功能权限</th><th v-for="role in policy.roles" :key="role">{{ policy.roleNames[role] }}</th></tr></thead>
          <tbody v-for="group in groups" :key="group">
            <tr class="group-row"><th colspan="5">{{ group }}</th></tr>
            <tr v-for="cap in policy.capabilities.filter(c => c.group === group)" :key="cap.key">
              <td><b>{{ cap.name }}</b><small>{{ cap.readOnly ? '查看' : '操作' }}</small></td>
              <td v-for="role in policy.roles" :key="role">
                <input type="checkbox" :aria-label="policy.roleNames[role] + ' · ' + cap.name"
                  :data-role="role" :data-permission="cap.key"
                  :checked="policy.assignments[role].includes(cap.key)" :disabled="saving || locked(role, cap)"
                  @change="toggle(role, cap.key, ($event.target as HTMLInputElement).checked)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="permission-save">
        <span>{{ dirty ? '有未保存的权限修改' : '当前权限已保存' }} · 版本 {{ policy.revision }}</span>
        <button class="btn pri" :disabled="!dirty || saving" @click="save">{{ saving ? '保存中…' : '保存角色权限' }}</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.role-permissions { background:#fff; padding:20px; border-radius:16px; box-shadow:var(--shadow); }
.permission-head { display:flex; justify-content:space-between; gap:16px; align-items:start; }
h3 { font-size:18px; margin:0 0 8px; }p { font-size:12px; color:var(--t2); line-height:1.7; margin:0; }
.role-cards { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:10px; margin:20px 0 14px; }
.role-card { padding:14px; border:1px solid var(--line); border-radius:12px; background:#fafbfe; }
.role-card b,.role-card span { display:block; }.role-card b { font-size:14px; }.role-card span { font-size:11px; color:var(--t2); margin:7px 0; }
.reset { color:#0071e3; font-size:11px; border:0; background:transparent; cursor:pointer; padding:0; }
.permission-note { margin-bottom:15px; }.table-wrap { overflow-x:auto; }.permission-matrix { min-width:620px; }
.permission-matrix th,.permission-matrix td { padding:12px; font-size:12px; }.permission-matrix th:not(:first-child),.permission-matrix td:not(:first-child) { text-align:center; width:98px; }
.permission-matrix td b { font-weight:500; }.permission-matrix td small { margin-left:8px; color:var(--t3); font-size:10px; }
.permission-matrix input { width:18px; height:18px; accent-color:#0071e3; cursor:pointer; }.permission-matrix input:disabled { cursor:default; opacity:.5; }
.group-row th { background:#f1f5fb; color:#28517a; text-align:left; }
.permission-save { display:flex; justify-content:space-between; align-items:center; gap:10px; padding-top:18px; font-size:12px; color:var(--t2); }
.error { background:#fff0ee; color:#b32921; border-radius:10px; padding:12px; margin:12px 0; font-size:13px; }
@media(max-width:650px) { .role-cards { grid-template-columns:repeat(2,minmax(0,1fr)); }.permission-head { flex-direction:column; }.role-permissions { padding:14px; } }
</style>
