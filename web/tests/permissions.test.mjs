import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const exports = {}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(new URL('../src/config/menus.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, { exports, URLSearchParams })
const { ROLE_NAMES, roleFromRoles, homeForRoles, routeAllowed } = exports

test('the UI recognizes the five staff roles, including super administrator', () => {
  assert.deepEqual(Object.keys(ROLE_NAMES).sort(), ['ADMIN', 'IT', 'SUPER_ADMIN', 'USER', 'VIEWER'])
  assert.equal(ROLE_NAMES.USER, '普通用户')
  assert.equal(ROLE_NAMES.VIEWER, '可查看用户')
  assert.equal(roleFromRoles(['IT']), 'it')
  assert.equal(roleFromRoles(['VIEWER']), 'viewer')
  assert.equal(ROLE_NAMES.SUPER_ADMIN, '超级管理员')
  assert.equal(roleFromRoles(['SUPER_ADMIN']), 'admin')
  assert.equal(homeForRoles(['SUPER_ADMIN'], ['stats.view']), '/admin/dashboard')
  assert.equal(routeAllowed(['SUPER_ADMIN'], '/admin/missing-reports', []), true)
})
test('an explicitly restricted viewer follows its configured view-only permissions', () => {
  const permissions = ['ticket.view.all', 'asset.view', 'request.view.all', 'kb.view', 'stats.view']
  assert.equal(homeForRoles(['VIEWER'], permissions), '/admin/tickets')
  for (const path of ['/admin/tickets', '/admin/assets', '/admin/approvals', '/admin/stats', '/kb']) assert.equal(routeAllowed(['VIEWER'], path, permissions), true)
  for (const path of ['/new', '/admin/config', '/admin/config?tab=matrix']) assert.equal(routeAllowed(['VIEWER'], path, permissions), false)
})
test('menus follow permissions rather than automatically trusting the IT role', () => {
  assert.equal(routeAllowed(['IT'], '/admin/stats', ['asset.view']), false)
  assert.equal(routeAllowed(['IT'], '/admin/assets', ['asset.view']), true)
  assert.equal(homeForRoles(['IT'], ['asset.view']), '/admin/assets')
  assert.equal(routeAllowed(['IT'], '/admin/approvals', ['request.view.all']), true)
})
test('administrator retains access to the role editor even when settings access is disabled', () => {
  assert.equal(routeAllowed(['ADMIN'], '/admin/config', ['role.manage']), false)
  assert.equal(routeAllowed(['ADMIN'], '/admin/config?tab=matrix', ['role.manage']), true)
  assert.equal(homeForRoles(['ADMIN'], ['role.manage']), '/admin/config?tab=matrix')
})
test('delegated management capabilities expose their own sections only', () => {
  assert.equal(routeAllowed(['IT'], '/admin/config?tab=users', ['user.manage']), true)
  assert.equal(routeAllowed(['IT'], '/admin/config?tab=matrix', ['user.manage']), false)
  assert.equal(routeAllowed(['IT'], '/admin/config?tab=dict', ['dict.manage']), true)
  assert.equal(routeAllowed(['IT'], '/admin/config?tab=depts', ['contact.sync']), true)
})
test('protected configuration tabs require the super administrator role even with delegated permissions', () => {
  const permissions = ['settings.manage', 'audit.view', 'dict.manage']
  for (const tab of ['sla', 'audit', 'storage', 'jobs']) {
    const path = '/admin/config?tab=' + tab
    for (const role of ['ADMIN', 'IT', 'USER', 'VIEWER']) {
      assert.equal(routeAllowed([role], path, permissions), false)
    }
    assert.equal(routeAllowed(['SUPER_ADMIN'], path, permissions), true)
  }
  for (const path of ['/admin/config', '/admin/config?tab=duty', '/admin/config?tab=dict', '/admin/ding-approval']) {
    assert.equal(routeAllowed(['ADMIN'], path, permissions), true)
  }
})
test('a user without business permissions still has a safe home route', () => {
  assert.equal(homeForRoles(['USER'], []), '/home')
  assert.equal(routeAllowed(['USER'], '/home', []), true)
  assert.equal(routeAllowed(['USER'], '/new', []), false)
  assert.equal(routeAllowed(['USER'], '/tickets', []), false)
})
test('ordinary users cannot open the ticket center with stale all-ticket permissions', () => {
  const permissions = ['ticket.view.all', 'ticket.view.own']
  assert.equal(routeAllowed(['USER'], '/admin/tickets', permissions), false)
  assert.equal(routeAllowed(['USER'], '/admin/ticket/IT-TEST-0001', permissions), false)
  assert.equal(routeAllowed(['USER'], '/tickets', permissions), true)
  assert.equal(routeAllowed(['VIEWER'], '/admin/tickets', permissions), true)
})
test('device detail deep links require the same viewing permission as the ledger', () => {
  assert.equal(routeAllowed(['VIEWER'], '/admin/assets/IT-PC-2026-0001', ['asset.view']), true)
  assert.equal(routeAllowed(['IT'], '/admin/assets/IT-PC-2026-0001?edit=1', ['asset.view', 'asset.create']), true)
  assert.equal(routeAllowed(['USER'], '/admin/assets/IT-PC-2026-0001', []), false)
})
test('device detail page has its own context title', () => {
  assert.equal(exports.titleFor('admin', '/admin/assets/IT-PC-2026-0001'), '设备详情')
})

test('all four workspaces expose the same personal modules and phone shortcuts', () => {
  const permissions = ['ticket.view.own', 'ticket.create', 'ticket.confirm', 'asset.view', 'request.view.own', 'request.create', 'kb.view', 'kb.vote']
  for (const role of ['emp', 'it', 'admin', 'viewer']) {
    const paths = exports.MENUS[role].flatMap(group => group.items.map(item => item.path))
    for (const path of ['/home', '/new', '/tickets', '/assets', '/kb']) {
      assert.ok(paths.includes(path), `${role} missing ${path}`)
      assert.equal(paths.filter(candidate => candidate === path).length, 1)
      assert.equal(routeAllowed([], path, permissions), true)
      assert.notEqual(exports.titleFor(role, path), '服务平台')
    }
    const shortcuts = exports.TABS[role].map(item => item.path)
    for (const path of ['/home', '/new', '/tickets', '/assets']) assert.ok(shortcuts.includes(path), `${role} phone missing ${path}`)
  }
})
