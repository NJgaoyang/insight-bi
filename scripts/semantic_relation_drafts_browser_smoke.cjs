const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18225'
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:960}})
  let rows=[],requests=[],errors=[]
  page.on('pageerror',e=>errors.push(e.stack||e.message))
  const send=(r,v,status=200)=>r.fulfill({status,contentType:'application/json',body:JSON.stringify(v)})
  await page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method()
   if(p==='/api/me')return send(route,{user:'admin',csrf:'csrf'})
   if(p==='/api/datasets'&&m==='GET')return send(route,[
    {id:'ds1',name:'订单',connection_id:'local',folder_id:null,fields:[]},
    {id:'ds2',name:'城市',connection_id:'local',folder_id:null,fields:[]},
    {id:'ds3',name:'其他连接',connection_id:'other',folder_id:null,fields:[]}])
   if(p==='/api/model/relations')return send(route,[])
   if(p==='/api/model/semantic-relations'&&m==='GET')return send(route,rows)
   if(p==='/api/model/semantic-relations'&&m==='POST'){
    const data=req.postDataJSON();requests.push(data)
    assert.equal(data.sourceDatasetId,'ds1')
    assert.equal(data.targetDatasetId,'ds2')
    assert.deepEqual(data.pairs,[{leftFieldId:'city',rightFieldId:'region'}])
    rows=[{id:'draft1',...data,revision:1,status:'DRAFT',reason:''}]
    return send(route,rows[0])
   }
   if(p==='/api/model/semantic-relations/draft1/approve'&&m==='POST'){
    assert.equal(req.postDataJSON().revision,1)
    rows=[{...rows[0],status:'APPROVED'}]
    return send(route,rows[0])
   }
   if(p==='/api/model/semantic-relations/draft1'&&m==='PUT'){
    const data=req.postDataJSON()
    assert.equal(data.revision,1)
    rows=[{...rows[0],name:data.name,pairs:data.pairs,revision:2,status:'DRAFT'}]
    return send(route,rows[0])
   }
   if(p==='/api/model/semantic-relations/draft1'&&m==='DELETE'){
    assert.equal(url.searchParams.get('revision'),'2')
    rows=[];return send(route,{ok:true})
   }
   if(p==='/api/datasets/ds1/fields')return send(route,[
    {id:'city',name:'城市',logicalType:'STRING',calculated:false,hidden:false}])
   if(p==='/api/datasets/ds2/fields')return send(route,[
    {id:'region',name:'地区',logicalType:'STRING',calculated:false,hidden:false}])
   if(['/api/connections','/api/dashboards','/api/folders','/api/data-folders'].includes(p))
    return send(route,[])
   return send(route,{error:'Unexpected '+m+' '+p},404)
  })
  await page.goto(origin+'/#/datasets',{waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'模型关系'}).click()
  await page.waitForTimeout(1100)
  if(!await page.locator('.semantic-draft').count()){
    console.log('MODEL UI DEBUG',JSON.stringify({
     url:page.url(),errors,dialogs:await page.getByRole('dialog').allTextContents(),
     visible:(await page.locator('body').innerText()).slice(-1500),
     modelCount:await page.locator('.model-relations').count()
    }))
    throw new Error('The semantic draft panel did not mount')
  }
  await page.locator('.semantic-draft').waitFor()
  await page.getByRole('button',{name:'新建关系'}).click()
  const dialog=page.getByRole('dialog',{name:'新建语义关系草稿'})
  await dialog.waitFor()
  await dialog.locator('input[maxlength="100"]').fill('订单城市关系')
  const selects=dialog.locator('.semantic-draft-sources .el-select')
  // Scope each option lookup to its own combobox-controlled listbox. Old
  // Element Plus poppers can remain mounted during transitions.
  async function selectOption(select, label){
    await select.click()
    const input=select.locator('input[role="combobox"]')
    const controls=await input.getAttribute('aria-controls')
    assert.ok(controls,'select needs an accessible listbox id')
    const list=page.locator('[id="'+controls+'"]')
    const option=list.locator('[role="option"]').filter({hasText:label}).first()
    await option.waitFor({state:'visible',timeout:10000})
    await option.click()
    // Element Plus emits update:modelValue on a later Vue tick; the source
    // selector additionally waits for field catalog loading before committing.
    await select.locator('.el-select__selected-item').filter({hasText:label})
      .waitFor({state:'visible',timeout:10000})
  }
  await selectOption(selects.nth(0),'订单')
  await selectOption(selects.nth(1),'城市')
  const pair=dialog.locator('.semantic-draft-pair')
  await selectOption(pair.locator('.el-select').nth(0),'城市')
  await selectOption(pair.locator('.el-select').nth(1),'地区')
  const saveBtn=dialog.getByRole('button',{name:'保存草稿'})
  if(!await saveBtn.isEnabled()){
    console.log('DRAFT FIELDS DEBUG',JSON.stringify({
      sources:await selects.allTextContents(),
      pairs:await pair.locator('.el-select').allTextContents(),
      inputs:await dialog.locator('.el-select input').evaluateAll(nodes=>nodes.map(n=>({
        value:n.value,role:n.getAttribute('role'),expanded:n.getAttribute('aria-expanded')
      }))),
      errors,dialogs:(await dialog.innerText()).slice(0,1400)
    }))
    throw new Error('Draft fields not selected: save is disabled')
  }
  await saveBtn.click()
  await page.locator('.semantic-draft-item').waitFor()
  assert.match(await page.locator('.semantic-draft-item').innerText(),/草稿/)
  assert.equal(requests.length,1)
  await page.locator('.semantic-draft-item').getByRole('button',{name:'确认版本'}).click()
  const approval=page.locator('.el-message-box').last()
  await approval.waitFor()
  await approval.locator('.el-message-box__btns .el-button--primary').click()
  await page.getByText(/已确认 v1/).waitFor()
  assert.equal(await page.locator('.semantic-draft-item').getByRole('button',{name:'确认版本'}).count(),0)
  await page.locator('.semantic-draft-item').getByRole('button',{name:'编辑'}).click()
  const edit=page.getByRole('dialog',{name:'编辑语义关系草稿'})
  await edit.locator('input[maxlength="100"]').fill('城市映射更新')
  await edit.getByRole('button',{name:'保存草稿'}).click()
  await page.getByText('城市映射更新').waitFor()
  assert.match(await page.locator('.semantic-draft-item').innerText(),/v2/)
  assert.equal(await page.locator('.semantic-draft-item').getByRole('button',{name:'确认版本'}).count(),1)
  await page.locator('.semantic-draft-item').getByRole('button',{name:'删除'}).click()
  const confirm=page.locator('.el-message-box').last()
  await confirm.waitFor()
  await confirm.locator('.el-message-box__btns .el-button--primary').click()
  await page.getByText('尚未创建语义关系草稿。').waitFor()
  // Isolate the stale-approval UI contract from the original create/edit/delete flow.
  rows=[{id:'stale2',name:'源变化后的关系',sourceDatasetId:'ds1',targetDatasetId:'ds2',
   pairs:[{leftFieldId:'city',rightFieldId:'region'}],revision:3,status:'DRAFT',
   reason:'源 SQL、连接或字段定义已变化，请重新确认当前版本'}]
  await page.reload({waitUntil:'domcontentloaded'})
  // App tab state is ephemeral; reopen Model Relations after a hard reload.
  await page.getByRole('button',{name:'模型关系'}).click()
  await page.locator('.semantic-draft-item').waitFor()
  await page.getByText(/源 SQL、连接或字段定义已变化/).waitFor()
  assert.equal(await page.locator('.semantic-draft-item').getByRole('button',{name:'确认版本'}).count(),1)

  assert.deepEqual(errors,[])
  console.log('PASS semantic relationship draft: create/edit/CAS/delete, scoped fields, no SQL')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL semantic relation draft browser',e.stack||e);process.exitCode=1})
