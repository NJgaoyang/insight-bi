// P1 focused read-only published analysis view and legacy dashboard routing smoke.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:900}})
  const errors=[],requests=[]
  page.on('pageerror',error=>errors.push(error.message))
  const widget=id=>({id,title:'指标'+id,type:'bar',datasetId:id==='w1'?'ds1':'',
    analysisV2:{dimensions:id==='w1'?[{fieldId:'city'}]:[],measures:id==='w1'?[{id:'total',fieldId:'revenue',aggregate:'SUM'}]:[],filters:{pre:[],post:[]},sort:[],limit:200},
    dimension:'',measure:'',agg:'SUM',columns:[],style:{}})
  const analysis={
   id:'published-analysis',name:'经营分析',kind:'analysis',mode:'grid',revision:3,status:'published',
   content:{mode:'grid',theme:'light',widgets:[widget('w1'),widget('w2')],
    dashboards:[{id:'all',name:'整体经营',widgetIds:['w1','w2'],jumps:{w1:'finance'}},
      {id:'finance',name:'财务专栏',widgetIds:['w2']}]}}
  const legacy={id:'legacy',name:'旧仪表板',kind:'dashboard',mode:'grid',revision:2,status:'published',
   content:{mode:'grid',theme:'light',widgets:[]}}
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   requests.push(method+' '+path)
   const send=(value,status=200)=>route.fulfill({status,contentType:'application/json',
     body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'csrf'})
   if(path==='/api/dashboards/published-analysis'&&method==='GET')return send(analysis)
   if(path==='/api/dashboards/legacy'&&method==='GET')return send(legacy)
   if(path==='/api/datasets')return send([])
   if(path==='/api/analysis/v2/query'&&method==='POST')return send({
    columns:[{id:'city',name:'d1',kind:'dimension',label:'城市'},
     {id:'total',name:'total',kind:'measure',label:'收入'}],
    rows:[{d1:'北京',total:120},{d1:'上海',total:80}],executionMs:3,truncated:false})
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/view/published-analysis',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-header').waitFor()
  assert.equal(await page.locator('.analysis-dashboard-card').count(),2)
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] canvas').waitFor()
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] canvas').click({position:{x:130,y:170}})
  assert.ok((await page.locator('.analysis-dashboard-card').first().innerText()).includes('指标w2'))
  await page.locator('.analysis-view-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'整体经营'}).first().click()
  assert.equal(await page.locator('.analysis-dashboard-card').count(),2)
  assert.equal(await page.locator('.analysis-published-view .analysis-primary').count(),0)
  await page.locator('.analysis-view-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务专栏'}).first().click()
  const cards=page.locator('.analysis-dashboard-card')
  assert.equal(await cards.count(),1)
  assert.ok((await cards.first().innerText()).includes('指标w2'))
  assert.ok(requests.includes('GET /api/dashboards/published-analysis'))
  await page.goto(origin+'/#/view/legacy',{waitUntil:'domcontentloaded'})
  await page.locator('.toolbar-left').waitFor()
  assert.equal(await page.locator('.analysis-published-view').count(),0)
  assert.equal(await page.locator('.toolbar-left input[disabled]').count(),1)
  assert.deepEqual(errors,[])
  console.log('PASS P1 published routes: multi-view V2 readonly and original legacy Designer readonly')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1 published routes',e.stack||e);process.exitCode=1})
