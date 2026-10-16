import test from 'node:test'
import assert from 'node:assert/strict'
import {checkPipelineDependencies,formulaFieldReferences} from '../frontend/src/analysis/pipelineDependencies.ts'

const base=[
 {id:'city',type:'STRING'},{id:'revenue',type:'DECIMAL',numeric:true},
 {id:'cost',type:'DECIMAL',numeric:true},{id:'date',type:'DATE'}
]
const steps=()=>[
 {id:'derive',type:'formula',fieldId:'profit',expression:'[revenue] - [cost]'},
 {id:'filtered',type:'filter',fieldId:'profit'},
 {id:'summary',type:'group',dimensions:['city'],measures:[
  {id:'total',fieldId:'profit',aggregate:'SUM'}]},
 {id:'after',type:'filter',fieldId:'total'}
]
test('valid four-step formula/filter/group/filter lineage stays clean',()=>{
 assert.deepEqual(checkPipelineDependencies(base,steps()).issues,[])
})
test('deleting source marks every dependent downstream use without mutating steps',()=>{
 const input=steps()
 const original=JSON.stringify(input)
 const missing=checkPipelineDependencies(base,input.slice(1))
 assert.deepEqual(missing.issues.map(x=>x.index),[0,1])
 assert.match(missing.byStep.filtered[0],/profit/)
 assert.match(missing.byStep.summary[0],/profit/)
 assert.equal(JSON.stringify(input),original)
})
test('moving group before formula breaks prior row fields and a later dimension',()=>{
 const input=steps()
 const rearranged=[input[2],input[0],input[1],input[3]]
 const result=checkPipelineDependencies(base,rearranged)
 assert.ok(result.byStep.summary?.some(x=>x.includes('profit')))
 assert.ok(result.byStep.derive?.some(x=>x.includes('revenue')))
})
test('group shape leaves only dimension and measure IDs available',()=>{
 const input=[
  {id:'summary',type:'group',dimensions:['city'],measures:[{id:'total',fieldId:'revenue',aggregate:'SUM'}]},
  {id:'bad',type:'filter',fieldId:'revenue'}
 ]
 const out=checkPipelineDependencies(base,input)
 assert.equal(out.issues.length,1)
 assert.equal(out.issues[0].stepId,'bad')
})
test('duplicate output, conversion dependencies and invalid SUM/COUNT star are rejected',()=>{
 const input=[
  {id:'cast',type:'cast',fieldId:'revenue',sourceFieldId:'city',outputType:'DECIMAL'},
  {id:'group',type:'group',dimensions:['city'],measures:[{id:'total',fieldId:'city',aggregate:'SUM'},
  {id:'other',fieldId:'*',aggregate:'AVG'}]}
 ]
 const result=checkPipelineDependencies(base,input)
 assert.ok(result.byStep.cast.length>=2)
 assert.ok(result.byStep.group.length>=2)
})
test('parent identifiers in text literals do not introduce false dependencies',()=>{
 assert.deepEqual(formulaFieldReferences("'[missing]' + [revenue] + 'x''[also_missing]'"),['revenue'])
})
test('new JOIN projection is visible downstream, and illegal position is rejected',()=>{
 const input=[
  {id:'derive',type:'formula',fieldId:'profit',expression:'[revenue]'},
  {id:'join',type:'join',fields:[{fieldId:'refund_id',sourceFieldId:'refund'}]},
  {id:'use_join',type:'filter',fieldId:'refund_id'}
 ]
 const result=checkPipelineDependencies(base,input)
 assert.equal(result.issues.length,1)
 assert.equal(result.issues[0].stepId,'join')
 assert.match(result.issues[0].message,/第一步/)
})
test('schema changes after CAST invalidate later field references',()=>{
 const input=[
  {id:'cast',type:'cast',fieldId:'amount_string',sourceFieldId:'revenue',outputType:'STRING'},
  {id:'filter',type:'filter',fieldId:'amount_string'}
 ]
 assert.deepEqual(checkPipelineDependencies(base,input).issues,[])
 const moved=[input[1],input[0]]
 assert.match(checkPipelineDependencies(base,moved).issues[0].message,/amount_string/)
})
