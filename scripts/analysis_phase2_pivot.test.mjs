import test from 'node:test'
import assert from 'node:assert/strict'
import {buildPivot,pivotCell,pivotRowTotal,pivotColumnTotal,pivotGrandTotal,
 PIVOT_MAX_COLUMNS} from '../frontend/src/analysis/pivotTable.ts'

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
 assert.match(invalid.error,/恰好两个维度/)
})
