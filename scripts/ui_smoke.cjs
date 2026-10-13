const {chromium}=require('/root/DataForge/frontend-v2/node_modules/playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1600,height:930},deviceScaleFactor:1});
 page.on('pageerror',e=>console.log('PAGE_ERROR',e.message));
 page.on('console',e=>{if(e.type()==='error')console.log('CONSOLE_ERROR',e.text().slice(0,200))});
 const assert=async(text)=>{await page.getByText(text,{exact:false}).first().waitFor({timeout:10000})};
 try{
  await page.goto('http://127.0.0.1:18110/');
  await page.locator('[autocomplete=username]').fill('admin');
  await page.locator('[autocomplete=current-password]').fill(process.env.BI_ADMIN_PASSWORD);
  await page.getByRole('button',{name:'登录工作台'}).click();
  await assert('仪表板与自由画布');
  console.log('OK_LOGIN');
  await page.getByRole('button',{name:/新建报表/}).first().click();
  await page.getByPlaceholder('请输入报表名称').fill('UI_自动测试待清理');
  await page.getByRole('button',{name:/创建并编辑/}).click();
  await assert('常规仪表板');
  console.log('OK_DESIGNER');
  await page.getByRole('button',{name:/文本/}).first().click();
  await assert('组件属性');
  await page.getByRole('button',{name:'样式',exact:true}).first().click();
  console.log('OK_WIDGET');
  await page.screenshot({path:'/opt/insight-bi/runtime/ui_grid.png',fullPage:true});
  await page.getByRole('button',{name:/自由画布/}).first().click();
  await assert('自由画布 1600');
  await page.screenshot({path:'/opt/insight-bi/runtime/ui_free.png',fullPage:true});
  console.log('OK_FREE');
  await page.getByRole('button',{name:'保存',exact:true}).first().click();
  await assert('保存成功');
  await page.getByRole('button',{name:'发布',exact:true}).first().click();
  await assert('发布成功');
  console.log('OK_SAVE_PUBLISH');
  await page.locator('.designer-toolbar .toolbar-left .icon-button').click();
  await assert('UI_自动测试待清理');
  await page.getByRole('button',{name:/进入编辑器/}).first().click();
  await assert('文本');
  console.log('OK_RELOAD');
  await page.locator('.designer-toolbar .toolbar-left .icon-button').click();
  await page.locator('.dashboard-meta button[title=删除]').first().click();
  await page.getByRole('button',{name:'OK'}).click().catch(async()=>await page.getByRole('button',{name:'确定'}).click());
  await assert('还没有仪表板');
  console.log('OK_CLEANUP');
 }catch(e){console.log('FAILED_STEP',e.message);await page.screenshot({path:'/opt/insight-bi/runtime/ui_failed.png',fullPage:true});process.exitCode=1}
 finally{await browser.close()}
})();
