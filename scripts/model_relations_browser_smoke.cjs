// Read-only P1 relationship catalog: data list -> saved relation -> existing pipeline editor.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18221'
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}})
  const errors=[],urls=[]
  page.on('pageerror',error=>errors.push(error.message))
  const respond=(route,value,status=200)=>route.fulfill({
   status,contentType:'application/json',body:JSON.stringify(value)})
  await page.route('**/api/**',route=>{
   const request=route.request(),path=new URL(request.url()).pathname
   urls.push(path)
   if(path==='/api/me')return respond(route,{user:'admin',csrf:'csrf'})
   if(path==='/api/datasets')return respond(route,[
    {id:'sales',name:'销售',connection_id:'local',folder_id:null,fields:[]},
    {id:'refunds',name:'退款',connection_id:'local',folder_id:null,fields:[]}])
   if(path==='/api/model/relations')return respond(route,[
    {datasetId:'sales',datasetName:'销售',targetDatasetId:'refunds',
     targetDatasetName:'退款',mode:'JOIN',joinType:'LEFT',revision:4,status:'ACTIVE',
     reason:'',fields:[{leftId:'city',leftName:'城市',rightId:'region',rightName:'地区',compatible:true}],
     outputFields:2},
    {datasetId:'refunds',datasetName:'退款',targetDatasetId:'deleted',
     targetDatasetName:'数据集已删除',mode:'UNION',joinType:'',revision:2,
     status:'INVALID',reason:'关联的数据集已不存在',fields:[],outputFields:0}
   ])
   if(path==='/api/model/relations/sales/impact')return respond(route,{
    datasetId:'sales',pipelineRevision:4,consumers:[
     {dashboardId:'analysis1',dashboardName:'销售分析',stage:'DRAFT',
      widgetCount:2,matchingRevision:1,outdatedRevision:1},
     {dashboardId:'analysis1',dashboardName:'销售分析',stage:'PUBLISHED',
      widgetCount:1,matchingRevision:0,outdatedRevision:1}
    ]})
   if(path==='/api/datasets/sales/fields')return respond(route,[])
   if(path==='/api/datasets/sales/pipeline')return respond(route,{datasetId:'sales',steps:[],revision:0,stale:false})
   if(path==='/api/connections'||path==='/api/dashboards'||path==='/api/folders'||path==='/api/data-folders')
    return respond(route,[])
   return respond(route,{error:'Unexpected '+path},404)
  })
  await page.goto(origin+'/#/datasets',{waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'模型关系'}).waitFor()
  await page.getByRole('button',{name:'模型关系'}).click()
  const pane=page.locator('.model-relations')
  await pane.waitFor()
  assert.equal(await pane.locator('.model-relations-entry').count(),2)
  assert.match(await pane.innerText(),/城市 ↔ 地区/)
  assert.match(await pane.innerText(),/配置失效/)
  assert.match(await pane.innerText(),/关联的数据集已不存在/)
  assert.ok(urls.includes('/api/model/relations'))
  assert.equal(await pane.locator('input').count(),0,'the model directory must stay read-only')
  await pane.locator('.model-relations-entry').first().getByRole('button',{name:'影响分析'}).click()
  await pane.locator('.model-relations-impact table tbody tr').first().waitFor()
  assert.equal(await pane.locator('.model-relations-impact tbody tr').count(),2)
  assert.match(await pane.locator('.model-relations-impact').innerText(),/销售分析/)
  assert.match(await pane.locator('.model-relations-impact').innerText(),/已发布/)
  assert.ok(urls.includes('/api/model/relations/sales/impact'))
  await pane.locator('.model-relations-entry').first().getByRole('button',{name:'查看加工步骤'}).click()
  await page.waitForURL(/#\/pipeline\/sales/)
  assert.deepEqual(errors,[])
  console.log('PASS model relation catalog: saved relationships, status, fields and pipeline navigation')
 }finally{await browser.close()}
})().catch(error=>{console.error('FAIL model relation browser',error.stack||error);process.exitCode=1})
