import test from 'node:test'
import assert from 'node:assert/strict'
import {buildPivot,pivotCell,pivotRowTotal,pivotColumnTotal,pivotGrandTotal,pivotParentTotal,pivotParentCell,
 PIVOT_MAX_COLUMNS,formatPivotTotal} from '../frontend/src/analysis/pivotTable.ts'

const cols=[
 {id:'city',name:'d1',label:'同名字段',kind:'dimension',type:'STRING'},
 {id:'month',name:'d2',label:'同名字段',kind:'dimension',type:'STRING'},
 {id:'rev',name:'rev',label:'金额',kind:'measure',type:'DECIMAL',additive:true},
 {id:'cnt',name:'cnt',label:'行数',kind:'measure',type:'INTEGER',additive:true},
 {id:'avg',name:'avg',label:'平均值',kind:'measure',type:'DECIMAL',additive:false},
 {id:'distinct',name:'distinct',label:'去重',kind:'measure',type:'INTEGER',additive:false}
]
const bindings={
 dimensions:[{fieldId:'city'},{fieldId:'month'}],
 measures:[
  {id:'rev',fieldId:'revenue',aggregate:'SUM',alias:'金额'},
  {id:'cnt',fieldId:'revenue',aggregate:'COUNT',alias:'行数'},
  {id:'avg',fieldId:'revenue',aggregate:'AVG',alias:'均值'},
  {id:'distinct',fieldId:'revenue',aggregate:'COUNT',distinct:true,alias:'去重'}
 ],
 filters:{pre:[],post:[]},sort:[],limit:200
}
const result=(rows,truncated=false)=>({columns:cols,rows,executionMs:1,truncated,warnings:[]})
const data=[
 {d1:'北京',d2:'1月',rev:'0.10',cnt:1,avg:100,distinct:1},
 {d1:'北京',d2:null,rev:'0.20',cnt:2,avg:200,distinct:2},
 {d1:'上海',d2:'1月',rev:'20.50',cnt:1,avg:20,distinct:1},
 {d1:null,d2:'1月',rev:null,cnt:1,avg:null,distinct:0},
 {d1:'(空值)',d2:'1月',rev:'1',cnt:1,avg:1,distinct:1},
]
const axis=(model,label)=>model.rows.find(x=>x.label===label)
const column=(model,label)=>model.columns.find(x=>x.label===label)

test('row x column x multi-measure; label collision and real NULL remain independent',()=>{
 const model=buildPivot(result(data),bindings)
 assert.equal(model.error,'')
 assert.equal(model.rows.length,4)
 assert.equal(model.columns.length,2)
 assert.deepEqual(model.measures.map(x=>x.id),['rev','cnt','avg','distinct'])
 const bj=axis(model,'北京'),jan=column(model,'1月')
 assert.equal(pivotCell(model,bj.key,jan.key,'rev'),'0.10')
 assert.equal(pivotCell(model,axis(model,'上海').key,column(model,'(空值)').key,'rev'),null)
 assert.notEqual(model.rows.find(x=>x.value==='(空值)').key,model.rows.find(x=>x.value===null).key)
 assert.equal(pivotRowTotal(model,bj.key,'rev'),'0.30')
 assert.equal(pivotColumnTotal(model,jan.key,'rev'),'21.60')
 assert.equal(pivotGrandTotal(model,'rev'),'21.80')
 assert.equal(pivotGrandTotal(model,'cnt'),'6')
 assert.equal(pivotGrandTotal(model,'avg'),null)
 assert.equal(pivotGrandTotal(model,'distinct'),null)
})
test('sort controls, no data and hidden totals',()=>{
 const sorted=buildPivot(result(data),{...bindings,pivot:{rowOrder:'desc',columnOrder:'desc',showTotals:false}})
 assert.equal(sorted.rows[0].label,'上海')
 assert.equal(sorted.rows.at(-1).value,null)
 assert.equal(sorted.columns.at(-1).value,null)
 assert.equal(sorted.grandTotals.size,0)
 assert.equal(buildPivot(result([]),bindings).rows.length,0)
})
test('truncated result fails closed for totals, and unsafe metadata stays hidden',()=>{
 const partial=buildPivot(result(data,true),bindings)
 assert.match(partial.warning,/截断/)
 assert.equal(partial.additive.size,0)
 assert.equal(pivotGrandTotal(partial,'rev'),null)
 const noAdditive=buildPivot({...result(data),columns:cols.map(c=>({...c,additive:undefined}))},bindings)
 assert.equal(noAdditive.grandTotals.size,0)
})
test('bad schema, duplicate tuples, missing values and column cap are rejected',()=>{
 const mismatched=buildPivot({...result(data),columns:cols.filter(c=>c.id!=='month')},bindings)
 assert.match(mismatched.error,/不一致/)
 const duplicates=buildPivot(result([...data,data[0]]),bindings)
 assert.match(duplicates.error,/重复分组/)
 const missing=buildPivot(result([{d1:'北京',d2:'1月',rev:1}]),bindings)
 assert.match(missing.error,/缺少/)
 const overflow=buildPivot(result(Array.from({length:PIVOT_MAX_COLUMNS+1},(_,i)=>({
  d1:'城',d2:'列'+i,rev:1,cnt:1,avg:1,distinct:1
 }))),bindings)
 assert.match(overflow.error,/上限/)
 const invalid=buildPivot(result(data),{...bindings,dimensions:[{fieldId:'city'}]})
 assert.match(invalid.error,/二至三个维度/)
})


test('server recomputed SUM / weighted AVG / DISTINCT totals override unsafe client sums',()=>{
 const server={exact:true,rows:[
   {d1:'北京',rev:'0.3000',cnt:'3',avg:'0.1000',distinct:'2'},
   {d1:'上海',rev:'20.5000',cnt:'1',avg:'20.5000',distinct:'1'},
   {d1:null,rev:null,cnt:'1',avg:null,distinct:'0'},
   {d1:'(空值)',rev:'1.0000',cnt:'1',avg:'1.0000',distinct:'1'}
 ],columns:[
   {d1:'1月',rev:'21.6000',cnt:'4',avg:'5.4000',distinct:'3'},
   {d1:null,rev:'0.2000',cnt:'2',avg:'0.2000',distinct:'2'}
 ],grand:{rev:'21.8000',cnt:'6',avg:'3.6333333333',distinct:'3'}}
 const model=buildPivot({...result(data),pivotTotals:server},bindings)
 assert.equal(model.warning,'')
 assert.equal(model.additive.size,4)
 assert.equal(pivotRowTotal(model,axis(model,'北京').key,'avg'),'0.1000')
 assert.equal(pivotGrandTotal(model,'distinct'),'3')
 assert.equal(formatPivotTotal(pivotGrandTotal(model,'avg'),2),'3.63')
 const warning=buildPivot({...result(data),pivotTotals:{exact:false,reason:'后置过滤限制'}},bindings)
 assert.match(warning.warning,/后置过滤限制/)
 assert.equal(warning.additive.size,0)
 assert.equal(pivotGrandTotal(warning,'rev'),null)
 const inconsistent=buildPivot({...result(data),pivotTotals:{...server,grand:{rev:1}}},bindings)
 assert.equal(inconsistent.additive.size,0)
 assert.match(inconsistent.warning,/异常/)
})

test('exact formatting keeps all integer digits and stable rounding',()=>{
 assert.equal(formatPivotTotal('12345678901234567890.129',2),'12,345,678,901,234,567,890.13')
 assert.equal(formatPivotTotal('-0.005',2),'-0.01')
 assert.equal(formatPivotTotal('999.999',2),'1,000.00')
 assert.equal(formatPivotTotal('10.0001',4),'10.0001')
 assert.equal(formatPivotTotal('0',0),'0')
 assert.equal(formatPivotTotal(null,2),'—')
 assert.equal(formatPivotTotal('1e100',2),'—')
})
undefined

test('three dimensions create two row levels, one column and authoritative parent subtotal',()=>{
 const three={...bindings,
  dimensions:[{fieldId:'region'},{fieldId:'city'},{fieldId:'month'}],
  pivot:{showTotals:true,showSubtotals:true,rowOrder:'asc',columnOrder:'asc'}}
 const grouped=[
  {d1:'北区',d2:'北京',d3:'1月',rev:20,avg:10},
  {d1:'北区',d2:'北京',d3:'2月',rev:40,avg:20},
  {d1:'北区',d2:'天津',d3:'1月',rev:5,avg:5},
  {d1:'南区',d2:'上海',d3:'1月',rev:60,avg:60}
 ]
 const response={
  ...result(grouped),columns:[
   {id:'region',name:'d1',label:'区域',kind:'dimension',type:'STRING'},
   {id:'city',name:'d2',label:'城市',kind:'dimension',type:'STRING'},
   {id:'month',name:'d3',label:'月份',kind:'dimension',type:'STRING'},
   {id:'rev',name:'rev',label:'收入',kind:'measure',type:'DECIMAL',additive:true},
   {id:'avg',name:'avg',label:'均值',kind:'measure',type:'DECIMAL',additive:false},
   {id:'cnt',name:'cnt',label:'计数',kind:'measure',type:'INTEGER',additive:true},
   {id:'distinct',name:'distinct',label:'去重',kind:'measure',type:'INTEGER',additive:false}
  ],
  rows:grouped.map(r=>({...r,cnt:1,distinct:1})),
  pivotTotals:{
   exact:true,
   rows:[
    {d1:'北区',d2:'北京',rev:'60',avg:'15',cnt:'2',distinct:'1'},
    {d1:'北区',d2:'天津',rev:'5',avg:'5',cnt:'1',distinct:'1'},
    {d1:'南区',d2:'上海',rev:'60',avg:'60',cnt:'1',distinct:'1'}
   ],
   columns:[
    {d1:'1月',rev:'85',avg:'28.33',cnt:'3',distinct:'2'},
    {d1:'2月',rev:'40',avg:'20',cnt:'1',distinct:'1'}],
   grand:{rev:'125',avg:'31.25',cnt:'4',distinct:'2'},
   parents:[
    {d1:'北区',rev:'65',avg:'16.25',cnt:'3',distinct:'1'},
    {d1:'南区',rev:'60',avg:'60',cnt:'1',distinct:'1'}
   ],parentCells:[
    {d1:'北区',d2:'1月',rev:'25',avg:'8.333333',cnt:'2',distinct:'1'},
    {d1:'北区',d2:'2月',rev:'40',avg:'20',cnt:'1',distinct:'1'},
    {d1:'南区',d2:'1月',rev:'60',avg:'60',cnt:'1',distinct:'1'}
   ]}
 }
 const model=buildPivot(response,three)
 assert.equal(model.error,'')
 assert.equal(model.warning,'')
 assert.equal(model.rowLevels,2)
 assert.equal(model.rows.length,3)
 assert.deepEqual(model.rows.map(x=>[x.parentLabel,x.label]),[
  ['北区','北京'],['北区','天津'],['南区','上海']])
 assert.equal(pivotParentTotal(model,model.rows[0].parentKey,'rev'),'65')
 assert.equal(pivotParentCell(model,model.rows[0].parentKey,JSON.stringify(['1月']),'rev'),'25')
 assert.equal(pivotParentTotal(model,model.rows[0].parentKey,'distinct'),'1')
 assert.equal(pivotGrandTotal(model,'rev'),'125')
 const noParents=buildPivot({...response,pivotTotals:{...response.pivotTotals,parents:[]}},three)
 assert.match(noParents.warning,/小计/)
 assert.equal(noParents.additive.size,0)
 const disabled=buildPivot(response,{...three,pivot:{...three.pivot,showSubtotals:false}})
 assert.equal(disabled.parentTotals.size,0)
 assert.equal(disabled.grandTotals.get('rev'),'125')
})
