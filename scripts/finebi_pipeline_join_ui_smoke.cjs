// P1-02 focused join editor/browser contract: no real warehouse writes.
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
  const errors=[],saves=[],compiles=[]
  let revision=0,steps=[]
  page.on('pageerror',err=>errors.push(err.message))
  const field=(id,name,type,sourceColumn=id)=>({id,name,sourceColumn,logicalType:type,
   numeric:type==='DECIMAL',calculated:false,phase:'ROW'})
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(value,status=200)=>route.fulfill({
    status,contentType:'application/json',body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'csrf'})
   if(path==='/api/datasets'&&method==='GET')return send([
    {id:'ds1',name:'当前销售',connection_id:'conn'},
    {id:'archive',name:'历史销售',connection_id:'conn'},
    {id:'other',name:'不同连接',connection_id:'other'}])
   if(path==='/api/datasets/ds1/fields'&&method==='GET')
    return send([field('city','城市','STRING'),field('period','月份','STRING'),
      field('revenue','收入','DECIMAL')])
   if(path==='/api/datasets/archive/fields'&&method==='GET')
    return send([field('rcity','城市','STRING','city'),
      field('rperiod','月份','STRING','period'),
      field('refund','退款额','DECIMAL','refund')])
   if(path==='/api/datasets/ds1/pipeline'&&method==='GET')
    return send({datasetId:'ds1',steps,revision,stale:false})
   if(path==='/api/datasets/ds1/pipeline'&&method==='PUT'){
    const body=req.postDataJSON()
    assert.equal(body.revision,revision)
    saves.push(body);steps=structuredClone(body.steps);revision++
    return send({datasetId:'ds1',revision,stale:false})
   }
   if(path==='/api/datasets/ds1/pipeline/compile'&&method==='POST'){
    compiles.push(req.postDataJSON())
    return send({sqlPreview:'SELECT * FROM sales LEFT JOIN archive ON sales.city=archive.city LIMIT ?',
      columns:[],bindCount:1,stepCount:steps.length})
   }
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/pipeline/ds1',{waitUntil:'domcontentloaded'})
  await page.locator('.pipeline-toolbar button').filter({hasText:'关联'}).click()
  await page.locator('.pipeline-step').first().waitFor()
  const step=page.locator('.pipeline-step').first()
  assert.ok((await step.innerText()).includes('数据关联'))
  assert.equal(await step.locator('button[title="步骤后移"]').isDisabled(),true)
  assert.ok((await step.innerText()).includes('历史销售'))
  assert.equal(await step.locator('.el-checkbox').count(),1)
  await step.locator('button').filter({hasText:'添加关联条件'}).click()
  assert.equal(await step.locator('button[title="删除关联条件"]').count(),2)
  await step.locator('button').filter({hasText:'添加输出字段'}).click()
  assert.equal(await step.locator('button[title="删除右表输出字段"]').count(),2)
  await step.locator('.el-checkbox').click()
  await page.locator('.pipeline-actions button').filter({hasText:'SQL 预览'}).click()
  await page.locator('.pipeline-sql').waitFor()
  assert.equal(compiles.length,1)
  assert.equal(compiles[0].steps[0].type,'join')
  assert.equal(compiles[0].steps[0].acknowledgeFanout,true)
  assert.equal(compiles[0].steps[0].sourceDatasetId,'archive')
  await page.locator('.pipeline-save').click()
  await page.waitForFunction(()=>document.querySelector('.pipeline-save')?.hasAttribute('disabled'))
  assert.equal(saves.length,1)
  assert.equal(saves[0].steps[0].conditions.length,2)
  assert.equal(saves[0].steps[0].fields.length,2)
  assert.equal(saves[0].steps[0].joinType,'LEFT')
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.pipeline-step').first().waitFor()
  assert.equal(await page.locator('.pipeline-step').count(),1)
  assert.equal(await page.locator('.pipeline-step').first()
   .locator('button[title="删除关联条件"]').count(),2)
  assert.equal(await page.locator('.pipeline-toolbar button').filter({hasText:'上下合并'}).isDisabled(),true)
  assert.deepEqual(errors,[])
  console.log('PASS P1 JOIN: source fields, multikey, projections, fanout opt-in, save and reload')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1 JOIN',e.stack||e);process.exitCode=1})
