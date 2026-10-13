// P0-04 formula UI smoke: isolated browser + API mocks; no production database.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||'/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell'
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'

;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1550,height:960}})
  const errors=[],requests=[],formulaWrites=[],saves=[]
  let version=1,calculated=null,content={mode:'grid',theme:'light',widgets:[]}
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname,method=req.method()
   const send=(value,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(value)})
   if(path==='/api/me')return send({user:'admin',csrf:'mock-csrf'})
   if(path==='/api/dashboards/analysis-a'&&method==='GET')
    return send({id:'analysis-a',name:'计算字段分析',kind:'analysis',revision:version,status:'draft',mode:'grid',content})
   if(path==='/api/datasets')return send([{id:'ds1',name:'销售数据集',connection_id:'conn',sql_body:'SELECT city,revenue,cost FROM sales',fields:[
    {name:'城市',type:'VARCHAR',numeric:false},{name:'收入',type:'DECIMAL',numeric:true},{name:'成本',type:'DECIMAL',numeric:true}]}])
   if(path==='/api/datasets/ds1/fields')return send([
    {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',role:'DIMENSION',numeric:false,calculated:false,phase:'ROW'},
    {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',role:'BOTH',numeric:true,calculated:false,phase:'ROW'},
    {id:'cost',name:'成本',sourceColumn:'cost',logicalType:'DECIMAL',role:'BOTH',numeric:true,calculated:false,phase:'ROW'},
    ...(calculated?[calculated]:[])])
   if(path==='/api/datasets/ds1/calculated-fields'&&method==='POST'){
    const body=req.postDataJSON();formulaWrites.push(body)
    if(body.expression.includes('CALL_OS'))return send({error:'不支持的公式函数: CALL_OS'},400)
    assert.equal(body.expression,'[revenue]-[cost]')
    calculated={id:'calc_profit',name:body.name,sourceColumn:null,logicalType:'DECIMAL',
     role:'MEASURE',numeric:true,calculated:true,phase:'ROW',expression:body.expression}
    return send({id:'calc_profit',phase:'ROW',outputType:'DECIMAL',revision:1})
   }
   if(path==='/api/analysis/v2/query'&&method==='POST'){
    const data=req.postDataJSON();requests.push(data)
    return send({rows:[{d1:'北京',m1:60}],columns:[
     {id:'city',name:'d1',label:'城市',type:'STRING',kind:'dimension'},
     {id:'m1',name:'m1',label:'行利润',type:'DECIMAL',kind:'measure'}],
     executionMs:4,truncated:false,warnings:[]})
   }
   if(path==='/api/dashboards/analysis-a'&&method==='PUT'){
    const body=req.postDataJSON();assert.equal(body.revision,version)
    saves.push(body);content=structuredClone(body.content);version++
    return send({revision:version})
   }
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/analysis/analysis-a')
  await page.locator('.analysis-add-tab').click()
  await page.locator('.analysis-source-selector .el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'销售数据集'}).first().click()
  await page.locator('.analysis-formula-add').filter({hasText:'计算字段'}).click()
  await page.locator('.v2-formula-dialog input').first().fill('行利润')
  await page.locator('.v2-formula-dialog textarea').fill('CALL_OS([revenue])')
  await page.locator('.el-dialog__footer .primary-button').click()
  await page.locator('.el-message--error').first().waitFor({timeout:8000})
  assert.equal(await page.locator('.el-dialog:visible').count(),1,'Invalid formula must keep editor open')
  await page.locator('.v2-formula-dialog textarea').fill('[revenue]-[cost]')
  await page.locator('.el-dialog__footer .primary-button').click()
  await page.locator('.el-dialog').first().waitFor({state:'hidden'})
  assert.equal(formulaWrites.length,2)
  await page.locator('.analysis-field').filter({hasText:'城市'}).first().click()
  await page.locator('.analysis-field').filter({hasText:'行利润'}).last().click()
  await page.waitForFunction(()=>document.querySelector('.v2-query-meta')?.textContent?.includes('1 行'))
  assert.equal(requests.at(-1).measures[0].fieldId,'calc_profit')
  await page.locator('.analysis-header-actions .analysis-primary').click()
  await page.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
  assert.equal(saves[0].content.widgets[0].analysisV2.measures[0].fieldId,'calc_profit')
  await page.reload()
  await page.locator('.analysis-shelf.v2-shelf .analysis-chip').first().waitFor()
  assert.equal(requests.at(-1).measures[0].fieldId,'calc_profit')
  assert.deepEqual(errors,[])
  console.log('PASS P0 formula UI: invalid formula error, valid save, reload field catalog, metric binding and reopen')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P0 formula UI',e.stack||e);process.exitCode=1})
