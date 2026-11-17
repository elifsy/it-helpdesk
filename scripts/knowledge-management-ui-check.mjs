// 独立模拟接口和浏览器验证；不会创建生产工单或调用真实钉钉。
import fs from 'node:fs/promises'
import path from 'node:path'
import http from 'node:http'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const bundle=path.resolve(process.env.HELPDESK_UI_DIST || fileURLToPath(new URL('../web/dist-staging/',import.meta.url)))
const profile=fileURLToPath(new URL('../server/target/knowledge-ui-'+Date.now()+'/',import.meta.url))
await fs.mkdir(profile,{recursive:true})
const article={id:1,title:'打印机脱机处理',category:'打印',summary:'检查网络并重启打印服务',content:'问题现象\n打印机显示脱机\n\n处理步骤\n检查网络，重启打印服务并验证。',sourceTicketNo:'IT-CLOSED-1',status:'DRAFT',revision:0,views:0,helpful:0,notHelpful:0}
const state={role:'IT',conflict:false,requests:[],captures:0}
const permissions=()=>['kb.view','kb.vote',...(state.role==='IT'||state.role==='ADMIN'?['kb.manage','ticket.view.all']:[]),...(state.role==='ADMIN'?['kb.publish']:[])]
const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost')
  if(url.pathname.startsWith('/api/')) {
    let data=[],status=200,message=''; const chunks=[]
    for await(const chunk of req)chunks.push(chunk)
    const input=chunks.length?JSON.parse(Buffer.concat(chunks).toString()):{}
    state.requests.push({path:url.pathname,method:req.method,input})
    if(url.pathname==='/api/auth/config')data={demoEnabled:false,corpId:'fixture-only'}
    else if(url.pathname==='/api/auth/me')data={id:1,name:'知识测试人员',roles:[state.role],role:state.role,permissions:permissions()}
    else if(url.pathname.includes('unread'))data={count:0}
    else if(url.pathname==='/api/kb/manage') {
      const wanted=url.searchParams.get('status'),keyword=url.searchParams.get('keyword')||''
      const list=(!wanted||wanted===article.status)&&(article.title+article.summary+article.sourceTicketNo).includes(keyword)?[article]:[]
      data={list,total:list.length,page:1,size:20}
    } else if(url.pathname==='/api/kb/manage/from-ticket') {state.captures++;data=article}
    else if(url.pathname==='/api/kb/manage/1'&&req.method==='PUT') {
      if(state.conflict){state.conflict=false;status=409;message='知识已被其他人修改，请重新打开后再操作'}
      else {Object.assign(article,input,{revision:article.revision+1,status:'DRAFT'});data=article}
    } else if(url.pathname==='/api/kb/manage/1')data=article
    else if(/^\/api\/kb\/manage\/1\/(publish|withdraw|archive)$/.test(url.pathname)) {
      const action=url.pathname.split('/').at(-1)
      if((action==='publish'||action==='withdraw')&&!permissions().includes('kb.publish')){status=403;message='无发布权限'}
      else {article.status={publish:'PUBLISHED',withdraw:'DRAFT',archive:'ARCHIVED'}[action];article.revision++;data=article}
    } else if(url.pathname==='/api/kb')data=article.status==='PUBLISHED'?[{...article,sourceTicketNo:undefined}]:[]
    else if(url.pathname==='/api/kb/1')data={...article,sourceTicketNo:undefined}
    res.writeHead(status,{'Content-Type':'application/json;charset=utf-8'});res.end(JSON.stringify({code:status===200?0:status,message,data}));return
  }
  try {
    const file=path.resolve(bundle,'.'+(url.pathname==='/'?'/index.html':decodeURIComponent(url.pathname)))
    if(!file.startsWith(bundle+path.sep))throw Error('outside bundle')
    let data=await fs.readFile(file)
    if(file.endsWith('index.html'))data=Buffer.from(data.toString().replace(/<script src="https:[^"]*dingtalk[^<]*<\/script>/g,''))
    res.writeHead(200,{'Content-Type':{'.html':'text/html;charset=utf-8','.js':'text/javascript','.css':'text/css'}[path.extname(file)]||'application/octet-stream'});res.end(data)
  } catch {res.writeHead(404);res.end()}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const frontend='http://127.0.0.1:'+server.address().port
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:'ignore'})
let socket,next=0
const pending=new Map(),exceptions=[],checks=[]
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
function call(method,params={}) {
  const id=++next
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout: '+method))},10000)
    pending.set(id,{resolve:value=>{clearTimeout(timer);resolve(value)},reject:error=>{clearTimeout(timer);reject(error)}})
    socket.send(JSON.stringify({id,method,params}))
  })
}
async function evaluate(expression) {
  const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true})
  if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text)
  return r.result?.value
}
async function until(expression) {
  const deadline=Date.now()+10000
  while(Date.now()<deadline){if(await evaluate(expression))return;await pause(100)}
  throw Error('UI timeout: '+expression+'\n'+await evaluate('document.querySelector("main")?.innerText'))
}
async function button(text,scope='main') {
  await evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(scope+' button')})).find(b=>b.textContent.trim()===${JSON.stringify(text)})?.click()`)
}
async function input(id,value) {
  await evaluate(`(()=>{const e=document.getElementById(${JSON.stringify(id)});e.value=${JSON.stringify(value)};e.dispatchEvent(new Event('input',{bubbles:true}))})()`)
}
async function navigate(route) {await call('Page.navigate',{url:frontend+'/?fixture='+Date.now()+'#'+route})}
const pass=label=>{checks.push(label);console.log('PASS '+label)}
try {
  let port
  for(let i=0;i<60&&!port;i++){try{port=Number((await fs.readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0])}catch{await pause(100)}}
  assert.ok(port)
  const pages=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json()
  socket=new WebSocket(pages.find(page=>page.type==='page').webSocketDebuggerUrl)
  await new Promise(resolve=>socket.addEventListener('open',resolve,{once:true}))
  socket.addEventListener('message',event=>{
    const m=JSON.parse(event.data),callback=pending.get(m.id)
    if(callback){pending.delete(m.id);m.error?callback.reject(Error(m.error.message)):callback.resolve(m.result)}
    if(m.method==='Runtime.exceptionThrown')exceptions.push(m.params.exceptionDetails.text)
  })
  await call('Runtime.enable');await call('Page.enable')
  await call('Emulation.setFocusEmulationEnabled',{enabled:true});await call('Page.bringToFront')
  await call('Page.addScriptToEvaluateOnNewDocument',{source:"if(location.protocol==='http:')localStorage.setItem('it_helpdesk_token','fixture-only-token')"})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await navigate('/admin/kb');await until('!!document.querySelector("[data-knowledge-row]")')
  assert.ok(await evaluate('document.querySelector(".side")?.textContent.includes("知识草稿与发布")'))
  await evaluate('(()=>{const e=document.querySelector("[aria-label=搜索知识库]");e.focus();e.value="打印";e.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await until('!!document.querySelector(".suggestion-option")')
  await evaluate('document.querySelector(".suggestion-option").click()')
  await until('!document.querySelector(".knowledge-suggestions") && !!document.querySelector("[data-knowledge-row]")')
  pass('management autocomplete searches drafts within the selected status')
  await button('整理 / 查看');await until('!!document.getElementById("knowledge-content")')
  assert.equal(await evaluate('document.querySelector(".knowledge-editor").textContent.includes("发布知识")'),false)
  await input('knowledge-content','检查打印机网络和 IP。\n清理队列，重启打印服务后验证。')
  await button('保存草稿','.knowledge-editor');await until('!document.querySelector(".knowledge-editor").getAttribute("aria-busy").includes("true") && !document.querySelector(".knowledge-editor").textContent.includes("有未保存修改")')
  assert.equal(article.revision,1);assert.ok(article.content.includes('清理队列'))
  pass('IT can open and edit generated drafts, with no publish action')
  state.conflict=true;await input('knowledge-summary','这是尚未保存的新摘要')
  await button('保存草稿','.knowledge-editor');await until('document.querySelector(".knowledge-editor .error")?.textContent.includes("其他人修改")')
  assert.equal(await evaluate('document.getElementById("knowledge-summary").value'),'这是尚未保存的新摘要')
  assert.equal(article.revision,1)
  await button('重新加载','.knowledge-editor');await until('document.getElementById("knowledge-summary").value==="检查网络并重启打印服务"')
  pass('a revision conflict keeps typed content and offers explicit reload')
  await evaluate('document.querySelector("[aria-label=关闭知识编辑]").click()')
  await input('knowledge-ticket','IT-CLOSED-1');await button('生成 / 打开草稿');await until('!!document.querySelector(".knowledge-editor")')
  assert.equal(state.captures,1);assert.equal(article.id,1)
  pass('historical capture opens the existing linked article')
  state.role='ADMIN';await call('Page.reload');await until('!!document.querySelector("[data-knowledge-row]")')
  await button('整理 / 查看');await until('Array.from(document.querySelectorAll(".knowledge-editor button")).some(b=>b.textContent.trim()==="发布知识")')
  await input('knowledge-title','打印机脱机通用排查')
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".knowledge-editor button")).find(b=>b.textContent.trim()==="发布知识").disabled'),true)
  await button('保存草稿','.knowledge-editor');await until('Array.from(document.querySelectorAll(".knowledge-editor button")).some(b=>b.textContent.trim()==="发布知识"&&!b.disabled)')
  await button('发布知识','.knowledge-editor');await until('document.getElementById("knowledge-editor-title")?.textContent.includes("已发布")')
  assert.equal(article.status,'PUBLISHED')
  await button('撤回发布','.knowledge-editor');await until('document.getElementById("knowledge-editor-title")?.textContent.includes("草稿")')
  assert.equal(article.status,'DRAFT')
  await button('发布知识','.knowledge-editor');await until('document.getElementById("knowledge-editor-title")?.textContent.includes("已发布")')
  pass('a publisher must save edits first and can publish or withdraw the reviewed article')
  state.role='USER';await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
  await navigate('/kb');await until('document.querySelector("main .wo")?.textContent.includes("通用排查")')
  assert.equal(await evaluate('Array.from(document.querySelectorAll("main button")).some(b=>b.textContent.includes("知识管理"))'),false)
  await evaluate('document.querySelector("main .wo").click()');await until('!!document.querySelector(".dlg")')
  assert.ok(await evaluate('document.querySelector(".dlg").textContent.includes("清理队列")'))
  assert.equal(await evaluate('document.querySelector(".dlg").textContent.includes("IT-CLOSED-1")'),false)
  pass('published steps are available to employees without management or source ticket details')
  state.role='ADMIN';await navigate('/admin/kb');await until('!!document.querySelector(".filters")')
  await button('已发布','.filters');await until('!!document.querySelector("[data-knowledge-row]")')
  await button('整理 / 查看');await until('!!document.querySelector(".knowledge-editor")')
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth && document.querySelector(".knowledge-editor").getBoundingClientRect().right<=innerWidth'))
  await fs.mkdir(new URL('../shots/regression/',import.meta.url),{recursive:true})
  await fs.writeFile(new URL('../shots/regression/knowledge-management-mobile.png',import.meta.url),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'))
  await button('归档','.knowledge-editor');await until('document.getElementById("knowledge-editor-title")?.textContent.includes("已归档")')
  assert.equal(article.status,'ARCHIVED');assert.deepEqual(exceptions,[])
  pass('management works on phones and published articles can be archived')
  console.log('Knowledge management UI checks: '+checks.length+' passed')
} finally {
  if(socket?.readyState===WebSocket.OPEN){socket.send(JSON.stringify({id:++next,method:'Browser.close'}));await pause(150);socket.close()}
  if(browser.exitCode===null)browser.kill()
  server.closeAllConnections();await new Promise(resolve=>server.close(resolve))
}
