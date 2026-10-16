// DRILL-01 browser: an authorized reader receives only a target-view pinned filter.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18219'
const makeWidget=(id)=>({id,title:id,type:'table',datasetId:'ds1',
 dimension:'',measure:'',agg:'SUM',columns:[],style:{},
 analysisV2:{dimensions:[{fieldId:'city'}],
 measures:[{id:'total',fieldId:'revenue',aggregate:'SUM'}],
 filters:{pre:[],post:[]},sort:[],limit:100}})
const mkReport=(drillEnabled)=>({id:'report',name:'钻取测试',kind:'analysis',
 status:'published',revision:1,content:{mode:'grid',theme:'light',
 widgets:[makeWidget('w1'),makeWidget('w2')],
 dashboards:[{id:'from',name:'来源',widgetIds:['w1'],jumps:{w1:'to'},
  drills:drillEnabled?{w1:true}:{}},
 {id:'to',name:'明细',widgetIds:['w2'],
 filter:{datasetId:'ds1',fieldId:'city',label:'城市'}}]}})
const result={columns:[
 {id:'city',name:'d1',label:'城市',kind:'dimension',type:'STRING'},
 {id:'total',name:'total',label:'总计',kind:'measure',type:'DECIMAL'}],
 rows:[{d1:'北京',total:120},{d1:'上海',total:80}],truncated:false,executionMs:1}
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:910}})
  let active=mkReport(true)
  const errors=[],calls=[]
  page.on('pageerror',err=>errors.push(err.message))
  const send=(route,value,status=200)=>route.fulfill({status,
   contentType:'application/json',body:JSON.stringify(value)})
  await page.route('**/api/**',route=>{
   const request=route.request(),path=new URL(request.url()).pathname
   if(path==='/api/me')return send(route,{error:'No admin session'},401)
   if(path==='/api/reader/me')return send(route,{user:'analyst',csrf:'reader-token'})
   if(path==='/api/reader/dashboards/report')return send(route,active)
   const matched=path.match(/^\/api\/reader\/dashboards\/report\/widgets\/(w1|w2)\/query$/)
   if(matched){
    calls.push({widget:matched[1],body:request.postDataJSON()})
    return send(route,result)
   }
   return send(route,{error:'Unexpected '+path},404)
  })
  await page.goto(origin+'/?reader=1#/reader-view/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] .v2-table-wrap tbody tr').first().waitFor()
  const table=page.locator('.analysis-dashboard-card[data-widget-id="w1"] .v2-table-wrap')
  await table.locator('input[aria-label="搜索当前表格结果"]').fill('上海')
  await page.waitForFunction(()=>document.querySelectorAll('.analysis-dashboard-card[data-widget-id="w1"] .v2-table-wrap tbody tr').length===1)
  assert.match(await table.locator('tbody tr').first().innerText(),/上海/)
  const drillResponse=page.waitForResponse(resp=>
   resp.url().endsWith('/api/reader/dashboards/report/widgets/w2/query')&&
   (resp.request().postData()||'').includes('"filterValue":"上海"'))
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] .v2-table-wrap tbody tr').first().click()
  await drillResponse
  assert.ok(calls.some(x=>x.widget==='w2'&&
   JSON.stringify(x.body)==='{"viewId":"to","filterValue":"上海"}'))
  assert.equal(await page.locator('input[aria-label="已发布视图筛选值"]').inputValue(),'上海')
  assert.equal(await page.locator('.analysis-view-picker .el-select__selected-item').count()>0,true)
  const unfiltered=page.waitForResponse(resp=>
   resp.url().endsWith('/api/reader/dashboards/report/widgets/w2/query')&&
   resp.request().postData()==='{}')
  await page.locator('.analysis-view-filter-published button').filter({hasText:'清除筛选'}).click()
  await unfiltered
  assert.deepEqual(errors,[])
  // Old jump without explicit drill opt-in must remain navigation-only.
  active=mkReport(false);calls.length=0
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] .v2-table-wrap tbody tr').first().waitFor()
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] .v2-table-wrap tbody tr').first().click()
  await page.locator('input[aria-label="已发布视图筛选值"]').waitFor()
  assert.equal(await page.locator('input[aria-label="已发布视图筛选值"]').inputValue(),'')
  assert.ok(calls.some(x=>x.widget==='w2'&&Object.keys(x.body).length===0))
  console.log('PASS DRILL-01: opted-in chart category -> pinned target filter, reader allowlist and legacy jump')
 }finally{await browser.close()}
})().catch(err=>{console.error('FAIL DRILL-01',err.stack||err);process.exitCode=1})
