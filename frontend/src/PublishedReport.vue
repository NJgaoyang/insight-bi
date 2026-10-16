<script setup lang="ts">
import {computed,onMounted,nextTick,ref,watch} from 'vue'
import {api,type Dashboard,type Dataset,type AnalysisWidgetV2,type FilterV2} from './api'
import Designer from './Designer.vue'
import AnalysisV2Chart from './AnalysisV2Chart.vue'
import ChartWidget from './ChartWidget.vue'
import {cardStyle,orderedViewWidgets,type DashboardView} from './analysis/dashboardLayout'
import {filterForViewWidget,isValidViewFilter,isValidSecondaryViewFilter,filtersForViewWidget} from './analysis/viewFilter'
import {drillTarget,type DrillSelection} from './analysis/drillPath'

const props=defineProps<{id:string;viewerMode?:boolean}>()
const emit=defineEmits<{(e:'back'):void}>()
const loading=ref(true),error=ref('')
const report=ref<Dashboard|null>(null)
const kind=ref<'analysis'|'dashboard'|null>(null)
const widgets=ref<AnalysisWidgetV2[]>([])
const datasets=ref<Dataset[]>([])
const dashboardViews=ref<DashboardView[]>([])
const selectedViewId=ref('')
const filterDraft=ref(''),filterApplied=ref('')
const filterDraft2=ref(''),filterApplied2=ref('')
const linkedSelection=ref<{datasetId:string;fieldId:string;value:string|number;sourceWidgetId:string}|null>(null)
const activeView=computed(()=>dashboardViews.value.find(v=>v.id===selectedViewId.value))
const visibleWidgets=computed(()=>orderedViewWidgets(activeView.value,widgets.value))
function applyViewFilter(){
 filterApplied.value=filterDraft.value.trim().slice(0,120)
 filterApplied2.value=filterDraft2.value.trim().slice(0,120)
 linkedSelection.value=null
}
function clearViewFilter(){filterDraft.value='';filterApplied.value='';filterDraft2.value='';filterApplied2.value=''}
watch(selectedViewId,()=>{clearViewFilter();linkedSelection.value=null})
function resolvedFilters(widget:AnalysisWidgetV2):FilterV2[]{
 const pinned=filtersForViewWidget(activeView.value,widget,filterApplied.value,filterApplied2.value)
 return pinned.length?pinned:(props.viewerMode?[]:linkedFilterFor(widget)?[linkedFilterFor(widget)!]:[])
}
function resolvedFilter(widget:AnalysisWidgetV2):FilterV2|null{
 return filterForViewWidget(activeView.value,widget,filterApplied.value)
   ||(props.viewerMode?null:linkedFilterFor(widget))
}
function readerSecondValue(widget:AnalysisWidgetV2):string|undefined{
 const f=activeView.value?.filter2
 return f&&filterApplied2.value&&widget.datasetId===f.datasetId&&
  widget.analysisV2?.dimensions.some(d=>d.fieldId===f.fieldId)?filterApplied2.value:undefined
}
function readerFilterView(widget:AnalysisWidgetV2):string|undefined{
 return props.viewerMode&&(filterForViewWidget(activeView.value,widget,filterApplied.value)
   ||readerSecondValue(widget))?activeView.value?.id:undefined
}
const jumpTarget=(id:string)=>{
 const target=activeView.value?.jumps?.[id]
 return target&&dashboardViews.value.some(v=>v.id===target)?target:''
}
let pendingDrill:{widgetId:string;selection:DrillSelection}|null=null
async function navigateWidget(id:string,fromChart=false){
 const target=jumpTarget(id)
 if(!target)return
 const source=widgets.value.find(w=>w.id===id)
 const candidate=fromChart&&source&&pendingDrill?.widgetId===id
  ?drillTarget(activeView.value,source,pendingDrill.selection,dashboardViews.value,widgets.value):null
 pendingDrill=null
 linkedSelection.value=null
 selectedViewId.value=target
 await nextTick() // View-change watcher clears the previous view's runtime filters.
 if(candidate&&selectedViewId.value===candidate.targetViewId){
  filterDraft.value=candidate.value
  filterApplied.value=candidate.value
 }
}
function selectDimension(widget:AnalysisWidgetV2,selection:{fieldId:string;value:string|number}|null){
 if(!selection)return
 if(jumpTarget(widget.id)){
  pendingDrill={widgetId:widget.id,selection}
  return
 }
 if(props.viewerMode)return
 linkedSelection.value={datasetId:widget.datasetId,fieldId:selection.fieldId,
  value:selection.value,sourceWidgetId:widget.id}
}
function linkedFilterFor(widget:AnalysisWidgetV2):FilterV2|null{
 const selected=linkedSelection.value
 if(!selected||widget.id===selected.sourceWidgetId||widget.datasetId!==selected.datasetId||
  !widget.analysisV2?.dimensions.some(d=>d.fieldId===selected.fieldId))return null
 return {fieldId:selected.fieldId,op:'eq',value:selected.value}
}

async function load(){
 try{
  const saved=await api<Dashboard&{kind:string}>(props.viewerMode
   ?'/reader/dashboards/'+encodeURIComponent(props.id)
   :'/dashboards/'+encodeURIComponent(props.id)+'?published=true')
  kind.value=saved.kind==='analysis'?'analysis':'dashboard'
  if(kind.value==='dashboard')return
  report.value=saved
  widgets.value=Array.isArray(saved.content?.widgets)?saved.content.widgets:[]
  const stored=(saved.content as typeof saved.content & {dashboards?:DashboardView[]}).dashboards
  dashboardViews.value=Array.isArray(stored)&&stored.length
   ?stored.map(v=>({id:v.id,name:v.name,widgetIds:[...v.widgetIds],layout:v.layout?{...v.layout}:undefined,jumps:v.jumps?{...v.jumps}:undefined,drills:v.drills?{...v.drills}:undefined,filter:v.filter?{...v.filter,mappings:v.filter.mappings?.map(mapping=>({...mapping}))}:undefined,filter2:v.filter2?{...v.filter2}:undefined}))
   :[{id:'overview',name:'仪表板',widgetIds:widgets.value.map(w=>w.id)}]
  selectedViewId.value=dashboardViews.value[0]?.id??''
  if(!props.viewerMode)datasets.value=await api<Dataset[]>('/datasets')
 }catch(e:unknown){error.value=e instanceof Error?e.message:'发布内容读取失败'}
 finally{loading.value=false}
}
onMounted(load)
</script>

<template>
 <div v-if="loading" class="analysis-published-loading">正在读取已发布内容…</div>
 <div v-else-if="error" class="analysis-published-error">
  <button @click="emit('back')">← 返回</button>
  <span>{{error}}</span>
 </div>
 <Designer v-else-if="kind==='dashboard'&&!viewerMode" :id="id" :readonly="true" @back="emit('back')"/>
 <div v-else class="analysis-published-view">
  <header class="analysis-published-header">
   <button class="analysis-back" @click="emit('back')">← 我的分析</button>
   <strong>{{report?.name}}</strong>
   <span>已发布</span>
   <el-select v-model="selectedViewId" class="analysis-view-picker" size="small" aria-label="选择已发布仪表板">
    <el-option v-for="view in dashboardViews" :key="view.id" :label="view.name" :value="view.id"/>
   </el-select>
  </header>
  <main class="analysis-published-body">
   <div v-if="activeView?.filter&&isValidViewFilter(activeView,widgets)"
    class="analysis-view-filter analysis-view-filter-published">
    <label>{{activeView.filter.label||activeView.filter.fieldId}}</label>
    <el-input v-model="filterDraft" size="small" clearable maxlength="120"
     aria-label="已发布视图筛选值" placeholder="输入值后应用" @keyup.enter="applyViewFilter"/>
    <template v-if="activeView?.filter2&&isValidSecondaryViewFilter(activeView,widgets)">
     <label>{{activeView.filter2.label||activeView.filter2.fieldId}}</label>
     <el-input v-model="filterDraft2" size="small" clearable maxlength="120"
      aria-label="第二已发布视图筛选值" placeholder="第二条件（AND）" @keyup.enter="applyViewFilter"/>
    </template>
    <button type="button" @click="applyViewFilter">应用筛选</button>
    <button type="button" @click="clearViewFilter">清除筛选</button>
    <span v-if="filterApplied" class="analysis-filter-value">当前：{{filterApplied}}</span>
   </div>
   <div v-else-if="activeView?.filter" role="status" class="analysis-filter-warning">
    已发布的筛选字段失效，无法应用
   </div>
   <div v-if="linkedSelection" class="analysis-view-components">
    <span>已联动筛选：{{linkedSelection.value}}</span>
    <button @click="linkedSelection=null">清除筛选</button>
   </div>
   <div v-if="!visibleWidgets.length" class="analysis-stage-empty">当前视图没有展示组件</div>
   <div v-else class="analysis-view-grid">
    <div v-for="widget in visibleWidgets" :key="widget.id" class="analysis-dashboard-card"
     :data-widget-id="widget.id" :style="cardStyle(activeView,widget.id)">
     <div class="analysis-layout-toolbar">
      <strong>{{widget.title}}</strong>
      <button v-if="jumpTarget(widget.id)"
       @click="navigateWidget(widget.id)">前往 {{dashboardViews.find(v=>v.id===activeView?.jumps?.[widget.id])?.name}} →</button>
     </div>
     <AnalysisV2Chart v-if="widget.analysisV2" :widget="widget" :readonly="true"
      :navigable="!!jumpTarget(widget.id)" :linked-filter="resolvedFilter(widget)"
      :linked-filters="resolvedFilters(widget)"
      :viewer-report-id="viewerMode?id:undefined"
      :viewer-view-id="readerFilterView(widget)"
      :viewer-filter-value2="readerSecondValue(widget)"
      @select-dimension="selection=>selectDimension(widget,selection)" @navigate="navigateWidget(widget.id,true)"/>
     <ChartWidget v-else :widget="widget" :datasets="datasets" :readonly="true"/>
    </div>
   </div>
  </main>
 </div>
</template>
