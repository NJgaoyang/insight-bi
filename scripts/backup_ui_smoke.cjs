const {chromium}=require('/root/DataForge/frontend-v2/node_modules/playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1450,height:920},acceptDownloads:true});
 const name='JSON配置测试_自动清理';let made=false;
 try{
  await page.goto('http://127.0.0.1:18110/');
  await page.locator('[autocomplete=username]').fill('admin');
  await page.locator('[autocomplete=current-password]').fill(process.env.BI_ADMIN_PASSWORD);
  await page.getByRole('button',{name:'登录工作台'}).click();
  await page.getByRole('button',{name:/新建报表/}).first().click();
  await page.getByPlaceholder('请输入报表名称').fill(name);
  await page.getByRole('button',{name:/创建并编辑/}).click();made=true;
  await page.getByRole('button',{name:/文本/}).first().click();
  await page.getByRole('button',{name:'保存',exact:true}).click();
  await page.getByText('保存成功').waitFor();
  await page.locator('.designer-toolbar .toolbar-left .icon-button').click();
  const pending=page.waitForEvent('download');
  await page.getByRole('button',{name:/导出配置/}).first().click();
  const result=await pending;
  if(!result.suggestedFilename().endsWith('.json'))throw Error('download extension incorrect');
  console.log('PASS_JSON_EXPORT_DOWNLOAD');
  const payload={format:'insight-bi-dashboard',version:1,name:name,mode:'grid',content:{mode:'grid',theme:'light',widgets:[{id:'import-text',type:'text',title:'导入测试组件',datasetId:'',dimension:'',measure:'',agg:'SUM',columns:[],x:0,y:0,w:4,h:3,px:10,py:10,width:300,height:180,style:{text:'导入内容可见'},columnStyles:{}}]}};
  await page.locator('input[aria-label="导入报表配置"]').setInputFiles({name:'import.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(payload))});
  await page.getByText('导入测试组件').first().waitFor({timeout:10000});
  await page.locator('.designer-toolbar .toolbar-left .icon-button').click();
  const cards=page.locator('.dashboard-card').filter({hasText:name});
  if(await cards.count()!==2)throw Error('expected 2 reports after import');
  console.log('PASS_JSON_IMPORT_AND_VIEW');
 }catch(e){console.log('FAILED_JSON',e.message);process.exitCode=1}
 finally{
  if(made)try{
   await page.goto('http://127.0.0.1:18110/#/dashboards');
   for(let i=0;i<3;i++){
    const item=page.locator('.dashboard-card').filter({hasText:name}).first();
    if(!await item.count())break;
    await item.locator('button[title=删除]').click();
    await page.getByRole('button',{name:'OK'}).click().catch(async()=>await page.getByRole('button',{name:'确定'}).click());
    await page.waitForTimeout(140);
   }
   if(await page.locator('.dashboard-card').filter({hasText:name}).count())throw Error('cleanup incomplete');
   console.log('PASS_JSON_CLEANUP');
  }catch(e){console.log('FAILED_CLEANUP',e.message);process.exitCode=1}
  await browser.close();
 }
})();
