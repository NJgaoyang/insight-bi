// ANALYSIS-PHASE2-02 exact JDBC rollup presentation and snapshot browser smoke. Mocked REST only.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||chromium.executablePath()
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18118'
const dimensions=[{fieldId:'stable-city'},{fieldId:'stable-month'}]
const measures=[
 {id:'revenue',fieldId:'stable-revenue',aggregate:'SUM',alias:'销售额',decimals:2},
 {id:'average',fieldId:'stable-revenue',aggregate:'AVG',alias:'平均金额',decimals:2}
]
const widget={
 id:'w-pivot',type:'bar',title:'销售交叉分析',datasetId:'ds1',dimension:'',measure:'',agg:'SUM',
 columns:[],style:{},columnStyles:{},analysisV2:{dimensions,measures,
 filters:{pre:[],post:[]},sort:[],limit:200}
}
const dims=(first,second)=>[
 {id:first,name:'d1',label:first==='stable-city'?'同名维度':'月份',kind:'dimension',type:'STRING'},
 {id:second,name:'d2',label:second==='stable-month'?'同名维度':'城市',kind:'dimension',type:'STRING'}]
const grouped=[
 {city:'北京',month:'一月',revenue:10.10,average:5.05},
 {city:'北京',month:'二月',revenue:2.40,average:2.40},
 {city:'上海',month:'二月',revenue:20.25,average:20.25}
]
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1540,height:980}})
  const errors=[],saves=[]
  let revision=1,published=null,truncated=false,manyGroups=false,sawPivotRequest=false
  let content={mode:'grid',theme:'light',widgets:[widget],
   dashboards:[{id:'overview',name:'经营总览',widgetIds:['w-pivot']},
    {id:'finance',name:'财务专题',widgetIds:['w-pivot']}]}
  const send=(route,payload,status=200)=>route.fulfill({
   status,contentType:'application/json',body:JSON.stringify(payload)})
  page.on('pageerror',e=>errors.push(e.message))
  page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method()
   if(path==='/api/me')return send(route,{user:'admin',csrf:'mock'})
   if(path==='/api/dashboards/report/approval')return send(route,{required:false,state:'DISABLED'})
   if(path==='/api/dashboards/report'&&method==='GET'){
    const view=url.searchParams.get('published')==='true'
    return send(route,{id:'report',name:'销售主题',revision,kind:'analysis',status:'published',
      content:view?(published||content):content})
   }
   if(path==='/api/dashboards/report'&&method==='PUT'){
    const payload=req.postDataJSON()
    assert.equal(payload.revision,revision)
    saves.push(payload);content=structuredClone(payload.content);revision++
    return send(route,{revision})
   }
   if(path==='/api/dashboards/report/publish'&&method==='POST'){
    published=structuredClone(content)
    return send(route,{ok:true})
   }
   if(path==='/api/datasets')return send(route,[{id:'ds1',name:'SQL 销售数据',fields:[]}])
   if(path==='/api/datasets/ds1/fields')return send(route,[
    {id:'stable-city',name:'同名维度',logicalType:'STRING',numeric:false},
    {id:'stable-month',name:'同名维度',logicalType:'STRING',numeric:false},
    {id:'stable-revenue',name:'销售额',logicalType:'DECIMAL',numeric:true}
   ])
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    const body=req.postDataJSON()
    assert.equal(body.datasetId,'ds1')
    assert.deepEqual(body.dimensions.map(d=>d.fieldId),['stable-city','stable-month'])
    if(body.pivot){sawPivotRequest=true;assert.equal(body.pivot.showTotals,true)}
    const rows=manyGroups?Array.from({length:21},(_,i)=>({
     d1:'城市'+String(i+1).padStart(2,'0'),d2:'一月',revenue:i+1,average:i+1
    })):grouped.map(g=>({d1:g.city,d2:g.month,revenue:g.revenue,average:g.average}))
    const pivotTotals=truncated?{exact:false,reason:'结果已截断，无法生成总计'}:
      manyGroups?{exact:true,
       rows:rows.map(r=>({d1:r.d1,revenue:String(r.revenue),average:String(r.average)})),
       columns:[{d1:'一月',revenue:'231',average:'11'}],
       grand:{revenue:'231',average:'11'}}:
       {exact:true,rows:[
         {d1:'北京',revenue:'12.50',average:'4.216666666666666666'},
         {d1:'上海',revenue:'20.25',average:'20.25'}
       ],columns:[
         {d1:'一月',revenue:'10.10',average:'5.05'},
         {d1:'二月',revenue:'22.65',average:'11.325'}
       ],grand:{revenue:'32.75',average:'8.1875'}}
    return send(route,{pivotTotals,rows,columns:[...dims('stable-city','stable-month'),
      {id:'revenue',name:'revenue',label:'销售额',kind:'measure',type:'DECIMAL',additive:true},
      {id:'average',name:'average',label:'平均金额',kind:'measure',type:'DECIMAL',additive:false}
    ],executionMs:8,truncated,warnings:[]})
   }
   return send(route,{error:'unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-chart-types button[title="交叉表"]').click()
  const pivot=page.locator('.v2-pivot-wrap table').first()
  await pivot.waitFor()
  assert.equal(await pivot.locator('tbody tr').count(),2)
  assert.equal(sawPivotRequest,true,'pivot query must request server totals')
  assert.equal(await pivot.locator('tbody td').count(),12,'2 rows × 2 columns × 2 metrics + 2 totals')
  const beijing=pivot.locator('tbody tr').filter({has:page.locator('th:has-text("北京")')})
  assert.ok((await beijing.innerText()).includes('12.50'))
  assert.equal((await pivot.locator('tfoot tr th').allTextContents()).at(-1),'8.19',
    'AVG grand total must come from server SQL, not summed cell averages')
  assert.ok((await beijing.innerText()).includes('4.22'),
    'weighted row AVG must display independently computed server rollup')
  const help=page.locator('.analysis-pivot-help')
  await help.waitFor()
  assert.match(await help.innerText(),/第 1 个为行/)
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  assert.equal(saves[0].content.widgets[0].type,'pivot')
  assert.deepEqual(saves[0].content.widgets[0].analysisV2.dimensions,dimensions)
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.v2-pivot-wrap table').waitFor()
  await page.locator('.analysis-header-actions button').filter({hasText:'仪表板预览'}).click()
  await page.locator('.analysis-view-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务专题'}).click()
  await page.locator('.analysis-view-grid .v2-pivot-wrap table').waitFor()
  await page.locator('.analysis-header-actions button').filter({hasText:'发布'}).first().click()
  await page.goto(origin+'/#/view/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-view .v2-pivot-wrap table').waitFor()
  assert.equal(await page.locator('.analysis-published-view .v2-pivot-wrap tbody tr').count(),2)
  assert.deepEqual(published.widgets[0].analysisV2.dimensions,dimensions)
  assert.equal(await page.locator('.analysis-published-view .analysis-layout-toolbar button[title="进入组件分析编辑"]').count(),0)
  truncated=true
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-view .v2-pivot-warning').waitFor()
  assert.match(await page.locator('.analysis-published-view .v2-pivot-warning').innerText(),/截断/)
  assert.equal((await page.locator('.analysis-published-view .v2-pivot-wrap tfoot tr th').allTextContents()).at(-2),'—')
  truncated=false;manyGroups=true
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-view .v2-pivot-pager').waitFor()
  assert.equal(await page.locator('.analysis-published-view .v2-pivot-wrap tbody tr').count(),20)
  await page.locator('.analysis-published-view .v2-pivot-pager button').filter({hasText:'下一页'}).click()
  assert.equal(await page.locator('.analysis-published-view .v2-pivot-wrap tbody tr').count(),1)
  assert.deepEqual(errors,[])
  console.log('PASS ANALYSIS-PHASE2-02 Chromium: SQL rollup AVG row/grand, save/publish, truncation and pagination')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL ANALYSIS-PHASE2-02 rollup browser',e.stack||e);process.exitCode=1})
