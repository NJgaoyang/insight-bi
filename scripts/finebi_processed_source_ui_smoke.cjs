// P1-02 scoped Chromium smoke: raw vs saved processing V2 fields, revision and rebind.
// Mock REST, no external database access or production deployment.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
const origFields=[
 {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',numeric:false,calculated:false,phase:'ROW'},
 {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',numeric:true,calculated:false,phase:'ROW'}]
const processedFields=[
 {...origFields[1]},
 {id:'tier',name:'收入等级',sourceColumn:'step_tier',logicalType:'STRING',numeric:false,calculated:false,phase:'ROW'}]
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1450,height:920}})
  const errors=[],saves=[],queries=[]
  let revision=1,pipelineRevision=3
  let content={mode:'grid',theme:'light',widgets:[{
   id:'w1',type:'bar',title:'销售分析',datasetId:'ds1',
   style:{},columns:[],dimension:'',measure:'',agg:'SUM',
   analysisV2:{dimensions:[{fieldId:'city'}],
    measures:[{id:'m1',fieldId:'revenue',aggregate:'SUM',alias:'收入'}],
    filters:{pre:[],post:[]},sort:[],limit:50}
  }]}
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname
   const method=req.method()
   const send=(result,status=200)=>route.fulfill({
    status,contentType:'application/json',body:JSON.stringify(result)})
   if(path==='/api/me')return send({user:'admin',csrf:'mock-csrf'})
   if(path==='/api/datasets')return send([{id:'ds1',name:'订单测试',connection_id:'conn',fields:[]}])
   if(path==='/api/datasets/ds1/fields'&&method==='GET')return send(origFields)
   if(path==='/api/datasets/ds1/pipeline/fields'&&method==='GET')
    return send({fields:processedFields,revision:pipelineRevision})
   if(path==='/api/dashboards/report'&&method==='GET')
    return send({id:'report',name:'加工结果复用',kind:'analysis',status:'draft',mode:'grid',revision,content})
   if(path==='/api/dashboards/report'&&method==='PUT'){
    const body=req.postDataJSON()
    assert.equal(body.revision,revision)
    saves.push(body)
    content=structuredClone(body.content);revision++
    return send({revision})
   }
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    queries.push(req.postDataJSON())
    return send({rows:[],columns:[],truncated:false,executionMs:1,warnings:[]})
   }
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  const sw=page.locator('.analysis-processing-source .el-switch')
  await sw.waitFor()
  assert.equal(await page.locator('.analysis-field').filter({hasText:'城市'}).count(),1)
  await sw.click()
  await page.locator('.analysis-field').filter({hasText:'收入等级'}).waitFor()
  assert.ok((await page.locator('.analysis-processing-source').innerText()).includes('版本 3'))
  await page.locator('.analysis-field').filter({hasText:'收入等级'}).click()
  await page.locator('.analysis-field').filter({hasText:'收入'}).first().click()
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  const binding=saves.at(-1).content.widgets[0].analysisV2
  assert.equal(binding.usePipeline,true)
  assert.equal(binding.pipelineRevision,3)
  assert.equal(binding.dimensions[0].fieldId,'tier')
  assert.equal(binding.measures[0].fieldId,'revenue')
  await page.reload({waitUntil:'domcontentloaded'})
  await sw.waitFor()
  assert.ok((await page.locator('.analysis-processing-source').innerText()).includes('版本 3'))

  // A newly saved processing version should require explicit rebind.
  pipelineRevision=4
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-pipeline-warning').waitFor()
  assert.ok((await page.locator('.analysis-pipeline-warning').innerText()).includes('新版本'))
  await page.locator('button[title="更新加工结果绑定版本"]').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-processing-source')?.textContent?.includes('版本 4'))
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.at(-1).content.widgets[0].analysisV2.pipelineRevision,4)
  assert.equal(saves.at(-1).content.widgets[0].analysisV2.measures.length,0)

  // Switching back keeps existing nonprocessing analysis request shape.
  await sw.click()
  await page.locator('.analysis-field').filter({hasText:'城市'}).waitFor()
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  const raw=saves.at(-1).content.widgets[0].analysisV2
  assert.equal(raw.usePipeline,false)
  assert.equal(raw.pipelineRevision,undefined)
  assert.deepEqual(errors,[])
  assert.ok(queries.length>=1)
  console.log('PASS P1-02 processed source: switch, use derived fields, save, reload, detect revision change, rebind, return raw')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1-02 processed source',e.stack||e);process.exitCode=1})
