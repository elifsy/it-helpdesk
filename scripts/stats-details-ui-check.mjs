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
const detailRequests = []
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (message.method === 'Network.requestWillBeSent' && (/\/api\/assets(?:\/|\?|$)/.test(message.params.request.url)
      || /\/api\/tickets\?.*assetNo=/.test(message.params.request.url))) detailRequests.push(message.params.request.url)
})
async function api(method, path, payload, token) {
  return evaluate('(async()=>{const response=await fetch("/api"+'+JSON.stringify(path)+',{method:'+JSON.stringify(method)+',headers:{"Content-Type":"application/json",Authorization:"Bearer "+('+JSON.stringify(token || '')+' || localStorage.getItem("it_helpdesk_token"))},body:'+JSON.stringify(payload === undefined ? null : JSON.stringify(payload))+' || undefined}); const result=await response.json(); if(!response.ok || result.code!==0)throw new Error(result.message); return result.data})()')
}
async function click(selector) {
  await evaluate('(()=>{const element=document.querySelector('+JSON.stringify(selector)+'); if(!element || element.disabled)throw new Error("Element unavailable"); element.focus(); element.click()})()')
}
async function button(text, root='.stats-data-dialog') {
  await evaluate('(()=>{const element=Array.from(document.querySelector('+JSON.stringify(root)+').querySelectorAll("button")).find(b=>b.textContent.trim()==='+JSON.stringify(text)+'); if(!element || element.disabled)throw new Error("Button unavailable"); element.click()})()')
}
async function close() {
  await click('[aria-label="关闭数据明细"]')
  await until('!document.querySelector(".stats-data-dialog")','dialog closed')
}
async function problem(no, total) {
  await click('button[data-asset="'+no+'"]')
  await until('document.querySelector(".selected-asset")?.textContent === '+JSON.stringify(no)+' && document.querySelector(".repair-heading")?.textContent.includes('+JSON.stringify('共 '+total+' 次')+') && document.querySelectorAll(".repair-data-row").length === '+Math.min(10,total),'problem '+no)
}
async function assetList(key, total) {
  await click('[data-metric="'+key+'"]')
  await until('document.querySelector(".data-toolbar")?.textContent.includes('+JSON.stringify('共 '+total+' 台')+') && !document.querySelector(".asset-data-table tbody")?.textContent.includes("正在加载") && document.querySelectorAll(".asset-data-row").length === '+Math.min(10,total),'asset list '+key)
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*']})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate',{url:frontend})
  await until('!!document.querySelector(".shell")','initial app')
  await login('陈静','/admin/stats')
  await until('!!document.querySelector(".asset-line")','statistics page')
  const admin = await evaluate('localStorage.getItem("it_helpdesk_token")')
  const owner = (await api('POST','/auth/login',{demoUser:'张伟'})).token
  assert.equal((await api('GET','/assets')).length,2,'请使用全新启动的独立 H2 测试实例')
  for(let index=1; index<=12; index++) await api('POST','/assets',{
    no:'STAT-DEVICE-'+index,name:'统计设备 '+index,category:'终端',status:index===1?'在用':index===4?'维修中':index===5?'待入库':'闲置',location:'统计测试区 '+index
  })
  const request = await api('POST','/asset-requests',{type:'BORROW',assetNo:'STAT-DEVICE-3',category:'终端',reason:'统计弹窗借用设备',expectReturn:new Date(Date.now()+86400000).toISOString().slice(0,10)},owner)
  await api('POST','/asset-requests/'+request.id+'/approve',{})
  for(const [assetNo, count] of [['STAT-DEVICE-1',12],['STAT-DEVICE-2',2],['STAT-DEVICE-3',1],['STAT-DEVICE-4',1],['STAT-DEVICE-10',1],['STAT-ORPHAN',3]]) {
    for(let index=0;index<count;index++) await api('POST','/tickets',{title:assetNo+' 的报修 '+index,description:'对应设备的报修详细描述',location:'A座3楼测试区',cat1:'终端',impact:'SELF',workImpact:'SLOW',assetNo})
  }
  await call('Page.reload')
  await until('document.querySelectorAll("button.prob").length===5 && document.querySelector("[data-metric=assetTotal] b")?.textContent==="14"','seeded statistics')
  const ranking = await evaluate('Array.from(document.querySelectorAll("button.prob")).map(row=>({no:row.dataset.asset,total:parseInt(row.lastElementChild.textContent)}))')
  assert.equal(ranking[0].no,'STAT-DEVICE-1'); assert.equal(ranking[0].total,12)
  pass('问题设备 Top 5 和四个资产统计项均为可点击按钮')

  for(const item of ranking) {
    await problem(item.no,item.total)
    assert.equal(await evaluate('location.hash'),'#/admin/stats')
    if(item.no==='STAT-ORPHAN') assert.ok(await evaluate('document.querySelector(".stats-data-dialog").textContent.includes("资产台账中未找到该设备")'))
    else assert.ok(await evaluate('!!document.querySelector(".asset-info")'))
    await close()
  }
  pass('Top 5 每一项在当前页面弹出对应设备资料和报修记录')
  pass('已不在台账中的设备仍可查看历史报修')
  await problem('STAT-DEVICE-1',12)
  assert.equal(await evaluate('document.querySelector(".repair-data-table").textContent.includes("STAT-DEVICE-10")'),false)
  await screenshot('stats-device-repairs.png')
  await button('下一页')
  await until('document.querySelector(".data-pager span")?.textContent.includes("2 / 2") && document.querySelectorAll(".repair-data-row").length===2','repair second page')
  await close()
  pass('报修记录精确匹配设备编号，并支持完整分页')

  await assetList('assetTotal',14)
  await button('下一页')
  await until('document.querySelector(".data-pager span")?.textContent.includes("2 / 2") && document.querySelectorAll(".asset-data-row").length===4','asset second page')
  await evaluate('(()=>{const input=document.querySelector(".data-toolbar input"); input.value="STAT-DEVICE-10";input.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await until('document.querySelectorAll(".asset-data-row").length===1 && document.querySelector(".data-pager span")?.textContent.includes("1 / 1")','asset search')
  assert.ok(await evaluate('document.querySelector(".asset-data-row").textContent.includes("统计设备 10")'))
  await close()
  pass('资产总数弹窗展示完整资产，支持分页和搜索并重置页码')
  for(const [key,total,status] of [['assetInUse',1,'在用'],['assetIdle',10,'闲置'],['assetBorrowed',1,'借出中']]) {
    await assetList(key,total)
    assert.ok(await evaluate('Array.from(document.querySelectorAll(".asset-data-row")).every(row=>row.children[3].textContent.trim()==='+JSON.stringify(status)+')'))
    if(key==='assetBorrowed') assert.ok(await evaluate('document.querySelector(".asset-data-row").textContent.includes("张伟")'))
    if(key==='assetIdle') await screenshot('stats-idle-assets.png')
    await close()
  }
  pass('在用、闲置和借出中弹窗展示对应状态和使用人数据')
  const returned = await api('POST','/asset-requests',{type:'RETURN',assetNo:'STAT-DEVICE-3',category:'终端',reason:'统计弹窗归还设备'},owner)
  await api('POST','/asset-requests/'+returned.id+'/approve',{})
  await assetList('assetBorrowed',0)
  assert.ok(await evaluate('document.querySelector(".stats-data-dialog").textContent.includes("暂无借出中资产")'))
  await close()
  assert.equal(await evaluate('document.querySelector("[data-metric=assetBorrowed] b").textContent'),'0')
  await assetList('assetBorrowed',0); await close()
  pass('零数量也可点击查看空状态，并同步最新统计数量')

  await click('[data-metric=assetTotal]')
  await until('document.querySelectorAll(".asset-data-row").length===10','keyboard dialog')
  await call('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27})
  await call('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27})
  await until('!document.querySelector(".stats-data-dialog")','escape close')
  assert.equal(await evaluate('document.activeElement?.dataset.metric'),'assetTotal')
  assert.notEqual(await evaluate('document.body.style.overflow'),'hidden')
  pass('支持 Escape 关闭并恢复焦点和页面滚动')

  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*','*/api/tickets*assetNo=STAT-DEVICE-1*']})
  await click('[data-asset="STAT-DEVICE-1"]')
  await until('!!document.querySelector(".data-error") && !!document.querySelector(".asset-info")','ticket error')
  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*']})
  await button('重新加载工单')
  await until('document.querySelectorAll(".repair-data-row").length===10 && !document.querySelector(".data-error")','retry repair data')
  await close()
  pass('报修请求失败时保留设备资料，并支持重新加载')
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
  await problem('STAT-DEVICE-1',12)
  assert.ok(await evaluate('document.querySelector(".stats-data-dialog").getBoundingClientRect().right <= innerWidth'))
  assert.ok(await evaluate('document.querySelector(".stats-data-dialog").scrollWidth <= document.querySelector(".stats-data-dialog").clientWidth'))
  await screenshot('stats-device-dialog-mobile.png')
  await close()
  pass('手机端弹窗边界和内容滚动正常')

  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await login('赵敏','/admin/stats')
  await until('document.querySelectorAll("button.prob").length===5','viewer statistics')
  await problem('STAT-DEVICE-1',12)
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".stats-data-dialog button")).some(button=>/编辑|保存|删除/.test(button.textContent))'),false)
  await close()
  await assetList('assetTotal',14); await close()
  pass('可查看用户可打开全部明细，弹窗不提供编辑操作')
  const policy = await api('GET','/admin/manage/permissions',undefined,admin)
  policy.assignments.VIEWER = policy.assignments.VIEWER.filter(permission=>!['asset.view','request.create','ticket.view.all'].includes(permission))
  await api('PUT','/admin/manage/permissions',{revision:policy.revision,selfServiceVersion:policy.selfServiceVersion,assignments:policy.assignments},admin)
  await login('赵敏','/admin/stats')
  await until('document.querySelectorAll("button.prob").length===5','restricted statistics')
  const before = detailRequests.length
  await click('[data-asset="STAT-DEVICE-1"]')
  await until('document.querySelector(".stats-data-dialog")?.textContent.includes("没有查看设备资料的权限")','restricted asset')
  assert.ok(await evaluate('document.querySelector(".stats-data-dialog").textContent.includes("没有查看全部工单明细的权限")'))
  assert.equal(await evaluate('!!document.querySelector(".asset-info") || !!document.querySelector(".repair-data-row")'),false)
  await close()
  await click('[data-metric=assetTotal]')
  await until('document.querySelector(".stats-data-dialog")?.textContent.includes("没有查看资产明细的权限")','restricted list')
  await close()
  assert.equal(detailRequests.length,before)
  pass('只有统计查看权限时不会读取或展示无权查看的设备和工单明细')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Stats details UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
