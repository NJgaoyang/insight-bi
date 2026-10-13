const {chromium}=require('/root/DataForge/frontend-v2/node_modules/playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1600,height:950}});
 const requests=[];const marker='多选筛选自动测试_清理';let created=false;
 await page.route('**/api/datasets',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify([{id:'multi_mock',name:'多选筛选数据集',connection_id:'mock',fields:[{name:'region',numeric:false,type:'VARCHAR'},{name:'amount',numeric:true,type:'DECIMAL'}]}])}));
 await page.route('**/api/query',r=>{requests.push(JSON.parse(r.request().postData()||'{}'));return r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({rows:[{region:'North',amount:100},{region:'South',amount:80},{region:'West',amount:40}]})})});
 try{
  await page.goto('http://127.0.0.1:18110/');
  await page.locator('[autocomplete=username]').fill('admin');
  await page.locator('[autocomplete=current-password]').fill(process.env.BI_ADMIN_PASSWORD);
  await page.getByRole('button',{name:'登录工作台'}).click();
  await page.getByRole('button',{name:/新建报表/}).first().click();
  await page.getByPlaceholder('请输入报表名称').fill(marker);
  await page.getByRole('button',{name:/创建并编辑/}).click();created=true;
  await page.getByRole('button',{name:/筛选器/}).first().click();
  await page.locator('.property-panel .el-select').first().click();
  await page.getByText('多选筛选数据集',{exact:true}).last().click({timeout:6000});
  await page.getByRole('button',{name:'样式',exact:true}).first().click();
  await page.getByText('允许多选').locator('..').locator('.el-switch').click();
  await page.getByRole('button',{name:/柱状图/}).first().click();
  await page.getByRole('button',{name:'数据',exact:true}).first().click();
  await page.locator('.property-panel .el-select').first().click();
  await page.getByText('多选筛选数据集',{exact:true}).last().click({timeout:6000});
  const select=page.locator('.filter-panel select[multiple]');
  await select.waitFor();
  await select.selectOption(['North','South']);
  await page.waitForTimeout(350);
  if(!requests.some(q=>q.measures?.length && q.filters?.some(f=>f.field==='region'&&f.op==='in'&&f.values?.includes('North')&&f.values?.includes('South'))))throw Error('missing parameterized multiselect payload '+JSON.stringify(requests.slice(-4)));
  await page.getByText('已筛选：').waitFor({timeout:6000});
  console.log('PASS_FILTER_MULTISELECT_AND_IN_QUERY');
  await page.getByRole('button',{name:'清除筛选'}).click();
  await page.waitForTimeout(200);
  if(!requests.some(q=>q.measures?.length && (!q.filters||!q.filters.length)))throw Error('clear filter did not reload chart');
  console.log('PASS_FILTER_CLEAR_RELOAD');
 }catch(e){console.log('FAILED_MULTISELECT',e.stack||e);await page.screenshot({path:'/opt/insight-bi/runtime/multi-failed.png',fullPage:true});process.exitCode=1}
 finally{
  if(created)try{
   await page.goto('http://127.0.0.1:18110/#/dashboards');
   await page.locator('.dashboard-card').filter({hasText:marker}).locator('button[title=删除]').click();
   await page.getByRole('button',{name:'OK'}).click().catch(async()=>await page.getByRole('button',{name:'确定'}).click());
   console.log('PASS_MULTISELECT_CLEANUP');
  }catch(e){console.log('MULTISELECT_CLEANUP_FAILED',e.message);process.exitCode=1}
  await browser.close();
 }
})();
