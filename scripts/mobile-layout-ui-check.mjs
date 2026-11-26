// 仅连接独立 H2 预览（8096/5186）和独立浏览器（9231），不访问业务库或真实钉钉。
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
const frontend = 'http://127.0.0.1:5186'
let pages
for (let attempt = 0; attempt < 50 && !pages; attempt++) {
  try { pages = await (await fetch('http://127.0.0.1:9231/json/list')).json() } catch { await new Promise(resolve => setTimeout(resolve, 100)) }
}
assert.ok(pages?.some(page => page.type === 'page'), 'Start the isolated mobile browser on port 9231 first')
const socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
let next = 0
const pending = new Map(), exceptions = [], serverErrors = [], checks = []
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data), callback = pending.get(message.id)
  if (callback) { pending.delete(message.id); message.error ? callback.reject(new Error(message.error.message)) : callback.resolve(message.result) }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text)
  if (message.method === 'Network.responseReceived' && message.params.response.url.includes('/api/') && message.params.response.status >= 500) serverErrors.push(message.params.response.url)
})
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
  const end = Date.now() + 15000
  while (Date.now() < end) { try { if (await evaluate(expression)) return } catch {} await new Promise(resolve => setTimeout(resolve, 100)) }
  throw new Error('UI timeout: ' + expression + '\n' + await evaluate('document.body.innerText'))
}
async function size(width, height = 844) {
  await call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 900 })
  if (await evaluate("location.protocol==='http:'")) await until('innerWidth===' + width)
}
async function login(name, route) {
  await evaluate(`(async()=>{const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({demoUser:${JSON.stringify(name)}})});const b=await r.json();if(b.code!==0)throw new Error(b.message);localStorage.setItem('it_helpdesk_token',b.data.token)})()`)
  await call('Page.navigate', { url: 'about:blank' }); await until("location.href==='about:blank'")
  await call('Page.navigate', { url: frontend + '/#' + route })
  await until('!!document.querySelector(".shell") && document.querySelector(".side .me b")?.textContent===' + JSON.stringify(name))
}
async function route(path, ready) {
  await evaluate('location.hash=' + JSON.stringify('#' + path)); await until(ready)
}
async function fit(label) {
  const result = await evaluate(`({width:innerWidth,scroll:document.documentElement.scrollWidth,side:getComputedStyle(document.querySelector('.side')).display,rail:getComputedStyle(document.querySelector('.rail')).display,tabs:getComputedStyle(document.querySelector('.tabs')).position,bottom:document.querySelector('.tabs').getBoundingClientRect().bottom,height:innerHeight})`)
  assert.ok(result.scroll <= result.width + 1, label + ' horizontal overflow: ' + JSON.stringify(result))
  assert.equal(result.side, 'none', label); assert.equal(result.rail, 'none', label); assert.equal(result.tabs, 'fixed', label)
  assert.ok(Math.abs(result.bottom - result.height) < 2, label + ' bottom navigation must stay visible')
}
async function screenshot(file) {
  const result = await call('Page.captureScreenshot', { format: 'png' })
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  await fs.writeFile(new URL('../shots/regression/' + file, import.meta.url), Buffer.from(result.data, 'base64'))
}
function pass(label) { checks.push(label); console.log('PASS ' + label) }
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs', { urls: ['*g.alicdn.com*'] })
  await call('Network.setUserAgentOverride', { userAgent: 'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/90.0.4430.210 Mobile Safari/537.36 DingTalk/7.6.0' })
  await size(390)
  await call('Page.navigate', { url: frontend }); await until('!!document.querySelector(".shell")')
  await login('张伟', '/home')
  for (const width of [360, 390, 430, 768]) {
    await size(width); await fit('employee home ' + width)
    assert.equal(await evaluate('getComputedStyle(document.querySelector(".entries")).gridTemplateColumns.split(" ").length'), 4)
  }
  await size(390); await screenshot('mobile-home.png'); pass('360/390/430/768px use a single column and fixed bottom navigation')
  assert.equal(await evaluate('!!document.querySelector(".mobile-menu-button")'), false)
  await evaluate('Array.from(document.querySelectorAll("nav.tabs button")).find(b=>b.textContent.trim()==="知识").click()')
  await until('location.hash==="#/kb" && !document.querySelector(".mobile-nav")')
  await fit('knowledge base'); assert.notEqual(await evaluate('document.body.style.overflow'), 'hidden')
  pass('ordinary employees use bottom navigation')
  await size(360)
  await route('/new', '!!document.querySelector(".new-ticket .ta")'); await fit('new ticket')
  assert.ok(await evaluate('parseFloat(getComputedStyle(document.querySelector(".new-ticket .ta")).fontSize)>=16'))
  await route('/tickets', '!!document.querySelector("main .tabs2")'); await fit('my tickets')
  await route('/assets?type=RECEIVE', '!!document.querySelector(".asset-picker")')
  assert.ok(await evaluate('document.querySelector(".asset-selection-dialog").getBoundingClientRect().right<=innerWidth'))
  await evaluate('document.querySelector("[aria-label=关闭设备选择]").click()'); await until('!document.querySelector(".asset-picker")')
  pass('the reporting form, ticket list and asset picker fit the phone screen')
  await login('李强', '/admin/tickets')
  await until('document.querySelectorAll(".ticket-card").length>0'); await fit('IT ticket list')
  await evaluate('document.querySelector(".mobile-menu-button").click()'); await until('!!document.querySelector(".mobile-nav")')
  assert.equal(await evaluate('document.body.style.overflow'), 'hidden')
  assert.deepEqual(await evaluate('Array.from(document.querySelectorAll(".mobile-nav .mi-label")).map(label=>label.textContent.trim())'), await evaluate('Array.from(document.querySelectorAll(".side .mi-label")).map(label=>label.textContent.trim())'))
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape' }); await until('!document.querySelector(".mobile-nav")')
  assert.notEqual(await evaluate('document.body.style.overflow'), 'hidden')
  pass('the IT mobile menu preserves permitted entries and restores scrolling when closed')
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".desktop-records")).display'), 'table')
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".desktop-records").parentElement).display'), 'none')
  await screenshot('mobile-it-tickets.png')
  await evaluate('document.querySelector(".ticket-card").click()'); await until('location.hash.includes("/admin/ticket/") && !!document.querySelector(".detail")'); await fit('IT ticket details')
  await route('/admin/assets', 'document.querySelectorAll(".asset-card").length>0'); await fit('asset ledger')
  await evaluate('document.querySelector(".asset-card").click()'); await until('location.hash.includes("/admin/assets/") && !!document.querySelector(".asset-detail")'); await fit('asset details')
  pass('IT ticket and asset cards open the original detail pages')
  await login('陈静', '/admin/config'); await until('!!document.querySelector(".management .panel")'); await fit('administrator settings')
  assert.equal(await evaluate('document.querySelectorAll(".tabs button.on").length'), 1)
  await route('/admin/config?tab=matrix', '!!document.querySelector(".permission-matrix")'); await fit('permissions')
  assert.equal(await evaluate('document.querySelectorAll(".tabs button.on").length'), 1)
  await screenshot('mobile-role-permissions.png')
  await evaluate('document.querySelector(".mobile-menu-button").click()'); await until('!!document.querySelector(".mobile-nav")')
  assert.ok(await evaluate('document.querySelector(".mobile-nav").textContent.includes("部门与通讯录")'))
  await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape' }); await until('!document.querySelector(".mobile-nav")')
  await route('/admin/config?tab=users', '!!document.querySelector(".management tbody tr")'); await fit('user management')
  pass('administrator sections stay reachable and the selected bottom tab respects the section')
  await login('赵敏', '/admin/assets'); await until('document.querySelectorAll(".asset-card").length>0'); await fit('viewer assets')
  assert.equal(await evaluate('Array.from(document.querySelectorAll("main button")).some(b=>b.textContent.includes("新增资产"))'), false)
  await size(1440, 1000); await until('getComputedStyle(document.querySelector(".tabs")).display==="none"')
  assert.notEqual(await evaluate('getComputedStyle(document.querySelector(".side")).display'), 'none')
  assert.notEqual(await evaluate('getComputedStyle(document.querySelector(".desktop-records")).display'), 'none')
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".mobile-records")).display'), 'none')
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'))
  pass('viewer management screens remain read-only and desktop tables stay available')
  assert.deepEqual(exceptions, []); assert.deepEqual(serverErrors, [])
  console.log('Mobile layout UI checks: ' + checks.length + ' passed')
} finally {
  socket.send(JSON.stringify({ id: ++next, method: 'Browser.close' })); await new Promise(resolve => setTimeout(resolve, 150)); socket.close()
}
