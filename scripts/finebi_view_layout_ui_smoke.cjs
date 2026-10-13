// P1-01 focused layout smoke: per-view sizes/order survive save, reload and publication.
// Browser events + mocked REST only. Does NOT touch production services or data.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
const widget=id=>({id,title:'组件'+id,type:'bar',datasetId:'',
 analysisV2:{dimensions:[],measures:[],filters:{pre:[],post:[]},sort:[],limit:200},
 dimension:'',measure:'',agg:'SUM',columns:[],style:{}})
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}})
  const errors=[],saves=[]
  let revision=1
  let content={mode:'grid',theme:'light',widgets:[widget('w1'),widget('w2')],
   dashboards:[{id:'overview',name:'整体经营',widgetIds:['w1','w2']},
    {id:'finance',name:'财务分析',widgetIds:['w2','w1']}]}
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(value,status=200)=>route.fulfill({status,contentType:'application/json',
    body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'mock-csrf'})
   if(path==='/api/dashboards/report'&&method==='GET')
    return send({id:'report',name:'视图布局',kind:'analysis',status:'published',mode:'grid',revision,content})
   if(path==='/api/dashboards/report'&&method==='PUT'){
    const body=req.postDataJSON()
    assert.equal(body.revision,revision)
    saves.push(body);content=structuredClone(body.content);revision++
    return send({revision})
   }
   if(path==='/api/datasets')return send([])
   return send({error:'Unexpected '+method+' '+path},404)
  })
  const order=()=>page.locator('.analysis-layout-card').evaluateAll(
   cards=>cards.map(c=>c.getAttribute('data-widget-id')))
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-header-actions button').filter({hasText:'仪表板预览'}).click()
  const picker=page.locator('.analysis-view-picker')
  await picker.click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务分析'}).click()
  assert.deepEqual(await order(),['w2','w1'])
  const w2=page.locator('.analysis-layout-card[data-widget-id="w2"]')
  assert.ok((await w2.getAttribute('style')).includes('380px'),'legacy default remains 380px')
  await w2.locator('button[title="切换组件宽度"]').click()
  assert.ok((await w2.getAttribute('style')).includes('span 12'))
  await w2.locator('button[title="切换组件高度"]').click()
  assert.ok((await w2.getAttribute('style')).includes('480px'))
  await w2.locator('button[title="组件后移"]').click()
  assert.deepEqual(await order(),['w1','w2'])
  await picker.click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'整体经营'}).click()
  assert.deepEqual(await order(),['w1','w2'])
  assert.ok((await page.locator('.analysis-layout-card[data-widget-id="w2"]').getAttribute('style')).includes('380px'))
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  const finance=saves[0].content.dashboards.find(d=>d.id==='finance')
  assert.deepEqual(finance.widgetIds,['w1','w2'])
  assert.deepEqual(finance.layout.w2,{columns:12,height:480})
  assert.equal(saves[0].content.dashboards.find(d=>d.id==='overview').layout,undefined)
  assert.deepEqual(saves[0].content.widgets.map(w=>w.id),['w1','w2'])

  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-header-actions button').filter({hasText:'仪表板预览'}).click()
  await picker.click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务分析'}).click()
  assert.ok((await page.locator('.analysis-layout-card[data-widget-id="w2"]').getAttribute('style')).includes('480px'))

  await page.goto(origin+'/#/view/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-header').waitFor()
  await page.locator('.analysis-published-header .analysis-view-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'财务分析'}).click()
  const published=page.locator('.analysis-published-view [data-widget-id="w2"]')
  assert.ok((await published.getAttribute('style')).includes('span 12'))
  assert.ok((await published.getAttribute('style')).includes('480px'))
  assert.equal(await page.locator('.analysis-published-view .analysis-layout-toolbar').count(),0)
  await page.setViewportSize({width:720,height:850})
  assert.equal(await published.evaluate(x=>getComputedStyle(x).gridColumnStart),'1')
  assert.deepEqual(errors,[])
  console.log('PASS P1-01 view layout: view independence, reorder, sizing, save/reload, published and responsive')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1 view layout',e.stack||e);process.exitCode=1})
