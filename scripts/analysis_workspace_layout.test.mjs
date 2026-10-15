import test from 'node:test'
import assert from 'node:assert/strict'
import {reorderViewWidget,duplicateDashboardView,viewLayout,cardStyle,orderedViewWidgets,removeUnusedLayout}
  from '../frontend/src/analysis/dashboardLayout.ts'

const original=()=>({
 id:'overview',name:'经营总览',widgetIds:['w1','w2','w3'],
 layout:{w1:{columns:12,height:480},w2:{columns:6,height:320},orphan:{columns:12,height:640}},
 jumps:{w1:'finance',orphan:'missing'}
})
const components=()=>['w1','w2','w3'].map(id=>({id,title:id}))

test('drag reorder changes only active view order and leaves widget configurations intact',()=>{
 const view=original(),other=original(),widgets=components()
 assert.equal(reorderViewWidget(view,'w3','w1'),true)
 assert.deepEqual(view.widgetIds,['w3','w1','w2'])
 assert.deepEqual(orderedViewWidgets(view,widgets).map(x=>x.id),['w3','w1','w2'])
 assert.deepEqual(other.widgetIds,['w1','w2','w3'])
 assert.deepEqual(widgets,components())
 assert.deepEqual(view.layout.w1,{columns:12,height:480})
 assert.deepEqual(view.jumps.w1,'finance')
 assert.deepEqual(cardStyle(view,'w1'),{gridColumn:'span 12',height:'480px'})
})
test('invalid drag sources and no-op destinations are rejected without mutation',()=>{
 const view=original(),previous=structuredClone(view)
 assert.equal(reorderViewWidget(view,'invalid','w1'),false)
 assert.equal(reorderViewWidget(view,'w1','invalid'),false)
 assert.equal(reorderViewWidget(view,'w1','w1'),false)
 assert.equal(reorderViewWidget(view,'w2','w1','after'),false)
 assert.deepEqual(view,previous)
 assert.equal(reorderViewWidget(view,'w1','w3','after'),true)
 assert.deepEqual(view.widgetIds,['w2','w3','w1'])
})
test('duplicated view keeps shared references with isolated size, order, and removal',()=>{
 const source=original()
 const copy=duplicateDashboardView(source,'finance-copy','经营总览 副本')
 assert.notEqual(copy.layout,source.layout)
 assert.notEqual(copy.widgetIds,source.widgetIds)
 assert.deepEqual(copy.widgetIds,source.widgetIds)
 assert.deepEqual(copy.layout?.w1,source.layout?.w1)
 assert.equal(copy.layout?.orphan,undefined)
 assert.equal(copy.jumps?.orphan,undefined)
 assert.equal(copy.jumps?.w1,'finance')
 assert.equal(viewLayout(copy,'w1').height,480)
 copy.layout.w1.columns=6
 assert.equal(reorderViewWidget(copy,'w3','w1'),true)
 copy.widgetIds=copy.widgetIds.filter(id=>id!=='w2')
 removeUnusedLayout(copy)
 assert.deepEqual(source.widgetIds,['w1','w2','w3'])
 assert.equal(source.layout.w1.columns,12)
 assert.deepEqual(source.layout.w2,{columns:6,height:320})
 assert.equal(copy.layout.w2,undefined)
 assert.deepEqual(components().map(x=>x.id),['w1','w2','w3'])
})
test('view copy does not introduce self-referential jumps and validates IDs',()=>{
 const source=original()
 source.jumps.w2='overview'
 const copy=duplicateDashboardView(source,'second','Copy')
 assert.equal(copy.jumps.w2,undefined)
 assert.throws(()=>duplicateDashboardView(source,'overview','invalid'))
 assert.throws(()=>duplicateDashboardView(source,'second',' '))
})
