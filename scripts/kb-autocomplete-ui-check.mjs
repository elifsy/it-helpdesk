// 使用构建包、独立浏览器和模拟只读接口，检查联想交互；不连接业务库或真实钉钉。
import fs from 'node:fs/promises'
import path from 'node:path'
import http from 'node:http'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const bundle = path.resolve(process.env.HELPDESK_UI_DIST || fileURLToPath(new URL('../web/dist/', import.meta.url)))
const profile = fileURLToPath(new URL('../server/target/kb-autocomplete-profile-' + Date.now() + '/', import.meta.url))
await fs.mkdir(profile, { recursive: true })
const articles = [
  { id: 1, title: '打印机脱机排查', summary: '恢复打印队列并检查连接', category: '打印复印' },
  { id: 2, title: '打印机卡纸处理', summary: '取出卡纸后恢复打印', category: '打印复印' },
  { id: 3, title: '打印机驱动安装', summary: '安装办公设备驱动', category: '打印复印' },
  { id: 4, title: '打印机双面设置', summary: '设置双面输出', category: '打印复印' },
  { id: 5, title: '办公室打印异常', summary: '检查纸张和耗材', category: '打印复印' },
  { id: 6, title: '共享设备排查', summary: '网络打印机连接检查', category: '网络' },
  { id: 7, title: '打印机连接设置', summary: '添加局域网设备', category: '打印复印' },
  { id: 8, title: 'VPN 连接失败', summary: '检查远程接入配置', category: '网络' },
  { id: 9, title: 'VPN 权限不足', summary: '申请远程办公权限', category: '账号' },
  { id: 10, title: '<img src=x onerror=window.__unsafe=1>', summary: '安全展示测试', category: '测试' }
].map(row => ({ ...row, views: 10, helpful: 1, notHelpful: 0 }))
const state = { role: 'IT', requests: [], slow: false, failNext: false }
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (url.pathname.startsWith('/api/')) {
    let data = []
    if (url.pathname === '/api/auth/config') data = { demoEnabled: false, corpId: 'fixture-only' }
    else if (url.pathname === '/api/auth/me') data = { id: 1, name: '搜索测试账号', deptName: '测试部门', roles: [state.role], role: state.role, permissions: ['kb.view', 'kb.vote'] }
    else if (url.pathname.includes('unread')) data = { count: 0 }
    else if (url.pathname === '/api/kb') {
      const keyword = (url.searchParams.get('keyword') || '').toLowerCase(), category = url.searchParams.get('category')
      state.requests.push({ keyword, category })
      if (state.failNext) { state.failNext = false; res.writeHead(503, { 'Content-Type': 'application/json' }); res.end('{"code":503,"message":"fixture unavailable"}'); return }
      data = articles.filter(row => (!category || category === row.category) && (row.title + ' ' + row.summary).toLowerCase().includes(keyword))
      if (state.slow && keyword === '打印') await new Promise(resolve => setTimeout(resolve, 750))
    }
    if (!res.destroyed) { res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ code: 0, data })) }
    return
  }
  try {
    const file = path.resolve(bundle, '.' + (url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname)))
    if (!file.startsWith(bundle + path.sep)) throw new Error('outside fixture')
    let data = await fs.readFile(file)
    if (file.endsWith('index.html')) data = Buffer.from(data.toString().replace(/<script src="https:[^"]*dingtalk[^<]*<\/script>/g, ''))
    res.writeHead(200, { 'Content-Type': { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' }[path.extname(file)] || 'application/octet-stream' }); res.end(data)
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
  const deadline = Date.now() + 10000
  while (Date.now() < deadline) { if (await evaluate(expression)) return; await pause(100) }
  throw new Error('UI timeout: ' + expression + '\n' + JSON.stringify(await evaluate(`({input:document.querySelector('[aria-label="搜索知识库"]')?.value,expanded:document.querySelector('[aria-label="搜索知识库"]')?.getAttribute('aria-expanded'),focused:document.activeElement?.outerHTML?.slice(0,300),list:document.querySelector('.knowledge-suggestions')?.innerText})`)) + '\n' + JSON.stringify(state.requests) + '\n' + JSON.stringify(exceptions))
}
async function type(text) {
  await evaluate(`(()=>{const input=document.querySelector('[aria-label="搜索知识库"]');input.focus();input.value=${JSON.stringify(text)};input.dispatchEvent(new Event('input',{bubbles:true}))})()`)
}
async function key(key) { await call('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key }) }
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
    if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text)
  })
  await call('Runtime.enable'); await call('Page.enable')
  await call('Emulation.setFocusEmulationEnabled', { enabled: true })
  await call('Page.bringToFront')
  await call('Page.addScriptToEvaluateOnNewDocument', { source: "if(location.protocol==='http:')localStorage.setItem('it_helpdesk_token','fixture-only-token')" })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: frontend + '/#/admin/kb' }); await until('!!document.querySelector(".kb-search")')
  await type('打印'); await until('document.querySelectorAll(".suggestion-option").length===5')
  assert.ok(await evaluate('document.querySelector(".suggestion-title").textContent.startsWith("打印")'))
  assert.ok(await evaluate('!!document.querySelector(".suggestion-title .match")'))
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  const shot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/kb-autocomplete.png', import.meta.url), Buffer.from(shot.data, 'base64'))
  await evaluate('document.querySelector(".suggestion-option").focus();document.querySelector(".suggestion-option").click()')
  await until('!document.querySelector(".knowledge-suggestions") && document.querySelectorAll("main tbody tr").length===1')
  await pause(300); assert.equal(await evaluate('!!document.querySelector(".knowledge-suggestions")'), false)
  pass('typing finds up to five title/summary matches; choosing one performs the search and closes the list')
  await type('VPN'); await until('document.querySelectorAll(".suggestion-option").length===2')
  await key('ArrowUp'); assert.equal(await evaluate('document.querySelector(".suggestion-option:last-child").getAttribute("aria-selected")'), 'true')
  await key('ArrowDown'); await key('Enter')
  await until('!document.querySelector(".knowledge-suggestions") && document.querySelector("main tbody")?.textContent.includes("VPN 连接失败")')
  await type('无匹配内容'); await until('document.querySelector(".suggestion-state")?.textContent.includes("没有相关条目")')
  await key('Escape'); assert.equal(await evaluate('!!document.querySelector(".knowledge-suggestions")'), false)
  pass('arrow keys and Enter select candidates; Escape and empty matches behave correctly')
  state.slow = true; await type('打印')
  for (let i = 0, before = state.requests.length; i < 30 && !state.requests.slice(before - 1).some(row => row.keyword === '打印'); i++) await pause(100)
  await pause(250); await type('VPN'); await until('document.querySelectorAll(".suggestion-option").length===2')
  await pause(800); assert.ok(await evaluate('Array.from(document.querySelectorAll(".suggestion-title")).every(row=>row.textContent.includes("VPN"))'))
  state.slow = false; pass('a slower previous request cannot replace the latest suggestions')
  const beforeComposition = state.requests.length
  await evaluate(`(()=>{const input=document.querySelector('[aria-label="搜索知识库"]');input.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));input.value='da';input.dispatchEvent(new Event('input',{bubbles:true}))})()`)
  await pause(300); assert.equal(state.requests.length, beforeComposition)
  await evaluate(`(()=>{const input=document.querySelector('[aria-label="搜索知识库"]');input.value='打印';input.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true}))})()`)
  await until('document.querySelectorAll(".suggestion-option").length===5')
  pass('Chinese input waits until composition completes before requesting candidates')
  state.failNext = true; await type('连接'); await until('document.querySelector(".suggestion-state")?.textContent.includes("联想暂时不可用")')
  await evaluate('document.querySelector(".kb-search > button").click()'); await until('!document.querySelector(".knowledge-suggestions")')
  await type('<img'); await until('document.querySelectorAll(".suggestion-option").length===1')
  assert.equal(await evaluate('document.querySelector(".knowledge-suggestions img")===null && !window.__unsafe'), true)
  pass('suggestion failures preserve manual search and candidate text is rendered safely')
  state.role = 'USER'
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await call('Page.navigate', { url: frontend + '/#/kb' }); await until('!!document.querySelector(".cats")')
  await type('打印'); await until('document.querySelectorAll(".suggestion-option").length===5')
  assert.ok(await evaluate('document.querySelector(".knowledge-suggestions").getBoundingClientRect().right<=innerWidth && document.documentElement.scrollWidth<=innerWidth'))
  const mobile = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/kb-autocomplete-mobile.png', import.meta.url), Buffer.from(mobile.data, 'base64'))
  await evaluate('document.querySelector(".suggestion-option").focus();document.querySelector(".suggestion-option").click()')
  await until('!document.querySelector(".knowledge-suggestions") && document.querySelectorAll("main .list .wo").length===1')
  assert.deepEqual(exceptions, [])
  pass('the employee page uses the same autocomplete and phone dropdowns stay within the screen')
  console.log('Knowledge search UI checks: ' + checks.length + ' passed')
} finally {
  if (socket?.readyState === WebSocket.OPEN) { socket.send(JSON.stringify({ id: ++next, method: 'Browser.close' })); await pause(150); socket.close() }
  if (browser.exitCode === null) browser.kill()
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
}
