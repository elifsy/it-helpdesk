import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { DINGTALK_KEYS, parseEnv, mergeEnvText, readDingTalkConfig, backendEnvironment, verifyLoginConfig, findRuntimeJar } from './start-env.mjs'

const dingTalk = { DINGTALK_ENABLED: 'true', DINGTALK_CORP_ID: 'ding-test-corp', DINGTALK_APP_KEY: 'test-app-key', DINGTALK_APP_SECRET: 'test-secret-not-real', DINGTALK_AGENT_ID: '12345', DINGTALK_BOOTSTRAP_ADMIN_USER_ID: '' }

test('startup selects the newest packaged update while keeping the running archive intact', () => {
  const root = path.resolve('test-workspace')
  const current = path.join(root, 'server', 'target', 'runtime', 'it-helpdesk.jar')
  const update = path.join(root, 'server', 'target', 'runtime-update', 'it-helpdesk.jar')
  const dates = new Map([[current, 10], [update, 20]])
  const stat = file => ({ isFile: () => dates.has(file), mtimeMs: dates.get(file) })
  assert.equal(findRuntimeJar(root, stat), update)
  dates.set(current, 30)
  assert.equal(findRuntimeJar(root, stat), current)
})
test('startup falls back to the legacy executable only when no current packages exist', () => {
  const root = path.resolve('test-workspace')
  assert.equal(findRuntimeJar(root, () => { throw new Error('missing') }), path.join(root, 'server', 'target', 'it-helpdesk.jar'))
})

test('dotenv parser supports BOM, quotes, export, comments and literal characters', () => {
  const result = parseEnv('\uFEFF# 本地配置\r\nexport DINGTALK_ENABLED="true" # 启用\r\nKEY=abc=xyz\r\nSECRET=\'test # literal\'\r\nCUSTOM=$(literal)\r\nEMPTY=\r\n')
  assert.equal(result.DINGTALK_ENABLED, 'true')
  assert.equal(result.KEY, 'abc=xyz')
  assert.equal(result.SECRET, 'test # literal')
  assert.equal(result.CUSTOM, '$(literal)')
  assert.equal(result.EMPTY, '')
})
test('saving runtime settings preserves DingTalk credentials and unknown configuration', () => {
  const original = '# 保留注释\r\nDINGTALK_ENABLED=true\r\nDINGTALK_APP_SECRET="test # preserve"\r\nCUSTOM_SETTING=keep-me\r\nMYSQL_ROOT_PASSWORD=old-test-password\r\n'
  const updated = mergeEnvText(original, { MYSQL_ROOT_PASSWORD: 'new # test=password', QRCODE_BASE_URL: 'http://localhost:5174' })
  const result = parseEnv(updated)
  assert.equal(result.DINGTALK_ENABLED, 'true')
  assert.equal(result.DINGTALK_APP_SECRET, 'test # preserve')
  assert.equal(result.CUSTOM_SETTING, 'keep-me')
  assert.equal(result.MYSQL_ROOT_PASSWORD, 'new # test=password')
  assert.ok(updated.startsWith('# 保留注释\r\n'))
  assert.equal(mergeEnvText(updated, { MYSQL_ROOT_PASSWORD: 'new # test=password', QRCODE_BASE_URL: 'http://localhost:5174' }), updated)
})
test('saved values round trip quotes, backslashes, equals and line escapes', () => {
  const values = { A: 'test"quote', B: String.raw`test\path`, C: 'test=value', D: 'line\nnext', E: '', F: "single'quote" }
  assert.deepEqual(parseEnv(mergeEnvText('', values)), values)
})
test('duplicate runtime entries are updated consistently without losing unrelated fields', () => {
  const updated = mergeEnvText('MYSQL_USER=one\n# comment\nMYSQL_USER=two\nCUSTOM=kept\n', { MYSQL_USER: 'root' })
  assert.equal(parseEnv(updated).MYSQL_USER, 'root')
  assert.equal(updated.split('MYSQL_USER=root').length - 1, 2)
  assert.ok(updated.includes('# comment\n'))
  assert.equal(parseEnv(updated).CUSTOM, 'kept')
})
test('local DingTalk configuration overrides inherited defaults and disabled mode remains valid', () => {
  assert.equal(readDingTalkConfig(dingTalk, { DINGTALK_ENABLED: 'false' }).DINGTALK_ENABLED, 'true')
  assert.deepEqual(readDingTalkConfig({}, dingTalk), dingTalk)
  assert.equal(readDingTalkConfig({ DINGTALK_ENABLED: 'false' }, dingTalk).DINGTALK_ENABLED, 'false')
  assert.equal(readDingTalkConfig({}, {}).DINGTALK_ENABLED, 'false')
})
test('enabled mode fails early when required fields are absent without printing credentials', () => {
  assert.throws(() => readDingTalkConfig({ DINGTALK_ENABLED: 'true' }, {}), /DINGTALK_CORP_ID.*DINGTALK_APP_KEY.*DINGTALK_APP_SECRET.*DINGTALK_AGENT_ID/)
  assert.throws(() => readDingTalkConfig({ DINGTALK_ENABLED: 'invalid' }, {}), /true.*false/)
})
test('backend child process receives DingTalk variables including the optional initial administrator', () => {
  const cfg = { ...dingTalk, DINGTALK_BOOTSTRAP_ADMIN_USER_ID: 'verified-admin-id', MYSQL_USER: 'test-db-user', MYSQL_ROOT_PASSWORD: 'test-db-password', JWT_SECRET: 'test-jwt-only' }
  const env = backendEnvironment(cfg, { ...process.env, DINGTALK_ENABLED: 'false' })
  for (const key of DINGTALK_KEYS) assert.equal(env[key], cfg[key])
  assert.equal(env.SPRING_DATASOURCE_PASSWORD, cfg.MYSQL_ROOT_PASSWORD)
  const result = spawnSync(process.execPath, ['-e', 'if(process.env.DINGTALK_ENABLED!=="true" || process.env.DINGTALK_CORP_ID!=="ding-test-corp" || process.env.DINGTALK_APP_KEY!=="test-app-key" || process.env.DINGTALK_APP_SECRET!=="test-secret-not-real" || process.env.DINGTALK_AGENT_ID!=="12345" || process.env.DINGTALK_BOOTSTRAP_ADMIN_USER_ID!=="verified-admin-id" || process.env.SPRING_DATASOURCE_USERNAME!=="test-db-user") process.exit(1)'], { env, windowsHide: true })
  assert.equal(result.status, 0)
})
test('initial administrator is optional and follows local dotenv precedence', () => {
  const { DINGTALK_BOOTSTRAP_ADMIN_USER_ID, ...required } = dingTalk
  assert.equal(readDingTalkConfig(required, {}).DINGTALK_BOOTSTRAP_ADMIN_USER_ID, '')
  assert.equal(readDingTalkConfig({ ...required, DINGTALK_BOOTSTRAP_ADMIN_USER_ID: ' local-admin ' }, { DINGTALK_BOOTSTRAP_ADMIN_USER_ID: 'inherited-admin' }).DINGTALK_BOOTSTRAP_ADMIN_USER_ID, 'local-admin')
})
test('startup verifies actual backend mode and corporate configuration', () => {
  assert.doesNotThrow(() => verifyLoginConfig({ demoEnabled: false, corpId: dingTalk.DINGTALK_CORP_ID }, dingTalk))
  assert.doesNotThrow(() => verifyLoginConfig({ demoEnabled: true, corpId: '' }, { DINGTALK_ENABLED: 'false' }))
  assert.throws(() => verifyLoginConfig({ demoEnabled: true, corpId: '' }, dingTalk), /登录模式/)
  assert.throws(() => verifyLoginConfig({ demoEnabled: false, corpId: 'wrong-test-corp' }, dingTalk), /企业配置/)
})
