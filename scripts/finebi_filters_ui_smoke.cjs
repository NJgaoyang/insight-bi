// P0-04 focused filter editor smoke with synthetic REST replies. No production calls.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')
}})()
const executablePath=process.env.CHROMIUM_BIN||
 '/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1500,height:1050}})
  const errors=[],queries=[],saves=[]
  let revision=1,content={mode:'grid',theme:'light',widgets:[]}
  page.on('pageerror',error=>errors.push(error.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(v,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(v)})
   if(path==='/api/me')return send({user:'admin',csrf:'test-csrf'})
   if(path==='/api/dashboards/analysis-a'&&method==='GET')
    return send({id:'analysis-a',name:'过滤分析',kind:'analysis',mode:'grid',
      status:'draft',revision,content})
   if(path==='/api/dashboards/analysis-a'&&method==='PUT'){
    const body=req.postDataJSON();assert.equal(body.revision,revision)
    saves.push(body);content=structuredClone(body.content);revision++
    return send({revision})
   }
   if(path==='/api/datasets')return send([{id:'ds1',name:'销售数据集',
    connection_id:'readonly',sql_body:'SELECT city,revenue FROM sales',fields:[
      {name:'城市',type:'VARCHAR',numeric:false},{name:'收入',type:'DECIMAL',numeric:true}]}])
   if(path==='/api/datasets/ds1/fields')return send([
    {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',role:'DIMENSION',
     numeric:false,calculated:false,phase:'ROW'},
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',role:'BOTH',
     numeric:true,calculated:false,phase:'ROW'}])
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    queries.push(req.postDataJSON())
    return send({rows:[{d1:'北京',m1:300}],columns:[
     {id:'city',name:'d1',label:'城市',type:'STRING',kind:'dimension'},
     {id:'m1',name:'m1',label:'收入',type:'DECIMAL',kind:'measure'}],
     executionMs:3,truncated:false,warnings:[]})
   }
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/analysis-a',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-add-tab').click()
  await page.locator('.analysis-source-selector .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'销售数据集'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'城市'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'收入'}).last().click()
  const editor=page.locator('.v2-filter-editor')
  await editor.waitFor()
  const selects=editor.locator('.el-select')
  await selects.nth(0).click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'城市'}).first().click()
  await selects.nth(1).click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'包含'}).first().click()
  await editor.locator('input[placeholder="输入过滤值"]').first().fill('北京')
  await editor.locator('button').filter({hasText:'添加明细过滤'}).click()
  assert.equal(await editor.locator('.v2-applied-filter').count(),1)
  assert.equal(queries.at(-1).filters.pre[0].op,'contains')
  assert.equal(queries.at(-1).filters.pre[0].value,'北京')
  await selects.nth(1).click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'包含任一'}).first().click()
  await editor.locator('input[placeholder*="英文逗号"]').first().fill('北京,上海')
  await editor.locator('button').filter({hasText:'添加明细过滤'}).click()
  assert.deepEqual(queries.at(-1).filters.pre[1].values,['北京','上海'])
  await selects.nth(1).click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'为空'}).first().click()
  await editor.locator('button').filter({hasText:'添加明细过滤'}).click()
  assert.equal(queries.at(-1).filters.pre[2].op,'isNull')
  assert.equal(Object.hasOwn(queries.at(-1).filters.pre[2],'value'),false)
  await editor.locator('button[aria-label="移除明细过滤 1"]').click()
  assert.equal(queries.at(-1).filters.pre.length,2)

  await selects.nth(2).click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'收入'}).first().click()
  await editor.locator('input[placeholder="输入过滤值"]').last().fill('150')
  await editor.locator('button').filter({hasText:'添加结果过滤'}).click()
  assert.equal(queries.at(-1).filters.post[0].op,'gte')
  assert.equal(queries.at(-1).filters.post[0].value,'150')
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves.length,1)
  assert.equal(saves[0].content.widgets[0].analysisV2.filters.pre.length,2)
  assert.equal(saves[0].content.widgets[0].analysisV2.filters.post.length,1)
  await page.reload({waitUntil:'domcontentloaded'})
  await editor.locator('.v2-applied-filter').first().waitFor()
  assert.equal(await editor.locator('.v2-applied-filter').count(),3)
  assert.deepEqual(queries.at(-1).filters.pre[0].values,['北京','上海'])
  assert.equal(queries.at(-1).filters.post[0].value,'150')
  assert.deepEqual(errors,[])
  console.log('PASS P0 filter editor: contains, IN, IS NULL, remove, HAVING, save/reload')
 }finally{await browser.close()}
})().catch(error=>{console.error('FAIL P0 filter editor',error.stack||error);process.exitCode=1})
