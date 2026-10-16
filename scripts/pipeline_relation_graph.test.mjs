import test from 'node:test'
import assert from 'node:assert/strict'
import {pipelineRelationGraph} from '../frontend/src/analysis/pipelineRelationGraph.ts'
const primary={id:'sales',name:'销售事实表'}
const others=[{id:'refund',name:'退款维表'},{id:'unrelated',name:'外部数据'}]
const left=[{id:'city',label:'城市',type:'STRING'},{id:'amount',label:'收入',type:'DECIMAL'}]
const right={refund:[{id:'rcity',label:'退款城市',type:'STRING'},
 {id:'refund',label:'退款金额',type:'DECIMAL'}]}
test('no synthetic relation when there is no JOIN or UNION stage',()=>{
 assert.equal(pipelineRelationGraph(primary,others,left,right,[
  {id:'f',type:'filter',fieldId:'city'}]),null)
})
test('JOIN graph reports trusted label mapping, data shape and dependent steps',()=>{
 const steps=[{id:'j',type:'join',joinType:'LEFT',sourceDatasetId:'refund',
  acknowledgeFanout:true,conditions:[{leftFieldId:'city',rightFieldId:'rcity'}],
  fields:[{sourceFieldId:'refund',fieldId:'joined_refund',name:'退款额'}]},
 {id:'f',type:'formula',fieldId:'profit',expression:'[amount] - [joined_refund]'},
 {id:'q',type:'filter',fieldId:'joined_refund'}]
 const g=pipelineRelationGraph(primary,others,left,right,steps)
 assert.equal(g?.mode,'JOIN')
 assert.equal(g?.source.name,'销售事实表')
 assert.equal(g?.target.name,'退款维表')
 assert.deepEqual(g?.links,[{from:'城市',to:'退款城市',compatible:true}])
 assert.equal(g?.outputs[0].fieldId,'joined_refund')
 assert.equal(g?.downstreamSteps,2)
 assert.equal(g?.warning,'')
})
test('invalid key, unacknowledged fanout, UNION mapping and no raw SQL',()=>{
 const join={id:'j',type:'join',sourceDatasetId:'refund',joinType:'INNER',
  acknowledgeFanout:false,conditions:[{leftFieldId:'city',rightFieldId:'refund'}],
  fields:[]}
 let g=pipelineRelationGraph(primary,others,left,right,[join])
 assert.equal(g?.links[0].compatible,false)
 assert.match(g?.warning||'',/行数放大/)
 const union={id:'u',type:'union',sourceDatasetId:'refund',distinct:false,
  mappings:[{leftFieldId:'city',rightFieldId:'rcity'}]}
 g=pipelineRelationGraph(primary,others,left,right,[union])
 assert.equal(g?.mode,'UNION')
 assert.equal(g?.links[0].compatible,true)
 assert.match(g?.title||'',/保留重复/)
 assert.equal(JSON.stringify(g).includes('SELECT'),false)
})
