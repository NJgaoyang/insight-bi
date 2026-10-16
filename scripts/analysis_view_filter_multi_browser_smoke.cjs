// VIEW-FILTER-02: bounded second dimension, AND semantics and reader allowlist.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18219'
const widget={id:'w1',title:'收入明细',type:'table',datasetId:'sales',dimension:'',
 measure:'',agg:'SUM',columns:[],columnStyles:{},style:{},
 analysisV2:{dimensions:[{fieldId:'city'},{fieldId:'region'}],
  measures:[{id:'rev',fieldId:'amount',aggregate:'SUM',alias:'收入'}],
  filters:{pre:[],post:[]},sort:[],limit:100}}
const fixture={mode:'grid',theme:'light',widgets:[widget],
 dashboards:[{id:'overview',name:'汇总',widgetIds:['w1'],
  filter:{datasetId:'sales',fieldId:'city',label:'城市'}}]}
const queryResult={columns:[{id:'city',name:'d1',kind:'dimension',label:'城市',type:'STRING'},
 {id:'region',name:'d2',kind:'dimension',label:'区域',type:'STRING'},
 {id:'rev',name:'rev',label:'收入',kind:'measure',type:'DECIMAL'}],
 rows:[{d1:'北京',d2:'华北',rev:100}],warnings:[],truncated:false,executionMs:3}
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
 const page=await browser.newPage({viewport:{width:1530,height:970}})
 let content=structuredClone(fixture),published=null,revision=1,reader=false
 const queries=[],readerQueries=[],saves=[],errors=[]
 page.on('pageerror',e=>errors.push(e.message))
 const json=(r,v,status=200)=>r.fulfill({status,contentType:'application/json',body:JSON.stringify(v)})
 await page.route('**/api/**',route=>{
  const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method()
  if(p==='/api/me')return reader?json(route,{error:'unauthorized'},401):
   json(route,{user:'admin',csrf:'csrf'})
  if(p==='/api/reader/me')return json(route,{user:'reader',csrf:'reader'})
  if(p==='/api/dashboards/report/approval')return json(route,{required:false,state:'DISABLED'})
  if(p==='/api/dashboards/report'&&m==='GET')return json(route,{
   id:'report',name:'两条件视图',kind:'analysis',status:'published',revision,
   content:url.searchParams.get('published')==='true'?(published||content):content})
  if(p==='/api/dashboards/report'&&m==='PUT'){
   const input=req.postDataJSON()
   assert.equal(input.revision,revision)
   saves.push(input);content=structuredClone(input.content);revision++
   return json(route,{revision})
  }
  if(p==='/api/dashboards/report/publish'&&m==='POST'){
   published=structuredClone(content);return json(route,{ok:true})
  }
  if(p==='/api/reader/dashboards/report'&&m==='GET')return json(route,{
   id:'report',name:'两条件视图',kind:'analysis',status:'published',revision,
   content:published||content})
  if(p==='/api/analysis/v2/query'&&m==='POST'){
   queries.push(req.postDataJSON());return json(route,queryResult)
  }
  if(p==='/api/reader/dashboards/report/widgets/w1/query'&&m==='POST'){
   readerQueries.push(req.postDataJSON());return json(route,queryResult)
  }
  if(p==='/api/datasets'&&m==='GET')return json(route,[{id:'sales',name:'销售',connection_id:'local'}])
  if(p==='/api/datasets/sales/fields'&&m==='GET')return json(route,[
   {id:'city',name:'城市',logicalType:'STRING',numeric:false,calculated:false,sourceColumn:'city'},
   {id:'region',name:'区域',logicalType:'STRING',numeric:false,calculated:false,sourceColumn:'region'},
   {id:'amount',name:'收入',logicalType:'DECIMAL',numeric:true,calculated:false,sourceColumn:'amount'}])
  return json(route,{error:'Unexpected '+m+' '+p},404)
 })
 await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
 await page.getByRole('button',{name:'仪表板预览'}).click()
 const container=page.locator('.analysis-view-filter')
 await container.waitFor()
 // Secondary slot is independently configured; its value is not a saved field.
 await page.locator('.analysis-view-filter > .el-select').nth(1).click()
 await page.locator('.el-select-dropdown__item:visible').filter({hasText:'region'}).click()
 await page.locator('input[aria-label="视图筛选值"]').fill('北京')
 await page.locator('input[aria-label="第二视图筛选值"]').fill('华北')
 const both=page.waitForResponse(r=>r.url().includes('/api/analysis/v2/query')&&
  r.request().method()==='POST'&&
  (r.request().postData()||'').includes('"fieldId":"region"')&&
  (r.request().postData()||'').includes('"value":"华北"'))
 await container.locator('button').filter({hasText:'应用'}).click()
 await both
 const hit=queries.find(q=>q.filters?.pre?.some(f=>f.fieldId==='city'&&f.value==='北京')&&
  q.filters.pre.some(f=>f.fieldId==='region'&&f.value==='华北'))
 assert.ok(hit,'both equalities must reach the one same-dataset widget')
 await page.locator('.analysis-primary').click()
 await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
 assert.equal(saves.length,1)
 assert.equal(saves[0].content.dashboards[0].filter2.fieldId,'region')
 assert.ok(!JSON.stringify(saves[0].content.dashboards).includes('华北'))
 await page.reload({waitUntil:'domcontentloaded'})
 await page.getByRole('button',{name:'仪表板预览'}).click()
 await page.locator('input[aria-label="第二视图筛选值"]').waitFor()
 await page.getByRole('button',{name:'发布',exact:true}).first().click()
 await page.goto(origin+'/#/view/report',{waitUntil:'domcontentloaded'})
 await page.locator('input[aria-label="第二已发布视图筛选值"]').waitFor()
 await page.locator('input[aria-label="已发布视图筛选值"]').fill('北京')
 await page.locator('input[aria-label="第二已发布视图筛选值"]').fill('华北')
 const publishedFilter=page.waitForResponse(r=>r.url().includes('/api/analysis/v2/query')&&
  (r.request().postData()||'').includes('"value":"华北"'))
 await page.locator('.analysis-view-filter-published button').filter({hasText:'应用筛选'}).click()
 await publishedFilter
 reader=true
 await page.goto(origin+'/?reader=1#/reader-view/report',{waitUntil:'domcontentloaded'})
 await page.locator('input[aria-label="第二已发布视图筛选值"]').waitFor()
 await page.locator('input[aria-label="已发布视图筛选值"]').fill('北京')
 await page.locator('input[aria-label="第二已发布视图筛选值"]').fill('华北')
 const readerBoth=page.waitForResponse(r=>r.url().includes('/api/reader/dashboards/report/widgets/w1/query')&&
  (r.request().postData()||'').includes('"filterValue2":"华北"'))
 await page.locator('.analysis-view-filter-published button').filter({hasText:'应用筛选'}).click()
 await readerBoth
 assert.ok(readerQueries.some(x=>JSON.stringify(x)==='{"viewId":"overview","filterValue":"北京","filterValue2":"华北"}'))
 // Test secondary-only request (no empty primary is transmitted).
 await page.locator('input[aria-label="已发布视图筛选值"]').clear()
 const secondOnly=page.waitForResponse(r=>r.url().includes('/api/reader/dashboards/report/widgets/w1/query')&&
  (r.request().postData()||'').includes('"filterValue2":"华北"')&&
  !(r.request().postData()||'').includes('"filterValue":'))
 await page.locator('.analysis-view-filter-published button').filter({hasText:'应用筛选'}).click()
 await secondOnly
 assert.deepEqual(errors,[])
 console.log('PASS view filter 02: two independent dimensions AND, snapshot, reader allowlist and second-only')
 }finally{await browser.close()}
})().catch(err=>{console.error('FAIL view filter 02',err.stack||err);process.exitCode=1})
