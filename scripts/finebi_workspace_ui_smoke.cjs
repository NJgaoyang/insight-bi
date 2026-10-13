const assert=require('node:assert/strict')
const {chromium}=require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')
;(async()=>{
 const browser=await chromium.launch({executablePath:'/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell',args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}})
  const errors=[],folders=[],dashboards=[{id:'existing-root',name:'经营分析',mode:'grid',status:'draft',revision:1,folder_id:null}]
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method()
   const respond=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)})
   const data=()=>req.postDataJSON()
   if(path==='/api/me')return respond({user:'admin',csrf:'fake-token'})
   if(path==='/api/folders'&&method==='GET')return respond(folders)
   if(path==='/api/data-folders'&&method==='GET')return respond([])
   if(path==='/api/folders'&&method==='POST'){
    assert.equal(req.headers()['x-csrf-token'],'fake-token')
    const x=data()
    const id='folder-'+(folders.length+1)
    folders.push({id,name:x.name,parent_id:x.parentId??null})
    return respond({id})
   }
   if(path.startsWith('/api/folders/')&&method==='PUT'){
    const folder=folders.find(f=>path.endsWith(f.id))
    folder.name=data().name
    return respond({id:folder.id,name:folder.name})
   }
   if(path.startsWith('/api/folders/')&&method==='DELETE'){
    const i=folders.findIndex(f=>path.endsWith(f.id))
    if(dashboards.some(d=>d.folder_id===folders[i].id))return respond({error:'文件夹内仍有分析主题'},409)
    folders.splice(i,1);return respond({ok:true})
   }
   if(path==='/api/dashboards'&&method==='GET')return respond(dashboards)
   if(path==='/api/dashboards'&&method==='POST'){
     const x=data();dashboards.push({id:'created-1',name:x.name,mode:x.mode,status:'draft',revision:1,folder_id:x.parentId??null})
     return respond({id:'created-1',revision:1})
   }
   if(path.startsWith('/api/dashboards/')&&method==='PUT'&&path.endsWith('/folder')){
    const target=dashboards.find(d=>path.includes(d.id))
    target.folder_id=data().parentId??null
    return respond({id:target.id,moved:true})
   }
   if(path==='/api/datasets'||path==='/api/connections')return respond([])
   if(path==='/api/dashboards/created-1')return respond({id:'created-1',name:'新主题',mode:'grid',revision:1,status:'draft',content:{mode:'grid',widgets:[],theme:'light'}})
   if(path==='/api/dashboards/existing-root')return respond({id:'existing-root',name:'经营分析',mode:'grid',revision:1,status:'draft',content:{mode:'grid',widgets:[],theme:'light'}})
   return respond({error:'Unexpected mocked API '+method+' '+path},404)
  })
  await page.goto('http://127.0.0.1:18117/',{waitUntil:'domcontentloaded'})
  await page.getByText('我的分析',{exact:true}).first().waitFor()
  assert.ok(await page.locator('.fine-folder-pane').count())
  await page.locator('.fine-folder-actions button').filter({hasText:'新建文件夹'}).click()
  await page.locator('.el-message-box input').fill('运营分析')
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await page.getByText('运营分析',{exact:true}).first().waitFor()
  assert.equal(folders.length,1)
  assert.equal(await page.locator('.fine-subject').count(),0)
  await page.locator('.fine-folder-root').click()
  assert.equal(await page.locator('.fine-subject').count(),1)
  await page.locator('.fine-subject-actions button').filter({hasText:'移动'}).click()
  await page.locator('.el-dialog:visible .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'运营分析'}).first().click()
  await page.locator('.el-dialog:visible').getByText('确认移动').click()
  await page.waitForFunction(()=>document.querySelectorAll('.fine-subject').length===0)
  assert.equal(dashboards[0].folder_id,folders[0].id)
  await page.locator('.fine-folder-open').filter({hasText:'运营分析'}).click()
  assert.equal(await page.locator('.fine-subject').count(),1)
  await page.locator('.nav-panel nav button').filter({hasText:'公共数据'}).click()
  assert.equal(await page.locator('.fine-folder-pane').count(),1)
  await page.locator('.nav-panel nav button').filter({hasText:'系统管理'}).click()
  assert.ok((await page.locator('.fine-page-heading h1').innerText()).includes('系统管理'))
  assert.deepEqual(errors,[])
  await page.screenshot({path:'/tmp/insight-finebi-folder-ui.png',fullPage:true})
  console.log('PASS FineBI workspace: actual folder API, dashboard move, folder tree, Public Data & System routing')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAILED',e.stack);process.exit(1)})
