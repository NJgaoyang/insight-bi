import test from 'node:test'
import assert from 'node:assert/strict'
import {copyEditablePipelineStep,prefixForPipelinePreview}
 from '../frontend/src/analysis/pipelineEditing.ts'

test('formula duplication gives new output ID, retains references and does not mutate original',()=>{
 const source={id:'s1',type:'formula',fieldId:'field1',name:'利润',expression:'[revenue]-[cost]'}
 const copy=copyEditablePipelineStep(source,'s2','field2')
 assert.equal(copy?.id,'s2')
 assert.equal(copy?.fieldId,'field2')
 assert.equal(copy?.expression,source.expression)
 assert.equal(copy?.name,'利润 副本')
 assert.notEqual(copy,source)
 assert.deepEqual(source,{id:'s1',type:'formula',fieldId:'field1',name:'利润',expression:'[revenue]-[cost]'})
})
test('case duplication retains source dependency; filter duplicates comparison only',()=>{
 const conditional={id:'s1',type:'case',fieldId:'field1',sourceFieldId:'revenue',name:'分类',op:'in',
  values:['A','B'],thenValue:'好',elseValue:'其他',outputType:'STRING'}
 const copied=copyEditablePipelineStep(conditional,'s2','field2')
 assert.deepEqual(copied?.values,['A','B'])
 assert.notEqual(copied?.values,conditional.values)
 assert.equal(copied?.sourceFieldId,'revenue')
 assert.equal(copied?.fieldId,'field2')
 const filter={id:'s3',type:'filter',fieldId:'field1',op:'eq',value:'北京'}
 assert.deepEqual(copyEditablePipelineStep(filter,'s4','unused'),{...filter,id:'s4'})
})
test('join, union and group cannot duplicate or violate output references',()=>{
 for(const type of ['join','union','group'])
  assert.equal(copyEditablePipelineStep({id:'s1',type},'s2','field2'),null)
 assert.equal(copyEditablePipelineStep({id:'s1',type:'formula',fieldId:'x'},'s1','field2'),null)
 assert.equal(copyEditablePipelineStep({id:'s1',type:'case',fieldId:'x'},'s2','x'),null)
})
test('prefix preview works with 3+ steps and rejects invalid position without mutation',()=>{
 const steps=['s1','s2','s3','s4'].map(id=>({id,type:'filter',fieldId:id,op:'eq',value:'v'}))
 const prefix=prefixForPipelinePreview(steps,1)
 assert.deepEqual(prefix.map(x=>x.id),['s1','s2'])
 prefix[0].value='changed'
 assert.equal(steps[0].value,'v')
 for(const index of [-1,4,1.5,NaN])assert.throws(()=>prefixForPipelinePreview(steps,index))
})
