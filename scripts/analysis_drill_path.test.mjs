import test from 'node:test'
import assert from 'node:assert/strict'
import {drillTarget,invalidDrillLinks,removeInvalidDrillLinks} from '../frontend/src/analysis/drillPath.ts'
const widget=(id,ds,field)=>({id,title:id,datasetId:ds,analysisV2:{
 dimensions:[{fieldId:field}],measures:[{id:'total',fieldId:'sales',aggregate:'SUM'}],
 filters:{pre:[],post:[]},sort:[],limit:100}})
const widgets=[widget('source','ds1','city'),widget('target','ds1','city'),
 widget('unrelated','ds2','city')]
const views=()=>[{id:'from',name:'来源',widgetIds:['source'],jumps:{source:'to'},drills:{source:true}},
 {id:'to',name:'目标',widgetIds:['target'],
 filter:{datasetId:'ds1',fieldId:'city',label:'城市'}}]
test('pinned drill passes only selected first source dimension to target',()=>{
 const [source,...rest]=views()
 assert.deepEqual(drillTarget(source,widgets[0],{fieldId:'city',value:'北京'},[source,...rest],widgets),
  {targetViewId:'to',value:'北京'})
 assert.deepEqual(invalidDrillLinks(views(),widgets),[])
})
test('disabled/missing jump and mismatched dimension never propagate',()=>{
 const [source,target]=views()
 source.drills.source=false
 assert.equal(drillTarget(source,widgets[0],{fieldId:'city',value:'北京'},[source,target],widgets),null)
 source.drills.source=true
 source.jumps.source='missing'
 assert.equal(drillTarget(source,widgets[0],{fieldId:'city',value:'北京'},[source,target],widgets),null)
 source.jumps.source='to'
 assert.equal(drillTarget(source,widgets[0],{fieldId:'other',value:'北京'},[source,target],widgets),null)
 target.filter.fieldId='region'
 assert.equal(drillTarget(source,widgets[0],{fieldId:'city',value:'北京'},[source,target],widgets),null)
})
test('value size, controls and cross-source chart cannot drill',()=>{
 const instances=views()
 for(const value of ['', 'x'.repeat(121), 'abc\nid']){
  assert.equal(drillTarget(instances[0],widgets[0],{fieldId:'city',value},instances,widgets),null)
 }
 assert.equal(drillTarget(instances[0],widgets[2],{fieldId:'city',value:'北京'},instances,widgets),null)
})
test('views deleted, mismatched targets or obsolete jumps prune only invalid links',()=>{
 const instances=views()
 instances[0].drills.other=true
 assert.deepEqual(invalidDrillLinks(instances,widgets).map(x=>x.widgetId),['other'])
 removeInvalidDrillLinks(instances,widgets)
 assert.deepEqual(instances[0].drills,{source:true})
 instances.splice(1)
 removeInvalidDrillLinks(instances,widgets)
 assert.deepEqual(instances[0].drills,{})
})
test('drill produces runtime value only, never mutates saved view or widget config',()=>{
 const input=views()
 const before=JSON.stringify({input,widgets})
 const path=drillTarget(input[0],widgets[0],{fieldId:'city',value:123},input,widgets)
 assert.equal(path?.value,'123')
 assert.equal(JSON.stringify({input,widgets}),before)
 assert.ok(!before.includes('123'))
})
