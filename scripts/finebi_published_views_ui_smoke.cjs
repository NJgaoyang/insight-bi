// P1 focused read-only published analysis view and legacy dashboard routing smoke.
const assert=require('node:assert/strict')
const {chromium}=(()=>{try{return require('playwright-core')}catch{
 return require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')}})()
const executablePath=process.env.CHROMIUM_BIN||chromium.executablePath()
const origin=process.env.P0_PREVIEW_URL||'http://127.0.0.1:18117'
;(async()=>{
 const browser=await chromium.launch({executablePath,args:['--no-sandbox']})
 try{
  const page=await browser.newPage({viewport:{width:1480,height:900}})
  const errors=[],requests=[]
  const provisioned=[],preconfiguredGrants=[]
  const departments=[]
  const departmentGrants=[]
  const rowPolicies=[]
  let sessionMode='admin'
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
   if(path==='/api/me')return sessionMode==='admin'
    ?send({user:'admin',csrf:'csrf'}):send({error:'Unauthorized'},401)
   if(path==='/api/logout'&&method==='POST'){sessionMode='none';return send({ok:true})}
   if(path==='/api/reader/login'&&method==='POST'){
    assert.equal(req.postDataJSON().username,'analyst01')
    assert.equal(req.postDataJSON().password,'the-long-reader-password')
    sessionMode='reader'
    return send({user:'analyst01',role:'VIEWER',csrf:'reader-csrf'})
   }
   if(path==='/api/reader/me'&&method==='GET')
    return sessionMode==='reader'?send({user:'analyst01',role:'VIEWER',csrf:'reader-csrf'}):send({error:'Unauthorized'},401)
   if(path==='/api/reader/logout'&&method==='POST'){
    assert.equal(req.headers()['x-csrf-token'],'reader-csrf')
    sessionMode='none';return send({ok:true})
   }
   if(path==='/api/reader/catalog'&&method==='GET')
    return send([{id:'published-analysis',name:'经营分析',kind:'analysis',status:'published'}])
   if(path==='/api/reader/dashboards/published-analysis'&&method==='GET')
    return send({...analysis,content:{...analysis.content,widgets:[widget('w1')],
     dashboards:[{id:'all',name:'整体经营',widgetIds:['w1']}]}})
   if(path==='/api/reader/dashboards/published-analysis/widgets/w1/query'&&method==='POST'){
    assert.deepEqual(req.postDataJSON(),{})
    assert.equal(req.headers()['x-csrf-token'],'reader-csrf')
    return send({columns:[{id:'city',name:'d1',kind:'dimension',label:'城市'},
     {id:'total',name:'total',kind:'measure',label:'收入'}],
     rows:[{d1:'北京',total:120},{d1:'上海',total:80}],executionMs:3,truncated:false})
   }
   if(method==='GET'&&path==='/api/connections')return send([])
   if(method==='GET'&&path==='/api/folders')return send([])
   if(method==='GET'&&path==='/api/data-folders')return send([])
   if(method==='GET'&&path==='/api/dashboards')
     return send([{id:'published-analysis',name:'经营分析',status:'published',kind:'analysis',folder_id:null}])
   if(method==='GET'&&path==='/api/governance/departments')return send(departments)
   if(method==='POST'&&path==='/api/governance/departments'){
     const body=req.postDataJSON()
     assert.equal(req.headers()['x-csrf-token'],'csrf')
     departments.push({id:'dept1',name:body.name,parent_id:body.parentId||null})
     return send({...departments[0]})
   }
   if(method==='PUT'&&path==='/api/governance/users/u1/department'){
     assert.equal(req.headers()['x-csrf-token'],'csrf')
     assert.equal(req.postDataJSON().departmentId,'dept1')
     provisioned[0].department_id='dept1'
     return send({id:'u1',department_id:'dept1'})
   }
   if(method==='GET'&&path==='/api/governance/departments/dept1/grants')
    return send(departmentGrants)
   if(method==='POST'&&path==='/api/governance/departments/dept1/grants'){
    assert.equal(req.headers()['x-csrf-token'],'csrf')
    assert.deepEqual(req.postDataJSON(),{dashboardId:'published-analysis'})
    departmentGrants.push({dashboard_id:'published-analysis',permission:'READ_PUBLISHED'})
    return send({departmentId:'dept1',dashboardId:'published-analysis',
      permission:'READ_PUBLISHED',alreadyGranted:false})
   }
   if(method==='GET'&&path==='/api/governance/users')return send(provisioned)
   if(method==='POST'&&path==='/api/governance/users'){
     const body=req.postDataJSON()
     assert.equal(req.headers()['x-csrf-token'],'csrf')
     assert.equal(body.role,'VIEWER')
     provisioned.push({id:'u1',username:body.username,display_name:body.displayName,role:body.role,enabled:false})
     return send({id:'u1',username:body.username,role:body.role,enabled:false})
   }
   if(method==='PUT'&&path==='/api/governance/users/u1/password'){
     assert.equal(req.headers()['x-csrf-token'],'csrf')
     assert.equal(req.postDataJSON().password,'the-long-reader-password')
     provisioned[0].enabled=false;provisioned[0].password_configured=true
     return send({id:'u1',enabled:false,passwordConfigured:true})
   }
   if(method==='PUT'&&path==='/api/governance/users/u1/activation'){
     assert.equal(req.headers()['x-csrf-token'],'csrf')
     assert.equal(req.postDataJSON().enabled,true)
     provisioned[0].enabled=true
     return send({id:'u1',enabled:true})
   }
   if(method==='GET'&&path==='/api/governance/users/u1/row-policies')return send(rowPolicies)
   if(method==='GET'&&path==='/api/datasets/ds1/fields')
    return send([{id:'city-field',name:'城市',logicalType:'STRING',calculated:false},
      {id:'revenue-field',name:'收入',logicalType:'DECIMAL',calculated:false}])
   if(method==='PUT'&&path==='/api/governance/users/u1/row-policies/ds1'){
     assert.equal(req.headers()['x-csrf-token'],'csrf')
     assert.deepEqual(req.postDataJSON(),{fieldId:'city-field',allowedValue:'北京'})
     rowPolicies.splice(0,rowPolicies.length,
       {dataset_id:'ds1',field_id:'city-field',allowed_value:'北京'})
     return send({datasetId:'ds1',fieldId:'city-field',allowedValue:'北京'})
   }
   if(method==='GET'&&path==='/api/governance/users/u1/grants')return send(preconfiguredGrants)
   if(method==='POST'&&path==='/api/governance/users/u1/grants'){
     assert.equal(req.postDataJSON().dashboardId,'published-analysis')
     preconfiguredGrants.push({dashboard_id:'published-analysis',permission:'READ_PUBLISHED'})
     return send({userId:'u1',dashboardId:'published-analysis',permission:'READ_PUBLISHED',alreadyGranted:false})
   }
   if(path==='/api/dashboards/published-analysis'&&method==='GET')return send(analysis)
   if(path==='/api/dashboards/legacy'&&method==='GET')return send(legacy)
   if(path==='/api/dashboards/audit-demo'&&method==='GET')
    return send({...analysis,id:'audit-demo',status:'draft',
      content:{mode:'grid',theme:'light',widgets:[]}})
   if(path==='/api/dashboards/audit-demo/publication-audit'&&method==='GET')
    return send([{event_type:'PUBLISH',actor:'admin',version_no:2,
      created_at:'2026-10-15T09:00:00'}, {event_type:'UNPUBLISH',actor:'admin',
      version_no:null,created_at:'2026-10-15T09:30:00'}])
   if(path==='/api/datasets')return send([{id:'ds1',name:'经营数据集',
     connection_id:'',sql_body:'',fields:[]}])
   if(path==='/api/analysis/v2/query'&&method==='POST')return send({
    columns:[{id:'city',name:'d1',kind:'dimension',label:'城市'},
     {id:'total',name:'total',kind:'measure',label:'收入'}],
    rows:[{d1:'北京',total:120},{d1:'上海',total:80}],executionMs:3,truncated:false})
   return send({error:'Unexpected '+method+' '+path},404)
  })
  await page.goto(origin+'/#/view/published-analysis',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-published-header').waitFor()
  assert.equal(await page.locator('.analysis-dashboard-card').count(),2)
  // CSV is built from the real V2 result set, including the UTF-8 BOM.
  const csvDownload=page.waitForEvent('download')
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"]')
    .getByRole('button',{name:/导出当前查询结果CSV/}).click()
  const downloaded=await csvDownload
  assert.match(downloaded.suggestedFilename(),/\.csv$/)
  const stream=await downloaded.createReadStream()
  const chunks=[]
  for await(const chunk of stream)chunks.push(chunk)
  const csv=Buffer.concat(chunks).toString('utf8')
  assert.ok(csv.startsWith('\uFEFF'))
  assert.ok(csv.includes('北京')&&csv.includes('上海'))
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
  // Linked filter: no navigation target; same dataset/dimension receives
  // a parameterized eq filter, while source widget does not filter itself.
  analysis.content.dashboards[0].jumps={}
  analysis.content.widgets[1]={...widget('w2'),datasetId:'ds1',
    analysisV2:{dimensions:[{fieldId:'city'}],
      measures:[{id:'total',fieldId:'revenue',aggregate:'SUM'}],
      filters:{pre:[],post:[]},sort:[],limit:200}}
  const linkedRequests=[]
  page.on('request',request=>{
    if(new URL(request.url()).pathname==='/api/analysis/v2/query'&&request.method()==='POST')
      linkedRequests.push(request.postDataJSON())
  })
  // Revisit the SAME hash route with a full reload so the fixture is fetched
  // again rather than preserving the previous SPA view selection.
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] canvas').waitFor()
  await page.locator('.analysis-dashboard-card[data-widget-id="w2"] canvas').waitFor()
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] canvas').click({position:{x:130,y:170}})
  await page.locator('.analysis-view-components').filter({hasText:'已联动筛选'}).waitFor()
  await page.waitForTimeout(200)
  assert.ok(linkedRequests.some(q=>q.filters?.pre?.some(f=>f.fieldId==='city'&&f.op==='eq'&&['北京','上海'].includes(f.value))),
    'linked widget must receive a typed dimension equality filter')
  await page.getByRole('button',{name:'清除筛选'}).click()
  await page.locator('.analysis-view-components').filter({hasText:'已联动筛选'}).waitFor({state:'hidden'})
  // Advanced table controls use the returned rows, not a new SQL query.
  analysis.content.widgets[1].type='table'
  await page.reload({waitUntil:'domcontentloaded'})
  const detail=page.locator('.analysis-dashboard-card[data-widget-id="w2"]')
  await detail.locator('tbody tr').first().waitFor()
  assert.equal(await detail.locator('tbody tr').count(),2)
  await detail.getByRole('textbox',{name:'搜索当前表格结果'}).fill('上海')
  assert.equal(await detail.locator('tbody tr').count(),1)
  assert.ok((await detail.locator('tbody').innerText()).includes('上海'))
  await detail.locator('details summary').click()
  const cityToggle=detail.locator('details label').filter({hasText:'城市'}).locator('input')
  await cityToggle.uncheck()
  assert.equal(await detail.locator('thead th').count(),1)
  await detail.getByRole('textbox',{name:'搜索当前表格结果'}).fill('')
  // A published snapshot preserves its default hidden columns after reload.
  // Readers may toggle visibility temporarily, but cannot mutate saved config.
  analysis.content.widgets[1].analysisV2.tableHiddenColumnIds=['city']
  await page.reload({waitUntil:'domcontentloaded'})
  const savedTable=page.locator('.analysis-dashboard-card[data-widget-id="w2"]')
  await savedTable.locator('tbody tr').first().waitFor()
  assert.deepEqual(await savedTable.locator('thead th').allInnerTexts(),['收入'])
  await savedTable.locator('details summary').click()
  const savedCity=savedTable.locator('details label').filter({hasText:'城市'}).locator('input')
  assert.equal(await savedCity.isChecked(),false)
  await savedCity.check()
  assert.equal(await savedTable.locator('thead th').count(),2)
  assert.deepEqual(analysis.content.widgets[1].analysisV2.tableHiddenColumnIds,['city'],
    'published reader should not rewrite the saved column configuration')
  await page.reload({waitUntil:'domcontentloaded'})
  const restoredTable=page.locator('.analysis-dashboard-card[data-widget-id="w2"]')
  await restoredTable.locator('tbody tr').first().waitFor()
  assert.deepEqual(await restoredTable.locator('thead th').allInnerTexts(),['收入'])
  const persistedCsvDownload=page.waitForEvent('download')
  await restoredTable.getByRole('button',{name:/导出当前查询结果CSV/}).click()
  const persistedCsv=await persistedCsvDownload
  const persistedStream=await persistedCsv.createReadStream()
  const persistedChunks=[]
  for await(const chunk of persistedStream)persistedChunks.push(chunk)
  assert.equal(Buffer.concat(persistedChunks).toString('utf8').replace(/^\uFEFF/,'').split('\r\n')[0],
    '"收入"')
  assert.ok(requests.includes('GET /api/dashboards/published-analysis'))
  await page.goto(origin+'/#/view/legacy',{waitUntil:'domcontentloaded'})
  await page.locator('.toolbar-left').waitFor()
  assert.equal(await page.locator('.analysis-published-view').count(),0)
  assert.equal(await page.locator('.toolbar-left input[disabled]').count(),1)
  // P2 admin publication audit: populated table in the theme editor.
  await page.goto(origin+'/#/analysis/audit-demo',{waitUntil:'domcontentloaded'})
  await page.locator('.analysis-header-actions').getByRole('button',{name:'发布记录'}).click()
  const publicationDialog=page.locator('.el-dialog').filter({hasText:'发布记录'})
  await publicationDialog.getByText('撤销发布').waitFor()
  assert.ok((await publicationDialog.innerText()).includes('发布'))
  assert.ok((await publicationDialog.innerText()).includes('admin'))
  assert.ok((await publicationDialog.innerText()).includes('2026-10-15'))
  assert.equal(await publicationDialog.locator('tbody tr').count(),2)
  // P2-02: Admin creates a disabled user and preconfigures published-only access.
  await page.goto(origin+'/#/connections',{waitUntil:'domcontentloaded'})
  await page.getByRole('button',{name:'权限治理'}).click()
  await page.getByRole('button',{name:'＋ 预配置用户'}).click()
  const createUserDialog=page.locator('.el-dialog').filter({hasText:'预配置普通账号'})
  await createUserDialog.getByPlaceholder('例如 analyst01').fill('analyst01')
  await createUserDialog.locator('input').nth(1).fill('分析查看者')
  await createUserDialog.getByRole('button',{name:'创建预配置账号'}).click()
  await page.getByText('analyst01').waitFor()
  assert.equal(provisioned[0].enabled,false)
  // Department membership does not enable login or grant report access.
  await page.getByRole('button',{name:'＋ 新建部门'}).click()
  const deptDialog=page.locator('.el-dialog').filter({hasText:'新建部门'})
  await deptDialog.getByPlaceholder('例如：研发部').fill('研发部')
  await deptDialog.getByRole('button',{name:'保存部门'}).click()
  // Target the actual department section; other tables can precede it
  // when directory and authorization modules are rendered.
  const departmentSection=page.locator('section.resource-table')
    .filter({has:page.getByText('部门管理',{exact:true})})
  await departmentSection.getByText('研发部',{exact:true}).waitFor()
  // Department read grants must be configured independently of direct user grants.
  const orgRow=departmentSection.locator('tbody tr').filter({hasText:'研发部'})
  await orgRow.getByRole('button',{name:'报表授权'}).click()
  const departmentGrantDialog=page.locator('.el-dialog').filter({hasText:'部门报表授权'})
  await departmentGrantDialog.locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'经营分析'}).first().click()
  await departmentGrantDialog.getByRole('button',{name:'添加授权'}).click()
  await departmentGrantDialog.getByText('本部门及下级部门 · 只读').waitFor()
  assert.equal(departmentGrants.length,1)
  assert.equal(preconfiguredGrants.length,0)
  await page.keyboard.press('Escape')

  const createdUser=page.locator('tbody tr').filter({hasText:'analyst01'})
  await createdUser.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'研发部'}).first().click()
  await page.waitForFunction(()=>document.body.textContent?.includes('研发部'))
  assert.equal(provisioned[0].department_id,'dept1')
  assert.equal(provisioned[0].enabled,false)
  assert.equal(preconfiguredGrants.length,0)
  // Scoped row policy set by the admin, never sent as a viewer override.
  await createdUser.getByRole('button',{name:'数据行限制'}).click()
  const rowDialog=page.locator('.el-dialog').filter({hasText:'数据行限制 · analyst01'})
  await rowDialog.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'经营数据集'}).first().click()
  await rowDialog.locator('.el-select').nth(1).click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'城市'}).first().click()
  await rowDialog.getByPlaceholder('例如：北京').fill('北京')
  await rowDialog.getByRole('button',{name:'保存行级限制'}).click()
  await rowDialog.getByText('北京').waitFor()
  assert.equal(rowPolicies[0].allowed_value,'北京')
  await page.keyboard.press('Escape')
  await page.getByRole('button',{name:'授权报表'}).click()
  const accessDialog=page.locator('.el-dialog').filter({hasText:'预配置报表授权'})
  await accessDialog.locator('.el-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'经营分析'}).first().click()
  await accessDialog.getByRole('button',{name:'添加'}).click()
  await accessDialog.getByText('只读（预配置）').waitFor()
  assert.equal(preconfiguredGrants.length,1)
  // Explicitly configure a password, activate, sign in and run V2 only via reader API.
  await accessDialog.getByRole('button',{name:'取消'}).count().catch(()=>0)
  await page.keyboard.press('Escape')
  const editableUser=page.locator('tbody tr').filter({hasText:'analyst01'})
  await editableUser.getByRole('button',{name:'设置密码'}).click()
  const credentialDialog=page.locator('.el-dialog').filter({hasText:'设置读者密码'})
  await credentialDialog.getByPlaceholder('输入读者的新密码').fill('the-long-reader-password')
  await credentialDialog.getByRole('button',{name:'保存密码'}).click()
  await page.waitForFunction(()=>document.querySelector('button')!==null)
  await editableUser.getByRole('button',{name:'启用',exact:true}).click()
  assert.equal(provisioned[0].enabled,true)
  await page.locator('button.signout').click()
  await page.locator('.login-panel input').first().fill('analyst01')
  await page.locator('.login-panel input').nth(1).fill('the-long-reader-password')
  await page.getByRole('button',{name:/登录工作台/}).click()
  await page.getByText('已授权分析').waitFor()
  const mark=requests.length
  await page.getByRole('button',{name:'查看分析'}).click()
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] canvas').waitFor()
  assert.ok(requests.slice(mark).includes('POST /api/reader/dashboards/published-analysis/widgets/w1/query'))
  assert.ok(!requests.slice(mark).includes('POST /api/analysis/v2/query'))
  assert.ok(!requests.slice(mark).includes('GET /api/datasets'))
  const beforeReload=requests.length
  await page.reload({waitUntil:'domcontentloaded'})
  await page.locator('.analysis-dashboard-card[data-widget-id="w1"] canvas').waitFor()
  assert.ok(requests.slice(beforeReload).includes('GET /api/reader/me'),
    'reader session must survive reload through the reader-only identity API')
  assert.ok(requests.slice(beforeReload).includes(
    'POST /api/reader/dashboards/published-analysis/widgets/w1/query'))
  assert.ok(!requests.slice(beforeReload).includes('GET /api/datasets'))
  assert.deepEqual(errors,[])
  console.log('PASS P1 published routes: multi-view V2 readonly and original legacy Designer readonly')
 }finally{await browser.close()}
})().catch(e=>{console.error('FAIL P1 published routes',e.stack||e);process.exitCode=1})
