// 隔离浏览器与模拟接口：不访问生产数据库或真实钉钉。
import fs from 'node:fs/promises'
import path from 'node:path'
import http from 'node:http'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const bundle=path.resolve(process.env.HELPDESK_UI_DIST||fileURLToPath(new URL('../web/dist-staging/',import.meta.url)))
const profile=fileURLToPath(new URL('../server/target/asset-fields-ui-'+Date.now()+'/',import.meta.url))
await fs.mkdir(profile,{recursive:true})
const assets=[],writes=[],state={role:'IT',failNext:false}
const statuses=['待入库','闲置','在用','维修中','待报废','已报废']
const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://localhost')
  if(url.pathname.startsWith('/api/')) {
    const chunks=[];for await(const chunk of req)chunks.push(chunk)
    const input=chunks.length?JSON.parse(Buffer.concat(chunks).toString()):{}
    let data=[],status=200,message=''
    if(url.pathname==='/api/auth/config')data={demoEnabled:false,corpId:'fixture-only'}
    else if(url.pathname==='/api/auth/me')data={id:1,name:'资产测试人员',roles:[state.role],role:state.role,permissions:['asset.view',...(state.role==='IT'?['asset.create']:[])]}
    else if(url.pathname.includes('unread'))data={count:0}
    else if(url.pathname==='/api/asset-categories')data=[{id:1,name:'终端',sort:0,assetCount:assets.length}]
    else if(url.pathname==='/api/assets'&&req.method==='POST') {
      writes.push(input)
      if(state.failNext){state.failNext=false;status=409;message='资产编号已存在，请调整后重试'}
      else {data={...input};assets.push(data)}
    } else if(url.pathname==='/api/assets')data=url.searchParams.has('page')?{list:assets,total:assets.length,page:1,size:15}:assets
    else if(url.pathname.startsWith('/api/assets/')) {
      const no=decodeURIComponent(url.pathname.slice('/api/assets/'.length)),asset=assets.find(a=>a.no===no)
      if(req.method==='PUT'){Object.assign(asset,input);writes.push(input)}
      data={asset,editToken:'a'.repeat(64),editableStatuses:statuses,actions:[],history:[]}
    }
    res.writeHead(status,{'Content-Type':'application/json;charset=utf-8'});res.end(JSON.stringify({code:status===200?0:status,message,data}));return
  }
  try {
    const file=path.resolve(bundle,'.'+(url.pathname==='/'?'/index.html':decodeURIComponent(url.pathname)))
    if(!file.startsWith(bundle+path.sep))throw Error('outside bundle')
    let data=await fs.readFile(file)
    if(file.endsWith('index.html'))data=Buffer.from(data.toString().replace(/<script src="https:[^"]*dingtalk[^<]*<\/script>/g,''))
    res.writeHead(200,{'Content-Type':{'.html':'text/html;charset=utf-8','.js':'text/javascript','.css':'text/css'}[path.extname(file)]||'application/octet-stream'});res.end(data)
  }catch{res.writeHead(404);res.end()}
})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
const frontend='http://127.0.0.1:'+server.address().port
const browser=spawn('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:'ignore'})
let socket,next=0
const pending=new Map(),exceptions=[],checks=[]
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
function call(method,params={}){
  const id=++next
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout: '+method))},10000)
    pending.set(id,{resolve:value=>{clearTimeout(timer);resolve(value)},reject:error=>{clearTimeout(timer);reject(error)}})
    socket.send(JSON.stringify({id,method,params}))
  })
}
async function evaluate(expression){const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result?.value}
async function until(expression){const deadline=Date.now()+10000;while(Date.now()<deadline){if(await evaluate(expression))return;await pause(100)}throw Error('UI timeout: '+expression+'\n'+await evaluate('document.querySelector("main")?.innerText'))}
async function button(text,scope='main'){await evaluate(`Array.from(document.querySelectorAll(${JSON.stringify(scope+' button')})).find(b=>b.textContent.trim()===${JSON.stringify(text)})?.click()`)}
async function fill(label,value){await evaluate(`(()=>{const e=document.querySelector('[aria-label="'+${JSON.stringify(label)}+'"]');e.value=${JSON.stringify(value)};e.dispatchEvent(new Event(e.tagName==='SELECT'?'change':'input',{bubbles:true}))})()`)}
async function navigate(route){await call('Page.navigate',{url:frontend+'/?fixture='+Date.now()+'#'+route})}
async function screenshot(name){await fs.mkdir(new URL('../shots/regression/',import.meta.url),{recursive:true});await fs.writeFile(new URL('../shots/regression/'+name,import.meta.url),Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'))}
const labels=['设备名称','设备型号','新增资产分类','SN序列号','存放位置','购置日期','维保到期','维保方式','设备状态','设备备注']
const pass=text=>{checks.push(text);console.log('PASS '+text)}
try {
  let port;for(let i=0;i<60&&!port;i++){try{port=Number((await fs.readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0])}catch{await pause(100)}}assert.ok(port)
  const pages=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json()
  socket=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise(resolve=>socket.addEventListener('open',resolve,{once:true}))
  socket.addEventListener('message',event=>{const m=JSON.parse(event.data),callback=pending.get(m.id);if(callback){pending.delete(m.id);m.error?callback.reject(Error(m.error.message)):callback.resolve(m.result)}if(m.method==='Runtime.exceptionThrown')exceptions.push(m.params.exceptionDetails.text)})
  await call('Runtime.enable');await call('Page.enable');await call('Emulation.setFocusEmulationEnabled',{enabled:true})
  await call('Page.addScriptToEvaluateOnNewDocument',{source:"if(location.protocol==='http:')localStorage.setItem('it_helpdesk_token','fixture-only-token')"})
  await call('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false})
  await navigate('/admin/assets');await until('!!document.querySelector(".asset-toolbar")')
  await button('＋ 新增资产');await until('!!document.querySelector(".asset-add-dialog")')
  const actual=await evaluate('Array.from(document.querySelectorAll(".asset-add-dialog fieldset [aria-label]")).map(e=>e.getAttribute("aria-label"))')
  assert.deepEqual(actual,labels)
  assert.equal(await evaluate('document.querySelector("[aria-label=设备型号]").required'),false)
  assert.equal(await evaluate('document.querySelector("[aria-label=设备型号]").maxLength'),128)
  assert.equal(await evaluate('document.querySelector("[aria-label=设备状态]").value'),'待入库')
  assert.equal(await evaluate('document.querySelector("[aria-label=设备状态]").textContent.includes("借出中")'),false)
  pass('新增完整提供名称、型号、分类、SN、位置、购置日期、维保、状态与备注')
  const values={'资产编号':'ASSET-UI-1','设备名称':'办公笔记本','设备型号':'ThinkPad T14s Gen 5','SN序列号':'SN-CREATE-1','存放位置':'A座3楼','购置日期':'2026-01-01','维保到期':'2029-01-01','维保方式':'厂商保修','设备状态':'闲置','设备备注':'新增时完整填写'}
  for(const [label,value]of Object.entries(values))await fill(label,value)
  await screenshot('asset-create-full-desktop.png')
  state.failNext=true;await button('保存','.asset-add-dialog');await until('document.querySelector(".asset-add-dialog [role=alert]")?.textContent.includes("已存在")')
  assert.equal(await evaluate('document.querySelector("[aria-label=设备型号]").value'),'ThinkPad T14s Gen 5')
  assert.equal(assets.length,0)
  await button('保存','.asset-add-dialog');await until('!document.querySelector(".asset-add-dialog")&&!!document.querySelector(".asset-row")')
  assert.equal(writes.length,2);assert.equal(assets[0].buyDate,'2026-01-01');assert.equal(assets[0].warrantyEnd,'2029-01-01');assert.equal(assets[0].remark,'新增时完整填写')
  pass('新增完整提交所有字段，失败保留输入并支持重试')
  await call('Page.reload');await until('document.querySelector(".asset-row")?.textContent.includes("T14s Gen 5")')
  await button('查看详情','.asset-row');await until('document.querySelector(".info-grid")?.textContent.includes("ThinkPad T14s Gen 5")')
  await button('编辑设备');await until('!!document.querySelector(".edit-dialog")')
  const editLabels=await evaluate('Array.from(document.querySelectorAll(".edit-dialog fieldset [aria-label]")).map(e=>e.getAttribute("aria-label").replace("编辑资产分类","新增资产分类"))')
  assert.deepEqual(editLabels,actual)
  for(const [label,value]of Object.entries(values).filter(([label])=>label!=='资产编号'))assert.equal(await evaluate(`document.querySelector('[aria-label="'+${JSON.stringify(label)}+'"]').value`),value,label)
  await fill('设备型号','ThinkPad T14s Gen 6');await button('保存设备','.edit-dialog')
  await until('!document.querySelector(".edit-dialog")&&document.querySelector(".info-grid")?.textContent.includes("Gen 6")')
  assert.equal(assets[0].model,'ThinkPad T14s Gen 6');assert.equal(assets[0].warranty,'厂商保修')
  pass('刷新台账、详情和编辑均回显完整资料，新增与编辑字段一致，型号可修改')
  await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true})
  await navigate('/admin/assets');await until('!!document.querySelector(".asset-toolbar")')
  await button('＋ 新增资产');await until('!!document.querySelector(".asset-add-dialog")')
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth&&document.querySelector(".asset-add-dialog").getBoundingClientRect().right<=innerWidth&&document.querySelector(".asset-add-dialog .dlg-f").getBoundingClientRect().bottom<=innerHeight'))
  await screenshot('asset-create-full-mobile.png')
  await fill('资产编号','ASSET-UI-2');await fill('设备名称','只填必填项')
  await button('保存','.asset-add-dialog');await until('!document.querySelector(".asset-add-dialog")&&document.querySelectorAll(".asset-card").length===2')
  assert.equal(assets[1].model,'');assert.equal(assets[1].buyDate,null);assert.equal(assets[1].warrantyEnd,null);assert.equal(assets[1].status,'待入库')
  pass('手机新增可滚动且按钮可见，型号和其余资料可留空，日期以空值提交')
  await button('＋ 新增资产');await until('!!document.querySelector(".asset-add-dialog")')
  await fill('资产编号','ASSET-UI-3');await fill('设备名称','填写库位自动入库')
  await fill('存放位置','   ');assert.equal(await evaluate('document.querySelector("[aria-label=设备状态]").value'),'待入库')
  await fill('存放位置','11');await until('document.querySelector("[aria-label=设备状态]").value==="闲置"')
  await fill('设备状态','维修中');await fill('存放位置','12')
  assert.equal(await evaluate('document.querySelector("[aria-label=设备状态]").value'),'维修中')
  await fill('设备状态','待入库');await until('document.querySelector("[aria-label=设备状态]").value==="闲置"')
  await button('保存','.asset-add-dialog');await until('!document.querySelector(".asset-add-dialog")&&document.querySelectorAll(".asset-card").length===3')
  assert.equal(assets[2].status,'闲置');assert.equal(assets[2].location,'12')
  await navigate('/admin/assets/ASSET-UI-2');await until('!!document.querySelector(".info-grid")')
  await button('编辑设备');await until('!!document.querySelector(".edit-dialog")')
  assert.equal(await evaluate('document.querySelector("[aria-label=设备状态]").value'),'待入库')
  await fill('存放位置','11');await until('document.querySelector("[aria-label=设备状态]").value==="闲置"')
  await button('保存设备','.edit-dialog');await until('!document.querySelector(".edit-dialog")')
  assert.equal(assets[1].status,'闲置');assert.equal(assets[1].location,'11')
  pass('新增和编辑填写有效位置时待入库自动改为闲置，空格不触发，也不覆盖维修状态')
  state.role='VIEWER';await navigate('/admin/assets');await until('!!document.querySelector(".asset-toolbar")')
  assert.equal(await evaluate('document.querySelector(".asset-toolbar").textContent.includes("新增资产")'),false)
  await navigate('/admin/assets/ASSET-UI-1');await until('!!document.querySelector(".info-grid")')
  assert.equal(await evaluate('!!document.querySelector("[data-action=edit-asset]")'),false)
  assert.ok(await evaluate('document.querySelector(".info-grid").textContent.includes("Gen 6")'))
  assert.deepEqual(exceptions,[])
  pass('只读用户可以查看型号，仍不能新增或编辑资产')
  console.log('Asset fields UI checks: '+checks.length+' passed')
} finally {
  if(socket?.readyState===WebSocket.OPEN){socket.send(JSON.stringify({id:++next,method:'Browser.close'}));await pause(150);socket.close()}
  if(browser.exitCode===null)browser.kill()
  server.closeAllConnections();await new Promise(resolve=>server.close(resolve))
}
