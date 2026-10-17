const assert=require('node:assert/strict')
const {chromium}=require('playwright-core')
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18225'
;(async()=>{
 const browser=await chromium.launch({args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:960}})
  let rows=[],requests=[],errors=[],profileRequests=0,accessReviews=0,releaseVersions=[],draftBound=null
  page.on('pageerror',e=>errors.push(e.stack||e.message))
  const send=(r,v,status=200)=>r.fulfill({status,contentType:'application/json',body:JSON.stringify(v)})
  await page.route('**/api/**',route=>{
   const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method()
   if(p==='/api/me')return send(route,{user:'admin',csrf:'csrf'})
   if(p==='/api/datasets'&&m==='GET')return send(route,[
    {id:'ds1',name:'订单',connection_id:'local',folder_id:null,fields:[]},
    {id:'ds2',name:'城市',connection_id:'local',folder_id:null,fields:[]},
    {id:'ds3',name:'其他连接',connection_id:'other',folder_id:null,fields:[]}])
   if(p==='/api/dashboards'&&m==='GET')
    return send(route,[{id:'dash1',name:'测试分析主题',kind:'analysis',revision:draftBound?2:1}])
   if(p==='/api/dashboards/dash1'&&m==='GET')
    return send(route,{id:'dash1',name:'测试分析主题',kind:'analysis',mode:'grid',
     revision:draftBound?2:1,content:{mode:'grid',theme:'light',
      widgets:[{id:'widget1',title:'订单图表',datasetId:'ds1',type:'bar',
       analysisV2:{dimensions:[],measures:[],filters:{pre:[],post:[]},sort:[],limit:200}}]}})
   if(p==='/api/dashboards/dash1'&&m==='PUT'){
    const data=req.postDataJSON()
    assert.equal(data.revision,1)
    assert.deepEqual(data.content.widgets[0].analysisV2.semanticRelation,
     {relationId:'draft1',readerId:'viewer1',releaseNo:1})
    draftBound=data
    return send(route,{revision:2})
   }
   if(p==='/api/governance/users')return send(route,[
    {id:'viewer1',display_name:'测试读者',role:'VIEWER',enabled:true},
    {id:'disabled',display_name:'禁用账号',role:'VIEWER',enabled:false}])
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
   if(p==='/api/model/semantic-relations/draft1/access-review'&&m==='POST'){
    accessReviews++
    assert.deepEqual(req.postDataJSON(),{revision:1,readerId:'viewer1',joinType:'LEFT',
     leftFieldIds:['city'],rightFieldIds:['region']})
    return send(route,{status:'READY_FOR_REVIEW',reason:'仅元数据审查，不授予权限'})
   }
   if(p==='/api/model/semantic-relations/draft1/access-review'&&m==='GET'){
    assert.equal(url.searchParams.get('readerId'),'viewer1')
    return send(route,{status:'READY_FOR_REVIEW',reason:'未发布'})
   }
   if(p==='/api/model/semantic-relations/draft1/release-snapshots'&&m==='POST'){
    assert.deepEqual(req.postDataJSON(),{
     revision:1,readerId:'viewer1',acknowledgeNotPublished:true})
    const releaseNo=releaseVersions.length+1
    releaseVersions.unshift({releaseNo,relationRevision:1,status:'SEALED_ONLY',
     reason:'仅候选封存，未正式发布'})
    return send(route,{releaseNo,status:'SEALED_ONLY',relationRevision:1})
   }
   if(p==='/api/model/semantic-relations/draft1/release-snapshots'&&m==='GET'){
    assert.equal(url.searchParams.get('readerId'),'viewer1')
    return send(route,releaseVersions)
   }
   if(p==='/api/model/semantic-relations/draft1/join-key-profile'&&m==='POST'){
    const data=req.postDataJSON();profileRequests++
    assert.deepEqual(data,{revision:1,acknowledgeSourceScan:true})
    return send(route,{sampledRows:5,nullKeyRows:1,keyGroups:2,duplicateGroups:1,
     extraRightRows:1,maxRightMultiplicity:2,hasDuplicateKeys:true,
     sampleCapReached:false,sampleLimit:2000,scope:'RIGHT_SOURCE_SAMPLE',
     exactSourceCount:false,exactJoinRowCount:false,warning:'仅代表右表样本'})
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
  await page.locator('.semantic-draft-item').getByRole('button',{name:'检查关联风险'}).click()
  const scanConfirm=page.locator('.el-message-box').last()
  await scanConfirm.waitFor()
  await scanConfirm.locator('.el-message-box__btns .el-button--primary').click()
  await page.getByText(/右表样本行数：5/).waitFor()
  assert.equal(profileRequests,1)
  await page.locator('.semantic-draft-item').getByRole('button',{name:'预发布访问审查'}).click()
  const accessDialog=page.getByRole('dialog',{name:'语义关系 · 预发布访问审查'})
  await accessDialog.waitFor()
  const readerSelect=accessDialog.locator('.el-select').first()
  await selectOption(readerSelect,'测试读者')
  await accessDialog.getByRole('button',{name:'生成审查记录'}).click()
  await accessDialog.getByText(/已记录预发布审查/).waitFor()
  assert.equal(accessReviews,1)
  await accessDialog.getByRole('button',{name:'检查已有状态'}).click()
  await accessDialog.getByText(/READY_FOR_REVIEW/).waitFor()
  await accessDialog.getByRole('button',{name:'封存候选版本'}).click()
  const sealing=page.locator('.el-message-box').last()
  await sealing.waitFor()
  await sealing.locator('.el-message-box__btns .el-button--primary').click()
  await accessDialog.getByText(/候选版本 v1 已封存/).waitFor()
  await accessDialog.getByText(/SEALED_ONLY/).waitFor()
  assert.equal(releaseVersions.length,1)
  await accessDialog.getByRole('button',{name:'绑定分析主题草稿'}).click()
  const targetSelects=accessDialog.locator('.semantic-draft-bind .el-select')
  await selectOption(targetSelects.nth(0),'测试分析主题')
  await selectOption(targetSelects.nth(1),'订单图表')
  await accessDialog.getByRole('button',{name:'保存草稿绑定'}).click()
  await accessDialog.getByText(/已绑定分析主题草稿 v2/).waitFor()
  assert.ok(draftBound)
  await accessDialog.getByRole('button',{name:'关闭'}).click()
  await page.locator('.semantic-draft-item').getByRole('button',{name:'编辑'}).click()
  const edit=page.getByRole('dialog',{name:'编辑语义关系草稿'})
  await edit.locator('input[maxlength="100"]').fill('城市映射更新')
  await edit.getByRole('button',{name:'保存草稿'}).click()
  await page.getByText('城市映射更新').waitFor()
  assert.match(await page.locator('.semantic-draft-item').innerText(),/v2/)
  assert.equal(await page.locator('.semantic-draft-item').getByRole('button',{name:'确认版本'}).count(),1)
  assert.equal(await page.locator('.semantic-draft-item').getByRole('button',{name:'检查关联风险'}).count(),0)
  assert.equal(await page.locator('.semantic-draft-item').getByRole('button',{name:'预发布访问审查'}).count(),0)
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
