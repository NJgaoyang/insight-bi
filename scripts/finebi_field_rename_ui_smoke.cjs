// Focused P0 field-label rename UI smoke. Mocked APIs; no live writes.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1500,height:950}})
  const errors=[],renames=[],queries=[],saves=[]
  let label='城市',revision=1,content={mode:'grid',theme:'light',widgets:[]}
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(v,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(v)})
   if(path==='/api/me')return send({user:'admin',csrf:'csrf'})
   if(path==='/api/dashboards/report'&&method==='GET')
    return send({id:'report',name:'重命名测试',kind:'analysis',mode:'grid',status:'draft',revision,content})
   if(path==='/api/dashboards/report'&&method==='PUT'){
    let data=req.postDataJSON();saves.push(data);content=structuredClone(data.content);revision++
    return send({revision})
   }
   if(path==='/api/datasets')return send([{id:'ds1',name:'销售数据集',connection_id:'readonly',
    sql_body:'SELECT city,revenue FROM sales',fields:[
     {name:'city',type:'VARCHAR',numeric:false},{name:'revenue',type:'DECIMAL',numeric:true}]}])
   if(path==='/api/datasets/ds1/fields'&&method==='GET')return send([
    {id:'city',name:label,sourceColumn:'city',logicalType:'STRING',role:'DIMENSION',numeric:false,calculated:false,phase:'ROW'},
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',role:'BOTH',numeric:true,calculated:false,phase:'ROW'}])
   if(path==='/api/datasets/ds1/fields/city'&&method==='PUT'){
    const body=req.postDataJSON();renames.push(body)
    label=body.displayName
    return send({id:'city',name:label,sourceColumn:'city',logicalType:'STRING',role:'DIMENSION',numeric:false,calculated:false,phase:'ROW'})
   }
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    queries.push(req.postDataJSON())
    return send({rows:[{d1:'北京',m1:100}],columns:[
     {id:'city',name:'d1',label,type:'STRING',kind:'dimension'},
     {id:'m1',name:'m1',label:'收入',type:'DECIMAL',kind:'measure'}],
     executionMs:2,truncated:false,warnings:[]})
   }
   return send({error:'Unexpected route '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-add-tab').click()
  await page.locator('.analysis-source-selector .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'销售数据集'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'城市'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'收入'}).last().click()
  await page.waitForFunction(()=>document.querySelector('.v2-query-meta')?.textContent?.includes('1 行'))
  assert.equal(queries.at(-1).dimensions[0].fieldId,'city')
  const baseline=queries.length
  await page.locator('.analysis-field').filter({hasText:'城市'}).first()
    .locator('button[title="修改展示名称"]').click()
  await page.locator('.el-message-box__input input').fill('所属城市')
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await page.locator('.analysis-field').filter({hasText:'所属城市'}).first().waitFor()
  assert.deepEqual(renames,[{displayName:'所属城市'}])
  assert.equal(queries.length,baseline,'Display-only rename must not rebuild the analytical query')
  assert.equal(await page.locator('.analysis-shelf.v2-shelf').first().locator('.analysis-chip').count(),1)
  assert.ok((await page.locator('.analysis-shelf.v2-shelf').first().innerText()).includes('所属城市'))
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.at(-1).content.widgets[0].analysisV2.dimensions[0].fieldId,'city')
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-shelf.v2-shelf .analysis-chip').first().waitFor()
  assert.ok((await page.locator('.analysis-shelf.v2-shelf').first().innerText()).includes('所属城市'))
  assert.equal(queries.at(-1).dimensions[0].fieldId,'city')
  assert.deepEqual(errors,[])
  console.log('PASS P0 display alias rename: source+ID unchanged, bindings persist')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P0 display alias rename',e.stack||e);process.exitCode=1})
