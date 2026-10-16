// Focused browser test: analysis dashboard is the same shared-widget workspace.
// All REST calls are mocked. No real database, credentials or deployments.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18212'
const widget=id=>({
 id,title:'组件'+id,type:'bar',datasetId:'',dimension:'',measure:'',agg:'SUM',columns:[],
 style:{},analysisV2:{dimensions:[],measures:[],filters:{pre:[],post:[]},sort:[],limit:200}
})
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:920}})
  const errors=[],updates=[]
  let revision=1
  let content={mode:'grid',theme:'light',widgets:[widget('w1'),widget('w2'),widget('w3')],
   dashboards:[
    {id:'overview',name:'经营总览',widgetIds:['w1','w2','w3'],
     layout:{w1:{columns:12,height:480}}},
    {id:'finance',name:'财务视图',widgetIds:['w2','w1']}]
  }
  const send=(route,value,status=200)=>route.fulfill({status,
   contentType:'application/json',body:JSON.stringify(value)})
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()).pathname,method=req.method()
   if(url==='/api/me')return send(route,{user:'admin',csrf:'csrf'})
   if(url==='/api/dashboards/report'&&method==='GET')
    return send(route,{id:'report',name:'统一工作台',mode:'grid',status:'published',
     kind:'analysis',revision,content})
   if(url==='/api/dashboards/report'&&method==='PUT'){
    const payload=req.postDataJSON()
    assert.equal(payload.revision,revision)
    updates.push(payload)
    content=structuredClone(payload.content)
    revision++
    return send(route,{revision})
   }
   if(url==='/api/dashboards/report/approval')return send(route,{required:false,state:'DISABLED'})
   if(url==='/api/datasets')return send(route,[])
   return send(route,{error:'Unexpected '+method+' '+url},404)
  })
  const order=()=>page.locator('.analysis-view-grid .analysis-layout-card').evaluateAll(
   cards=>cards.map(card=>card.getAttribute('data-widget-id')))
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'仪表板预览'}).click()
  assert.deepEqual(await order(),['w1','w2','w3'])
  const drag=page.locator('.analysis-layout-card[data-widget-id="w3"] .analysis-widget-drag-handle')
  await drag.dragTo(page.locator('.analysis-layout-card[data-widget-id="w1"]'))
  assert.deepEqual(await order(),['w3','w1','w2'])
  // Pure keyboard operation, including view-local layout size.
  const grip=page.locator('.analysis-layout-card[data-widget-id="w1"] .analysis-widget-drag-handle')
  await grip.focus()
  await grip.press('Home')
  assert.deepEqual(await order(),['w1','w3','w2'])
  await grip.press('End')
  assert.deepEqual(await order(),['w3','w2','w1'])
  await grip.press('ArrowUp')
  assert.deepEqual(await order(),['w3','w1','w2'])
  await grip.press('Shift+ArrowLeft')
  assert.ok((await page.locator('[data-widget-id="w1"]').getAttribute('style')).includes('span 6'))
  await grip.press('Shift+ArrowRight')
  await grip.press('Shift+ArrowDown')
  assert.ok((await page.locator('[data-widget-id="w1"]').getAttribute('style')).includes('640px'))
  await grip.press('Shift+ArrowUp')
  assert.ok((await page.locator('[data-widget-id="w1"]').getAttribute('style')).includes('480px'))
  assert.match(await page.locator('[role="status"][aria-live="polite"]').innerText(),/尺寸/)
  await page.locator('.analysis-layout-card[data-widget-id="w3"] button[title="进入组件分析编辑"]').click()
  await page.locator('.analysis-stage-title strong').getByText('组件w3').waitFor()
  await page.getByRole('button',{name:'仪表板预览'}).click()

  await page.locator('button[title="复制仪表板视图"]').click()
  assert.deepEqual(await order(),['w3','w1','w2'])
  // ElSelect internals vary between Element Plus versions; assert the
  // actual selected view via persisted view-local layout below instead.
  assert.equal(await page.locator('.analysis-view-picker').count(),1)
  const before=await page.locator('.analysis-layout-card[data-widget-id="w1"]').getAttribute('style')
  assert.ok(before.includes('span 12'))
  await page.locator('.analysis-layout-card[data-widget-id="w1"] button[title="切换组件宽度"]').click()
  const after=await page.locator('.analysis-layout-card[data-widget-id="w1"]').getAttribute('style')
  assert.ok(after.includes('span 6'))

  await page.locator('.analysis-header-actions button.analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(updates.length,1)
  const views=updates[0].content.dashboards
  assert.equal(views.length,3)
  const overview=views.find(v=>v.id==='overview'),copied=views.find(v=>v.id!==overview.id&&v.name==='经营总览 副本')
  assert.deepEqual(overview.widgetIds,['w3','w1','w2'])
  assert.deepEqual(copied.widgetIds,['w3','w1','w2'])
  assert.deepEqual(overview.layout.w1,{columns:12,height:480})
  assert.deepEqual(copied.layout.w1,{columns:6,height:480})
  assert.deepEqual(updates[0].content.widgets.map(w=>w.id),['w1','w2','w3'])

  await page.reload({waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'仪表板预览'}).click()
  assert.deepEqual(await order(),['w3','w1','w2'])
  assert.ok((await page.locator('.analysis-layout-card[data-widget-id="w1"]').getAttribute('style')).includes('span 12'))
  await page.goto(origin+'/#/view/report',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-header').waitFor()
  assert.deepEqual(await page.locator('.analysis-published-view [data-widget-id]').evaluateAll(
    cards=>cards.map(c=>c.getAttribute('data-widget-id'))),['w3','w1','w2'])
  assert.equal(await page.locator('.analysis-published-view .analysis-widget-drag-handle').count(),0)
  assert.deepEqual(errors,[])
  console.log('PASS unified analysis workspace: drag/copy/edit/save/reload/published layout')
 }finally{await browser.close()}
})().catch(err=>{console.error('FAIL unified workspace browser',err.stack||err);process.exitCode=1})
