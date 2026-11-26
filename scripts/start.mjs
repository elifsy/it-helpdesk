#!/usr/bin/env node
/**
 * 内部 IT 报修平台 · 本地一键启动
 *
 *   1. 检查环境（JDK≥17 / Node / jar / 前端依赖）
 *   2. 首次运行建库导数据（--reset-db 强制重建）
 *   3. 自动挑选空闲端口（8080/5173 被占用就往后顺延）
 *   4. 起后端 + 前端，就绪后打开浏览器；Ctrl+C 一起停
 */
import fs from 'node:fs'
import path from 'node:path'
import net from 'node:net'
import readline from 'node:readline'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { DINGTALK_KEYS, parseEnv, mergeEnvText, readDingTalkConfig, backendEnvironment, verifyLoginConfig, findRuntimeJar } from './start-env.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const ENV_FILE = path.join(ROOT, '.env')
const LOG_DIR = path.join(ROOT, 'logs')
const JAR = findRuntimeJar(ROOT)
const WEB_DIR = path.join(ROOT, 'web')

const argv = new Set(process.argv.slice(2))
const RESET_DB = argv.has('--reset-db')
const NO_BROWSER = argv.has('--no-browser')
const AUTO_INSTALL = !argv.has('--no-install')

let BACK_PORT = 8080
let WEB_PORT = 5173
let WEB_URL = `http://localhost:${WEB_PORT}`

const children = []
let stopping = false

/* ------------------------------ 小工具 ------------------------------ */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const line = (char = '─', n = 58) => char.repeat(n)

function title(t) {
  console.log('')
  console.log(line('═'))
  console.log('  ' + t)
  console.log(line('═'))
}

const step = (t) => console.log('\n▶ ' + t)
const ok = (t) => console.log('  ✓ ' + t)
const warn = (t) => console.log('  ! ' + t)
const fail = (t) => console.log('  ✗ ' + t)

function die(t) {
  fail(t)
  console.log('\n启动中止。把上面的报错发我，或看 LOCAL-RUN.md 的手工步骤。\n')
  stopping = true
  process.exit(1)
}

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  return new Promise((resolve) => {
    rl.question(question, (answer) => {
      rl.close()
      resolve(String(answer || '').trim())
    })
  })
}

function which(cmd) {
  const r = spawnSync(process.platform === 'win32' ? 'where' : 'which', [cmd], { encoding: 'utf8' })
  if (r.status !== 0) return null
  return (r.stdout || '').split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0] || null
}

function portFree(port) {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' })
    const done = (v) => {
      socket.destroy()
      resolve(v)
    }
    socket.setTimeout(600, () => done(true))
    socket.on('connect', () => done(false))
    socket.on('error', () => done(true))
  })
}

async function pickPort(candidates) {
  for (const p of candidates) {
    if (await portFree(p)) return p
  }
  return null
}

async function waitHttp(url, timeoutMs = 120000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      await fetch(url, { signal: AbortSignal.timeout(1500) })
      return true
    } catch {
      await sleep(800)
    }
  }
  return false
}

/* ------------------------------ .env ------------------------------ */
function writeEnv(cfg) {
  const current = fs.existsSync(ENV_FILE) ? fs.readFileSync(ENV_FILE, 'utf8') : '# 本机配置，不提交到版本库\n'
  const existing = parseEnv(current)
  const updates = Object.fromEntries(['MYSQL_USER', 'MYSQL_ROOT_PASSWORD', 'REDIS_ENABLED', 'QRCODE_BASE_URL', 'JWT_SECRET'].map(key => [key, cfg[key]]))
  for (const key of DINGTALK_KEYS) if (!Object.hasOwn(existing, key)) updates[key] = cfg[key]
  fs.writeFileSync(ENV_FILE, mergeEnvText(current, updates), 'utf8')
}

function loadEnv() {
  if (!fs.existsSync(ENV_FILE)) return {}
  return parseEnv(fs.readFileSync(ENV_FILE, 'utf8'))
}

/* ------------------------------ MySQL ------------------------------ */
function findMysql() {
  const candidates = [
    'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe',
    'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',
    'C:/Program Files/MySQL/MySQL Server 8.3/bin/mysql.exe',
    'C:/xampp/mysql/bin/mysql.exe'
  ]
  for (const p of candidates) {
    if (fs.existsSync(p)) return p
  }
  return which('mysql')
}

function mysqlRun(mysql, args, opts = {}) {
  const { pwd, user = 'root', input } = opts
  return spawnSync(mysql, [`-u${user}`, ...args], {
    input,
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: pwd || '' },
    windowsHide: true,
    timeout: 60000
  })
}

function dbReady(mysql, pwd, user) {
  const r = mysqlRun(
    mysql,
    [
      '-N',
      '-B',
      '-e',
      "select count(*) from information_schema.tables where table_schema='it_helpdesk' and table_name='it_ticket'"
    ],
    { pwd, user }
  )
  if (r.status !== 0) return { ok: false, error: (r.stderr || '').trim() }
  return { ok: (r.stdout || '').trim() === '1', error: '' }
}

function importSql(mysql, pwd, user, file) {
  const buf = fs.readFileSync(file)
  const r = mysqlRun(mysql, ['--default-character-set=utf8mb4'], { pwd, user, input: buf })
  if (r.status !== 0) throw new Error((r.stderr || '').trim() || '导入失败')
}

/* ------------------------------ 启动 ------------------------------ */
function startBackend(cfg, port) {
  const java = which('java')
  if (!java) die('找不到 java，请先安装 JDK 17 并加入 PATH')

  const out = fs.createWriteStream(path.join(LOG_DIR, 'server.log'), { flags: 'w' })
  const child = spawn(
    java,
    [
      '-jar',
      JAR,
      `--server.port=${port}`,
      `--helpdesk.redis-enabled=${cfg.REDIS_ENABLED}`,
      `--helpdesk.qrcode.base-url=${cfg.QRCODE_BASE_URL}`
    ],
    {
      cwd: path.join(ROOT, 'server'),
      env: backendEnvironment(cfg),
      windowsHide: true
    }
  )
  child.stdout.pipe(out)
  child.stderr.pipe(out)
  child.on('exit', (code) => {
    if (!stopping && code !== 0) fail(`后端进程退出（code ${code}），日志：logs/server.log`)
  })
  children.push(child)
  return child
}

function startWeb(webPort, apiPort) {
  const out = fs.createWriteStream(path.join(LOG_DIR, 'web.log'), { flags: 'w' })
  const child = spawn(`npm run dev -- --port ${webPort} --strictPort`, {
    cwd: WEB_DIR,
    shell: true,
    env: {
      ...process.env,
      BROWSER: 'none',
      VITE_API_TARGET: `http://127.0.0.1:${apiPort}`,
      VITE_WEB_PORT: String(webPort)
    },
    windowsHide: true
  })
  child.stdout.pipe(out)
  child.stderr.pipe(out)
  children.push(child)
  return child
}

function openBrowser(url) {
  if (NO_BROWSER) return
  if (process.platform === 'win32') {
    spawn('explorer', [url], { detached: true, stdio: 'ignore' }).unref()
  } else if (process.platform === 'darwin') {
    spawn('open', [url], { detached: true, stdio: 'ignore' }).unref()
  } else {
    spawn('xdg-open', [url], { detached: true, stdio: 'ignore' }).unref()
  }
}

function stopAll() {
  if (stopping) return
  stopping = true
  console.log('\n正在停止服务…')
  for (const child of children) {
    if (!child || !child.pid) continue
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true })
    } else {
      try {
        child.kill('SIGTERM')
      } catch {}
    }
  }
  console.log('已停止。下次双击 start.cmd 即可。\n')
}

process.on('SIGINT', () => {
  stopAll()
  process.exit(0)
})
process.on('SIGTERM', () => {
  stopAll()
  process.exit(0)
})
process.on('exit', () => {
  if (!stopping) stopAll()
})

/* ------------------------------ 主流程 ------------------------------ */
async function main() {
  title('内部 IT 报修平台 · 本地启动')

  /* 1. 环境 */
  step('检查运行环境')
  ok(`Node ${process.versions.node}`)
  const java = which('java')
  if (!java) die('找不到 java，请安装 JDK 17 并加入 PATH')
  const javaVer = spawnSync('java', ['-version'], { encoding: 'utf8' })
  const verText = (javaVer.stderr || '') + (javaVer.stdout || '')
  const mv = verText.match(/version "(\d+)/)
  if (!mv || Number(mv[1]) < 17) die(`JDK 版本过低（需要 17+）：${verText.split('\n')[0]}`)
  ok(`JDK ${mv[1]}`)

  if (!fs.existsSync(JAR)) {
    die(`找不到后端 jar：${JAR}\n    请先构建：cd server && mvn package`)
  }
  ok(`后端 jar：${JAR}`)

  fs.mkdirSync(LOG_DIR, { recursive: true })

  if (!fs.existsSync(path.join(WEB_DIR, 'node_modules'))) {
    if (!AUTO_INSTALL) die('前端依赖未安装，请先 cd web && npm install')
    step('安装前端依赖（首次运行，约 1-2 分钟）')
    const r = spawnSync('npm install --no-audit --no-fund', { cwd: WEB_DIR, shell: true, stdio: 'inherit' })
    if (r.status !== 0) die('npm install 失败')
    ok('前端依赖安装完成')
  } else {
    ok('前端依赖已安装')
  }

  /* 2. 端口（先选，登录二维码地址要用） */
  step('选择端口')
  BACK_PORT = await pickPort([8080, 8081, 8082, 8083])
  if (!BACK_PORT) die('8080-8083 都被占用，请先释放一个端口')
  ok(`后端 ${BACK_PORT}${BACK_PORT === 8080 ? '' : '（8080 被占用，已自动避让）'}`)

  WEB_PORT = await pickPort([5173, 5174, 5175, 5176])
  if (!WEB_PORT) die('5173-5176 都被占用，请先释放一个端口')
  WEB_URL = `http://localhost:${WEB_PORT}`
  ok(`前端 ${WEB_PORT}${WEB_PORT === 5173 ? '' : '（5173 被占用，已自动避让）'}`)

  /* 3. 配置与数据库 */
  const env = loadEnv()
  // .env 里若是默认的 localhost:517x，跟随本次实际前端端口；自定义内网地址则保留
  const savedQr = env.QRCODE_BASE_URL || ''
  const qrBase = savedQr && !/^https?:\/\/localhost:517\d$/.test(savedQr) ? savedQr : WEB_URL
  const cfg = {
    MYSQL_USER: env.MYSQL_USER || 'root',
    MYSQL_ROOT_PASSWORD: env.MYSQL_ROOT_PASSWORD || '',
    REDIS_ENABLED: env.REDIS_ENABLED || 'false',
    QRCODE_BASE_URL: qrBase,
    JWT_SECRET: env.JWT_SECRET || 'tzme-helpdesk-change-this-secret-in-production-2026',
    ...readDingTalkConfig(env)
  }
  ok(`配置文件：${ENV_FILE}`)

  const mysql = findMysql()
  if (!mysql) {
    warn('未找到 mysql 客户端，跳过数据库自动初始化')
    warn('请确认库已建好，否则后端启动会失败（见 LOCAL-RUN.md）')
  } else {
    step('检查数据库')
    let pwd = cfg.MYSQL_ROOT_PASSWORD
    let state = pwd ? dbReady(mysql, pwd, cfg.MYSQL_USER) : { ok: false, error: 'no-password' }

    if (!state.ok && (!pwd || /Access denied|no-password/.test(state.error))) {
      console.log('  需要 MySQL 账号密码来建库导数据（仅保存在本机 .env，不会提交）。')
      let authed = false
      for (let i = 0; i < 3 && !authed; i++) {
        const input = await ask(`  MySQL ${cfg.MYSQL_USER} 密码${i ? '（重试）' : ''}：`)
        if (!input) {
          warn('密码不能为空')
          continue
        }
        pwd = input
        state = dbReady(mysql, pwd, cfg.MYSQL_USER)
        if (/Access denied/.test(state.error || '')) {
          fail('密码不正确')
          continue
        }
        authed = true
      }
      if (!authed) die('没有拿到可用的 MySQL 密码。可在 Workbench 建专用账号后写入 .env，见 LOCAL-RUN.md')
    }

    if (state.ok && !RESET_DB) {
      ok('数据库 it_helpdesk 已存在')
    } else {
      step(RESET_DB ? '重置数据库（--reset-db）' : '初始化数据库')
      try {
        importSql(mysql, pwd, cfg.MYSQL_USER, path.join(ROOT, 'sql', 'schema.sql'))
        ok('建表完成（12 张表）')
        importSql(mysql, pwd, cfg.MYSQL_USER, path.join(ROOT, 'sql', 'seed.sql'))
        ok('演示数据导入完成（15 张工单 / 11 台资产）')
      } catch (e) {
        die('导入 SQL 失败：' + e.message)
      }
    }
    cfg.MYSQL_ROOT_PASSWORD = pwd
    writeEnv(cfg)
    ok('配置已写入 .env')
  }

  /* 4. 后端 */
  step('启动后端（首次约 10-20 秒）')
  startBackend(cfg, BACK_PORT)
  const backOk = await waitHttp(`http://127.0.0.1:${BACK_PORT}/api/auth/config`, 90000)
  if (!backOk) {
    warn('后端未在 90 秒内就绪，最后 20 行日志：')
    try {
      const log = fs.readFileSync(path.join(LOG_DIR, 'server.log'), 'utf8').trim().split(/\r?\n/)
      console.log(log.slice(-20).map((l) => '    ' + l).join('\n'))
    } catch {}
    stopAll()
    die('后端启动失败')
  }
  try {
    const response = await fetch(`http://127.0.0.1:${BACK_PORT}/api/auth/config`, { signal: AbortSignal.timeout(5000) })
    const result = await response.json()
    if (!response.ok || result.code !== 0) throw new Error('无法读取后端登录配置')
    verifyLoginConfig(result.data, cfg)
  } catch (error) { stopAll(); die(error.message || '登录配置检查失败') }
  ok(`登录模式：${cfg.DINGTALK_ENABLED === 'true' ? '钉钉免登（演示登录已关闭）' : '本地演示'}`)
  ok(`后端已就绪：http://localhost:${BACK_PORT}/api`)

  /* 5. 前端 */
  step('启动前端')
  startWeb(WEB_PORT, BACK_PORT)
  const webOk = await waitHttp(WEB_URL, 90000)
  if (!webOk) {
    warn('前端未在 90 秒内就绪，最后 20 行日志：')
    try {
      const log = fs.readFileSync(path.join(LOG_DIR, 'web.log'), 'utf8').trim().split(/\r?\n/)
      console.log(log.slice(-20).map((l) => '    ' + l).join('\n'))
    } catch {}
    stopAll()
    die('前端启动失败')
  }
  ok(`前端已就绪：${WEB_URL}`)

  /* 6. 完成 */
  title('启动完成')
  console.log(`  访问地址   ${WEB_URL}`)
  console.log(`  后端接口   http://localhost:${BACK_PORT}/api`)
  console.log(`  Redis      ${cfg.REDIS_ENABLED === 'false' ? '已关闭（单机降级）' : '已启用'}`)
  console.log(`  日志       logs/server.log  logs/web.log`)
  console.log('')
  if (cfg.DINGTALK_ENABLED === 'true' && cfg.DINGTALK_DEMO_LOGIN !== 'true') {
    console.log('  当前为钉钉免登，请从已配置的钉钉工作台应用入口打开。')
    console.log('  普通浏览器不会提供演示身份切换。')
  } else {
    console.log('  本地演示：在右上角「我的账户」切换身份。')
    console.log('  谈帥宏、刘宇泽、朱思宇（普通用户），林凤新（IT），邵昕炜（超级管理员）。')
  }
  console.log('')
  console.log('  按 Ctrl+C 停止所有服务。')
  console.log(line('═'))

  openBrowser(WEB_URL)
  await new Promise(() => {})
}

main().catch((e) => {
  fail(e && e.stack ? e.stack : String(e))
  stopAll()
  process.exit(1)
})
