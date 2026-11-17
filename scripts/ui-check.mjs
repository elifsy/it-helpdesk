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
function pass(name) { checks.push(name); console.log('PASS ' + name) }

try {
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable')
  await call('Network.setBlockedURLs', { urls: ['*g.alicdn.com*'] })
  await call('Page.addScriptToEvaluateOnNewDocument', { source: 'window.confirm = () => true' })
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: frontend })
  await until("!!document.querySelector('.shell')", 'initial login')
  await login('陈静', '/admin/config?tab=users')
  await until("document.querySelectorAll('.management tbody tr').length === 6", 'users list')
  pass('管理员用户列表加载真实数据')
  await screenshot('admin-users.png')

  await evaluate("document.querySelector('.management tbody tr button').click()")
  await until("!!document.querySelector('.editor select')", 'user editor')
  await evaluate("{ const role=document.querySelector('.editor select'); role.value='IT'; role.dispatchEvent(new Event('change',{bubbles:true})); document.querySelector('form.editor').requestSubmit() }")
  await until("!document.querySelector('.editor') && document.querySelector('.management tbody tr')?.textContent.includes('IT')", 'role save')
  pass('用户角色编辑并刷新列表')

  await tab('', '运行开关')
  const previousRecommendation = await evaluate("document.querySelector('.management input[type=checkbox]').checked")
  await evaluate("document.querySelector('.management input[type=checkbox]').click()")
  await until("!!document.querySelector('.save-bar button')", 'dirty settings')
  await evaluate("document.querySelector('.save-bar button').click()")
  await until("!document.querySelector('.save-bar')", 'settings persisted')
  await call('Page.reload')
  await until("!!document.querySelector('.management input[type=checkbox]')", 'reload settings')
  assert.equal(await evaluate("document.querySelector('.management input[type=checkbox]').checked"), !previousRecommendation)
  pass('运行开关保存后刷新仍生效')

  for (const [key, title] of [['depts','部门'],['matrix','角色权限配置'],['sla','SLA 解决时限'],['dict','问题分类'],['audit','审计日志'],['storage','附件与存储'],['jobs','定时任务']]) {
    await tab(key, title)
    assert.equal(await evaluate("!!document.querySelector('.management .error')"), false)
    pass('管理分区：' + title)
  }

  await tab('matrix', '角色权限配置')
  await until("document.querySelectorAll('.role-card').length === 4", 'four role permission cards')
  assert.equal(await evaluate("document.querySelectorAll('.role-card').length"), 4)
  assert.equal(await evaluate("document.querySelector('[data-role=VIEWER][data-permission=\"attachment.upload\"]').disabled"), false)
  assert.equal(await evaluate("document.querySelector('[data-role=VIEWER][data-permission=\"asset.create\"]').disabled"), true)
  assert.equal(await evaluate("document.querySelector('[data-role=ADMIN][data-permission=\"role.manage\"]').disabled"), true)
  pass('权限配置页展示四种角色，可查看用户可配置本人自助权限，管理操作保持禁用')
  const existingItToken = await evaluate("(async()=>{const r=await (await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({demoUser:'李强'})})).json(); return r.data.token})()")
  await evaluate("{ const box=document.querySelector('[data-role=IT][data-permission=\"asset.create\"]'); if(box.checked) box.click() }")
  await evaluate("document.querySelector('.permission-save button').click()")
  await until("document.querySelector('.permission-save button')?.disabled && document.querySelector('.permission-save button')?.textContent === '保存角色权限' && !document.querySelector('.role-permissions .error')", 'save revoked IT permission')
  assert.equal(await evaluate("(async()=> (await fetch('/api/assets',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+ " + JSON.stringify(existingItToken) + "},body:JSON.stringify({no:'UI-DENIED',name:'不应创建',category:'终端'})})).status)()"), 403)
  pass('权限取消后，已有 IT 登录会话的新增资产接口立即被拒绝')
  await login('李强', '/admin/assets')
  await until("document.querySelector('main')?.textContent.includes('资产台账')", 'IT assets')
  assert.equal(await evaluate("Array.from(document.querySelectorAll('main button')).some(b=>b.textContent.includes('新增资产'))"), false)
  pass('IT 页面同步隐藏被取消权限的新增按钮')
  await login('陈静', '/admin/config?tab=matrix')
  await until("!!document.querySelector('[data-role=IT][data-permission=\"asset.create\"]')", 'role editor reload')
  assert.equal(await evaluate("document.querySelector('[data-role=IT][data-permission=\"asset.create\"]').checked"), false)
  await evaluate("document.querySelector('[data-role=IT][data-permission=\"asset.create\"]').click()")
  await until("document.querySelector('.permission-save button')?.disabled === false", 'restored permission ready to save')
  await evaluate("document.querySelector('.permission-save button').click()")
  await until("document.querySelector('.permission-save button')?.disabled && document.querySelector('.permission-save button')?.textContent === '保存角色权限'", 'restore IT create permission')
  await call('Page.reload')
  await until("!!document.querySelector('[data-role=IT][data-permission=\"asset.create\"]')", 'restored permissions after reload')
  assert.equal(await evaluate("document.querySelector('[data-role=IT][data-permission=\"asset.create\"]').checked"), true)
  pass('权限修改持久化，恢复后刷新仍生效')
  await screenshot('role-permissions.png')

  await tab('dict', '问题分类')
  await evaluate("Array.from(document.querySelectorAll('.management button')).find(b => b.textContent === '新增代码').click()")
  await until("!!document.querySelector('.editor')", 'code editor')
  await evaluate("{ const inputs=document.querySelectorAll('.editor input'); ['终端','UI-CHECK','浏览器回归代码','1'].forEach((value,i)=>{ inputs[i].value=value; inputs[i].dispatchEvent(new Event('input',{bubbles:true})) }); document.querySelector('form.editor').requestSubmit() }")
  await until("!document.querySelector('.editor') && document.querySelector('.management tbody')?.textContent.includes('UI-CHECK')", 'code saved')
  pass('解决代码可创建并在列表显示')
  await tab('audit', '审计日志')
  await until("document.querySelector('.management tbody')?.textContent.includes('SOLUTION_SAVE')", 'audit data')
  await screenshot('admin-audit.png')
  pass('审计记录显示实际管理操作')

  await evaluate(`(async () => {
    const bytes = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/D1sAAAAASUVORK5CYII='), c=>c.charCodeAt(0))
    const data = new FormData(); data.append('file',new Blob([bytes],{type:'image/png'}),'preview.png'); data.append('bizType','TICKET'); data.append('bizNo','IT-TEST-0001')
    const response=await fetch('/api/attachments',{method:'POST',headers:{Authorization:'Bearer '+localStorage.getItem('it_helpdesk_token')},body:data}); const result=await response.json(); if(result.code !== 0) throw new Error(result.message)
  })()`)
  await login('张伟', '/ticket/IT-TEST-0001')
  await until("document.querySelector('.att img')?.naturalWidth > 0", 'authenticated image preview')
  pass('带认证的附件图片通过 Blob URL 正常显示')
  await screenshot('ticket-attachment.png')
  await call('Page.reload')
  await until("document.querySelector('.att img')?.naturalWidth > 0", 'session restore after reload')
  pass('刷新页面恢复登录与附件展示')

  await login('赵敏', '/admin/config?tab=users')
  await until("location.hash === '#/admin/tickets' && !!document.querySelector('.shell')", 'employee route guard')
  assert.equal(await evaluate("!!document.querySelector('.management')"), false)
  pass('可查看用户无法进入系统管理')
  assert.deepEqual(exceptions, [], '浏览器出现未处理异常')
  assert.deepEqual(serverErrors, [], '页面请求出现服务器错误')
  console.log('UI checks: ' + checks.length + ' passed')
} finally {
  socket.send(JSON.stringify({ id: ++sequence, method: 'Browser.close' }))
  await new Promise(resolve => setTimeout(resolve, 150))
  socket.close()
}
