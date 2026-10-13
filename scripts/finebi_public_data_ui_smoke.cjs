const assert=require('node:assert/strict')
const {chromium}=require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')
;(async()=>{
 const b=await chromium.launch({executablePath:'/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell',args:['--no-sandbox']})
 try{
 const p=await b.newPage({viewport:{width:1510,height:900}}),errors=[],folders=[]
 const data=[{id:'ds-real',name:'2026_订单统计',connection_id:'conn-1',fields:[{name:'城市',type:'VARCHAR',numeric:false}],folder_id:null}]
 p.on('pageerror',e=>errors.push(e.message))
 await p.route('**/api/**',async r=>{
  const req=r.request(),method=req.method(),path=new URL(req.url()).pathname
  const ok=(result,status=200)=>r.fulfill({status,contentType:'application/json',body:JSON.stringify(result)})
  if(path==='/api/me')return ok({user:'admin',csrf:'csrf-mock'})
  if(path==='/api/folders')return ok([])
  if(path==='/api/data-folders'&&method==='GET')return ok(folders)
  if(path==='/api/data-folders'&&method==='POST'){
   assert.equal(req.headers()['x-csrf-token'],'csrf-mock')
   const input=req.postDataJSON(),id='folder-1'
   folders.push({id,name:input.name,parent_id:input.parentId??null})
   return ok({id})
  }
  if(path.startsWith('/api/datasets/')&&path.endsWith('/folder')&&method==='PUT'){
   const x=req.postDataJSON();data[0].folder_id=x.parentId??null;return ok({id:'ds-real',moved:true})
  }
  if(path==='/api/datasets')return ok(data)
  if(path==='/api/connections')return ok([{id:'conn-1',name:'生产 StarRocks',engine:'starrocks'}])
  if(path==='/api/dashboards')return ok([])
  return ok({error:'Unexpected '+method+' '+path},404)
 })
 await p.goto('http://127.0.0.1:18117/#/datasets')
 await p.getByText('2026_订单统计',{exact:true}).first().waitFor()
 await p.locator('.fine-folder-pane .fine-folder-actions button').first().click()
 await p.locator('.el-message-box input').fill('运营数据')
 await p.locator('.el-message-box__btns .el-button--primary').click()
 assert.equal(folders[0].name,'运营数据')
 await p.locator('.fine-folder-root').click()
 await p.locator('.resource-table td button').filter({hasText:'移动'}).click()
 await p.locator('.el-dialog:visible .el-select').click()
 await p.locator('.el-select-dropdown__item:visible').filter({hasText:'运营数据'}).first().click()
 await p.locator('.el-dialog:visible').getByText('确认移动').click()
 assert.equal(data[0].folder_id,'folder-1')
 await p.locator('.fine-folder-open').filter({hasText:'运营数据'}).click()
 assert.ok(await p.getByText('2026_订单统计',{exact:true}).count())
 assert.deepEqual(errors,[])
 console.log('PASS FineBI public data: create folder, move existing StarRocks dataset to folder, directory tree and list reflect DB changes')
 }finally{await b.close()}
})().catch(e=>{console.error('FAILED',e.stack);process.exit(1)})
