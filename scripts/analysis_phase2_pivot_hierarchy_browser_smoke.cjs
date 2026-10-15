// ANALYSIS-PHASE2-03 isolated three-dimension hierarchy smoke, mocked REST, no real DB.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||chromium.executablePath()
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18130'
const dims=['area','city','month'].map(fieldId=>({fieldId}))
const measures=[
 {id:'rev',fieldId:'revenue',aggregate:'SUM',alias:'收入',decimals:2},
 {id:'avg',fieldId:'revenue',aggregate:'AVG',alias:'平均收入',decimals:2}]
const widget={id:'w1',title:'区域城市季度',type:'pivot',datasetId:'ds',
 dimension:'',measure:'',agg:'SUM',columns:[],style:{},columnStyles:{},
 analysisV2:{dimensions:dims,measures,filters:{pre:[],post:[]},sort:[],limit:100,
 pivot:{showTotals:true,showSubtotals:true,rowOrder:'asc',columnOrder:'asc'}}}
const result={executionMs:4,truncated:false,warnings:[],
 columns:[
 {id:'area',name:'d1',label:'区域',kind:'dimension',type:'STRING'},
 {id:'city',name:'d2',label:'城市',kind:'dimension',type:'STRING'},
 {id:'month',name:'d3',label:'月份',kind:'dimension',type:'STRING'},
 {id:'rev',name:'rev',label:'收入',kind:'measure',type:'DECIMAL',additive:true},
 {id:'avg',name:'avg',label:'均值',kind:'measure',type:'DECIMAL',additive:false}
 ],
 rows:[
 {d1:'北区',d2:'北京',d3:'一月',rev:100,avg:50},
 {d1:'北区',d2:'天津',d3:'一月',rev:20,avg:20},
 {d1:'南区',d2:'上海',d3:'一月',rev:30,avg:30}
 ],
 pivotTotals:{exact:true,rows:[
 {d1:'北区',d2:'北京',rev:'100',avg:'50'},
 {d1:'北区',d2:'天津',rev:'20',avg:'20'},
 {d1:'南区',d2:'上海',rev:'30',avg:'30'}
 ],columns:[{d1:'一月',rev:'150',avg:'30'}],
 grand:{rev:'150',avg:'30'},
 parents:[{d1:'北区',rev:'120',avg:'40'},{d1:'南区',rev:'30',avg:'30'}],
 parentCells:[{d1:'北区',d2:'一月',rev:'120',avg:'40'},
              {d1:'南区',d2:'一月',rev:'30',avg:'30'}]}
}
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1550,height:990}})
  const errors=[],saves=[];let revision=1,published=null
  let content={mode:'grid',theme:'light',widgets:[widget],
   dashboards:[{id:'overview',name:'经营总览',widgetIds:['w1']},
               {id:'finance',name:'财务',widgetIds:['w1']}]}
  page.on('pageerror',e=>errors.push(e.message))
  const send=(route,payload,status=200)=>route.fulfill({
   status,contentType:'application/json',body:JSON.stringify(payload)})
  await page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method()
   if(path==='/api/me')return send(route,{user:'admin',csrf:'mock'})
   if(path==='/api/dashboards/report/approval')return send(route,{required:false,state:'DISABLED'})
   if(path==='/api/dashboards/report'&&method==='GET')
    return send(route,{id:'report',kind:'analysis',name:'多层交叉分析',status:'published',
     revision,content:url.searchParams.has('published')?(published||content):content})
   if(path==='/api/dashboards/report'&&method==='PUT'){
    const input=req.postDataJSON()
    assert.equal(input.revision,revision)
    saves.push(input);content=structuredClone(input.content);revision++
    return send(route,{revision})
   }
   if(path==='/api/dashboards/report/publish'&&method==='POST'){
    published=structuredClone(content);return send(route,{ok:true})
   }
   if(path==='/api/datasets')return send(route,[{id:'ds',name:'数据集',fields:[]}])
   if(path==='/api/datasets/ds/fields')return send(route,[
    {id:'area',name:'区域',logicalType:'STRING',numeric:false},
    {id:'city',name:'城市',logicalType:'STRING',numeric:false},
    {id:'month',name:'月份',logicalType:'STRING',numeric:false},
    {id:'revenue',name:'收入',logicalType:'DECIMAL',numeric:true}])
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    const input=req.postDataJSON()
    assert.deepEqual(input.dimensions.map(d=>d.fieldId),['area','city','month'])
    assert.equal(input.pivot?.showSubtotals,true)
    return send(route,result)
   }
   return send(route,{error:'unexpected '+method+' '+path},404)
  })
  const rows=page.locator('.v2-pivot-wrap tbody tr:not(.v2-pivot-subtotal)')
  const subtotals=page.locator('.v2-pivot-wrap .v2-pivot-subtotal')
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  await rows.first().waitFor()
  assert.equal(await rows.count(),3)
  assert.equal(await subtotals.count(),2)
  assert.match(await subtotals.first().innerText(),/北区/)
  assert.match(await subtotals.first().innerText(),/120.00/)
  assert.match(await subtotals.first().innerText(),/40.00/)
  assert.equal(await subtotals.first().locator('td').count(),4)
  assert.equal((await subtotals.first().locator('td').allTextContents()).filter(t=>t.includes('120.00')).length,2,
   'parent subtotal must appear both under its column and in right-side row total')
  assert.match(await page.locator('.v2-pivot-wrap tfoot').innerText(),/150.00/)
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  assert.deepEqual(saves[0].content.widgets[0].analysisV2.dimensions,dims)
  await page.reload({waitUntil:'domcontentloaded'})
  await subtotals.first().waitFor()
  await page.locator('.analysis-header-actions button').filter({hasText:'发布'}).first().click()
  await page.goto(origin+'/#/view/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-view .v2-pivot-subtotal').first().waitFor()
  assert.equal(await page.locator('.analysis-published-view .v2-pivot-subtotal').count(),2)
  await page.locator('.analysis-published-header .analysis-view-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务'}).click()
  assert.equal(await page.locator('.analysis-published-view .v2-pivot-subtotal').count(),2)
  assert.deepEqual(published.widgets[0].analysisV2.dimensions,dims)
  assert.deepEqual(errors,[])
  console.log('PASS ANALYSIS-PHASE2-03 hierarchy: 3 dims, server parent AVG, save/reload/two views/publish')
 }finally{await browser.close()}
})().catch(error=>{console.error('FAIL ANALYSIS-PHASE2-03 hierarchy browser',error.stack||error);process.exitCode=1})
