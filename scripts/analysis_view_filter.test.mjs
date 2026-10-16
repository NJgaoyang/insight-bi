import test from 'node:test'
import assert from 'node:assert/strict'
import {filterForViewWidget,isValidViewFilter,setViewFilter,viewFilterCandidates} from '../frontend/src/analysis/viewFilter.ts'
const widget=(id,datasetId,fieldId)=>({
 id,title:'组件 '+id,datasetId,analysisV2:{dimensions:[{fieldId}],measures:[{id:'m',fieldId:'revenue',aggregate:'SUM'}],
  filters:{pre:[],post:[]},sort:[],limit:100}
})
const widgets=[widget('a','sales','city'),widget('b','sales','city'),
 widget('c','sales','province'),widget('d','expenses','city')]
test('only visible V2 widget dimensions become selectable and deduplicate',()=>{
 const view={widgetIds:['a','b','c']}
 const choices=viewFilterCandidates(view,widgets)
 assert.equal(choices.length,2)
 assert.deepEqual(choices.map(x=>x.datasetId+':'+x.fieldId),['sales:city','sales:province'])
})
test('view filter is strictly same dataset and target-bound stable field',()=>{
 const view={widgetIds:['a','b','c'],filter:{datasetId:'sales',fieldId:'city'}}
 assert.deepEqual(filterForViewWidget(view,widgets[1],'北京'),{fieldId:'city',op:'eq',value:'北京'})
 assert.equal(filterForViewWidget(view,widgets[2],'北京'),null)
 assert.equal(filterForViewWidget(view,widgets[3],'北京'),null)
 assert.equal(filterForViewWidget(view,widgets[1],''),null)
 assert.equal(filterForViewWidget(view,widgets[1],'x'.repeat(121)),null)
})
test('a view never grants filtering into an unrelated dataset or stale field',()=>{
 const view={widgetIds:['a','b']}
 setViewFilter(view,{datasetId:'sales',fieldId:'city',label:'城市',key:'key'})
 assert.equal(isValidViewFilter(view,widgets),true)
 view.widgetIds=['d']
 assert.equal(isValidViewFilter(view,widgets),false)
 assert.equal(filterForViewWidget(view,widgets[3],'北京'),null)
 setViewFilter(view,null)
 assert.equal(isValidViewFilter(view,widgets),true)
})
test('view-local metadata is pure and never carries runtime value',()=>{
 const first={widgetIds:['a']},second={widgetIds:['d']}
 const choice=viewFilterCandidates(first,widgets)[0]
 setViewFilter(first,choice)
 assert.deepEqual(first.filter,{datasetId:'sales',fieldId:'city',label:choice.label})
 assert.equal(second.filter,undefined)
 assert.equal(JSON.stringify(first).includes('北京'),false)
})
