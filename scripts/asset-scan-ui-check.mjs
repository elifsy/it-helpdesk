// 隔离 H2 + 模拟钉钉 JSAPI。验证真实 Vue 路由与设备操作，不调用真实钉钉或业务库。
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
const frontend = 'http://127.0.0.1:5186'
let pages
for (let i = 0; i < 50 && !pages; i++) {
  try { pages = await (await fetch('http://127.0.0.1:9231/json/list')).json() } catch { await new Promise(resolve => setTimeout(resolve, 100)) }
}
assert.ok(pages?.length, 'Start the isolated browser on port 9231')
const socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl)
await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
let next = 0
const pending = new Map(), exceptions = [], checks = []
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data), callback = pending.get(message.id)
  if (callback) { pending.delete(message.id); message.error ? callback.reject(new Error(message.error.message)) : callback.resolve(message.result) }
  if (message.method === 'Runtime.exceptionThrown') exceptions.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text)
  if (message.method === 'Fetch.requestPaused') {
    void call('Fetch.fulfillRequest', { requestId: message.params.requestId, responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body: Buffer.from(JSON.stringify({ code: 0, data: { demoEnabled: false, corpId: 'fixture-only-corp' } })).toString('base64') })
  }
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
  const value = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (value.exceptionDetails) throw new Error(value.exceptionDetails.exception?.description || value.exceptionDetails.text)
  return value.result?.value
}
async function until(expression) {
  const end = Date.now() + 15000
  while (Date.now() < end) { try { if (await evaluate(expression)) return } catch {} await new Promise(resolve => setTimeout(resolve, 100)) }
  throw new Error('UI timeout: ' + expression + '\n' + await evaluate('document.body.innerText'))
}
async function navigate(suffix) {
  await call('Page.navigate', { url: 'about:blank' }); await until("location.href==='about:blank'")
  await call('Page.navigate', { url: frontend + suffix })
}
async function device(no) { await until('document.querySelector(".device-dialog")?.dataset.assetNo===' + JSON.stringify(no)) }
async function login(name, no) {
  await evaluate(`(async()=>{const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({demoUser:${JSON.stringify(name)}})});const b=await r.json();localStorage.setItem('it_helpdesk_token',b.data.token)})()`)
  await navigate('/#/assets?asset=' + no); await device(no)
}
const pass = label => { checks.push(label); console.log('PASS ' + label) }
try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs', { urls: ['*g.alicdn.com*'] })
  await call('Network.setUserAgentOverride', { userAgent: 'Mozilla/5.0 Android Mobile DingTalk/7.6.0' })
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  await call('Fetch.enable', { patterns: [{ urlPattern: frontend + '/api/auth/config*', requestStage: 'Request' }] })
  await call('Page.addScriptToEvaluateOnNewDocument', { source: `if(location.protocol==='http:'){
    if(new URL(location.href).searchParams.has('fresh'))localStorage.removeItem('it_helpdesk_token');
    window.__authCalls=0; window.dd={ready:cb=>cb(),error:()=>{},runtime:{permission:{requestAuthCode:args=>{window.__authCalls++;args.onSuccess({code:'fixture-only-code'})}}},biz:{util:{scan:args=>args.onSuccess({text:window.__scanValue||''})}}};
  }` })

  await navigate('/?fresh=1#asset=ASSET-TEST-1'); await device('ASSET-TEST-1')
  assert.ok(await evaluate('location.hash==="#/assets?asset=ASSET-TEST-1"'))
  assert.equal(await evaluate('window.__authCalls'), 1)
  assert.ok(await evaluate('document.querySelector(".device-actions").textContent.includes("资产领用")'))
  pass('cold entry from an old QR label survives DingTalk bootstrap and opens the correct operation dialog')
  await evaluate('location.hash="asset=ASSET-TEST-2"'); await device('ASSET-TEST-2')
  assert.ok(await evaluate('location.hash==="#/assets?asset=ASSET-TEST-2"'))
  await navigate('/#/assets?asset=ASSET-TEST-1'); await device('ASSET-TEST-1')
  assert.equal(await evaluate('window.__authCalls'), 0)
  await call('Page.reload'); await device('ASSET-TEST-1')
  pass('old links opened while running, canonical links and page reloads retain the selected device')

  await evaluate('location.hash="/home"'); await until('!!document.querySelector(".home .entries")')
  await evaluate('window.__scanValue="http://localhost:5173/#asset=ASSET-TEST-2"; Array.from(document.querySelectorAll(".entry")).find(e=>e.querySelector(".l")?.textContent==="扫码").click()')
  await device('ASSET-TEST-2'); assert.equal(await evaluate('location.origin'), frontend)
  await fs.mkdir(new URL('../shots/regression/', import.meta.url), { recursive: true })
  const shot = await call('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL('../shots/regression/scanned-device-operations.png', import.meta.url), Buffer.from(shot.data, 'base64'))
  await evaluate('Array.from(document.querySelectorAll(".device-actions button")).find(b=>b.textContent.includes("资产领用")).click()')
  await until('document.querySelector(".asset-picker")?.dataset.selectedAsset==="ASSET-TEST-2"')
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".picker-footer-actions button")).find(b=>b.textContent==="下一步").disabled'), false)
  pass('the in-app scanner extracts the device from an old localhost label and preselects it for receiving')

  await navigate('/#/assets?asset=ASSET-TEST-1'); await device('ASSET-TEST-1')
  await evaluate('Array.from(document.querySelectorAll(".device-actions button")).find(b=>b.textContent.includes("故障报修")).click()')
  await until('!!document.querySelector(".new-ticket") && Array.from(document.querySelectorAll(".new-ticket select")).some(s=>s.value==="ASSET-TEST-1")')
  assert.ok(await evaluate('location.hash.includes("asset=ASSET-TEST-1")'))
  pass('reporting from the scanned device automatically links that device on the ticket form')

  await navigate('/#/assets?asset=MISSING-DEVICE'); await until('document.querySelector(".device-error")?.textContent.includes("资产不存在")')
  assert.equal(await evaluate('document.querySelector(".device-dialog")===null'), true)
  pass('an unknown device displays an actionable error instead of the homepage or another device')

  await login('赵敏', 'ASSET-TEST-1')
  assert.ok(await evaluate('document.querySelector(".device-actions").textContent.includes("故障报修") && document.querySelector(".device-actions").textContent.includes("资产领用") && !document.querySelector(".device-actions").textContent.includes("编辑与管理")'))
  await login('李强', 'ASSET-TEST-2')
  await evaluate('Array.from(document.querySelectorAll(".device-actions button")).find(b=>b.textContent.includes("编辑与管理")).click()')
  await until('location.hash.includes("/admin/assets/ASSET-TEST-2") && document.querySelector(".asset-detail h2")?.textContent==="替换设备"')
  await evaluate('document.querySelector("[data-action=asset-qrcode]").click()'); await until('!!document.querySelector(".qr-url")')
  assert.ok(await evaluate('document.querySelector(".qr-url").value.includes("/#/assets?asset=ASSET-TEST-2")'))
  assert.deepEqual(exceptions, [])
  pass('viewer can report and apply for equipment, IT can manage the scanned device, and regenerated QR links use the valid route')
  console.log('Asset scan UI checks: ' + checks.length + ' passed')
} finally {
  socket.send(JSON.stringify({ id: ++next, method: 'Browser.close' })); await new Promise(resolve => setTimeout(resolve, 150)); socket.close()
}
