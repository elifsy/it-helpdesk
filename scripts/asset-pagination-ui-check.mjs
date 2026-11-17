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
async function api(method,path,payload) {
  return evaluate('(async()=>{const response=await fetch("/api"+'+JSON.stringify(path)+',{method:'+JSON.stringify(method)+',headers:{"Content-Type":"application/json",Authorization:"Bearer "+localStorage.getItem("it_helpdesk_token")},body:'+JSON.stringify(payload===undefined?null:JSON.stringify(payload))+' || undefined}); const result=await response.json(); if(!response.ok || result.code!==0)throw new Error(result.message); return result.data})()')
}
async function click(text,root='main') {
  await evaluate('(()=>{const element=Array.from(document.querySelector('+JSON.stringify(root)+').querySelectorAll("button")).find(button=>button.textContent.trim()==='+JSON.stringify(text)+'); if(!element || element.disabled)throw new Error("Button unavailable: "+'+JSON.stringify(text)+'); element.click()})()')
}
async function ready(page,pages,total,rows) {
  await until('document.querySelector(".pager span")?.textContent.trim() === '+JSON.stringify(page+' / '+pages)+' && document.querySelector(".ledger-count")?.textContent.includes('+JSON.stringify('共 '+total+' 台')+') && document.querySelectorAll(".asset-row").length === '+rows,'ledger page '+page)
}
async function choose(label,value) {
  await evaluate('(()=>{const select=Array.from(document.querySelectorAll("main select")).find(element=>element.getAttribute("aria-label")==='+JSON.stringify(label)+'); select.value='+JSON.stringify(value)+'; select.dispatchEvent(new Event("change",{bubbles:true}))})()')
}
async function search(value) {
  await evaluate('(()=>{const input=document.querySelector("[aria-label=搜索资产台账]"); input.value='+JSON.stringify(value)+'; input.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await click('查询')
}
async function assertMenus() {
  const labels = await evaluate('Array.from(document.querySelectorAll(".side .mi")).map(button=>button.textContent.trim())')
  assert.equal(labels.some(label=>label.includes('二维码标签') || label.includes('派单队列')),false)
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*']})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate',{url:frontend})
  await until('!!document.querySelector(".shell")','initial app')
  await login('陈静','/admin/assets')
  await ready(1,1,2,2)
  await assertMenus()
  await api('POST','/asset-categories',{name:'分页设备',sort:0})
  for(let index=1;index<=32;index++) await api('POST','/assets',{
    no:'PAGE-ASSET-'+String(index).padStart(3,'0'),name:'分页办公设备 '+index,category:'分页设备',status:index<=20?'闲置':'在用',sn:'PAGE-SN-'+String(index).padStart(3,'0'),location:'A座 3F'
  })
  for(let index=1;index<=4;index++) await api('POST','/assets',{no:'PAGE-PRINT-'+index,name:'分页打印设备 '+index,category:'打印复印',status:'闲置',location:'B座 2F'})
  await call('Page.reload')
  await ready(1,3,38,15)
  const first = await evaluate('Array.from(document.querySelectorAll(".asset-row")).map(row=>row.querySelector("td").textContent.trim())')
  pass('资产台账按每页 15 台显示，正确展示总数和页数')
  await click('下一页','.pager')
  await ready(2,3,38,15)
  const second = await evaluate('Array.from(document.querySelectorAll(".asset-row")).map(row=>row.querySelector("td").textContent.trim())')
  assert.equal(first.some(no=>second.includes(no)),false)
  assert.ok(await evaluate('location.hash.includes("page=2")'))
  assert.equal(await evaluate('document.querySelector(".ctx b").textContent'),'资产台账')
  assert.ok(await evaluate('document.querySelector(".side .mi.on")?.textContent.includes("资产台账")'))
  await screenshot('asset-ledger-pagination.png')
  await call('Page.reload'); await ready(2,3,38,15)
  pass('翻页无重复记录，地址栏、标题、菜单高亮及刷新保持正确')

  await evaluate('document.querySelector(".asset-row button").click()')
  await until('!!document.querySelector(".asset-detail .info-grid") && location.hash.includes("backPage=2")','paged device detail')
  await evaluate('document.querySelector("[data-action=asset-qrcode]").click()')
  await until('document.querySelector(".qr-dialog canvas")?.width>200 && !!document.querySelector(".qr-url")?.value','QR still available')
  await evaluate('document.querySelector("[aria-label=关闭二维码]").click()')
  await evaluate('document.querySelector(".back-link").click()')
  await ready(2,3,38,15)
  assert.deepEqual(await evaluate('Array.from(document.querySelectorAll(".asset-row")).map(row=>row.querySelector("td").textContent.trim())'),second)
  pass('详情返回恢复原页码，二维码仍可在设备详情查看')
  await click('下一页','.pager'); await ready(3,3,38,8)
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".pager button")).find(button=>button.textContent==="下一页").disabled'),true)
  await evaluate('history.back()'); await ready(2,3,38,15)
  pass('末页数量及按钮状态正确，浏览器返回同步恢复上一页')

  await choose('资产分类筛选','分页设备'); await ready(1,3,32,15)
  assert.ok(await evaluate('!location.hash.includes("page=")'))
  await click('下一页','.pager'); await ready(2,3,32,15)
  await choose('资产状态筛选','闲置'); await ready(1,2,20,15)
  await click('下一页','.pager'); await ready(2,2,20,5)
  await search('PAGE-SN-020'); await ready(1,1,1,1)
  assert.ok(await evaluate('document.querySelector(".asset-row").textContent.includes("PAGE-ASSET-020")'))
  assert.equal(await evaluate('document.querySelector("[aria-label=资产分类筛选]").value'),'分页设备')
  assert.equal(await evaluate('document.querySelector("[aria-label=资产状态筛选]").value'),'闲置')
  await search(''); await ready(1,2,20,15)
  pass('分类、状态和关键词组合筛选正确，条件变化重置到第一页')
  await click('下一页','.pager'); await ready(2,2,20,5)
  await evaluate('document.querySelector(".asset-row button").click()')
  await until('!!document.querySelector(".asset-detail .info-grid")','filtered detail')
  await evaluate('document.querySelector(".back-link").click()'); await ready(2,2,20,5)
  assert.equal(await evaluate('document.querySelector("[aria-label=资产分类筛选]").value'),'分页设备')
  assert.equal(await evaluate('document.querySelector("[aria-label=资产状态筛选]").value'),'闲置')
  pass('从筛选结果进入详情并返回时保留分类、状态与页码')

  for(let index=16;index<=20;index++) await api('PUT','/assets/PAGE-ASSET-'+String(index).padStart(3,'0')+'/category',{category:'打印复印'})
  await call('Page.reload'); await ready(1,1,15,15)
  assert.equal(await evaluate('location.hash.includes("page=")'),false)
  pass('设备移出分类导致末页消失时自动回到有效页码')
  await evaluate('location.hash="#/admin/assets?page=9999"'); await ready(3,3,38,8)
  assert.ok(await evaluate('location.hash.includes("page=3")'))
  await search('不存在分页资产'); await ready(1,1,0,0)
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".pager button")).every(button=>button.disabled)'),true)
  await search(''); await ready(1,3,38,15)
  assert.equal((await api('GET','/assets')).length,38)
  pass('超出范围和空结果处理正常，原完整资产接口保持兼容')

  await login('张伟','/home')
  await until('document.querySelector(".side .me b")?.textContent==="张伟"','employee sidebar')
  await assertMenus()
  const groups = await evaluate('Array.from(document.querySelectorAll(".side .grp")).map(group=>group.textContent.trim())')
  assert.deepEqual(groups,['我的服务'])
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".side .mi")).some(button=>button.textContent.includes("资产台账"))'),false)
  assert.ok(await evaluate('Array.from(document.querySelectorAll(".side .mi")).some(button=>button.textContent.includes("资产服务"))'))
  assert.ok(await evaluate('Array.from(document.querySelectorAll(".side .mi")).some(button=>button.textContent.includes("知识库"))'))
  await screenshot('employee-menu-simplified.png')
  pass('普通用户仅显示我的服务分组，保留资产服务和知识库入口')
  await evaluate('location.hash="#/assets?type=RECEIVE"')
  await until('document.querySelectorAll(".asset-picker .device-option").length===26','full picker choices')
  assert.equal(await evaluate('document.querySelector(".category-option.active small").textContent'),'26')
  await evaluate('document.querySelector("[aria-label=关闭设备选择]").click()')
  pass('台账分页不截断用户可选设备，卡片仍展示完整可用资产')

  for(const name of ['赵敏','李强']) {
    await login(name,'/admin/assets?page=2')
    await ready(2,3,38,15)
    await assertMenus()
    assert.ok(await evaluate('Array.from(document.querySelectorAll(".side .grp")).some(group=>group.textContent==="资产与知识")'))
    if(name==='赵敏') assert.equal(await evaluate('Array.from(document.querySelectorAll("main button")).some(button=>["管理分类","＋ 新增资产"].includes(button.textContent))'),false)
  }
  pass('IT 和可查看用户分页正常，所有角色移除两个重复入口')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Asset pagination and menus UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
