// P1-02 focused browser smoke: edit ordered steps, compile, save and reload.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1440,height:920}})
  const errors=[],saves=[],compiles=[],previews=[]
  let revision=0,steps=[]
  page.on('pageerror',error=>errors.push(error.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(value,status=200)=>route.fulfill({
    status,contentType:'application/json',body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'csrf'})
   if(path==='/api/datasets'&&method==='GET')return send([
    {id:'ds1',name:'销售测试数据集',connection_id:'conn',sql_body:'SELECT revenue,cost,city FROM sales',fields:[]}])
   if(path==='/api/datasets/ds1/fields'&&method==='GET')return send([
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',numeric:true,calculated:false,phase:'ROW'},
    {id:'cost',name:'成本',sourceColumn:'cost',logicalType:'DECIMAL',numeric:true,calculated:false,phase:'ROW'},
    {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',numeric:false,calculated:false,phase:'ROW'}])
   if(path==='/api/datasets/ds1/pipeline'&&method==='GET')
    return send({datasetId:'ds1',steps,revision,stale:false})
   if(path==='/api/datasets/ds1/pipeline'&&method==='PUT'){
    const value=req.postDataJSON()
    assert.equal(value.revision,revision)
    saves.push(value)
    steps=structuredClone(value.steps);revision++
    return send({datasetId:'ds1',revision,stale:false})
   }
   if(path==='/api/datasets/ds1/pipeline/compile'&&method==='POST'){
    const value=req.postDataJSON();compiles.push(value)
    return send({sqlPreview:'SELECT SUM(revenue) FROM sales LIMIT ?',columns:[],bindCount:1,stepCount:value.steps.length,warnings:[]})
   }
   if(path==='/api/datasets/ds1/pipeline/preview'&&method==='POST'){
    const value=req.postDataJSON();previews.push(value)
    return send({rows:[{city:'北京',revenue:'100.00'}],
      columns:[{id:'city',name:'city',label:'城市',type:'STRING',numeric:false},
       {id:'revenue',name:'revenue',label:'收入',type:'DECIMAL',numeric:true}],
      truncated:false,executionMs:10,stepCount:value.steps.length,warnings:[]})
   }
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/pipeline/ds1',{waitUntil:'domcontentloaded'})
  await page.locator('.pipeline-header').waitFor()
  assert.ok((await page.locator('.pipeline-header').innerText()).includes('销售测试数据集'))
  await page.locator('.pipeline-toolbar button').filter({hasText:'计算列'}).click()
  await page.locator('.pipeline-step').first().locator('.el-input input').fill('利润')
  await page.locator('.pipeline-step').first().locator('textarea').fill('[revenue]-[cost]')
  await page.locator('.pipeline-toolbar button').filter({hasText:'过滤'}).click()
  await page.locator('.pipeline-step').nth(1).locator('.el-input input').fill('100')
  await page.locator('.pipeline-toolbar button').filter({hasText:'分组汇总'}).click()
  assert.equal(await page.locator('.pipeline-step').count(),3)
  await page.locator('.pipeline-step').nth(1).locator('button[title="步骤前移"]').click()
  assert.ok((await page.locator('.pipeline-step').first().innerText()).includes('数据过滤'))
  await page.locator('.pipeline-actions button').filter({hasText:'SQL 预览'}).click()
  await page.locator('.pipeline-sql').waitFor()
  assert.equal(compiles.length,1)
  assert.deepEqual(compiles[0].steps.map(s=>s.type),['filter','formula','group'])
  await page.locator('.pipeline-actions button').filter({hasText:'数据预览'}).click()
  await page.locator('.pipeline-table-wrap').waitFor()
  assert.ok((await page.locator('.pipeline-table-wrap').innerText()).includes('北京'))
  assert.equal(previews.length,1)
  await page.locator('.pipeline-save').click()
  await page.waitForFunction(()=>document.querySelector('.pipeline-save')?.hasAttribute('disabled'))
  assert.equal(saves.length,1);assert.equal(saves[0].steps.length,3)
  assert.ok(saves[0].steps[1].expression.includes('[revenue]'))
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.pipeline-step').first().waitFor()
  assert.equal(await page.locator('.pipeline-step').count(),3)
  assert.ok((await page.locator('.pipeline-step').first().innerText()).includes('数据过滤'))
  assert.deepEqual(errors,[])
  console.log('PASS P1-02 pipeline UI: ordered formula/filter/group, SQL compile, preview, CAS save and reload')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1-02 pipeline UI',e.stack||e);process.exitCode=1})
