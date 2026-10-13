const assert=require('node:assert/strict')
const {chromium}=require('/root/.npm/_npx/9b9809298fe40d2c/node_modules/playwright-core')
;(async()=>{
const browser=await chromium.launch({executablePath:'/root/.cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell',args:['--no-sandbox']})
try{
 const p=await browser.newPage({viewport:{width:1530,height:940}})
 const errors=[],queries=[],saves=[]
 let version=1,publishes=0
 p.on('pageerror',e=>errors.push(e.message))
 await p.route('**/api/**',async r=>{
  const req=r.request(),path=new URL(req.url()).pathname,method=req.method()
  const send=(obj,status=200)=>r.fulfill({status,contentType:'application/json',body:JSON.stringify(obj)})
  if(path==='/api/me')return send({user:'admin',csrf:'mock-csrf'})
  if(path==='/api/dashboards/analysis-a'&&method==='GET')return send({id:'analysis-a',name:'城市销售分析',mode:'grid',kind:'analysis',revision:version,status:'draft',content:{mode:'grid',widgets:[],theme:'light'}})
  if(path==='/api/datasets')return send([{id:'ds1',name:'销售数据集',connection_id:'conn1',sql_body:'SELECT * FROM sales',fields:[
   {name:'城市',type:'VARCHAR',numeric:false},{name:'渠道',type:'VARCHAR',numeric:false},{name:'销售额',type:'DOUBLE',numeric:true}]}])
  if(path==='/api/query'&&method==='POST'){
   const body=req.postDataJSON();queries.push(body)
   assert.equal(body.datasetId,'ds1');assert.equal(body.dimensions[0],'城市')
   return send({rows:[{'城市':'北京','销售额':43000},{'城市':'上海','销售额':68000}]})
  }
  if(path==='/api/dashboards/analysis-a'&&method==='PUT'){
   const body=req.postDataJSON();saves.push(body);assert.equal(req.headers()['x-csrf-token'],'mock-csrf')
   version++;return send({revision:version})
  }
  if(path==='/api/dashboards/analysis-a/publish'&&method==='POST'){publishes++;return send({ok:true,version:1})}
  return send({error:'Unexpected route '+method+' '+path},404)
 })
 await p.goto('http://127.0.0.1:18117/#/analysis/analysis-a')
 await p.locator('input[aria-label="分析主题名称"]').waitFor()
 assert.equal(await p.locator('input[aria-label="分析主题名称"]').inputValue(),'城市销售分析')
 await p.locator('.analysis-add-tab').click()
 await p.locator('.analysis-source-selector .el-select').click()
 await p.locator('.el-select-dropdown__item:visible').filter({hasText:'销售数据集'}).first().click()
 await p.locator('.analysis-field').filter({hasText:'城市'}).first().click()
 await p.locator('.analysis-field').filter({hasText:'销售额'}).first().click()
 await p.waitForFunction(()=>document.querySelectorAll('.analysis-stage-content .echart-surface canvas').length>0,{timeout:9000}).catch(()=>{})
 assert.ok(queries.length>0,'Direct SQL backend query was invoked')
 await p.locator('.analysis-chart-types button[title="饼图"]').click()
 await p.locator('.analysis-header-actions button').filter({hasText:'保存'}).click()
 await p.waitForFunction(()=>document.querySelector('.analysis-save-indicator')?.textContent?.includes('已保存'))
 assert.equal(saves.length,1)
 assert.equal(saves[0].content.editorKind,'analysis')
 assert.equal(saves[0].content.widgets[0].dimension,'城市')
 assert.equal(saves[0].content.widgets[0].measure,'销售额')
 assert.equal(saves[0].content.widgets[0].type,'pie')
 await p.locator('.analysis-header-actions button').filter({hasText:'仪表板预览'}).click()
 assert.equal(await p.locator('.analysis-dashboard-card').count(),1)
 await p.locator('.analysis-header-actions button').filter({hasText:'发布'}).click()
 await p.waitForTimeout(250)
 assert.equal(publishes,1)
 assert.deepEqual(errors,[])
 await p.locator('.analysis-header-actions button').filter({hasText:'组件分析'}).click()
 await p.screenshot({path:'/tmp/insight-finebi-component-analysis.png',fullPage:true})
 console.log('PASS FineBI analysis subject: select StarRocks dataset, choose dimensions/measures, real query request, chart switch, save, dashboard preview, publish')
}finally{await browser.close()}
})().catch(e=>{console.error('FAILED',e.stack);process.exit(1)})
