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
async function click(text, root='main') {
  await evaluate("(()=>{const button=Array.from(document.querySelector("+JSON.stringify(root)+").querySelectorAll('button')).find(b=>b.textContent.trim()==="+JSON.stringify(text)+"); if(!button || button.disabled)throw new Error('Button unavailable: '+"+JSON.stringify(text)+"); button.click()})()")
}
async function fill(label,value) {
  const selector='[aria-label="'+label+'"]'
  await evaluate("(()=>{const input=document.querySelector("+JSON.stringify(selector)+"); if(!input)throw new Error('Missing input'); input.value="+JSON.stringify(value)+"; input.dispatchEvent(new Event('input',{bubbles:true}))})()")
}
async function select(label,value) {
  const selector='select[aria-label="'+label+'"]'
  await until("Array.from(document.querySelector("+JSON.stringify(selector)+")?.options || []).some(option=>option.value==="+JSON.stringify(value)+")",label+' options')
  await evaluate("(()=>{const input=document.querySelector("+JSON.stringify(selector)+"); input.value="+JSON.stringify(value)+"; input.dispatchEvent(new Event('change',{bubbles:true}))})()")
}
async function api(method,path,payload) {
  return evaluate("(async()=>{const response=await fetch('/api'+"+JSON.stringify(path)+",{method:"+JSON.stringify(method)+",headers:{'Content-Type':'application/json',Authorization:'Bearer '+localStorage.getItem('it_helpdesk_token')},body:"+JSON.stringify(payload===undefined?null:JSON.stringify(payload))+" || undefined}); const result=await response.json(); if(!response.ok || result.code!==0)throw new Error(result.message); return result.data})()")
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*']})
  await call('Page.addScriptToEvaluateOnNewDocument',{source:'window.confirm = () => true'})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate',{url:frontend})
  await until("!!document.querySelector('.shell')",'initial app')
  await login('陈静','/admin/assets')
  await until("document.querySelectorAll('.asset-row').length===2",'ledger rows')
  await select('资产分类筛选','终端')
  await until("document.querySelectorAll('.asset-row').length===2",'filtered ledger')
  await evaluate("document.querySelector('.asset-row td:nth-child(2) a').click()")
  await until("!!document.querySelector('.asset-detail') && document.querySelector('.asset-detail h2')?.textContent==='测试笔记本'",'device detail')
  assert.equal(await evaluate("document.querySelectorAll('.detail-actions button').length"),3)
  pass('点击台账设备名称打开详情，集中显示编辑、二维码和分类入口')
  await screenshot('asset-detail.png')
  await evaluate("document.querySelector('[data-action=asset-qrcode]').click()")
  await until("document.querySelector('.qr-dialog canvas')?.width>200 && !!document.querySelector('.qr-dialog input')?.value",'QR rendered')
  assert.ok((await evaluate("document.querySelector('.qr-dialog input').value")).includes('ASSET-TEST-1'))
  await screenshot('asset-detail-qrcode.png')
  await evaluate("document.querySelector('[aria-label=\"关闭二维码\"]').click()")
  pass('设备详情可查看对应设备的二维码')

  await evaluate("document.querySelector('[data-action=edit-asset]').click()")
  await until("!!document.querySelector('.edit-dialog')",'edit modal')
  await fill('设备名称','更新后的办公笔记本')
  await fill('SN序列号','SN-DETAIL-UPDATED')
  await fill('存放位置','A座 3F 新工位')
  await fill('购置日期','2024-01-01')
  await fill('维保到期','2027-01-01')
  await fill('维保方式','厂商保修')
  await fill('设备备注','设备详情编辑浏览器回归')
  await select('设备状态','维修中')
  await screenshot('asset-detail-edit.png')
  await click('保存设备','.edit-dialog')
  await until("!document.querySelector('.edit-dialog') && document.querySelector('.asset-detail h2')?.textContent==='更新后的办公笔记本'",'saved metadata')
  assert.equal((await api('GET','/assets/ASSET-TEST-1')).asset.sn,'SN-DETAIL-UPDATED')
  assert.ok(await evaluate("document.querySelector('.history')?.textContent.includes('编辑设备')"))
  pass('设备资料和状态可编辑保存，并显示资产履历')
  await call('Page.reload')
  await until("document.querySelector('.asset-detail h2')?.textContent==='更新后的办公笔记本'",'detail refresh')
  assert.ok(await evaluate("document.querySelector('.asset-detail')?.textContent.includes('A座 3F 新工位')"))
  pass('刷新详情页保留已保存的信息和设备定位')

  await evaluate("document.querySelector('[data-action=asset-category]').click()")
  await until("!!document.querySelector('select[aria-label=\"详情调整分类\"]')",'category modal')
  await select('详情调整分类','打印复印')
  await click('保存分类','.mask')
  await until("!document.querySelector('.mask') && document.querySelector('.info-grid')?.textContent.includes('打印复印')",'classification updated')
  assert.equal((await api('GET','/assets/ASSET-TEST-1')).asset.category,'打印复印')
  pass('详情页可调整分类，并同步更新资料与履历')
  await evaluate("document.querySelector('.back-link').click()")
  await until("!!document.querySelector('select[aria-label=\"资产分类筛选\"]') && document.querySelectorAll('.asset-row').length===1",'return to filtered ledger')
  assert.equal(await evaluate("document.querySelector('select[aria-label=\"资产分类筛选\"]').value"),'终端')
  assert.ok(await evaluate("document.querySelector('.asset-row')?.textContent.includes('ASSET-TEST-2')"))
  pass('返回台账保留原分类筛选，不再显示已移出该分类的设备')

  assert.deepEqual(await evaluate("Array.from(document.querySelectorAll('.asset-row button')).map(b=>b.textContent.trim())"),['查看详情'])
  await click('查看详情','.asset-row')
  await until("!!document.querySelector('.detail-actions') && location.hash.includes('ASSET-TEST-2')",'ledger view detail')
  await evaluate("document.querySelector('[data-action=edit-asset]').click()")
  await until("!!document.querySelector('.edit-dialog')",'edit from detail')
  assert.equal(await evaluate("document.querySelector('[aria-label=\"设备名称\"]').value"),'替换设备')
  await click('取消','.edit-dialog')
  await until("!document.querySelector('.edit-dialog') && !location.hash.includes('edit=')",'cancel direct edit')
  pass('台账仅保留查看详情，进入详情仍可编辑对应设备，取消不写入数据')

  await call('Page.navigate',{url:frontend+'/#/admin/assets/ASSET-TEST-1'})
  await until("document.querySelector('.asset-detail h2')?.textContent==='更新后的办公笔记本'",'detail before conflict')
  await evaluate("document.querySelector('[data-action=edit-asset]').click()")
  await until("!!document.querySelector('.edit-dialog')",'edit conflict modal')
  await fill('SN序列号','STALE-SHOULD-NOT-SAVE')
  await api('PUT','/assets/ASSET-TEST-1/category',{category:'终端'})
  await click('保存设备','.edit-dialog')
  await until("document.querySelector('.edit-dialog .error-box')?.textContent.includes('设备信息已变化')",'stale save rejection')
  assert.equal((await api('GET','/assets/ASSET-TEST-1')).asset.sn,'SN-DETAIL-UPDATED')
  await click('重新加载设备信息','.edit-dialog')
  await until("document.querySelector('.edit-dialog [aria-label=\"编辑资产分类\"]')?.value==='终端' && !document.querySelector('.edit-dialog .error-box')",'reload latest metadata')
  await click('取消','.edit-dialog')
  pass('旧编辑页面遇到分类变更时拒绝覆盖，并可重新加载最新信息')

  await login('赵敏','/admin/assets/ASSET-TEST-1?edit=1')
  await until("!!document.querySelector('.asset-detail .info-grid')",'readonly detail')
  assert.equal(await evaluate("!!document.querySelector('[data-action=edit-asset]')"),false)
  assert.equal(await evaluate("!!document.querySelector('[data-action=asset-category]')"),false)
  assert.equal(await evaluate("!!document.querySelector('.edit-dialog')"),false)
  assert.equal(await evaluate("!!document.querySelector('[data-action=asset-qrcode]')"),true)
  await screenshot('asset-detail-readonly.png')
  pass('可查看用户能打开详情和二维码，不能编辑或调整分类')

  await call('Page.navigate',{url:frontend+'/#/admin/assets/DOES-NOT-EXIST'})
  await until("document.querySelector('.asset-detail .error-box')?.textContent.includes('资产不存在')",'missing asset')
  assert.equal(await evaluate("!!document.querySelector('.detail-actions')"),false)
  pass('不存在的设备展示明确错误，不保留上一台设备信息')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Asset detail UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
