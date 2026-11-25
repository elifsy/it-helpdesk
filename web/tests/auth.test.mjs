import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

class ApiError extends Error { constructor(message, code) { super(message); this.code = code } }
const source = ts.transpileModule(fs.readFileSync(new URL('../src/stores/auth.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const menus = {}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(new URL('../src/config/menus.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText, { exports: menus })

function setup({ token = '', dingTalk = true, demo = false, meError, loginError } = {}) {
  const values = new Map(token ? [['token', token]] : [])
  const localStorage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  const calls = []
  const user = { id: 1, name: '张伟', roles: ['USER'], permissions: ['ticket.view.own'], token: 'new-token' }
  const authApi = {
    config: async () => ({ demoEnabled: demo, corpId: 'corp-from-server' }),
    me: async () => { calls.push(['me']); if (meError) throw meError; return { ...user, token: null } },
    login: async payload => { calls.push(['login', payload]); if (loginError) throw loginError; return { ...user, name: payload.demoUser || user.name } }
  }
  const defineStore = (_name, options) => () => {
    const state = options.state()
    for (const [name, getter] of Object.entries(options.getters)) Object.defineProperty(state, name, { get: () => getter(state) })
    for (const [name, action] of Object.entries(options.actions)) state[name] = action.bind(state)
    return state
  }
  const exports = {}
  vm.runInNewContext(source, {
    exports, localStorage,
    require: name => ({
      pinia: { defineStore }, '../api': { authApi },
      '../api/http': { ApiError, TOKEN_KEY: 'token', DEMO_KEY: 'demo' },
      '../config/menus': menus,
      '../utils/dingtalk': { isDingTalk: () => dingTalk, requestAuthCode: async corpId => { calls.push(['authCode', corpId]); return 'fresh-code' } }
    })[name]
  })
  return { store: exports.useAuthStore(), calls, values }
}

test('cached session uses /me without replaying DingTalk login', async () => {
  const { store, calls, values } = setup({ token: 'valid-token' })
  await store.bootstrap()
  assert.equal(store.user.name, '张伟')
  assert.deepEqual(calls, [['me']])
  assert.equal(values.get('token'), 'valid-token')
  assert.equal(store.loading, false)
})
test('expired session obtains a fresh code and persists the new token', async () => {
  const { store, calls, values } = setup({ token: 'expired', meError: new ApiError('expired', 401) })
  await store.bootstrap()
  assert.equal(calls[1][0], 'authCode')
  assert.equal(calls[1][1], 'corp-from-server')
  assert.equal(calls[2][1].authCode, 'fresh-code')
  assert.equal(values.get('token'), 'new-token')
})
test('network failure preserves existing identity and does not switch to a demo account', async () => {
  const { store, calls, values } = setup({ token: 'existing', demo: true, meError: new ApiError('network') })
  await assert.rejects(store.bootstrap(), /network/)
  assert.deepEqual(calls, [['me']])
  assert.equal(values.get('token'), 'existing')
  assert.equal(store.loading, false)
})
test('disabled account does not silently reauthenticate', async () => {
  const { store, calls } = setup({ token: 'disabled', demo: true, meError: new ApiError('disabled', 403) })
  await assert.rejects(store.bootstrap(), /disabled/)
  assert.deepEqual(calls, [['me']])
})
test('production browser asks to open DingTalk without using demo login', async () => {
  const { store, calls } = setup({ dingTalk: false })
  await assert.rejects(store.bootstrap(), /钉钉工作台/)
  assert.deepEqual(calls, [])
})
test('demo login is selected by server configuration', async () => {
  const { store, calls, values } = setup({ demo: true })
  await store.bootstrap()
  assert.equal(calls[0][0], 'login')
  assert.equal(calls[0][1].demoUser, '谈帥宏')
  assert.equal(values.get('token'), 'new-token')
  assert.equal(values.get('demo'), '谈帥宏')
})
test('an old demo session outside the local choices switches to the configured demo default', async () => {
  const { store, calls, values } = setup({ token: 'old-demo-token', demo: true })
  values.set('demo', '张伟')
  await store.bootstrap()
  assert.equal(calls[1][1].demoUser, '谈帥宏')
  assert.equal(store.user.name, '谈帥宏')
})
test('failed identity switch preserves previous demo selection', async () => {
  const { store, values } = setup({ demo: true, loginError: new ApiError('denied', 403) })
  store.config = { demoEnabled: true, corpId: '' }
  values.set('demo', '张伟')
  await assert.rejects(store.loginAsDemo('陈静'), /denied/)
  assert.equal(values.get('demo'), '张伟')
})
test('action permissions are derived from the server permission list', () => {
  const { store } = setup()
  store.user = { roles: ['IT'], permissions: ['asset.view'] }
  assert.equal(store.can('asset.view'), true)
  assert.equal(store.can('ticket.accept'), false)
  assert.equal(store.canCreateAsset, false)
})
