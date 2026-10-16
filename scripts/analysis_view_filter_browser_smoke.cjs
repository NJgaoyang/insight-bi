// VIEW-FILTER-01: same-dataset stable-field filter, published snapshot and reader payload.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18219'
const widget=(id,datasetId,fieldId)=>({
 id,title:'组件 '+id,type:'table',datasetId,dimension:'',measure:'',agg:'SUM',
 columns:[],columnStyles:{},style:{},
 analysisV2:{dimensions:[{fieldId}],measures:[{id:'revenue',fieldId:'revenue',aggregate:'SUM',alias:'收入'}],
 filters:{pre:[],post:[]},sort:[],limit:100}
})
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1530,height:980}})
  const errors=[],queries=[],readerQueries=[],saves=[]
  let revision=1,session='admin',published=null
  let content={mode:'grid',theme:'light',
   widgets:[widget('w1','ds1','city'),widget('w2','ds1','city'),widget('w3','ds2','region')],
   dashboards:[{id:'overview',name:'经营总览',widgetIds:['w1','w2','w3']},
    {id:'finance',name:'财务',widgetIds:['w3']}]}
  page.on('pageerror',e=>errors.push(e.message))
  const send=(route,result,status=200)=>route.fulfill({status,
   contentType:'application/json',body:JSON.stringify(result)})
  const result=()=>({columns:[
    {id:'city',name:'d1',label:'城市',kind:'dimension',type:'STRING'},
    {id:'revenue',name:'revenue',label:'收入',kind:'measure',type:'DECIMAL',additive:true}
   ],rows:[{d1:'北京',revenue:120},{d1:'上海',revenue:80}],truncated:false,executionMs:2,warnings:[]})
  await page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method()
   if(path==='/api/me')return session==='reader'?send(route,{message:'unauthorized'},401):
    send(route,{user:'admin',csrf:'csrf'})
   if(path==='/api/reader/me')return send(route,{user:'reader',csrf:'reader-csrf'})
   if(path==='/api/dashboards/report/approval')return send(route,{required:false,state:'DISABLED'})
   if(path==='/api/dashboards/report'&&method==='GET')
    return send(route,{id:'report',kind:'analysis',name:'筛选分析主题',
     status:'published',revision,content:url.searchParams.get('published')==='true'
      ?(published||content):content})
   if(path==='/api/dashboards/report'&&method==='PUT'){
    const body=req.postDataJSON()
    assert.equal(body.revision,revision)
    saves.push(body);content=structuredClone(body.content);revision++
    return send(route,{revision})
   }
   if(path==='/api/dashboards/report/publish'&&method==='POST'){
    published=structuredClone(content);return send(route,{ok:true})
   }
   if(path==='/api/reader/dashboards/report'&&method==='GET')
    return send(route,{id:'report',kind:'analysis',name:'筛选分析主题',
     status:'published',revision,content:published||content})
   if(path.startsWith('/api/reader/dashboards/report/widgets/')&&path.endsWith('/query')){
    readerQueries.push({widgetId:path.split('/')[6],body:req.postDataJSON()})
    return send(route,result())
   }
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    queries.push(req.postDataJSON());return send(route,result())
   }
   if(path==='/api/datasets'&&method==='GET')return send(route,[
    {id:'ds1',name:'收入数据',connection_id:'source',fields:[]},{id:'ds2',name:'费用数据',connection_id:'source',fields:[]}])
   if(path==='/api/datasets/ds1/fields')return send(route,[
    {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',numeric:false,calculated:false},
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',numeric:true,calculated:false}])
   if(path==='/api/datasets/ds2/fields')return send(route,[
    {id:'region',name:'区域',sourceColumn:'region',logicalType:'STRING',numeric:false,calculated:false},
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',numeric:true,calculated:false}])
   return send(route,{error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'仪表板预览'}).click()
  await page.locator('.analysis-view-filter').waitFor()
  await page.locator('.analysis-view-filter > .el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible')
    .filter({hasText:'组件 w1 · city'}).click()
  await page.locator('input[aria-label="视图筛选值"]').fill('北京')
  const filterRequest=page.waitForResponse(r=>r.url().includes('/api/analysis/v2/query')&&
    r.request().method()==='POST'&&
    (r.request().postData()||'').includes('"value":"北京"'))
  await page.locator('.analysis-view-filter button').filter({hasText:'应用'}).click()
  await filterRequest
  assert.ok(queries.some(q=>q.datasetId==='ds1'&&q.filters?.pre?.some(f=>f.fieldId==='city'&&f.value==='北京')))
  assert.ok(queries.every(q=>q.datasetId!=='ds2'||!q.filters?.pre?.some(f=>f.value==='北京')))
  // Same-name or matching chart alone never creates a cross-source link.
  const mappingSelect=page.locator('.analysis-filter-cross-mapping:visible .el-select')
  await mappingSelect.waitFor()
  await mappingSelect.locator('.el-select__wrapper').click()
  await page.getByRole('option',{name:'region',exact:true}).click()
  await page.locator('input[aria-label="视图筛选值"]').fill('上海')
  const mappedRequest=page.waitForResponse(r=>r.url().includes('/api/analysis/v2/query')&&
   (r.request().postData()||'').includes('"fieldId":"region"')&&
   (r.request().postData()||'').includes('"value":"上海"'))
  await page.locator('.analysis-view-filter button').filter({hasText:'应用'}).click()
  await mappedRequest
  assert.ok(queries.some(q=>q.datasetId==='ds2'&&q.filters?.pre?.some(f=>f.fieldId==='region'&&f.value==='上海')))
  await page.locator('input[aria-label="视图筛选值"]').fill('北京')
  await page.locator('.analysis-view-filter button').filter({hasText:'应用'}).click()
  await page.locator('.analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  assert.deepEqual(saves[0].content.dashboards[0].filter,
    {datasetId:'ds1',fieldId:'city',label:'组件 w1 · city',
     mappings:[{widgetId:'w3',fieldId:'region'}]})
  assert.equal(saves[0].content.dashboards[1].filter,undefined)
  assert.ok(!JSON.stringify(saves[0].content.dashboards).includes('"北京"'),
   'applied runtime filter value must not enter the published snapshot')
  await page.reload({waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'仪表板预览'}).click()
  await page.locator('.analysis-view-filter > .el-select').first().waitFor()
  await page.locator('input[aria-label="视图筛选值"]').fill('北京')
  await page.locator('.analysis-view-filter button').filter({hasText:'应用'}).click()
  await page.getByRole('button',{name:'发布',exact:true}).first().click()
  await page.goto(origin+'/#/view/report',{waitUntil:'domcontentloaded'})
  await page.locator('input[aria-label="已发布视图筛选值"]').waitFor()
  assert.match(await page.locator('.analysis-view-filter-published').innerText(),/组件 w1/)
  await page.locator('input[aria-label="已发布视图筛选值"]').fill('上海')
  const publishedRequest=page.waitForResponse(r=>r.url().includes('/api/analysis/v2/query')&&
    (r.request().postData()||'').includes('"value":"上海"'))
  await page.locator('.analysis-view-filter-published button').filter({hasText:'应用筛选'}).click()
  await publishedRequest
  await page.locator('.analysis-view-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务'}).click()
  assert.equal(await page.locator('.analysis-view-filter-published').count(),0)
  session='reader'
  // Changing the URL search component forces a fresh app bootstrap, so it
  // detects reader auth before parsing /reader-view rather than admin routing.
  await page.goto(origin+'/?reader=1#/reader-view/report',{waitUntil:'domcontentloaded'})
  await page.locator('input[aria-label="已发布视图筛选值"]').waitFor()
  await page.locator('input[aria-label="已发布视图筛选值"]').fill('北京')
  const authorizedRequest=page.waitForResponse(r=>r.url().includes('/api/reader/dashboards/report/widgets/w1/query')&&
    (r.request().postData()||'').includes('"filterValue":"北京"'))
  await page.locator('.analysis-view-filter-published button').filter({hasText:'应用筛选'}).click()
  await authorizedRequest
  assert.ok(readerQueries.some(q=>q.widgetId==='w1'&&q.body.viewId==='overview'&&
    q.body.filterValue==='北京'&&Object.keys(q.body).length===2))
  assert.ok(readerQueries.some(q=>q.widgetId==='w3'&&q.body.viewId==='overview'&&
    q.body.filterValue==='北京'&&Object.keys(q.body).length===2),
    'mapped foreign widget must send only published viewId + value')
  assert.deepEqual(errors,[])
  console.log('PASS view filter: same-source, isolated runtime, save/reopen/publish, read-only pinned reader value')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL view filter browser',e.stack||e);process.exitCode=1})
