import fs from 'node:fs'
import path from 'node:path'

const DINGTALK_REQUIRED_KEYS = ['DINGTALK_CORP_ID', 'DINGTALK_APP_KEY', 'DINGTALK_APP_SECRET', 'DINGTALK_AGENT_ID']
export const DINGTALK_KEYS = ['DINGTALK_ENABLED', ...DINGTALK_REQUIRED_KEYS, 'DINGTALK_BOOTSTRAP_ADMIN_USER_ID']

export function findRuntimeJar(root, stat = fs.statSync) {
  let selected = null, newest = -Infinity
  for (const directory of ['runtime', 'runtime-update']) {
    const candidate = path.join(root, 'server', 'target', directory, 'it-helpdesk.jar')
    try {
      const info = stat(candidate)
      if (info.isFile() && info.mtimeMs > newest) { selected = candidate; newest = info.mtimeMs }
    } catch { }
  }
  return selected || path.join(root, 'server', 'target', 'it-helpdesk.jar')
}

export function parseEnv(text) {
  const result = {}
  for (const raw of text.replace(/^\uFEFF/, '').split(/\r?\n/)) {
    const match = raw.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (!match) continue
    let value = match[2].trim()
    const doubleQuoted = value.match(/^"((?:\\.|[^"\\])*)"\s*(?:#.*)?$/)
    const singleQuoted = value.match(/^'([^']*)'\s*(?:#.*)?$/)
    if (doubleQuoted) value = doubleQuoted[1].replace(/\\([nrt"\\])/g, (_all, char) => ({ n: '\n', r: '\r', t: '\t', '"': '"', '\\': '\\' })[char])
    else if (singleQuoted) value = singleQuoted[1]
    else value = value.replace(/\s+#.*$/, '').trim()
    result[match[1]] = value
  }
  return result
}

export function mergeEnvText(text, updates) {
  const newline = text.includes('\r\n') ? '\r\n' : '\n'
  const values = Object.fromEntries(Object.entries(updates).filter(([_key, value]) => value !== undefined))
  const remaining = new Set(Object.keys(values))
  const encode = value => {
    const string = String(value ?? '')
    return /[\s#"'\\]/.test(string) ? JSON.stringify(string) : string
  }
  const lines = text ? text.split(/\r?\n/) : []
  if (lines[lines.length - 1] === '') lines.pop()
  const merged = lines.map(line => {
    const match = line.replace(/^\uFEFF/, '').match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=/)
    if (!match || !Object.hasOwn(values, match[1])) return line
    remaining.delete(match[1])
    return match[1] + '=' + encode(values[match[1]])
  })
  for (const key of remaining) merged.push(key + '=' + encode(values[key]))
  return merged.join(newline) + newline
}

export function readDingTalkConfig(fileEnv, inherited = process.env) {
  const result = Object.fromEntries(DINGTALK_KEYS.map(key => [key, String(Object.hasOwn(fileEnv, key) ? fileEnv[key] : inherited[key] ?? '').trim()]))
  const enabled = result.DINGTALK_ENABLED.toLowerCase() || 'false'
  if (!['true', 'false'].includes(enabled)) throw new Error('DINGTALK_ENABLED 必须填写 true 或 false')
  result.DINGTALK_ENABLED = enabled
  if (enabled === 'true') {
    const missing = DINGTALK_REQUIRED_KEYS.filter(key => !result[key])
    if (missing.length) throw new Error('钉钉免登缺少配置：' + missing.join('、') + '；请填写项目根目录的 .env')
  }
  return result
}

export function backendEnvironment(cfg, inherited = process.env) {
  return {
    ...inherited,
    ...readDingTalkConfig(cfg, inherited),
    SPRING_DATASOURCE_USERNAME: cfg.MYSQL_USER,
    SPRING_DATASOURCE_PASSWORD: cfg.MYSQL_ROOT_PASSWORD,
    JWT_SECRET: cfg.JWT_SECRET,
    TZ: 'Asia/Shanghai'
  }
}

export function verifyLoginConfig(actual, cfg) {
  const enabled = cfg.DINGTALK_ENABLED === 'true'
  if (typeof actual?.demoEnabled !== 'boolean' || actual.demoEnabled !== !enabled)
    throw new Error('后端登录模式与 .env 不一致，请停止旧服务后重新启动项目')
  if (enabled && actual.corpId !== cfg.DINGTALK_CORP_ID)
    throw new Error('后端企业配置与 .env 不一致，请确认访问的是本次启动的服务')
}
