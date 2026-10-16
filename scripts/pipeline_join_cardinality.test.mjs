import test from 'node:test'
import assert from 'node:assert/strict'
import {joinKeySignature,currentRightKeyProfile,cardinalitySummary} from '../frontend/src/analysis/joinCardinality.ts'
const step=()=>({id:'s1',type:'join',sourceDatasetId:'archive',joinType:'LEFT',
 conditions:[{leftFieldId:'city',rightFieldId:'city'}, {leftFieldId:'period',rightFieldId:'rperiod'}]})
const profile=()=>({keyGroups:3,rightRows:4,maxRightMultiplicity:2,
 duplicateGroups:1,extraRightRows:1,hasDuplicateKeys:true,
 scope:'RIGHT_SOURCE',exactJoinRowCount:false})
test('signature binds source dataset, JOIN mode and ordered key pairs',()=>{
 const a=step(),fingerprint=joinKeySignature('sales',a)
 assert.equal(fingerprint,joinKeySignature('sales',structuredClone(a)))
 a.conditions.reverse()
 assert.notEqual(joinKeySignature('sales',a),fingerprint)
 assert.notEqual(joinKeySignature('other',step()),fingerprint)
 a.conditions.reverse()
 a.sourceDatasetId='elsewhere'
 assert.notEqual(joinKeySignature('sales',a),fingerprint)
 a.sourceDatasetId='archive';a.joinType='INNER'
 assert.notEqual(joinKeySignature('sales',a),fingerprint)
})
test('diagnostic cannot silently apply to a changed step or other dataset',()=>{
 const p=profile(),snapshot={key:joinKeySignature('sales',step()),result:p}
 assert.deepEqual(currentRightKeyProfile(snapshot,'sales',step()),p)
 assert.equal(currentRightKeyProfile(snapshot,'other',step()),null)
 const modified=step();modified.conditions.push({leftFieldId:'amount',rightFieldId:'refund'})
 assert.equal(currentRightKeyProfile(snapshot,'sales',modified),null)
 assert.equal(currentRightKeyProfile(null,'sales',step()),null)
})
test('reject malformed or fabricated data and do not infer final row count',()=>{
 const base=profile(),key=joinKeySignature('sales',step())
 for(const patch of [
  {rightRows:-1},{rightRows:3.4},{maxRightMultiplicity:30},
  {keyGroups:0},{hasDuplicateKeys:false},{exactJoinRowCount:true},
  {scope:'LEFT_SOURCE'},{extraRightRows:9}]){
  const snapshot={key,result:{...base,...patch}}
  assert.equal(currentRightKeyProfile(snapshot,'sales',step()),null)
 }
 const text=cardinalitySummary(base)
 assert.match(text,/右表 4 行/)
 assert.match(text,/最大单键 2 行/)
 assert.match(text,/不能推算最终 JOIN 行数/)
 assert.doesNotMatch(text,/放大倍数为/)
})
test('empty right source and unique keys remain useful but not permanent proofs',()=>{
 const empty={...profile(),keyGroups:0,rightRows:0,maxRightMultiplicity:0,
  duplicateGroups:0,extraRightRows:0,hasDuplicateKeys:false}
 const unique={...empty,keyGroups:2,rightRows:2,maxRightMultiplicity:1}
 const key=joinKeySignature('sales',step())
 assert.deepEqual(currentRightKeyProfile({key,result:empty},'sales',step()),empty)
 assert.deepEqual(currentRightKeyProfile({key,result:unique},'sales',step()),unique)
 assert.match(cardinalitySummary(unique),/不能保证之后的数据不变化/)
})
