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
async function category(name) {
  await evaluate('(()=>{const element=Array.from(document.querySelectorAll(".asset-picker .category-option")).find(button=>button.dataset.category==='+JSON.stringify(name)+'); if(!element)throw new Error("Missing category"); element.focus(); element.click()})()')
  await until('document.querySelector(".asset-picker")?.dataset.selectedCategory === '+JSON.stringify(name),'selected category')
}
async function choose(no) {
  await evaluate('(()=>{const element=Array.from(document.querySelectorAll(".asset-picker .device-option")).find(button=>button.dataset.assetNo==='+JSON.stringify(no)+'); if(!element)throw new Error("Missing asset card"); element.focus(); element.click()})()')
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset === '+JSON.stringify(no),'selected asset')
}
async function click(text,root='.mask') {
  await evaluate('(()=>{const element=Array.from(document.querySelector('+JSON.stringify(root)+').querySelectorAll("button")).find(button=>button.textContent.trim()==='+JSON.stringify(text)+'); if(!element || element.disabled)throw new Error("Button unavailable"); element.click()})()')
}
async function search(value) {
  await evaluate('(()=>{const input=document.querySelector(".picker-search input"); input.value='+JSON.stringify(value)+'; input.dispatchEvent(new Event("input",{bubbles:true}))})()')
}
async function key(key,code=key,virtualKey=0) {
  await call('Input.dispatchKeyEvent',{type:'keyDown',key,code,windowsVirtualKeyCode:virtualKey})
  await call('Input.dispatchKeyEvent',{type:'keyUp',key,code,windowsVirtualKeyCode:virtualKey})
}
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs',{urls:['*g.alicdn.com*']})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await call('Page.navigate',{url:frontend})
  await until('!!document.querySelector(".shell")','initial app')
  await login('陈静','/admin/assets')
  await until('!!document.querySelector(".category-filter")','admin ledger')
  await api('POST','/asset-categories',{name:'办公笔记本',sort:0})
  for(let index=1;index<=9;index++) await api('POST','/assets',{
    no:'PICK-PC-'+String(index).padStart(2,'0'),name:index<=7?'联想 ThinkPad T14s 办公笔记本 '+index:index===8?'惠普 LaserJet 办公打印机':'联想 ThinkSystem 应用服务器',
    category:index<=7?'办公笔记本':index===8?'打印复印':'服务器',status:'闲置',sn:'PICK-SN-'+index,location:'A座 3F 设备库'
  })
  await api('POST','/assets',{no:'PICK-OTHER-OWNER',name:'他人名下在用笔记本',category:'办公笔记本',status:'在用',userName:'普通用户乙',location:'其他工位'})
  await login('张伟','/assets?type=RECEIVE')
  await until('!!document.querySelector(".asset-picker") && document.querySelectorAll(".device-option").length>=9','catalog picker')
  assert.ok(await evaluate('document.querySelector(".picker-categories").getBoundingClientRect().right <= document.querySelector(".picker-main").getBoundingClientRect().left+1'))
  assert.equal(await evaluate('document.querySelectorAll(".asset-picker select").length'),0)
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".mask button")).find(button=>button.textContent==="下一步").disabled'),true)
  pass('弹窗左侧分类、右侧卡片，默认等待用户主动选择')

  await category('办公笔记本')
  await until('document.querySelectorAll(".device-option").length===7','notebooks')
  assert.equal(await evaluate('document.querySelector(".category-option.active small").textContent'),'7')
  assert.equal(await evaluate('document.querySelector(".device-options").textContent.includes("他人名下")'),false)
  pass('分类显示当前业务可选数量，只展示符合领用规则的设备')
  await choose('PICK-PC-01'); await choose('PICK-PC-02')
  assert.equal(await evaluate('document.querySelectorAll(".device-option.selected").length'),1)
  assert.ok(await evaluate('document.querySelector(".selection-summary").textContent.includes("PICK-PC-02")'))
  await screenshot('asset-picker-category-cards.png')
  pass('设备卡片单选高亮，底部同步显示选中的设备')

  await choose('PICK-PC-01')
  await key('ArrowUp','ArrowUp',38)
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset === "PICK-PC-05"','keyboard previous row')
  await key('Home','Home',36)
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset === "PICK-PC-01"','first card')
  await key('End','End',35)
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset === "PICK-PC-07"','last card')
  pass('键盘方向键及 Home、End 可选择和定位设备卡片')
  await search('PICK-PC-07')
  await until('document.querySelectorAll(".device-option").length===1','search device')
  await search('没有这个设备')
  await until('document.querySelector(".picker-empty")?.textContent.includes("没有找到匹配的设备")','empty search')
  await click('清除搜索')
  await until('document.querySelectorAll(".device-option").length===7','clear search')
  pass('支持设备名称、编号搜索及清空搜索')
  await category('终端设备')
  await until('!document.querySelector(".device-option") && document.querySelector(".asset-picker")?.dataset.selectedAsset === ""','empty category reset')
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".mask button")).find(button=>button.textContent==="下一步").disabled'),true)
  await category('办公笔记本')
  assert.equal(await evaluate('document.querySelector(".asset-picker").dataset.selectedAsset'),'')
  pass('切换空分类清除原选择，不能携带旧设备继续申请')

  await choose('PICK-PC-03'); await click('下一步')
  await until('!!document.querySelector(".mask textarea")','application step')
  assert.ok(await evaluate('document.querySelector(".mask").textContent.includes("PICK-PC-03")'))
  await evaluate('(()=>{const input=document.querySelector(".mask textarea");input.value="卡片界面申请回归";input.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await click('重新选择分类与设备')
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset === "PICK-PC-03"','return to picker')
  pass('进入申请表单和重新选择时保留正确设备与分类')

  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
  await until('document.querySelector(".asset-picker").getBoundingClientRect().width < 400','mobile layout')
  assert.ok(await evaluate('document.querySelector(".picker-categories").getBoundingClientRect().right <= document.querySelector(".picker-main").getBoundingClientRect().left+1'))
  assert.ok(await evaluate('document.querySelector(".asset-selection-dialog").scrollWidth <= document.querySelector(".asset-selection-dialog").clientWidth'))
  assert.ok(await evaluate('Array.from(document.querySelectorAll(".picker-footer-actions button")).every(button=>button.getBoundingClientRect().bottom<=innerHeight)'))
  await choose('PICK-PC-01')
  await screenshot('asset-picker-cards-mobile.png')
  pass('手机端保持左分类右卡片，底部操作始终可见')
  await key('Escape','Escape',27)
  await until('!document.querySelector(".asset-picker")','escape picker')
  assert.notEqual(await evaluate('document.body.style.overflow'),'hidden')
  pass('Escape 关闭选择弹窗并恢复页面滚动')

  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await evaluate('location.hash="#/assets?asset=PICK-PC-04"')
  await until('document.querySelector(".mask .dlg-h")?.textContent.includes("设备详情")','direct device entry')
  await click('资产领用')
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset === "PICK-PC-04" && document.querySelector(".asset-picker")?.dataset.selectedCategory === "办公笔记本"','direct preselection')
  pass('从指定设备发起申请时自动定位分类并高亮该设备')
  await click('下一步')
  await until('!!document.querySelector(".mask textarea")','final application')
  await evaluate('(()=>{const input=document.querySelector(".mask textarea");input.value="设备卡片界面回归提交";input.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await click('提交申请')
  await until('!document.querySelector(".mask")','request submitted')
  const requests = await api('GET','/asset-requests?mine=true')
  const request = requests.find(item=>item.reason==='设备卡片界面回归提交')
  assert.equal(request.assetNo,'PICK-PC-04'); assert.equal(request.assetCategory,'办公笔记本')
  pass('卡片选中的设备与分类正确提交到申请单')
  await login('赵敏','/assets?type=RECEIVE')
  await until('!!document.querySelector(".category-toolbar")','viewer service')
  await until('!!document.querySelector(".asset-picker")','viewer personal receive picker')
  assert.equal(await evaluate('document.querySelectorAll(".qcard").length'),4)
  pass('可查看用户可以使用本人领用、借用、更换和归还入口')
  assert.deepEqual(exceptions,[],'浏览器出现未处理异常')
  assert.deepEqual(serverErrors,[],'页面请求出现服务器错误')
  console.log('Asset picker UI checks: '+checks.length+' passed')
} finally {
  socket.send(JSON.stringify({id:++sequence,method:'Browser.close'}))
  await new Promise(resolve=>setTimeout(resolve,150))
  socket.close()
}
