// Focused UI contract: duplicate, undo/redo and bounded prefix preview; all REST mocked.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18216'
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1440,height:900}})
  const errors=[],saves=[],previews=[]
  let revision=2
  let steps=[
   {id:'formula-original',type:'formula',fieldId:'profit_field',name:'利润',expression:'[revenue]-[cost]'},
   {id:'filter-original',type:'filter',fieldId:'profit_field',op:'gte',value:'10'},
   {id:'group-original',type:'group',dimensions:['city'],measures:[{
    id:'total_revenue',fieldId:'profit_field',aggregate:'SUM',name:'收入'}]}
  ]
  const send=(route,value,status=200)=>route.fulfill({
   status,contentType:'application/json',body:JSON.stringify(value)})
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()).pathname,method=req.method()
   if(url==='/api/me')return send(route,{user:'admin',csrf:'csrf'})
   if(url==='/api/datasets'&&method==='GET')
    return send(route,[{id:'ds1',name:'经营数据',connection_id:'conn'}])
   if(url==='/api/datasets/ds1/fields'&&method==='GET')
    return send(route,[
     {id:'revenue',name:'收入',sourceColumn:'revenue',logicalType:'DECIMAL',numeric:true,calculated:false,phase:'ROW'},
     {id:'cost',name:'成本',sourceColumn:'cost',logicalType:'DECIMAL',numeric:true,calculated:false,phase:'ROW'},
     {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',numeric:false,calculated:false,phase:'ROW'}])
   if(url==='/api/datasets/ds1/pipeline'&&method==='GET')
    return send(route,{datasetId:'ds1',steps,revision,stale:false})
   if(url==='/api/datasets/ds1/pipeline'&&method==='PUT'){
    const data=req.postDataJSON();assert.equal(data.revision,revision)
    saves.push(data);steps=structuredClone(data.steps);revision++
    return send(route,{revision,stale:false})
   }
   if(url==='/api/datasets/ds1/pipeline/preview'&&method==='POST'){
    const body=req.postDataJSON()
    previews.push(body)
    return send(route,{rows:[{city:'北京',revenue:'100.00'}],
     columns:[{id:'city',name:'city',label:'城市',type:'STRING',numeric:false},
      {id:'revenue',name:'revenue',label:'收入',type:'DECIMAL',numeric:true}],
     truncated:false})
   }
   return send(route,{error:'unexpected '+method+' '+url},404)
  })
  await page.goto(origin+'/#/pipeline/ds1',{waitUntil:'domcontentloaded'})
  const cards=page.locator('.pipeline-step')
  await cards.first().waitFor()
  assert.equal(await cards.count(),3)
  assert.equal(await cards.nth(2).getByRole('button',{name:'复制'}).isDisabled(),true)
  await cards.first().getByRole('button',{name:'复制'}).click()
  assert.equal(await cards.count(),4)
  await cards.nth(1).getByRole('button',{name:'预览到此步'}).click()
  await page.locator('.pipeline-result-head').getByText('截至第 2 步').waitFor()
  assert.equal(previews.length,1)
  assert.equal(previews[0].steps.length,2)
  assert.equal(previews[0].steps[1].type,'formula')
  assert.notEqual(previews[0].steps[0].fieldId,previews[0].steps[1].fieldId)
  assert.equal(previews[0].steps[1].expression,'[revenue]-[cost]')
  await page.locator('button[title="撤销加工编辑"]').click()
  assert.equal(await cards.count(),3)
  assert.equal(await page.locator('.pipeline-result-head').getByText('截至第 2 步').count(),0)
  await page.locator('button[title="重做加工编辑"]').click()
  assert.equal(await cards.count(),4)
  await page.locator('.pipeline-save').click()
  await page.waitForFunction(()=>document.querySelector('.pipeline-save')?.hasAttribute('disabled'))
  assert.equal(saves.length,1)
  assert.equal(saves[0].steps.length,4)
  assert.equal(saves[0].steps[1].type,'formula')
  await page.reload({waitUntil:'domcontentloaded'})
  await cards.first().waitFor()
  assert.equal(await cards.count(),4)
  assert.equal(await page.locator('button[title="撤销加工编辑"]').isDisabled(),true)
  // Removing an upstream formula must flag downstream stable field references.
  await cards.first().getByRole('button',{name:'删除'}).click()
  await page.locator('.pipeline-dependency-summary').waitFor()
  assert.ok(await page.locator('.pipeline-dependency-issue').count()>=2)
  assert.match(await page.locator('.pipeline-dependency-issue').first().innerText(),/profit_field/)
  assert.equal(await page.locator('.pipeline-save').isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'数据预览'}).isDisabled(),true)
  assert.equal(await cards.first().getByRole('button',{name:'预览到此步'}).isDisabled(),false,
   'a prefix before the invalid downstream step may still be previewed')
  await page.locator('button[title="撤销加工编辑"]').click()
  assert.equal(await cards.count(),4)
  assert.equal(await page.locator('.pipeline-dependency-issue').count(),0)
  assert.deepEqual(errors,[])
  console.log('PASS pipeline editing: prefix preview, safe formula clone, undo redo, save/reload')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL pipeline focused editor',e.stack||e);process.exitCode=1})
