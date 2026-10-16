import test from 'node:test'
import assert from 'node:assert/strict'
import {joinPreflight} from '../frontend/src/analysis/joinPreflight.ts'
const left=[{id:'city',type:'STRING'},{id:'amount',type:'DECIMAL'}]
const right=[{id:'rcity',type:'STRING'},{id:'refund',type:'DECIMAL'}]
const good=()=>({sourceDatasetId:'archive',joinType:'LEFT',acknowledgeFanout:true,
 conditions:[{leftFieldId:'city',rightFieldId:'rcity'}],
 fields:[{sourceFieldId:'refund',fieldId:'output',name:'退款金额'}]})
test('valid same-connection two-source join passes preview checks',()=>{
 assert.deepEqual(joinPreflight(good(),left,right,['archive']),[])
})
test('every key change must be explicitly acknowledged before save',()=>{
 const step=good();step.acknowledgeFanout=false
 assert.match(joinPreflight(step,left,right,['archive']).join(' '),/重新确认/)
 step.acknowledgeFanout=true
 assert.deepEqual(joinPreflight(step,left,right,['archive']),[])
})
test('source mismatch, repeated or incompatible keys and outputs fail closed',()=>{
 const step=good()
 step.sourceDatasetId='other'
 step.conditions.push({leftFieldId:'amount',rightFieldId:'rcity'})
 step.conditions.push({...step.conditions[0]})
 step.fields.push({sourceFieldId:'absent',fieldId:'output',name:''})
 const message=joinPreflight(step,left,right,['archive']).join(' ')
 for(const fragment of ['相同连接','类型不匹配','关联条件不能重复','字段不存在','ID 不合法或重复','名称无效'])
  assert.ok(message.includes(fragment),fragment)
})
test('keys absent and invalid types cannot be silently submitted',()=>{
 const step=good()
 step.conditions=[{leftFieldId:'wrong',rightFieldId:'rcity'}]
 step.joinType='CROSS'
 step.fields=[{sourceFieldId:'refund',fieldId:'99;drop',name:'x'}]
 assert.match(joinPreflight(step,left,right,['archive']).join(' '),/不存在/)
 assert.match(joinPreflight(step,left,right,['archive']).join(' '),/LEFT 或 INNER/)
 assert.match(joinPreflight(step,left,right,['archive']).join(' '),/不合法/)
})
