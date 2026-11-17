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
async function until(expression, label, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
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
async function api(method, path, payload, token) {
  return evaluate('(async()=>{const response=await fetch("/api"+'+JSON.stringify(path)+',{method:'+JSON.stringify(method)+',headers:{"Content-Type":"application/json",Authorization:"Bearer "+('+JSON.stringify(token || '')+' || localStorage.getItem("it_helpdesk_token"))},body:'+JSON.stringify(payload === undefined ? null : JSON.stringify(payload))+' || undefined}); const result=await response.json(); if(!response.ok || result.code!==0)throw new Error(result.message); return result.data})()')
}
async function refresh() {
  await until('Array.from(document.querySelectorAll(".queue-panel button")).some(b=>b.textContent==="刷新进度" && !b.disabled)', 'refresh ready')
  await evaluate('document.querySelector(".queue-panel button").click()')
}
async function queueIs(count, timeoutMs) {
  await until('document.querySelector(".queue-summary")?.textContent.includes('+JSON.stringify('前面还有 '+count+' 单待处理')+')', 'queue ahead '+count, timeoutMs)
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs', {urls:['*g.alicdn.com*']})
  await call('Emulation.setDeviceMetricsOverride', {width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate', {url:frontend})
  await until('!!document.querySelector(".shell")', 'initial app')
  await login('陈静', '/admin/dashboard')
  await until('!!document.querySelector("[data-kpi=pending]")', 'admin session')
  const admin = await evaluate('localStorage.getItem("it_helpdesk_token")')
  const owner = (await api('POST','/auth/login',{demoUser:'张伟'})).token
  assert.equal((await api('GET','/tickets')).total,1,'请使用全新启动的独立 H2 测试实例')
  const create = (title, token) => api('POST','/tickets',{title,description:'排队信息浏览器回归',location:'A座3楼测试区',cat1:'终端',impact:'SELF',workImpact:'SLOW'},token)
  const earlier = []
  for (let index=0; index<2; index++) {
    const ticket = await create('不可向用户展示的其他人工单-'+index, admin)
    await api('POST','/tickets/'+ticket.no+'/dispatch',{assigneeId:4},admin)
    earlier.push(ticket)
  }
  const target = await create('我的排队工单',owner)
  await api('POST','/tickets/'+target.no+'/dispatch',{assigneeId:4},admin)
  const unassigned = await create('我的未派单工单',owner)
  const finish = no => api('POST','/tickets/'+no+'/finish',{code:'RESTART',note:'排队测试处理完成'},admin)

  await login('张伟','/tickets')
  await until('Array.from(document.querySelectorAll(".list .wo")).some(r=>r.textContent.includes("我的排队工单") && r.textContent.includes("前面还有 3 单待处理"))','own tickets with queue')
  assert.equal(await evaluate('document.querySelector("main").textContent.includes("不可向用户展示")'),false)
  await screenshot('ticket-queue-list.png')
  pass('我的工单显示接单 IT 和前方数量，不展示其他人工单内容')
  await evaluate('Array.from(document.querySelectorAll(".list .wo")).find(r=>r.textContent.includes("我的排队工单")).click()')
  await queueIs(3)
  assert.ok(await evaluate('document.querySelector(".queue-summary").textContent.includes("李强")'))
  pass('用户工单详情展示当前 IT 的排队进度')
  await call('Page.reload')
  await queueIs(3)
  pass('刷新详情页保留正确排队数量')
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
  await until('document.querySelector(".queue-panel")?.getBoundingClientRect().width > 0','mobile queue')
  assert.ok(await evaluate('document.querySelector(".queue-panel").scrollWidth <= document.querySelector(".queue-panel").clientWidth'))
  await screenshot('ticket-queue-mobile.png')
  pass('手机页面的排队文字和刷新按钮正常显示')
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})

  await finish(earlier[0].no)
  await refresh(); await queueIs(2)
  pass('前方工单提交完成后立即移出队列，手动刷新更新数量')
  await finish(earlier[1].no)
  await queueIs(1,45000)
  pass('详情自动更新队列，无需刷新整个页面')
  await finish('IT-TEST-0001')
  await refresh(); await queueIs(0)
  await screenshot('ticket-queue-zero.png')
  pass('前方工单清空时明确显示 0 单')

  await finish(target.no)
  await refresh()
  await until('document.querySelector(".queue-summary")?.textContent.includes("等待您确认")','pending confirmation')
  assert.equal(await evaluate('document.querySelector(".queue-summary").textContent.includes("待处理")'),false)
  pass('当前工单处理完成后切换为待确认提示')
  await api('POST','/tickets/'+target.no+'/confirm',{solved:true,score:5},owner)
  await refresh()
  await until('!document.querySelector(".queue-panel") && document.querySelector(".status-card")?.textContent.includes("已关闭")','closed queue')
  pass('工单关闭后停止显示和更新排队信息')

  await evaluate('location.hash='+JSON.stringify('#/ticket/'+unassigned.no))
  await until('document.querySelector(".queue-summary")?.textContent.includes("等待 IT 接单")','unassigned queue')
  pass('尚未派单时展示等待分配提示')
  await api('POST','/tickets/'+unassigned.no+'/dispatch',{assigneeId:5},admin)
  await refresh(); await queueIs(0)
  assert.ok(await evaluate('document.querySelector(".queue-summary").textContent.includes("王敏")'))
  pass('派给另一位 IT 后显示该 IT 的队列')
  await evaluate('location.hash="#/home"')
  await until('Array.from(document.querySelectorAll(".ticket-queue")).some(r=>r.textContent.includes("王敏") && r.textContent.includes("前面还有 0 单待处理"))','home queue summary')
  pass('用户首页的最近工单同步显示排队摘要')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Ticket queue UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
