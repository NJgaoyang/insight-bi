// P0-04 isolated browser smoke: real UI events, mocked authenticated APIs, no StarRocks writes.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const browserPath=process.env.CHROMIUM_BIN||'/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'

;(async()=>{
 const browser=await chromium.launch({executablePath:browserPath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1550,height:960}})
  const errors=[],queries=[],saves=[]
  let revision=1,stored={mode:'grid',theme:'light',widgets:[]}
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),method=req.method(),path=url.pathname
   const send=(value,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'mock-csrf'})
   if(path==='/api/dashboards/analysis-a'&&method==='GET')
    return send({id:'analysis-a',name:'销售分析',kind:'analysis',mode:'grid',revision,status:'draft',content:stored})
   if(path==='/api/datasets')return send([{id:'ds1',name:'销售数据集',connection_id:'conn',sql_body:'SELECT city,month,revenue,cost FROM sales',fields:[
    {name:'城市',type:'VARCHAR',numeric:false},{name:'月份',type:'DATE',numeric:false},
    {name:'收入',type:'DECIMAL',numeric:true},{name:'成本',type:'DECIMAL',numeric:true}]}])
   if(path==='/api/datasets/ds1/fields')return send([
    {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',role:'DIMENSION',numeric:false,calculated:false,phase:'ROW'},
    {id:'month',name:'月份',sourceColumn:'month',logicalType:'DATE',role:'DIMENSION',numeric:false,calculated:false,phase:'ROW'},
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',role:'BOTH',numeric:true,calculated:false,phase:'ROW'},
    {id:'cost',name:'成本',sourceColumn:'cost',logicalType:'DECIMAL',role:'BOTH',numeric:true,calculated:false,phase:'ROW'}])
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    const body=req.postDataJSON();queries.push(body)
    const cols=[
     ...body.dimensions.map((d,i)=>({id:d.fieldId,name:'d'+(i+1),label:d.fieldId,type:'STRING',kind:'dimension'})),
     ...body.measures.map(m=>({id:m.id,name:m.id,label:m.alias,type:'DECIMAL',kind:'measure'}))
    ]
    const row={}
    body.dimensions.forEach((d,i)=>row['d'+(i+1)]=d.fieldId==='city'?'北京':'2026-01')
    body.measures.forEach(m=>row[m.id]=m.fieldId==='revenue'?300:220)
    return send({rows:[row],columns:cols,executionMs:5,truncated:false,warnings:[]})
   }
   if(path==='/api/dashboards/analysis-a'&&method==='PUT'){
    const body=req.postDataJSON();assert.equal(body.revision,revision)
    assert.equal(req.headers()['x-csrf-token'],'mock-csrf')
    saves.push(body);stored=structuredClone(body.content);revision++
    return send({revision})
   }
   return send({error:'Unexpected route '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/analysis-a',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-add-tab').waitFor()
  await page.locator('.analysis-add-tab').click()
  await page.locator('.analysis-source-selector .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'销售数据集'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'城市'}).first().waitFor()
  await page.locator('.analysis-field').filter({hasText:'城市'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'月份'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'收入'}).last().click()
  await page.locator('.analysis-field').filter({hasText:'成本'}).last().click()
  const shelves=page.locator('.analysis-shelf.v2-shelf')
  assert.equal(await shelves.nth(0).locator('.analysis-chip').count(),2)
  assert.equal(await shelves.nth(1).locator('.analysis-chip').count(),2)
  await page.waitForFunction(()=>document.querySelector('.v2-query-meta')?.textContent?.includes('1 行'))
  const before=queries.at(-1)
  assert.deepEqual(before.dimensions.map(d=>d.fieldId),['city','month'])
  assert.deepEqual(before.measures.map(m=>m.fieldId),['revenue','cost'])

  // Metric-specific settings must be independent, persist, and avoid data re-query for visual changes.
  const settings=page.locator('.v2-measure-setting')
  assert.equal(await settings.count(),2)
  const currentQueries=queries.length
  await settings.nth(1).locator('.el-input-number input').fill('3')
  await settings.nth(1).locator('.el-input-number input').press('Tab')
  await settings.nth(1).locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'右轴'}).first().click()
  await settings.nth(1).locator('.el-switch').first().click()
  await page.waitForTimeout(300)
  assert.equal(queries.length,currentQueries,'Style-only changes must not re-query StarRocks')
  assert.equal(await settings.nth(1).locator('.el-input-number input').inputValue(),'3')

  // Drag a dimension and verify undo/redo changes data-binding order rather than data values.
  await shelves.nth(0).locator('.analysis-chip').nth(0).dragTo(shelves.nth(0).locator('.analysis-chip').nth(1))
  await page.waitForFunction(()=>document.querySelector('.analysis-shelf.v2-shelf .analysis-chip')?.textContent?.includes('月份'))
  assert.deepEqual(queries.at(-1).dimensions.map(d=>d.fieldId),['month','city'])
  await page.locator('.analysis-header-actions button[title="撤销上一步操作"]').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-shelf.v2-shelf .analysis-chip')?.textContent?.includes('城市'))
  await page.locator('.analysis-header-actions button[title="重做上一步操作"]').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-shelf.v2-shelf .analysis-chip')?.textContent?.includes('月份'))

  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  assert.equal(saves[0].content.analysisSchemaVersion,2)
  assert.deepEqual(saves[0].content.widgets[0].analysisV2.dimensions.map(d=>d.fieldId),['month','city'])
  assert.deepEqual(saves[0].content.widgets[0].analysisV2.measures.map(m=>m.fieldId),['revenue','cost'])
  assert.equal(saves[0].content.widgets[0].analysisV2.measures[1].decimals,3)
  assert.equal(saves[0].content.widgets[0].analysisV2.measures[1].axis,'right')
  assert.equal(saves[0].content.widgets[0].analysisV2.measures[1].showLabel,true)
  await page.reload()
  await page.locator('.analysis-shelf.v2-shelf .analysis-chip').first().waitFor()
  assert.equal(await shelves.nth(0).locator('.analysis-chip').count(),2)
  assert.equal(await shelves.nth(1).locator('.analysis-chip').count(),2)
  assert.deepEqual(queries.at(-1).dimensions.map(d=>d.fieldId),['month','city'])

  // A STRING dimension can also be dragged/clicked as COUNT and made distinct.
  await page.locator('.analysis-field').filter({hasText:'城市'}).last().click()
  const countChip=shelves.nth(1).locator('.analysis-chip').last()
  await countChip.locator('.el-checkbox').click()
  await page.waitForFunction(()=>!!document.querySelector('.v2-query-meta'))
  await page.waitForTimeout(100)
  assert.equal(queries.at(-1).measures.at(-1).fieldId,'city')
  assert.equal(queries.at(-1).measures.at(-1).aggregate,'COUNT')
  assert.equal(queries.at(-1).measures.at(-1).distinct,true)
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.at(-1).content.widgets[0].analysisV2.measures.at(-1).distinct,true)
  await page.reload()
  await page.locator('.analysis-shelf.v2-shelf .analysis-chip').first().waitFor()
  assert.equal(queries.at(-1).measures.at(-1).distinct,true)

  // Incompatible chart switches should not silently discard three measure bindings.
  await page.locator('.analysis-chart-types button[title="饼图"]').click()
  assert.ok((await page.locator('.analysis-chart-types button[title="柱状图"]').getAttribute('class')).includes('active'))
  assert.equal(await shelves.nth(1).locator('.analysis-chip').count(),3)
  assert.deepEqual(errors,[])
  console.log('PASS P0 V2 UI: two dimensions, two measures, drag reorder, undo, redo, save, refresh/restore')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P0 V2 UI',e.stack||e);process.exitCode=1})
