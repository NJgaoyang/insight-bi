const {chromium}=require('/root/DataForge/frontend-v2/node_modules/playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1600,height:950}});
 const queries=[];const tag='交叉表多指标测试_自动清理';
 await page.route('**/api/datasets',r=>r.fulfill({status:200,contentType:'application/json',body:JSON.stringify([{id:'pivot_mock',name:'透视测试数据集',connection_id:'mock',fields:[{name:'region',type:'VARCHAR',numeric:false},{name:'category',type:'VARCHAR',numeric:false},{name:'amount',type:'DECIMAL',numeric:true},{name:'orders',type:'INT',numeric:true}]}])}));
 await page.route('**/api/query',async r=>{
  queries.push(JSON.parse(r.request().postData()||'{}'));
  await r.fulfill({status:200,contentType:'application/json',body:JSON.stringify({rows:[{region:'North',category:'A',amount:120,orders:5},{region:'North',category:'B',amount:90,orders:3},{region:'South',category:'A',amount:40,orders:2}]})});
 });
 let created=false;
 try{
  await page.goto('http://127.0.0.1:18110/');
  await page.locator('[autocomplete=username]').fill('admin');
  await page.locator('[autocomplete=current-password]').fill(process.env.BI_ADMIN_PASSWORD);
  await page.getByRole('button',{name:'登录工作台'}).click();
  await page.getByRole('button',{name:/新建报表/}).first().click();
  await page.getByPlaceholder('请输入报表名称').fill(tag);
  await page.getByRole('button',{name:/创建并编辑/}).click();created=true;
  await page.getByRole('button',{name:/交叉表/}).first().click();
  await page.locator('.property-panel .el-select').first().click();
  await page.getByText('透视测试数据集',{exact:true}).last().click();
  await page.locator('.form-field').filter({hasText:'附加数值指标'}).locator('.el-select').first().click();
  await page.getByRole('listbox').getByText('orders',{exact:true}).last().click();
  await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'样式',exact:true}).first().click();
  await page.getByText('显示小计 / 总计').locator('..').locator('.el-switch').click();
  await page.locator('.bi-table').getByText('orders',{exact:true}).first().waitFor();
  const table=await page.locator('.bi-table').innerText();
  if(!table.includes('120')||!table.includes('90')||!table.includes('40')||!table.includes('210')||!table.includes('250'))throw Error('pivot sum error: '+table);
  if(!queries.some(q=>q.measures?.some(m=>m.field==='amount')&&q.measures?.some(m=>m.field==='orders')&&q.dimensions.includes('region')&&q.dimensions.includes('category')))throw Error('pivot query does not include both metrics');
  console.log('PASS_PIVOT_MULTI_METRIC_QUERY_AND_TOTALS');
  await page.getByRole('button',{name:'保存',exact:true}).click();
  await page.getByText('保存成功').waitFor();
  await page.locator('.designer-toolbar .toolbar-left .icon-button').click();
  await page.getByRole('button',{name:/进入编辑器/}).first().click();
  await page.locator('.bi-table').getByText('orders',{exact:true}).first().waitFor();
  console.log('PASS_PIVOT_SAVE_RELOAD');
 }catch(e){console.log('FAILED_PIVOT',e.stack||e);process.exitCode=1;await page.screenshot({path:'/opt/insight-bi/runtime/pivot-failed.png',fullPage:true})}
 finally{
  if(created)try{
   await page.goto('http://127.0.0.1:18110/#/dashboards');
   await page.locator('.dashboard-card').filter({hasText:tag}).locator('button[title=删除]').click();
   await page.getByRole('button',{name:'OK'}).click().catch(async()=>await page.getByRole('button',{name:'确定'}).click());
   console.log('PASS_PIVOT_CLEANUP');
  }catch(e){console.log('PIVOT_CLEANUP_FAILED',e.message);process.exitCode=1}
  await browser.close();
 }
})();
