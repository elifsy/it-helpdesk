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
const dispatchRequests = []
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (message.method === 'Network.requestWillBeSent' && /\/tickets\/[^/]+\/dispatch(?:\?|$)/.test(message.params.request.url)) dispatchRequests.push(message.params.request.url)
})
async function api(method, path, payload) {
  return evaluate('(async()=>{const response=await fetch("/api"+'+JSON.stringify(path)+',{method:'+JSON.stringify(method)+',headers:{"Content-Type":"application/json",Authorization:"Bearer "+localStorage.getItem("it_helpdesk_token")},body:'+JSON.stringify(payload === undefined ? null : JSON.stringify(payload))+' || undefined}); const result=await response.json(); if(!response.ok || result.code!==0)throw new Error(result.message); return result.data})()')
}
async function click(text, root='main') {
  await evaluate('(()=>{const button=Array.from(document.querySelector('+JSON.stringify(root)+').querySelectorAll("button")).find(b=>b.textContent.trim()==='+JSON.stringify(text)+'); if(!button || button.disabled)throw new Error("Button unavailable"); button.click()})()')
}
async function fillNote(text) {
  await evaluate('(()=>{const input=document.querySelector(".mask textarea"); input.value='+JSON.stringify(text)+'; input.dispatchEvent(new Event("input",{bubbles:true}))})()')
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs', {urls:['*g.alicdn.com*']})
  await call('Emulation.setDeviceMetricsOverride', {width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate', {url:frontend})
  await until('!!document.querySelector(".shell")', 'initial app')
  await login('张伟','/tickets')
  await until('!!document.querySelector(".tabs2")','user session')
  const target = await api('POST','/tickets',{title:'工程师一键接单测试',description:'受理后直接由当前工程师处理',location:'A座3楼测试区',cat1:'终端',impact:'SELF',workImpact:'SLOW'})
  await evaluate('location.hash='+JSON.stringify('#/ticket/'+target.no))
  await until('document.querySelector(".queue-summary")?.textContent.includes("等待 IT 接单")','waiting for claim')
  pass('用户新工单显示等待 IT 接单')

  await login('李强','/admin/ticket/'+target.no)
  await until('Array.from(document.querySelectorAll(".ops button")).some(b=>b.textContent.trim()==="受理")','engineer accept action')
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".ops button")).some(b=>b.textContent.trim()==="派单")'),false)
  await click('受理','.ops')
  await until('document.querySelector(".status-card")?.textContent.includes("处理中") && document.querySelector(".queue-summary")?.textContent.includes("接单 IT：李强")','accepted directly')
  const accepted = (await api('GET','/tickets/'+target.no)).ticket
  assert.equal(accepted.status,'IN_PROGRESS'); assert.equal(accepted.assigneeName,'李强')
  assert.equal(accepted.assigneeId,4); assert.equal(accepted.queueAhead,1)
  assert.ok(accepted.assignedAt)
  await screenshot('direct-accept-engineer.png')
  pass('点击受理直接绑定当前工程师并进入处理中，排队数量同步计算')
  const buttons = await evaluate('Array.from(document.querySelectorAll(".ops button")).map(b=>b.textContent.trim())')
  assert.ok(buttons.includes('添加处理记录')); assert.ok(buttons.includes('提交完成'))
  assert.ok(!buttons.includes('派单')); assert.ok(!buttons.includes('受理'))
  assert.deepEqual(dispatchRequests,[])
  pass('受理后直接提供处理和完成操作，无派单步骤及请求')
  await call('Page.reload')
  await until('document.querySelector(".queue-summary")?.textContent.includes("接单 IT：李强")','accepted refresh')
  assert.ok(await evaluate('document.querySelector(".queue-summary").textContent.includes("前面还有 1 单待处理")'))
  pass('刷新后保持当前接单工程师及处理状态')

  await click('添加处理记录','.ops')
  await until('!!document.querySelector(".mask textarea")','record form')
  await fillNote('受理后直接进行初步排查')
  await click('保存','.mask')
  await until('!document.querySelector(".mask") && document.querySelector(".col-side")?.textContent.includes("受理后直接进行初步排查")','record saved')
  pass('受理后无需派单即可保存处理记录')
  await login('张伟','/ticket/'+target.no)
  await until('document.querySelector(".queue-summary")?.textContent.includes("接单 IT：李强")','owner sees assignee')
  assert.ok(await evaluate('document.querySelector(".queue-summary").textContent.includes("前面还有 1 单待处理")'))
  assert.equal(await evaluate('!!document.querySelector(".ops")'),false)
  await screenshot('direct-accept-user.png')
  pass('用户立即看到接单 IT 和排队数量，保持只读处理信息')

  await login('王敏','/admin/ticket/'+target.no)
  await until('document.querySelector(".status-card")?.textContent.includes("处理中")','second engineer')
  const duplicateStatus = await evaluate('(async()=>{const response=await fetch("/api/tickets/'+target.no+'/accept",{method:"POST",headers:{Authorization:"Bearer "+localStorage.getItem("it_helpdesk_token")}}); return response.status})()')
  assert.equal(duplicateStatus,400)
  assert.equal((await api('GET','/tickets/'+target.no)).ticket.assigneeId,4)
  pass('其他工程师不能重复受理覆盖接单人')

  await login('李强','/admin/ticket/'+target.no)
  await until('Array.from(document.querySelectorAll(".ops button")).some(b=>b.textContent.trim()==="提交完成")','finish action')
  await click('提交完成','.ops')
  await until('!!document.querySelector(".mask textarea") && !!document.querySelector(".mask select")?.value','finish form')
  await fillNote('一键接单后直接处理完成')
  await click('提交完成','.mask')
  await until('!document.querySelector(".mask") && document.querySelector(".status-card")?.textContent.includes("待确认")','finished claim')
  assert.equal((await api('GET','/tickets/'+target.no)).ticket.status,'PENDING_CONFIRM')
  assert.deepEqual(dispatchRequests,[])
  pass('工程师从受理直接处理至待确认，全程无需派单')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Direct accept UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
