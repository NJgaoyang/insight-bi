// P1 pipeline CAST UI: explicit safe SQL step, preview/save/reload, mocked API only.
const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18216'
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1440,height:920}})
  const errors=[],previews=[],saves=[]
  let revision=0,steps=[]
  page.on('pageerror',e=>errors.push(e.message))
  const send=(route,value,status=200)=>route.fulfill({
   status,contentType:'application/json',body:JSON.stringify(value)})
  await page.route('**/api/**',route=>{
   const request=route.request(),path=new URL(request.url()).pathname,method=request.method()
   if(path==='/api/me')return send(route,{user:'admin',csrf:'csrf'})
   if(path==='/api/datasets'&&method==='GET')return send(route,[
    {id:'ds1',name:'财务数据',connection_id:'source'}])
   if(path==='/api/datasets/ds1/fields'&&method==='GET')return send(route,[
    {id:'amount',name:'金额',sourceColumn:'amount',logicalType:'DECIMAL',numeric:true,calculated:false,phase:'ROW'},
    {id:'city',name:'城市',sourceColumn:'city',logicalType:'STRING',numeric:false,calculated:false,phase:'ROW'}])
   if(path==='/api/datasets/ds1/pipeline'&&method==='GET')
    return send(route,{datasetId:'ds1',steps,revision,stale:false})
   if(path==='/api/datasets/ds1/pipeline/change-impact'&&method==='GET')
    return send(route,{revision,reports:[],token:'',requiresAcknowledgment:false})
   if(path==='/api/datasets/ds1/pipeline'&&method==='PUT'){
    const payload=request.postDataJSON()
    assert.equal(payload.revision,revision)
    saves.push(payload);steps=structuredClone(payload.steps);revision++
    return send(route,{revision,stale:false})
   }
   if(path==='/api/datasets/ds1/pipeline/preview'&&method==='POST'){
    const data=request.postDataJSON()
    previews.push(data)
    return send(route,{rows:[{amount:'100.00',step_placeholder:'100.00'}],
     columns:[{id:'amount',name:'amount',label:'金额',type:'DECIMAL',numeric:true}],
     truncated:false,executionMs:3})
   }
   return send(route,{error:'unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/pipeline/ds1',{waitUntil:'domcontentloaded'})
  await page.locator('.pipeline-header').waitFor()
  await page.locator('.pipeline-toolbar button').filter({hasText:'类型转换'}).click()
  const first=page.locator('.pipeline-step').first()
  await first.waitFor()
  assert.match(await first.innerText(),/类型转换/)
  assert.match(await first.innerText(),/文本转数值不允许直接转换/)
  await first.locator('button[title="预览至此步骤"]').click()
  await page.locator('.pipeline-result-head').getByText('截至第 1 步').waitFor()
  assert.equal(previews.length,1)
  assert.equal(previews[0].steps[0].type,'cast')
  assert.equal(previews[0].steps[0].sourceFieldId,'amount')
  assert.equal(previews[0].steps[0].outputType,'STRING')
  assert.notEqual(previews[0].steps[0].fieldId,'amount')
  await page.locator('.pipeline-save').click()
  await page.waitForFunction(()=>document.querySelector('.pipeline-save')?.disabled===true)
  assert.equal(saves.length,1)
  assert.equal(saves[0].steps[0].type,'cast')
  await page.reload({waitUntil:'domcontentloaded'})
  await first.waitFor()
  assert.equal(await page.locator('.pipeline-step').count(),1)
  assert.match(await first.innerText(),/类型转换/)
  assert.deepEqual(errors,[])
  console.log('PASS P1 cast editor: controlled source, preview, stable new field, save and reload')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1 pipeline cast editor',e.stack||e);process.exitCode=1})
