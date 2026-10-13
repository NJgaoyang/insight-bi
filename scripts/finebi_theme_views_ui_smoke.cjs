// P1-01 focused browser test: one theme, multiple dashboard views sharing components.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:930}})
  const errors=[],saves=[]
  let revision=1,content={mode:'grid',theme:'light',widgets:[]}
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(value,status=200)=>route.fulfill({
    status,contentType:'application/json',body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'mock-csrf'})
   if(path==='/api/dashboards/report'&&method==='GET')
    return send({id:'report',name:'多视图分析主题',kind:'analysis',status:'draft',mode:'grid',revision,content})
   if(path==='/api/datasets')return send([])
   if(path==='/api/dashboards/report'&&method==='PUT'){
    const body=req.postDataJSON()
    assert.equal(body.revision,revision)
    assert.equal(req.headers()['x-csrf-token'],'mock-csrf')
    saves.push(body);content=structuredClone(body.content);revision++
    return send({revision})
   }
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/report',{waitUntil:'domcontentloaded'})
  const add=page.locator('.analysis-add-tab')
  await add.waitFor()
  await add.click();await add.click()
  await page.locator('.analysis-header-actions button').filter({hasText:'仪表板预览'}).click()
  const cards=page.locator('.analysis-dashboard-card')
  assert.equal(await cards.count(),2,'Legacy theme defaults to one view containing all widgets')
  await page.locator('button[title="新建仪表板"]').click()
  assert.equal(await cards.count(),0)
  const viewPicker=page.locator('.analysis-view-picker')
  assert.ok((await viewPicker.innerText()).includes('仪表板2'))
  await page.locator('.analysis-view-components-picker').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'组件1'}).first().click()
  assert.equal(await cards.count(),1)
  assert.ok((await cards.first().innerText()).includes('组件1'))
  // Rename the second view and ensure it does not alter underlying widget IDs.
  await page.locator('button[title="重命名仪表板"]').click()
  await page.locator('.el-message-box__input input').fill('成本明细视图')
  await page.locator('.el-message-box__btns .el-button--primary').click()
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  assert.equal(saves[0].content.widgets.length,2)
  assert.equal(saves[0].content.dashboards.length,2)
  assert.deepEqual(saves[0].content.dashboards[1].widgetIds,[saves[0].content.widgets[0].id])
  assert.equal(saves[0].content.dashboards[1].name,'成本明细视图')
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-add-tab').waitFor()
  await page.locator('.analysis-header-actions button').filter({hasText:'仪表板预览'}).click()
  assert.equal(await cards.count(),2)
  await viewPicker.click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'成本明细视图'}).first().click()
  assert.equal(await cards.count(),1)
  await page.locator('button[title="删除仪表板"]').click()
  await page.locator('.el-message-box__btns .el-button--primary').click()
  assert.equal(await cards.count(),2,'Deleting a view does not delete reusable components')
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.at(-1).content.widgets.length,2)
  assert.equal(saves.at(-1).content.dashboards.length,1)
  assert.deepEqual(errors,[])
  console.log('PASS P1-01 multi-dashboard views: create, select widgets, rename, persist, reload, delete')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1-01 theme views',e.stack||e);process.exitCode=1})
