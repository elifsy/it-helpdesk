// 构建包 + 隔离浏览器 + 内存模拟接口，验证普通用户工单入口、分类补充和加载恢复。
import fs from 'node:fs/promises'
import path from 'node:path'
import http from 'node:http'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const bundle = process.env.HELPDESK_UI_DIST ? path.resolve(process.env.HELPDESK_UI_DIST) : fileURLToPath(new URL('../web/dist/', import.meta.url))
const profile = fileURLToPath(new URL('../server/target/employee-ticket-profile-' + Date.now() + '/', import.meta.url))
await fs.mkdir(profile, { recursive: true })
const tickets = [
  { no: 'IT-UI-0001', title: '设备无法开机', status: 'IN_PROGRESS', assigneeId: 4, assigneeName: '测试工程师', queueAhead: 2 },
  { no: 'IT-UI-0002', title: '网络连接故障', status: 'CLOSED', assigneeId: 4, assigneeName: '测试工程师', queueAhead: null }
].map(row => ({ ...row, priority: 'P3', creatorId: 1, creatorName: '测试员工', deptName: '测试部门', cat1: '终端', cat2: '故障',
  type: 'INCIDENT', description: '用于检查用户工单详情', createdAt: '2026-11-12 10:00:00', remainMinutes: 180 }))
const state = { missingUntilReload: false, permanentMissing: false, failedDetails: 0, failedAttachments: 0, slowAttachments: false, detailRequests: [], attachmentRequests: 0, droppedChunks: 0 }
const submitted = []
let dictionaryRevision = 0
let categoriesByType = {
  INCIDENT: { 终端: ['无法开机', '其他'], 会议室: ['投屏故障', '其他'], 其他: ['其他'] },
  SERVICE_REQUEST: { 账号: ['账号开通', '权限申请', '其他'], 系统: ['软件安装', '其他'], 其他: ['其他'] },
  QUESTION: { 系统: ['软件使用', '邮箱使用', '其他'], 终端: ['设备使用', '其他'], 其他: ['其他'] }
}
let fixtureRole = 'USER'
const basePermissions = ['ticket.view.own', 'ticket.create', 'ticket.confirm', 'asset.view', 'request.view.own', 'request.create', 'kb.view', 'kb.vote', 'attachment.upload', 'attachment.delete']
const rolePermissions = {
  USER: [], IT: ['ticket.view.all', 'ticket.accept', 'ticket.handle', 'asset.create', 'request.view.all', 'request.approve', 'stats.view', 'dict.manage'],
  ADMIN: ['ticket.view.all', 'ticket.accept', 'ticket.handle', 'asset.create', 'request.view.all', 'request.approve', 'stats.view', 'dict.manage', 'settings.manage', 'role.manage'],
  VIEWER: ['ticket.view.all', 'request.view.all', 'stats.view']
}
const personalAssets = [
  { no: 'SELF-IDLE', name: '可领用设备', category: '终端', status: '闲置', location: 'A座1楼备件库' },
  { no: 'SELF-OWNED', name: '本人设备', category: '终端', status: '在用', userName: '测试员工' },
  { no: 'SELF-BORROWED', name: '本人借用设备', category: '终端', status: '借出中', borrower: '测试员工' }
]
const personalApplications = [], ticketReads = []
const fixturePolicy = () => ({ revision: 0, selfServiceVersion: 1, selfServicePermissions: basePermissions, roles: ['USER', 'IT', 'ADMIN', 'VIEWER'],
  roleNames: { USER: '普通用户', IT: 'IT', ADMIN: '管理员', VIEWER: '可查看用户' },
  capabilities: [
    { key: 'ticket.create', name: '提交报修', group: '工单', readOnly: false, requires: ['ticket.view.own'] },
    { key: 'request.create', name: '发起与撤销本人申请', group: '资产', readOnly: false, requires: ['asset.view', 'request.view.own'] },
    { key: 'asset.create', name: '新增、编辑资产与管理分类', group: '资产', readOnly: false, requires: ['asset.view'] },
    { key: 'role.manage', name: '配置角色权限', group: '系统管理', readOnly: false, requires: [] }
  ], assignments: Object.fromEntries(Object.keys(rolePermissions).map(role => [role, [...basePermissions, ...rolePermissions[role]]])),
  defaults: Object.fromEntries(Object.keys(rolePermissions).map(role => [role, [...basePermissions, ...rolePermissions[role]]])) })
let fixtureFeedbackAllowed = true
let failNextFeedback = false, feedbackDelay = 0, noRecommendations = false
const feedbackAttempts = [], feedbackSaved = []
const knowledge = [
  { id: 41, title: '笔记本连不上公司 WiFi 的自查四步', summary: '先看是否全局断，再排查账号认证', content: '一、确认是否只有你一台设备异常。二、重新连接公司 WiFi。', helpful: 215 },
  { id: 42, title: 'VPN 连接失败的常见原因', summary: '证书过期、密码变更、客户端太旧', content: '一、检查账号密码。二、检查客户端版本。', helpful: 131 }
]
const dictionaryWrites = []
const dictionaryData = () => ({ revision: dictionaryRevision, categoriesByType, categories: Object.assign({}, ...Object.values(categoriesByType)) })
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://fixture')
  if (url.pathname === '/' && state.missingUntilReload) state.missingUntilReload = false
  const json = (data, status = 200, message = '') => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify({ code: status === 200 ? 0 : status, data, message })) }
  if (url.pathname.startsWith('/api/')) {
    let data = []
    if (url.pathname === '/api/auth/config') data = { demoEnabled: false, corpId: 'fixture-only' }
    else if (url.pathname === '/api/auth/me') data = { id: 1, name: '测试员工', deptName: '测试部门', roles: [fixtureRole], role: fixtureRole, permissions: [...basePermissions.filter(key => fixtureFeedbackAllowed || key !== 'kb.vote'), ...rolePermissions[fixtureRole]] }
    else if (url.pathname.includes('unread')) data = { count: 0 }
    else if (url.pathname === '/api/meta/runtime') data = { maxAttachments: 3, maxUploadMb: 5, kbRecommendEnabled: true, requireSolutionCode: true }
    else if (url.pathname === '/api/meta/categories') data = { 终端: ['无法开机', '其他'], 会议室: ['投屏', '其他'], 其他: ['其他'] }
    else if (url.pathname === '/api/meta/categories-by-type') data = categoriesByType
    else if (url.pathname === '/api/assets') data = personalAssets
    else if (url.pathname === '/api/asset-categories') data = [{ id: 1, name: '终端', sort: 0, assetCount: personalAssets.length }]
    else if (url.pathname === '/api/asset-requests' && req.method === 'POST') {
      let body = ''; for await (const part of req) body += part
      const payload = JSON.parse(body)
      data = { ...payload, id: personalApplications.length + 1, no: 'AS-UI-' + (personalApplications.length + 1), applicant: '测试员工', status: 'PENDING', fixtureRole }
      personalApplications.push(data)
    }
    else if (url.pathname === '/api/asset-requests') data = personalApplications
    else if (url.pathname === '/api/admin/manage/permissions') data = fixturePolicy()
    else if (url.pathname === '/api/kb/recommend') data = noRecommendations ? [] : knowledge
    else if (/^\/api\/kb\/\d+\/vote$/.test(url.pathname)) {
      let body = ''; for await (const part of req) body += part
      const payload = { id: Number(url.pathname.split('/')[3]), ...JSON.parse(body) }
      feedbackAttempts.push(payload)
      if (feedbackDelay) await new Promise(resolve => setTimeout(resolve, feedbackDelay))
      if (failNextFeedback) { failNextFeedback = false; json(null, 503, '反馈暂时不可用'); return }
      feedbackSaved.push(payload)
    }
    else if (/^\/api\/kb\/\d+$/.test(url.pathname)) data = knowledge.find(article => article.id === Number(url.pathname.split('/').pop()))
    else if (url.pathname === '/api/admin/manage/dictionary') data = dictionaryData()
    else if (url.pathname === '/api/admin/manage/categories' && req.method === 'PUT') {
      let body = ''; for await (const part of req) body += part
      const payload = JSON.parse(body); dictionaryWrites.push(payload)
      categoriesByType = payload.categoriesByType; dictionaryRevision++; data = dictionaryData()
    }
    else if (url.pathname === '/api/tickets' && req.method === 'POST') {
      let body = ''; for await (const part of req) body += part
      const payload = JSON.parse(body); submitted.push(payload)
      data = { ...tickets[0], ...payload, no: 'IT-UI-NEW-' + submitted.length, status: 'NEW', assigneeId: null }
      tickets.push(data)
    }
    else if (url.pathname === '/api/tickets') { ticketReads.push({ role: fixtureRole, mine: url.searchParams.get('mine') }); data = { list: tickets, total: tickets.length, page: 1, size: 5 } }
    else if (url.pathname.startsWith('/api/tickets/')) {
      const no = decodeURIComponent(url.pathname.split('/').pop())
      state.detailRequests.push(no)
      if (state.failedDetails > 0) { state.failedDetails--; json(null, 503, '详情暂时不可用'); return }
      const ticket = tickets.find(row => row.no === no)
      if (!ticket) { json(null, 404, '工单不存在'); return }
      data = { ticket, actions: [], records: [{ id: 1, kind: 'REMOTE', content: '工程师正在处理', costMin: 10, operator: '测试工程师', createdAt: '2026-11-12 10:10:00' }], events: [{ id: 1, action: 'ACCEPT', remark: '已接单', operator: '测试工程师', createdAt: '2026-11-12 10:05:00' }] }
    } else if (url.pathname === '/api/attachments') {
      state.attachmentRequests++
      if (state.slowAttachments) await new Promise(resolve => setTimeout(resolve, 2000))
      if (state.failedAttachments > 0) { state.failedAttachments--; json(null, 500, '附件暂时不可用'); return }
    }
    if (!res.destroyed) json(data)
    return
  }
  try {
    if (/\/TicketDetail-[^/]+\.js$/.test(url.pathname) && (state.missingUntilReload || state.permanentMissing)) {
      state.droppedChunks++; res.writeHead(404, { 'Cache-Control': 'no-store' }); res.end(); return
    }
    const file = path.resolve(bundle, '.' + (url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname)))
    if (!file.startsWith(bundle)) throw new Error('outside fixture')
    let data = await fs.readFile(file)
    if (file.endsWith('index.html')) data = Buffer.from(data.toString().replace(/<script src="https:[^"]*dingtalk[^<]*<\/script>/g, ''))
    res.writeHead(200, { 'Content-Type': { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' }[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' }); res.end(data)
  } catch { res.writeHead(404); res.end() }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const frontend = 'http://127.0.0.1:' + server.address().port
const browser = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' })
let socket, next = 0
const pending = new Map(), exceptions = [], checks = []
const pause = delay => new Promise(resolve => setTimeout(resolve, delay))
function call(method, params = {}) {
  const id = ++next
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)) }, 10000)
    pending.set(id, { resolve: value => { clearTimeout(timer); resolve(value) }, reject: error => { clearTimeout(timer); reject(error) } })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text)
  return result.result?.value
}
async function until(expression) {
  const deadline = Date.now() + 12000
  while (Date.now() < deadline) { try { if (await evaluate(expression)) return } catch {} await pause(100) }
  throw new Error('UI timeout: ' + expression + '\n' + await evaluate('location.href + "\\n" + document.body.innerText'))
}
async function click(selector) {
  const point = await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`)
  await call('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point })
  await call('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point })
}
async function home() { await evaluate('location.hash="/home"'); await until('document.querySelectorAll(".home .wo").length===2') }
async function detail(no) { await until('document.querySelector(".status-card .mono")?.textContent===' + JSON.stringify(no)) }
async function freshHome() { await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/home' }); await until('document.querySelectorAll(".home .wo").length===2') }
const pass = label => { checks.push(label); console.log('PASS ' + label) }
try {
  let port
  for (let i = 0; i < 60 && !port; i++) { try { port = Number((await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]) } catch { await pause(100) } }
  assert.ok(port)
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json()
  socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl)
  await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), callback = pending.get(message.id)
    if (callback) { pending.delete(message.id); message.error ? callback.reject(new Error(message.error.message)) : callback.resolve(message.result) }
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text)
  })
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setCacheDisabled', { cacheDisabled: true })
  await call('Emulation.setFocusEmulationEnabled', { enabled: true })
  await call('Page.addScriptToEvaluateOnNewDocument', { source: "if(location.protocol==='http:')localStorage.setItem('it_helpdesk_token','fixture-only-token')" })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await freshHome()
  assert.ok(await evaluate('document.querySelector(".home a.hero").getAttribute("href")==="#/ticket/IT-UI-0001"'))
  await click('.hero .f b'); await detail('IT-UI-0001')
  assert.ok(await evaluate('document.querySelector(".queue-summary").textContent.includes("前面还有 2 单待处理") && document.querySelector(".tl").textContent.includes("已接单")'))
  pass('desktop 查看进度 opens its own ticket, queue and timeline')
  await home(); await click('.home .wo:nth-of-type(2)'); await detail('IT-UI-0001')
  await home(); await click('.home .wo:last-of-type'); await detail('IT-UI-0002')
  assert.ok(await evaluate('document.querySelector(".status-card .ttl").textContent==="网络连接故障"'))
  await call('Page.reload'); await detail('IT-UI-0002')
  pass('recent tickets open their selected detail and keep the selection after reload')
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await home(); await click('.hero .f b'); await detail('IT-UI-0001')
  await home(); await click('.home .wo:last-of-type'); await detail('IT-UI-0002')
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'))
  pass('both entry points work on phone screens without horizontal overflow')
  await home(); state.failedDetails = 1; await click('.hero .f b')
  await until('document.querySelector(".detail-error")?.textContent.includes("详情暂时不可用")')
  await click('.detail-error .btn.pri'); await detail('IT-UI-0001')
  pass('a failed detail request shows a persistent retry that loads the same ticket')
  await home(); state.failedAttachments = 1; await click('.hero .f b'); await detail('IT-UI-0001')
  await until('!!document.querySelector(".attachment-error")'); await click('.attachment-error button')
  await until('!document.querySelector(".attachment-error") && document.querySelector(".detail").textContent.includes("还没有附件")')
  pass('attachment failures leave ticket progress usable and can be retried separately')
  await home(); state.slowAttachments = true; const requestCount = state.attachmentRequests; await click('.hero .f b')
  await detail('IT-UI-0001'); assert.ok(state.attachmentRequests > requestCount)
  assert.ok(await evaluate('document.querySelector(".detail").textContent.includes("附件加载中")'))
  state.slowAttachments = false
  pass('slow attachments do not delay the ticket detail')
  assert.deepEqual(exceptions, [])
  await freshHome(); await evaluate("sessionStorage.removeItem('it_helpdesk_navigation_reload')")
  state.missingUntilReload = true; await click('.hero .f b'); await detail('IT-UI-0001')
  assert.ok(state.droppedChunks >= 1)
  const recovery = await evaluate('({hash:location.hash,type:performance.getEntriesByType("navigation")[0].type,marker:sessionStorage.getItem("it_helpdesk_navigation_reload")})')
  assert.equal(recovery.hash, '#/ticket/IT-UI-0001')
  assert.ok(recovery.marker, JSON.stringify(recovery))
  pass('a missing lazy module automatically recovers while retaining the selected ticket')
  await freshHome(); state.permanentMissing = true; await evaluate("sessionStorage.removeItem('it_helpdesk_navigation_reload')")
  await click('.hero .f b'); await until('document.querySelector(".boot button")?.textContent.includes("重新尝试")')
  const dropped = state.droppedChunks; await pause(1000); assert.equal(state.droppedChunks, dropped)
  assert.ok(await evaluate('location.hash==="#/ticket/IT-UI-0001"'))
  state.permanentMissing = false; await click('.boot button'); await detail('IT-UI-0001')
  pass('repeated module failures stop automatic reloads and manual retry retains the target')
  async function newTicket() {
    await evaluate('location.hash="/new"'); await until('document.querySelector("[aria-label=问题大类]")?.options.length===3')
  }
  async function selectCategory(label, value) {
    await evaluate(`(()=>{const e=document.querySelector('[aria-label="${label}"]');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('change',{bubbles:true}))})()`)
  }
  async function inputNote(value) {
    await evaluate(`(()=>{const e=document.querySelector('#ticket-category-note');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}))})()`)
  }
  async function submitNew(location = 'A座3楼财务办公室，第2个工位') {
    const expectedNo = 'IT-UI-NEW-' + (submitted.length + 1)
    await evaluate('(()=>{const e=document.querySelector(".new-ticket textarea");e.value="会议室设备需要排查故障";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
    await evaluate(`(()=>{const e=document.querySelector('#fault-location');e.value=${JSON.stringify(location)};e.dispatchEvent(new Event('input',{bubbles:true}))})()`)
    await click('.new-ticket > button'); await detail(expectedNo)
    assert.equal(submitted.at(-1).location, location.trim())
  }
  await newTicket()
  const beforeMissingLocation = submitted.length
  await evaluate('(()=>{const e=document.querySelector(".new-ticket textarea");e.value="打印机无法使用需要排查";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await click('.new-ticket > button')
  await until('document.querySelector("#fault-location-error")?.textContent.includes("请填写故障位置")')
  assert.equal(submitted.length, beforeMissingLocation)
  assert.ok(await evaluate('document.activeElement.id==="fault-location" && document.querySelector("#fault-location").required && document.querySelector("#fault-location").maxLength===64'))
  await evaluate('(()=>{const e=document.querySelector("#fault-location");e.value="   ";e.dispatchEvent(new Event("input",{bubbles:true}));const asset=document.querySelector("[aria-label=关联设备]");asset.value="SELF-IDLE";asset.dispatchEvent(new Event("change",{bubbles:true}))})()')
  await click('.new-ticket > button'); assert.equal(submitted.length, beforeMissingLocation)
  const locationShot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  await fs.writeFile(new URL('../shots/regression/ticket-location-required-mobile.png', import.meta.url), Buffer.from(locationShot.data, 'base64'))
  await submitNew('  B座3楼财务办公室，靠窗第2个工位  ')
  assert.equal(submitted.at(-1).assetNo, 'SELF-IDLE')
  assert.equal(await evaluate('document.querySelector(".ticket-location").textContent'), 'B座3楼财务办公室，靠窗第2个工位')
  await call('Page.reload'); await detail('IT-UI-NEW-' + submitted.length)
  assert.equal(await evaluate('document.querySelector(".ticket-location").textContent'), 'B座3楼财务办公室，靠窗第2个工位')
  pass('fault location is required even for linked equipment, trimmed on submit and retained in the detail after reload')
  await newTicket()
  await click('[data-ticket-type="QUESTION"]')
  await until('document.querySelector("[aria-label=问题大类]")?.value==="系统" && document.querySelector("[aria-label=问题子分类]")?.value==="软件使用"')
  assert.ok(await evaluate('!document.querySelector("[aria-label=问题子分类]").textContent.includes("无法开机") && document.querySelector("[data-ticket-type=QUESTION]").getAttribute("aria-pressed")==="true"'))
  await selectCategory('问题大类', '其他'); await inputNote('旧咨询补充')
  await click('[data-ticket-type="SERVICE_REQUEST"]')
  await until('document.querySelector("[aria-label=问题大类]")?.value==="账号" && document.querySelector("[aria-label=问题子分类]")?.value==="账号开通"')
  assert.equal(await evaluate('!!document.querySelector("#ticket-category-note")'), false)
  assert.ok(await evaluate('!document.querySelector("[aria-label=问题子分类]").textContent.includes("无法开机")'))
  await submitNew()
  assert.equal(submitted.at(-1).type, 'SERVICE_REQUEST'); assert.equal(submitted.at(-1).cat2, '账号开通'); assert.equal(submitted.at(-1).categoryNote, null)
  pass('switching question type replaces both category levels, clears stale notes, and submits the matching type')
  await newTicket(); assert.equal(await evaluate('!!document.querySelector("#ticket-category-note")'), false)
  await selectCategory('问题子分类', '其他'); await until('!!document.querySelector("#ticket-category-note")')
  assert.ok(await evaluate('!document.querySelector("#ticket-category-note").required && document.querySelector(".category-note label").textContent.includes("选填")'))
  await inputNote('会议室触控屏'); await selectCategory('问题子分类', '无法开机')
  await until('!document.querySelector("#ticket-category-note")'); await selectCategory('问题子分类', '其他')
  assert.equal(await evaluate('document.querySelector("#ticket-category-note").value'), '')
  await inputNote('不会带入的旧分类'); await selectCategory('问题大类', '其他')
  assert.equal(await evaluate('document.querySelector("#ticket-category-note").value'), '')
  const customNote = '  会议室音视频 <img src=x onerror=window.__unsafe=1>  '
  await inputNote(customNote)
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'))
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  const formShot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/other-category-mobile.png', import.meta.url), Buffer.from(formShot.data, 'base64'))
  await submitNew()
  assert.equal(submitted.at(-1).categoryNote, customNote.trim())
  assert.equal(submitted.at(-1).cat1, '其他'); assert.equal(submitted.at(-1).cat2, '其他')
  assert.equal(await evaluate('document.querySelector(".detail .category-note b")?.textContent'), customNote.trim())
  assert.ok(await evaluate('!document.querySelector(".detail .category-note img") && !window.__unsafe'))
  pass('Other in either category shows an optional field; saved text is safely displayed in detail on mobile')
  await newTicket(); await selectCategory('问题大类', '其他'); await submitNew()
  assert.equal(submitted.at(-1).categoryNote, null)
  assert.equal(await evaluate('!!document.querySelector(".detail .category-note")'), false)
  pass('Other can be submitted without a note')
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await newTicket(); await selectCategory('问题子分类', '其他'); await inputNote('旧内容')
  await selectCategory('问题子分类', '无法开机'); await submitNew()
  assert.equal(submitted.at(-1).categoryNote, null)
  pass('switching back to a predefined category clears the hidden note and sends no stale text')
  fixtureRole = 'IT'
  await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/admin/config?tab=dict' })
  await until('document.querySelectorAll("[data-category-type]").length===3 && document.querySelectorAll(".category-row").length===3')
  await click('[data-category-type="QUESTION"]')
  await until('document.querySelector(".category-row input")?.value==="系统"')
  await evaluate('(()=>{const e=document.querySelector(".category-row textarea");e.value="软件使用，咨询测试，其他";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await click('[data-category-type="INCIDENT"]')
  assert.equal(await evaluate('document.querySelector(".category-row input").value'), '终端')
  await click('[data-category-type="QUESTION"]')
  assert.ok(await evaluate('document.querySelector(".category-row textarea").value.includes("咨询测试")'))
  await click('.save-bar button'); await until('!document.querySelector(".save-bar")')
  const dictionaryShot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/type-category-config.png', import.meta.url), Buffer.from(dictionaryShot.data, 'base64'))
  assert.equal(dictionaryWrites.length, 1)
  assert.deepEqual(dictionaryWrites[0].categoriesByType.INCIDENT.终端, ['无法开机', '其他'])
  assert.ok(dictionaryWrites[0].categoriesByType.QUESTION.系统.includes('咨询测试'))
  fixtureRole = 'USER'
  await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/new' })
  await until('document.querySelector("[aria-label=问题大类]")?.options.length===3')
  await click('[data-ticket-type="QUESTION"]')
  await until('document.querySelector("[aria-label=问题子分类]")?.textContent.includes("咨询测试")')
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  const questionShot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/type-category-question-mobile.png', import.meta.url), Buffer.from(questionShot.data, 'base64'))
  await selectCategory('问题子分类', '咨询测试'); await submitNew()
  assert.equal(submitted.at(-1).type, 'QUESTION'); assert.equal(submitted.at(-1).cat2, '咨询测试')
  pass('a delegated dictionary editor saves each type independently and employee forms use the updated matching choices')
  async function recommendKnowledge() {
    await evaluate('(()=>{const e=document.querySelector(".new-ticket textarea");e.value="笔记本无法连接公司无线网络";e.dispatchEvent(new Event("input",{bubbles:true}));Array.from(document.querySelectorAll(".new-ticket button")).find(b=>b.textContent.includes("先看看有没有解决办法")).click()})()')
    await until('!!document.querySelector(".knowledge-recommendations")')
  }
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await newTicket(); await recommendKnowledge()
  assert.equal(await evaluate('document.querySelectorAll(".knowledge-recommendations [data-kb-resolve]").length'), 2)
  const beforeResolution = submitted.length
  const beforeAttempts = feedbackAttempts.length
  feedbackDelay = 700
  await evaluate('(()=>{const b=document.querySelector("[data-kb-resolve=\\"42\\"]");b.click();b.click()})()')
  await until('document.querySelector("[data-kb-resolve=\\"42\\"]")?.disabled')
  assert.ok(await evaluate('Array.from(document.querySelectorAll(".knowledge-recommendations button")).every(b=>b.disabled)'))
  await until('!!document.querySelector(".home .entries")')
  assert.equal(feedbackAttempts.length, beforeAttempts + 1)
  assert.deepEqual(feedbackSaved.at(-1), { id: 42, helpful: true })
  assert.equal(submitted.length, beforeResolution)
  feedbackDelay = 0
  pass('each recommendation has 已解决; clicking one records that exact article once and exits without creating a ticket')

  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await newTicket(); await recommendKnowledge()
  await evaluate('document.querySelectorAll(".knowledge-recommendations .kbi a").forEach(a=>a.click())')
  await until('document.querySelector(".knowledge-recommendations").textContent.includes("重新连接公司 WiFi")')
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth && Array.from(document.querySelectorAll(".knowledge-recommendations [data-kb-resolve]")).every(b=>{const r=b.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})'))
  const resolveShot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/knowledge-resolved-mobile.png', import.meta.url), Buffer.from(resolveShot.data, 'base64'))
  const successesBeforeFailure = feedbackSaved.length
  failNextFeedback = true; await click('[data-kb-resolve="41"]')
  await until('document.querySelector(".kb-resolve-error")?.textContent.includes("反馈暂时不可用")')
  assert.equal(await evaluate('document.querySelector(".new-ticket textarea").value'), '笔记本无法连接公司无线网络')
  assert.equal(feedbackSaved.length, successesBeforeFailure)
  assert.equal(submitted.length, beforeResolution)
  await click('[data-kb-resolve="41"]'); await until('!!document.querySelector(".home .entries")')
  assert.deepEqual(feedbackSaved.at(-1), { id: 41, helpful: true })
  assert.equal(submitted.length, beforeResolution)
  pass('phone buttons fit the dialog; failed feedback preserves the form and retry only saves the selected article')

  await newTicket(); await recommendKnowledge()
  const votesBeforeContinue = feedbackAttempts.length
  await click('.knowledge-recommendations .dlg-f button'); await until('!document.querySelector(".knowledge-recommendations")')
  assert.equal(await evaluate('document.querySelector(".new-ticket textarea").value'), '笔记本无法连接公司无线网络')
  assert.equal(feedbackAttempts.length, votesBeforeContinue)
  await submitNew()
  assert.equal(submitted.at(-1).triedKb, 1); assert.equal(submitted.at(-1).kbHit, '41,42')
  pass('还是不行 continues the existing repair form and only explicit submission creates a ticket')

  noRecommendations = true; await newTicket(); await recommendKnowledge()
  assert.equal(await evaluate('document.querySelectorAll(".knowledge-recommendations [data-kb-resolve]").length'), 0)
  await click('.knowledge-recommendations .dlg-f button'); noRecommendations = false
  fixtureFeedbackAllowed = false
  await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/new' })
  await until('document.querySelector("[aria-label=问题大类]")?.options.length===3')
  await recommendKnowledge()
  assert.equal(await evaluate('document.querySelectorAll(".knowledge-recommendations .kbi").length'), 2)
  assert.equal(await evaluate('document.querySelectorAll(".knowledge-recommendations [data-kb-resolve]").length'), 0)
  assert.equal(feedbackAttempts.length, votesBeforeContinue)
  await click('.knowledge-recommendations .dlg-f button')
  pass('empty recommendations keep the continue action and feedback buttons follow the configured role permission')
  fixtureFeedbackAllowed = true
  await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/new' })
  await until('document.querySelector("[aria-label=问题大类]")?.options.length===3')
  await recommendKnowledge(); feedbackDelay = 700
  const attemptsBeforeLeave = feedbackAttempts.length
  await click('[data-kb-resolve="41"]')
  await until('document.querySelector(".knowledge-recommendations [data-kb-resolve]")?.disabled')
  await evaluate('location.hash="/tickets"')
  await until('!document.querySelector(".knowledge-recommendations")')
  await pause(900)
  assert.equal(await evaluate('location.hash'), '#/tickets')
  assert.equal(feedbackAttempts.length, attemptsBeforeLeave + 1)
  assert.equal(submitted.length, beforeResolution + 1)
  pass('leaving during a slow feedback request does not redirect a later page or create a ticket')
  for (const role of ['USER', 'IT', 'ADMIN', 'VIEWER']) {
    fixtureRole = role
    await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
    await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/home' })
    await until('!!document.querySelector(".home .entries")')
    assert.equal(await evaluate('location.hash'), '#/home')
    const personalLabels = await evaluate('Array.from(document.querySelectorAll(".side .mi")).map(b=>b.textContent.trim())')
    for (const label of ['首页', '我要报修', '我的工单', '资产服务', '知识库']) assert.ok(personalLabels.some(text => text.includes(label)), role + ' missing ' + label)
    await call('Page.reload'); await until('!!document.querySelector(".home .entries")')
    assert.equal(await evaluate('location.hash'), '#/home')
    await evaluate('Array.from(document.querySelectorAll(".side .mi")).find(b=>b.textContent.includes("我要报修")).click()')
    await until('!!document.querySelector(".new-ticket")'); await submitNew()
    assert.equal(submitted.at(-1).type, 'INCIDENT')
    await evaluate('Array.from(document.querySelectorAll(".side .mi")).find(b=>b.textContent.includes("我的工单")).click()')
    await until('!!document.querySelector("main .tabs2")')
    assert.ok(ticketReads.some(read => read.role === role && read.mine === 'true'))
    await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
    const shortcuts = await evaluate('Array.from(document.querySelectorAll("nav.tabs button, .tabs button, .tabs a, .tabs .tab")).map(b=>b.textContent.trim())')
    for (const label of ['首页', '报修', '工单', '资产']) assert.ok(shortcuts.some(text=>text.includes(label)), role + ' missing phone ' + label)
    await evaluate('document.querySelector(".mobile-menu-button").click()'); await until('!!document.querySelector(".mobile-nav")')
    await evaluate('Array.from(document.querySelectorAll(".mobile-nav .mi")).find(b=>b.textContent.includes("资产服务")).click()')
    await until('document.querySelectorAll(".qcard").length===4')
    assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'))
    await evaluate('Array.from(document.querySelectorAll(".qcard")).find(card=>card.textContent.includes("领用")).click()')
    await until('!!document.querySelector(".asset-picker")')
    await click('.asset-picker .device-option[data-asset-no="SELF-IDLE"]'); await click('.picker-footer-actions .btn.pri')
    await until('!!document.querySelector(".mask textarea")')
    await evaluate('(()=>{const e=document.querySelector(".mask textarea");e.value="当前角色本人领用设备";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
    await click('.mask .dlg-f .btn.pri'); await until('!document.querySelector(".mask")')
    assert.equal(personalApplications.at(-1).fixtureRole, role)
    assert.equal(personalApplications.at(-1).type, 'RECEIVE')
    assert.equal(personalApplications.at(-1).assetNo, 'SELF-IDLE')
    if (role === 'VIEWER') {
      const roleShot = await call('Page.captureScreenshot', { format: 'png' })
      await fs.writeFile(new URL('../shots/regression/viewer-personal-services-mobile.png', import.meta.url), Buffer.from(roleShot.data, 'base64'))
    }
    pass(role + ' can use personal modules, refresh personal home, submit a ticket and apply for equipment on mobile')
  }
  fixtureRole = 'ADMIN'
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: 'about:blank' }); await call('Page.navigate', { url: frontend + '/#/admin/config?tab=matrix' })
  await until('!!document.querySelector("[data-role=VIEWER][data-permission=\\"request.create\\"]")')
  assert.ok(await evaluate('(()=>{const own=document.querySelector("[data-role=VIEWER][data-permission=\\"request.create\\"]"), manage=document.querySelector("[data-role=VIEWER][data-permission=\\"asset.create\\"]");return own.checked&&!own.disabled&&!manage.checked&&manage.disabled})()'))
  pass('role configuration allows viewer personal requests while keeping asset management controls read-only')
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  const shot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/employee-ticket-detail-mobile.png', import.meta.url), Buffer.from(shot.data, 'base64'))
  console.log('Employee ticket UI checks: ' + checks.length + ' passed')
} finally {
  if (socket?.readyState === WebSocket.OPEN) { socket.send(JSON.stringify({ id: ++next, method: 'Browser.close' })); await pause(150); socket.close() }
  if (browser.exitCode === null) browser.kill()
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
}
