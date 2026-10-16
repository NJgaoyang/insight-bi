import test from 'node:test'
import assert from 'node:assert/strict'
import {filterForViewWidget,isValidViewFilter,setViewFilter,viewFilterCandidates,mappingCandidates,updateViewFilterMapping,secondaryViewFilterCandidates,isValidSecondaryViewFilter,setSecondaryViewFilter,filtersForViewWidget} from '../frontend/src/analysis/viewFilter.ts'
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

test('explicit different-ID field mapping works for same connection and equal type only',()=>{
 const examples=[widget('source','d1','city_v2'),widget('target','d2','region_v2'),
  widget('other','d3','region_v2')]
 const datasets=[{id:'d1',connection_id:'primary'},{id:'d2',connection_id:'primary'},
  {id:'d3',connection_id:'secondary'}]
 const directory={d1:[{id:'city_v2',logicalType:'STRING',calculated:false}],
  d2:[{id:'region_v2',logicalType:'STRING',calculated:false}],
  d3:[{id:'region_v2',logicalType:'STRING',calculated:false}]}
 const view={widgetIds:['source','target','other'],filter:{datasetId:'d1',fieldId:'city_v2'}}
 assert.deepEqual(mappingCandidates(view,examples[1],examples,datasets,directory),
  [{fieldId:'region_v2',label:'region_v2'}])
 assert.deepEqual(mappingCandidates(view,examples[2],examples,datasets,directory),[])
 assert.equal(filterForViewWidget(view,examples[1],'北京'),null,
  'same field name never enables implicit cross dataset filtering')
 updateViewFilterMapping(view,'target','region_v2')
 assert.deepEqual(filterForViewWidget(view,examples[1],'北京'),
  {fieldId:'region_v2',op:'eq',value:'北京'})
 assert.equal(filterForViewWidget(view,examples[2],'北京'),null)
 assert.equal(isValidViewFilter(view,examples),true)
 updateViewFilterMapping(view,'target','')
 assert.equal(filterForViewWidget(view,examples[1],'北京'),null)
})
test('field type mismatch and calculated target are never offered as mappings',()=>{
 const w=[widget('s','a','city'),widget('t','b','region')]
 const v={widgetIds:['s','t'],filter:{datasetId:'a',fieldId:'city'}}
 const data=[{id:'a',connection_id:'C'},{id:'b',connection_id:'C'}]
 const dirs={a:[{id:'city',logicalType:'STRING',calculated:false}],
  b:[{id:'region',logicalType:'INTEGER',calculated:false}]}
 assert.deepEqual(mappingCandidates(v,w[1],w,data,dirs),[])
 dirs.b[0].logicalType='STRING';dirs.b[0].calculated=true
 assert.deepEqual(mappingCandidates(v,w[1],w,data,dirs),[])
 dirs.b[0].calculated=false
 assert.deepEqual(mappingCandidates(v,w[1],w,data,dirs).map(x=>x.fieldId),['region'])
 updateViewFilterMapping(v,'t','region')
 updateViewFilterMapping(v,'t','region')
 assert.equal(v.filter.mappings.length,1)
 assert.equal(isValidViewFilter(v,w),true)
 v.widgetIds=['s']
 assert.equal(isValidViewFilter(v,w),false)
 assert.equal(filterForViewWidget(v,w[1],'北京'),null)
})

test('second view filter exposes only another dimension on the primary dataset',()=>{
 const widgets2=[widget('a','sales','city'),widget('b','sales','region'),
  widget('c','expenses','region')]
 const view={widgetIds:['a','b','c'],filter:{datasetId:'sales',fieldId:'city'}}
 assert.deepEqual(secondaryViewFilterCandidates(view,widgets2).map(c=>c.fieldId),['region'])
 setSecondaryViewFilter(view,secondaryViewFilterCandidates(view,widgets2)[0])
 assert.equal(isValidSecondaryViewFilter(view,widgets2),true)
 assert.deepEqual(filtersForViewWidget(view,widgets2[1],'北京','华北'),
  [{fieldId:'region',op:'eq',value:'华北'}])
 assert.deepEqual(filtersForViewWidget(view,widgets2[0],'北京','华北'),
  [{fieldId:'city',op:'eq',value:'北京'}])
 assert.deepEqual(filtersForViewWidget(view,widgets2[2],'北京','华北'),[])
 // Both conditions apply on a chart with both fields explicitly bound.
 widgets2[0].analysisV2.dimensions.push({fieldId:'region'})
 assert.deepEqual(filtersForViewWidget(view,widgets2[0],'北京','华北'),
  [{fieldId:'city',op:'eq',value:'北京'},{fieldId:'region',op:'eq',value:'华北'}])
 assert.deepEqual(filtersForViewWidget(view,widgets2[0],'','华北'),
  [{fieldId:'region',op:'eq',value:'华北'}])
 assert.equal(JSON.stringify(view).includes('华北'),false)
})
test('secondary rejects same field, missing primary, foreign dataset and mapped target',()=>{
 const view={widgetIds:['a','b','c'],filter:{datasetId:'sales',fieldId:'city'},
  filter2:{datasetId:'sales',fieldId:'city'}}
 assert.equal(isValidSecondaryViewFilter(view,widgets),false)
 view.filter2={datasetId:'expenses',fieldId:'city'}
 assert.equal(isValidSecondaryViewFilter(view,widgets),false)
 view.filter2={datasetId:'sales',fieldId:'province',mappings:[{widgetId:'d',fieldId:'city'}]}
 assert.equal(isValidSecondaryViewFilter(view,widgets),false)
 delete view.filter
 assert.equal(isValidSecondaryViewFilter(view,widgets),false)
})
