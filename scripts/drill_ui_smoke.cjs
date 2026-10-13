const {chromium}=require('/root/DataForge/frontend-v2/node_modules/playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1600,height:950}});
 const queries=[];
 page.on('pageerror',e=>console.log('PAGE_ERROR',e.message));
 await page.route('**/api/datasets',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify([{id:'mock_drill',name:'钻取测试数据集',connection_id:'mock',fields:[{name:'region',type:'VARCHAR',numeric:false},{name:'city',type:'VARCHAR',numeric:false},{name:'amount',type:'DECIMAL',numeric:true}]}])}));
 await page.route('**/api/query',async r=>{
  const body=JSON.parse(r.request().postData()||'{}');queries.push(body);
  const level=body.dimensions?.[0];
  const rows=level==='city'?[{city:'Hangzhou',amount:110},{city:'Ningbo',amount:70}]:[{region:'Zhejiang',amount:180},{region:'Guangdong',amount:160}];
  await r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({rows})});
 });
 let created=false;
 try{
  await page.goto('http://127.0.0.1:18110/');
  await page.locator('[autocomplete=username]').fill('admin');
  await page.locator('[autocomplete=current-password]').fill(process.env.BI_ADMIN_PASSWORD);
  await page.getByRole('button',{name:'登录工作台'}).click();
  await page.getByRole('button',{name:/新建报表/}).first().click();
  await page.getByPlaceholder('请输入报表名称').fill('钻取排序自动测试_清理');
  await page.getByRole('button',{name:/创建并编辑/}).click();created=true;
  await page.getByRole('button',{name:/柱状图/}).first().click();
  await page.locator('.property-panel .el-select').first().click();
  await page.getByText('钻取测试数据集',{exact:true}).last().click();
  await page.locator('.form-field').filter({hasText:'下钻维度'}).locator('.el-select').first().click();
  await page.getByRole('listbox').getByText('city',{exact:true}).last().click();
  await page.keyboard.press('Escape');
  const top=page.locator('.form-field').filter({hasText:'Top N'}).locator('input');
  await top.fill('2');await top.press('Tab');
  await page.getByText('当前：region').waitFor();
  await page.waitForTimeout(600);
  if(!queries.some(q=>q.dimensions?.[0]==='region'&&q.limit===2&&q.sort==='amount'&&q.direction==='desc')) throw Error('Top N or sorting payload missing: '+JSON.stringify(queries.slice(-3)));
  console.log('PASS_DRILL_CONFIG_AND_TOPN_QUERY');
  const chart=page.locator('.echart-surface').first();
  await chart.waitFor();
  await chart.click({position:{x:115,y:95}});
  await page.getByText('当前：city').waitFor({timeout:6000});
  if(!queries.some(q=>q.dimensions?.[0]==='city'&&q.filters?.some(f=>f.field==='region'&&f.value==='Zhejiang')))throw Error('Drill filter incorrect: '+JSON.stringify(queries.slice(-3)));
  console.log('PASS_DRILL_CLICK_AND_FILTER');
  await page.getByRole('button',{name:'region',exact:true}).first().click();
  await page.getByText('当前：region').waitFor({timeout:6000});
  console.log('PASS_DRILL_BREADCRUMB_BACK');
  await page.getByRole('button',{name:'保存',exact:true}).click();
  await page.getByText('保存成功').waitFor();
  await page.locator('.designer-toolbar .toolbar-left .icon-button').click();
  await page.getByRole('button',{name:/进入编辑器/}).first().click();
  await page.getByText('当前：region').waitFor();
  if(!queries.some(q=>q.limit===2))throw Error('TopN not reloaded');
  console.log('PASS_DRILL_SAVED_AND_RELOADED');
 }catch(e){console.log('FAILED_DRILL',e.stack||e);process.exitCode=1;await page.screenshot({path:'/opt/insight-bi/runtime/drill-failed.png',fullPage:true})}
 finally{
  if(created)try{
   await page.goto('http://127.0.0.1:18110/#/dashboards');
   await page.locator('.dashboard-card').filter({hasText:'钻取排序自动测试_清理'}).locator('button[title=删除]').click();
   await page.getByRole('button',{name:'OK'}).click().catch(async()=>await page.getByRole('button',{name:'确定'}).click());
   console.log('PASS_DRILL_CLEANUP');
  }catch(e){console.log('CLEANUP_FAILED',e.message);process.exitCode=1}
  await browser.close();
 }
})();
