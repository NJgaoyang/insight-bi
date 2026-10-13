// Narrow browser test of dataset SQL alias mapping, no real database writes.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1500,height:1000}})
  const errors=[],writes=[]
  page.on('pageerror',e=>errors.push(e.message))
  let saved={id:'ds1',name:'销售数据集',connection_id:'conn',sql_body:'SELECT city,amount FROM sales',
    folder_id:null,fields:[{name:'city'},{name:'amount'}]}
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)})
   if(path==='/api/me')return send({user:'admin',csrf:'test-csrf'})
   if(path==='/api/connections')return send([{id:'conn',name:'测试连接',db_name:'sandbox'}])
   if(path==='/api/datasets'&&method==='GET')return send([saved])
   if(path==='/api/dashboards')return send([])
   if(path==='/api/folders'||path==='/api/data-folders')return send([])
   if(path==='/api/connections/conn/tables')return send(['sales'])
   if(path==='/api/datasets/ds1'&&method==='GET')return send(saved)
   if(path==='/api/datasets'&&method==='POST'){
    const body=req.postDataJSON();writes.push(body)
    assert.deepEqual(body.fieldRenames,{city:'region'})
    assert.ok(body.sql.includes('AS region'))
    return send({id:'ds1',fields:[{name:'region'},{name:'amount'}]})
   }
   return send({error:'Unexpected: '+method+' '+path},404)
  })
  await page.goto(origin+'/#/datasets',{waitUntil:'domcontentloaded'})
  await page.locator('.resource-table button').filter({hasText:'编辑'}).first().click()
  await page.locator('.el-dialog:visible').getByText('源字段别名迁移（可选）').waitFor()
  await page.locator('.el-dialog:visible').getByRole('button',{name:'添加别名映射'}).click()
  const map=page.locator('.el-dialog:visible .horizontal-form').last()
  await map.locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'city'}).first().click()
  await map.locator('.el-input input').fill('region')
  const dialog=page.locator('.el-dialog:visible')
  await dialog.locator('textarea').fill('SELECT city AS region,amount FROM sales')
  await dialog.getByRole('button',{name:'校验并保存'}).click()
  await dialog.waitFor({state:'hidden'})
  assert.equal(writes.length,1)
  assert.equal(writes[0].id,'ds1')
  assert.equal(writes[0].fieldRenames.city,'region')
  assert.deepEqual(errors,[])
  console.log('PASS P0 dataset alias editor: select old field, map new alias, persist request')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P0 dataset alias editor',e.stack||e);process.exitCode=1})
