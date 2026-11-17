// 浏览器回归：仅连接本机独立 H2 预览实例（8096 / 5186），不接触业务数据库。
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
const frontend = 'http://127.0.0.1:5186'
const pages = await (await fetch('http://127.0.0.1:9226/json/list')).json()
const page = pages.find(p => p.type === 'page')
assert.ok(page, '请先启动使用独立配置目录的 Chrome 调试实例')
const socket = new WebSocket(page.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
let sequence = 0
const pending = new Map()
const exceptions = []
const serverErrors = []
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (message.id) {
    const callback = pending.get(message.id)
    if (callback) { pending.delete(message.id); message.error ? callback.reject(new Error(message.error.message)) : callback.resolve(message.result) }
  }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.text + ': ' + (message.params.exceptionDetails.exception?.description || ''))
  if (message.method === 'Network.responseReceived' && message.params.response.url.includes('/api/') && message.params.response.status >= 500) serverErrors.push(message.params.response.url)
})
function call(method, params = {}) {
  const id = ++sequence
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)) }, 15000)
    pending.set(id, { resolve: value => { clearTimeout(timer); resolve(value) }, reject: error => { clearTimeout(timer); reject(error) } })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text)
  return result.result?.value
}
async function until(expression, label) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    try { if (await evaluate(expression)) return } catch { }
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  throw new Error('UI timeout: ' + label + '\n' + await evaluate('document.body.innerText'))
}
async function tab(key, title) {
  await evaluate(`location.hash = ${JSON.stringify('#/admin/config' + (key ? '?tab=' + key : ''))}`)
  await until(`document.querySelector('.management h3')?.textContent === ${JSON.stringify(title)}`, title)
}
async function login(name, path) {
  await evaluate(`(async () => {
    const response = await fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({demoUser:${JSON.stringify(name)}})})
    const result = await response.json(); if(result.code !== 0) throw new Error(result.message)
    localStorage.setItem('it_helpdesk_token', result.data.token); localStorage.setItem('it_helpdesk_demo_user', ${JSON.stringify(name)})
  })()`)
  await call('Page.navigate', { url: 'about:blank' })
  await until("location.href === 'about:blank'", 'clear previous account page')
  await call('Page.navigate', { url: frontend + '/#' + path })
}
async function screenshot(name) {
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  const shot = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
  await fs.writeFile(new URL('../shots/regression/' + name, import.meta.url), Buffer.from(shot.data, 'base64'))
}

const checks = []
const pass = name => { checks.push(name); console.log('PASS ' + name) }
async function api(method, path, payload) {
  return evaluate('(async()=>{const response=await fetch("/api"+'+JSON.stringify(path)+',{method:'+JSON.stringify(method)+',headers:{"Content-Type":"application/json",Authorization:"Bearer "+localStorage.getItem("it_helpdesk_token")},body:'+JSON.stringify(payload === undefined ? null : JSON.stringify(payload))+' || undefined}); const result=await response.json(); if(!response.ok || result.code!==0)throw new Error(result.message); return result.data})()')
}
async function dashboard() {
  await evaluate('location.hash = "#/admin/dashboard"')
  await until('document.querySelector("[data-kpi=pending] .n")?.textContent === "17"', 'dashboard totals')
}
async function clickCard(key) {
  await evaluate('document.querySelector('+JSON.stringify('[data-kpi='+key+']')+').click()')
}
async function listReady(filter, count, rows) {
  await until('location.hash.includes('+JSON.stringify('status='+filter)+') && Array.from(document.querySelectorAll(".filter-tab")).some(b=>b.classList.contains("on") && b.getAttribute("aria-pressed")==="true") && !document.querySelector(".tb tbody")?.textContent.includes("加载中") && document.querySelector(".filters .small")?.textContent.includes('+JSON.stringify('共 '+count+' 张')+') && document.querySelectorAll(".tb tbody tr[style]").length === '+rows, filter+' list')
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs', {urls:['*g.alicdn.com*']})
  await call('Emulation.setDeviceMetricsOverride', {width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate', {url:frontend})
  await until('!!document.querySelector(".shell")', 'initial app')
  await login('陈静', '/admin/dashboard')
  await until('!!document.querySelector("[data-kpi=pending]")', 'dashboard')
  assert.equal((await api('GET','/tickets')).total,1,'请使用全新启动的独立 H2 测试实例')
  const tickets=[]
  for(let i=1;i<=18;i++) tickets.push(await api('POST','/tickets',{title:'卡片跳转-'+String(i).padStart(2,'0'),description:'浏览器回归专用',location:'A座3楼测试区',cat1:'终端',impact:'SELF',workImpact:'SLOW'}))
  await api('POST','/tickets/'+tickets[0].no+'/accept',{})
  await call('Page.reload')
  await until('document.querySelector("[data-kpi=pending] .n")?.textContent === "17"', 'seeded dashboard')
  assert.equal(await evaluate('document.querySelectorAll(".kpis a").length'),4)
  await screenshot('dashboard-clickable-cards.png')
  pass('四张统计卡片提供可点击链接')

  await clickCard('pending')
  await listReady('NEW',17,15)
  assert.ok(await evaluate('document.querySelector(".filter-tab.on")?.textContent.includes("待受理")'))
  pass('待受理卡片打开正确的工单列表和总数')
  await evaluate('Array.from(document.querySelectorAll(".pager button")).find(b=>b.textContent==="下一页").click()')
  await until('location.hash.includes("page=2") && document.querySelectorAll(".tb tbody tr[style]").length===2','second page')
  await call('Page.reload')
  await until('document.querySelector(".pager span")?.textContent.includes("2 / 2") && document.querySelectorAll(".tb tbody tr[style]").length===2','refresh page')
  pass('分页和刷新保留状态及页码')
  await evaluate('history.back()')
  await until('!location.hash.includes("page=") && document.querySelectorAll(".tb tbody tr[style]").length===15','back to first page')
  pass('浏览器返回同步恢复上一页列表')

  await dashboard()
  await clickCard('processing')
  await listReady('PROCESSING',2,2)
  const states=await evaluate('Array.from(document.querySelectorAll(".tb tbody tr[style]")).map(r=>r.children[4].textContent.trim())')
  assert.deepEqual(states,['处理中','处理中'])
  await screenshot('dashboard-processing-list.png')
  pass('受理后工单直接进入处理中，卡片与列表数量一致')

  await dashboard()
  await clickCard('overdue')
  await listReady('OVERDUE',0,0)
  assert.ok(await evaluate('document.querySelector(".filter-tab.on")?.textContent.includes("超时预警")'))
  assert.ok(await evaluate('document.querySelector(".tb tbody")?.textContent.includes("没有符合条件的工单")'))
  await call('Page.reload')
  await listReady('OVERDUE',0,0)
  pass('超时预警卡片支持空列表和刷新')
  await evaluate('Array.from(document.querySelectorAll(".filter-tab")).find(b=>b.textContent.trim()==="处理中").click()')
  await listReady('PROCESSING',2,2)
  await evaluate('history.back()')
  await listReady('OVERDUE',0,0)
  pass('切换筛选与返回时重新加载正确的工单')

  await dashboard()
  await clickCard('rating')
  await until('location.hash === "#/admin/stats" && document.querySelector("main h2")?.textContent === "统计看板"','rating report')
  await until('document.querySelectorAll(".chart canvas").length===2','charts ready')
  pass('满意度卡片进入统计看板')

  await login('赵敏','/admin/dashboard')
  await until('document.querySelectorAll(".kpis a").length===4','viewer cards')
  await clickCard('processing')
  await listReady('PROCESSING',2,2)
  pass('可查看用户可通过卡片查看筛选结果')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Dashboard UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
