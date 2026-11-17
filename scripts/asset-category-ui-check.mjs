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
async function clickButton(text, root = 'main') {
  await evaluate("(()=>{const root=document.querySelector("+JSON.stringify(root)+"); const button=Array.from(root.querySelectorAll('button')).find(b=>b.textContent.trim()==="+JSON.stringify(text)+"); if(!button || button.disabled) throw new Error('Button unavailable: '+"+JSON.stringify(text)+"); button.click()})()")
}
async function fill(selector, value) {
  await evaluate("(()=>{const input=document.querySelector("+JSON.stringify(selector)+"); if(!input) throw new Error('Missing input'); input.value="+JSON.stringify(value)+"; input.dispatchEvent(new Event('input',{bubbles:true}))})()")
}
async function choose(label, value) {
  if (['申请资产分类','替换设备分类'].includes(label)) {
    await until("Array.from(document.querySelectorAll('.asset-picker .category-option')).some(button=>button.dataset.category==="+JSON.stringify(value)+")",label+' category ready')
    await evaluate("Array.from(document.querySelectorAll('.asset-picker .category-option')).find(button=>button.dataset.category==="+JSON.stringify(value)+").click()")
    await until("document.querySelector('.asset-picker')?.dataset.selectedCategory==="+JSON.stringify(value),label)
    return
  }
  if (['申请设备','替换设备'].includes(label)) {
    await until("Array.from(document.querySelectorAll('.asset-picker .device-option')).some(button=>button.dataset.assetNo==="+JSON.stringify(value)+")",label+' card ready')
    await evaluate("Array.from(document.querySelectorAll('.asset-picker .device-option')).find(button=>button.dataset.assetNo==="+JSON.stringify(value)+").click()")
    await until("document.querySelector('.asset-picker')?.dataset.selectedAsset==="+JSON.stringify(value),label)
    return
  }
  const selector='select[aria-label="'+label+'"]'
  await until("Array.from(document.querySelector("+JSON.stringify(selector)+")?.options || []).some(option=>option.value === "+JSON.stringify(value)+")", label+' options ready')
  await evaluate("(()=>{const input=document.querySelector("+JSON.stringify(selector)+"); if(!input) throw new Error('Missing select'); input.value="+JSON.stringify(value)+"; input.dispatchEvent(new Event('change',{bubbles:true}))})()")
  await until("document.querySelector("+JSON.stringify(selector)+")?.value === "+JSON.stringify(value), label)
}
async function api(method, path, payload, token) {
  return evaluate("(async()=>{const response=await fetch('/api'+"+JSON.stringify(path)+",{method:"+JSON.stringify(method)+",headers:{'Content-Type':'application/json',Authorization:'Bearer '+("+JSON.stringify(token||'')+" || localStorage.getItem('it_helpdesk_token'))},body:"+JSON.stringify(payload===undefined?null:JSON.stringify(payload))+" || undefined}); const result=await response.json(); if(!response.ok || result.code!==0) throw new Error(result.message); return result.data})()")
}
async function beginRequest(type, no) {
  await login('张伟', '/assets?type='+type)
  await until("!!document.querySelector('.asset-picker')", type+' category picker')
  await choose('申请资产分类','打印复印')
  await until("document.querySelector('.asset-picker')?.dataset.selectedAsset === ''", type+' reset selection')
  assert.equal(await evaluate("Array.from(document.querySelectorAll('.mask button')).find(b=>b.textContent==='下一步').disabled"), true)
  await choose('申请资产分类','终端')
  await until("Array.from(document.querySelectorAll('.asset-picker .device-option')).some(button=>button.dataset.assetNo==="+JSON.stringify(no)+")", type+' eligible device')
  await choose('申请设备',no)
  if(type==='RECEIVE') await screenshot('asset-request-category.png')
  await clickButton('下一步','.mask')
  await until("!!document.querySelector('.mask textarea')", type+' application form')
  await fill('.mask textarea','分类选择浏览器回归-'+type)
  await clickButton('提交申请','.mask')
  await until("!document.querySelector('.mask')", type+' request submitted')
  const requests=await api('GET','/asset-requests?mine=true')
  const request=requests.find(r=>r.reason==='分类选择浏览器回归-'+type)
  assert.ok(request,type+' request was persisted')
  assert.equal(request.assetCategory,'终端')
  pass(type+' 按分类选设备并提交成功；空分类不保留旧设备')
  return request
}

try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*']})
  await call('Page.addScriptToEvaluateOnNewDocument',{source:'window.confirm = () => true'})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate',{url:frontend})
  await until("!!document.querySelector('.shell')",'initial app')
  await login('陈静','/admin/assets')
  await until("Array.from(document.querySelectorAll('main button')).some(b=>b.textContent==='管理分类')",'asset ledger')
  const adminToken=await evaluate("localStorage.getItem('it_helpdesk_token')")
  await clickButton('管理分类')
  await until("!!document.querySelector('.category-manager')",'category manager')
  await fill('input[aria-label="分类名称"]','显示设备')
  await clickButton('新增分类','.category-manager')
  await until("document.querySelector('.category-manager tbody')?.textContent.includes('显示设备')",'category create')
  pass('在资产台账新增持久化分类')
  await evaluate("Array.from(document.querySelectorAll('.category-manager tbody tr')).find(r=>r.textContent.includes('显示设备')).querySelector('button').click()")
  await fill('input[aria-label="分类名称"]','显示器')
  await clickButton('保存修改','.category-manager')
  await until("document.querySelector('.category-manager tbody')?.textContent.includes('显示器') && !document.querySelector('.category-manager tbody')?.textContent.includes('显示设备')",'category rename')
  await screenshot('asset-categories.png')
  pass('分类可重命名，列表同步刷新')
  await clickButton('完成','.category-manager')
  await clickButton('＋ 新增资产')
  await until("!!document.querySelector('select[aria-label=\"新增资产分类\"]')",'new asset')
  await fill('input[placeholder="IT-PC-2026-0001"]','SCREEN-1')
  await fill('input[placeholder="联想 ThinkPad T14s"]','回归测试显示器')
  await choose('新增资产分类','显示器')
  await clickButton('保存','.mask')
  await until("!document.querySelector('.mask') && document.querySelector('main tbody')?.textContent.includes('SCREEN-1')",'asset create')
  await choose('资产分类筛选','显示器')
  await until("document.querySelector('main tbody')?.textContent.includes('SCREEN-1') && !document.querySelector('main tbody')?.textContent.includes('ASSET-TEST-1')",'ledger category filter')
  pass('新增资产使用动态分类，台账按分类查询')
  await clickButton('查看详情')
  await until("!!document.querySelector('.asset-detail .info-grid')",'asset detail')
  await clickButton('调整分类')
  await until("!!document.querySelector('select[aria-label=\"详情调整分类\"]')",'assign category')
  await choose('详情调整分类','打印复印')
  await clickButton('保存分类','.mask')
  await until("!document.querySelector('.mask')",'assigned category')
  assert.equal((await api('GET','/assets/SCREEN-1')).asset.category,'打印复印')
  await evaluate("document.querySelector('.back-link').click()")
  await until("!!document.querySelector('select[aria-label=\"资产分类筛选\"]')",'return to ledger')
  await choose('资产分类筛选','打印复印')
  await until("document.querySelector('main tbody')?.textContent.includes('SCREEN-1')",'updated filter')
  await evaluate("Array.from(document.querySelectorAll('.asset-row')).find(r=>r.textContent.includes('SCREEN-1')).querySelector('button').click()")
  await until("!!document.querySelector('.asset-detail .info-grid')",'updated asset detail')
  await clickButton('调整分类')
  await until("!!document.querySelector('select[aria-label=\"详情调整分类\"]')",'assign category again')
  await choose('详情调整分类','显示器')
  await clickButton('保存分类','.mask')
  await until("!document.querySelector('.mask')",'restored category')
  await evaluate("document.querySelector('.back-link').click()")
  await until("!!document.querySelector('select[aria-label=\"资产分类筛选\"]')",'return to ledger again')
  await choose('资产分类筛选','显示器')
  await until("document.querySelector('main tbody')?.textContent.includes('SCREEN-1')",'restored ledger filter')
  await screenshot('asset-ledger-category.png')
  pass('已有资产可调整分类，筛选和设备详情同步更新')

  // 新增资产默认待入库，通过正常借用/归还将测试替换机置为闲置。
  const ownerToken=(await api('POST','/auth/login',{demoUser:'张伟'})).token
  const preparedBorrow=await api('POST','/asset-requests',{type:'BORROW',assetNo:'SCREEN-1',category:'显示器',reason:'准备替换机借用',expectReturn:new Date(Date.now()+86400000).toISOString().slice(0,10)},ownerToken)
  await api('POST','/asset-requests/'+preparedBorrow.id+'/approve',{},adminToken)
  const preparedReturn=await api('POST','/asset-requests',{type:'RETURN',assetNo:'SCREEN-1',category:'显示器',reason:'准备替换机归还'},ownerToken)
  await api('POST','/asset-requests/'+preparedReturn.id+'/approve',{},adminToken)

  const receive=await beginRequest('RECEIVE','ASSET-TEST-1')
  await api('POST','/asset-requests/'+receive.id+'/approve',{},adminToken)
  assert.equal((await api('GET','/assets/ASSET-TEST-1')).asset.userName,'张伟')
  const borrow=await beginRequest('BORROW','ASSET-TEST-2')
  await api('POST','/asset-requests/'+borrow.id+'/approve',{},adminToken)
  assert.equal((await api('GET','/assets/ASSET-TEST-2')).asset.borrower,'张伟')
  const replace=await beginRequest('REPLACE','ASSET-TEST-1')

  await login('陈静','/admin/approvals')
  await until("Array.from(document.querySelectorAll('main .card')).some(r=>r.textContent.includes('分类选择浏览器回归-REPLACE'))",'replacement approval')
  await evaluate("Array.from(document.querySelectorAll('main .card')).find(r=>r.textContent.includes('分类选择浏览器回归-REPLACE')).querySelector('button.pri').click()")
  await until("!!document.querySelector('.asset-picker')",'replacement categories')
  await choose('替换设备分类','终端')
  await until("document.querySelector('.asset-picker')?.dataset.selectedAsset === ''",'no idle terminals')
  assert.equal(await evaluate("Array.from(document.querySelectorAll('.mask button')).find(b=>b.textContent==='确认批准').disabled"),true)
  await choose('替换设备分类','显示器')
  await choose('替换设备','SCREEN-1')
  await until("document.querySelector('.asset-picker')?.dataset.selectedAsset === 'SCREEN-1'",'replacement selected')
  await screenshot('asset-replacement-category.png')
  await clickButton('确认批准','.mask')
  await until("!document.querySelector('.mask')",'replacement approved')
  assert.equal((await api('GET','/assets/SCREEN-1')).asset.userName,'张伟')
  assert.equal((await api('GET','/assets/ASSET-TEST-1')).asset.status,'闲置')
  pass('更换审批可按新设备分类选择替换机，空分类禁止批准')

  const returned=await beginRequest('RETURN','ASSET-TEST-2')
  await api('POST','/asset-requests/'+returned.id+'/approve',{},adminToken)
  const asset=(await api('GET','/assets/ASSET-TEST-2')).asset
  assert.equal(asset.status,'闲置')
  assert.equal(asset.borrower,'')
  pass('归还后解除借用关系，设备分类保留')

  await login('赵敏','/admin/assets')
  await until("!!document.querySelector('select[aria-label=\"资产分类筛选\"]')",'viewer category filter')
  assert.equal(await evaluate("Array.from(document.querySelectorAll('main button')).some(b=>['管理分类','＋ 新增资产','调整分类'].includes(b.textContent))"),false)
  await choose('资产分类筛选','显示器')
  await until("document.querySelector('main tbody')?.textContent.includes('SCREEN-1')",'viewer filter')
  pass('可查看用户可筛选资产分类，不能维护分类或修改资产')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Asset category UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
