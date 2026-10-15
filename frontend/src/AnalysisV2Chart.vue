<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,ref,watch} from 'vue'
import * as echarts from 'echarts'
import {analysisCsv} from './analysis/exportCsv'
import {buildPivot,pivotCell,pivotRowTotal,pivotColumnTotal,pivotGrandTotal,formatPivotTotal,type PivotAxis} from './analysis/pivotTable'
import {api,type AnalysisWidgetV2,type QueryV2Result,type FilterV2} from './api'

const props=defineProps<{widget:AnalysisWidgetV2,readonly?:boolean,navigable?:boolean,linkedFilter?:FilterV2|null;viewerReportId?:string}>()
const emit=defineEmits<{(e:'navigate'):void;(e:'selectDimension',selection:{fieldId:string;value:string|number}|null):void;(e:'tableColumnsChange',hiddenIds:string[]):void}>()
const element=ref<HTMLElement|null>(null)
const loading=ref(false),error=ref(''),rows=ref<Record<string,unknown>[]>([])
const result=ref<QueryV2Result|null>(null)
const bindings=computed(()=>props.widget.analysisV2)
let chart:echarts.ECharts|null=null,observer:ResizeObserver|null=null,generation=0
let activeRequest:AbortController|null=null
const supported=['bar','hbar','line','area','pie','donut','combo','metric','table','pivot']
const dimNames=computed(()=>result.value?.columns.filter(c=>c.kind==='dimension')??[])
const measureNames=computed(()=>result.value?.columns.filter(c=>c.kind==='measure')??[])
const hiddenColumnIds=ref<string[]>([...(bindings.value?.tableHiddenColumnIds??[])])
watch(()=>bindings.value?.tableHiddenColumnIds,ids=>{hiddenColumnIds.value=[...(ids??[])]},{deep:true})
const tableSearch=ref('')
const visibleColumns=computed(()=>{
 const cols=result.value?.columns??[]
 const visible=cols.filter(c=>!hiddenColumnIds.value.includes(c.id))
 return visible.length?visible:cols.slice(0,1)
})
const tableRows=computed(()=>{const q=tableSearch.value.trim().toLowerCase();return q?rows.value.filter(row=>visibleColumns.value.some(c=>String(row[c.name]??'').toLowerCase().includes(q))):rows.value})
function toggleColumn(id:string){
 const next=hiddenColumnIds.value.includes(id)?hiddenColumnIds.value.filter(x=>x!==id):[...hiddenColumnIds.value,id]
 if(result.value&&next.length>=result.value.columns.length)return
 hiddenColumnIds.value=next
 if(!props.readonly)emit('tableColumnsChange',[...next])
}

const pivotModel=computed(()=>result.value&&bindings.value?buildPivot(result.value,bindings.value):null)
const pivotShowTotals=computed(()=>bindings.value?.pivot?.showTotals!==false)
const pivotExact=computed(()=>result.value?.pivotTotals?.exact===true)
function formatTotal(value:unknown,id:string){
 return pivotExact.value?formatPivotTotal(value,optionsFor(id)?.decimals):formatMeasure(value,id)
}
const pivotPage=ref(1)
const pivotPageSize=20
const pivotPageCount=computed(()=>Math.max(1,Math.ceil((pivotModel.value?.rows.length??0)/pivotPageSize)))
const visiblePivotRows=computed(()=>pivotModel.value?.rows.slice(
 (pivotPage.value-1)*pivotPageSize,pivotPage.value*pivotPageSize)??[])

function selectPivotRow(axis:PivotAxis){
 const field=bindings.value?.dimensions[0]
 if(!field)return
 if(typeof axis.value==='string'||typeof axis.value==='number')
  emit('selectDimension',{fieldId:field.fieldId,value:axis.value})
 if(props.navigable)emit('navigate')
}
const metricValue=computed(()=>measureNames.value.length&&rows.value.length?rows.value[0][measureNames.value[0].name]:null)
const format=(v:unknown)=>v===undefined||v===null?'—':typeof v==='number'?new Intl.NumberFormat('zh-CN',{maximumFractionDigits:2}).format(v):String(v)
const numeric=(v:unknown):number|null=>{if(v===null||v===undefined||v==='')return null;const n=Number(v);return Number.isFinite(n)?n:null}
const optionsFor=(id:string)=>bindings.value?.measures.find(m=>m.id===id)
function formatMeasure(value:unknown,id:string){
 const base=numeric(value)
 if(base===null)return '—'
 const decimals=optionsFor(id)?.decimals
 if(decimals===undefined||decimals===null)return format(base)
 const requested=Number(decimals)
 if(!Number.isFinite(requested))return format(base)
 const digits=Math.max(0,Math.min(6,Math.trunc(requested)))
 return new Intl.NumberFormat('zh-CN',{minimumFractionDigits:digits,maximumFractionDigits:digits}).format(base)
}
function formatCell(row:Record<string,unknown>,col:{name:string;id:string;kind:string}){
 return col.kind==='measure'?formatMeasure(row[col.name],col.id):format(row[col.name])
}
function downloadCsv(){
 if(loading.value||error.value||!result.value||!visibleColumns.value.length)return
 const csv=analysisCsv(visibleColumns.value,props.widget.type==='table'?tableRows.value:rows.value)
 const blob=new Blob([csv],{type:'text/csv;charset=utf-8'})
 const url=URL.createObjectURL(blob)
 try{
  const anchor=document.createElement('a')
  anchor.href=url
  anchor.download=(props.widget.title||'分析结果').replace(/[\\/:*?"<>|]/g,'_').slice(0,80)+'.csv'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
 }finally{URL.revokeObjectURL(url)}
}
function dispose(){if(chart){chart.dispose();chart=null}}
async function load(){
 const gen=++generation,config=bindings.value
 activeRequest?.abort();activeRequest=null
 if(!props.widget.datasetId||!config||!config.measures.length){
  result.value=null;rows.value=[];error.value='请选择数据集并拖入至少一个指标';loading.value=false;dispose();return
 }
 if(props.widget.type==='pivot'&&config.dimensions.length!==2){
  result.value=null;rows.value=[];error.value='交叉表需要恰好两个维度（行、列）及至少一个指标';loading.value=false;dispose();return
 }
 if(!supported.includes(props.widget.type)){
  result.value=null;rows.value=[];error.value='当前 V2 图表类型尚未支持，请选择柱图、折线图、饼图、指标卡或明细表';dispose();return
 }
 loading.value=true;error.value=''
 const controller=new AbortController();activeRequest=controller
 try{
  const path=props.viewerReportId
   ?'/reader/dashboards/'+encodeURIComponent(props.viewerReportId)+'/widgets/'+encodeURIComponent(props.widget.id)+'/query'
   :'/analysis/v2/query'
  const request=props.viewerReportId?{}:{datasetId:props.widget.datasetId,...config,
   ...(props.widget.type==='pivot'?{pivot:{...config.pivot,showTotals:config.pivot?.showTotals!==false}}:{}),
   filters:{pre:[...(config.filters?.pre||[]),...(props.linkedFilter?[props.linkedFilter]:[])],post:config.filters?.post||[]}}
  const response=await api<QueryV2Result>(path,{method:'POST',body:JSON.stringify(request),signal:controller.signal})
  if(gen!==generation)return
  result.value=response;rows.value=response.rows;pivotPage.value=1
  await nextTick();render()
 }catch(e:unknown){
  if(gen===generation){error.value=e instanceof Error?e.message:'查询失败';rows.value=[];result.value=null;dispose()}
 }finally{if(gen===generation){loading.value=false;if(activeRequest===controller)activeRequest=null}}
}
function selectRow(index:number){
 const dim=bindings.value?.dimensions[0],col=dimNames.value[0],row=rows.value[index]
 if(!dim||!col||!row)return
 const value=row[col.name]
 if(typeof value==='string'||typeof value==='number')emit('selectDimension',{fieldId:dim.fieldId,value})
}
function render(){
 const t=props.widget.type
 if(!element.value||!rows.value.length||['table','pivot','metric'].includes(t)){dispose();return}
 const dims=dimNames.value,metrics=measureNames.value
 if(!metrics.length||(!dims.length&&t!=='metric')){dispose();return}
 if(!chart)chart=echarts.init(element.value,undefined,{renderer:'canvas'})
 const categories=rows.value.map(row=>dims.map(d=>format(row[d.name])).join(' / '))
 const style=props.widget.style||{}
 const palette=[style.chartColor||'#3671dc','#16a6a4','#eba54c','#846ac7','#df7684']
 const base:any={
   color:palette,animationDuration:220,tooltip:{trigger:['pie','donut'].includes(t)?'item':'axis'},
   legend:{show:style.legend!==false,bottom:0,type:'scroll'},
   grid:{left:32,right:28,top:22,bottom:60,containLabel:true},
   textStyle:{fontSize:12,color:style.color||'#53647d'}
 }
 if(t==='pie'||t==='donut'){
   if(metrics.length!==1){error.value='饼图仅支持一个指标，请移除其他指标';dispose();return}
   base.series=[{name:metrics[0].label,type:'pie',radius:t==='donut'?['47%','70%']:'70%',
      center:['50%','44%'],data:rows.value.map((r,i)=>({name:categories[i],value:numeric(r[metrics[0].name])})),
      label:{show:optionsFor(metrics[0].id)?.showLabel??!!style.dataLabel,formatter:(p:any)=>p.name+': '+formatMeasure(p.value,metrics[0].id)},
      tooltip:{valueFormatter:(value:unknown)=>formatMeasure(value,metrics[0].id)}}]
 }else{
   const horizontal=t==='hbar'
   const dualAxis=!horizontal&&metrics.some(metric=>optionsFor(metric.id)?.axis==='right')
   base.xAxis=horizontal?{type:'value'}:{type:'category',data:categories,axisLabel:{hideOverlap:true}}
   base.yAxis=horizontal?{type:'category',data:categories}:dualAxis?[{type:'value'},{type:'value',alignTicks:true}]:{type:'value'}
   base.series=metrics.map((metric,i)=>{
     const setting=optionsFor(metric.id)
     return {
       name:metric.label,
       type:t==='line'||t==='area'?'line':t==='combo'?(setting?.seriesType||(i%2===1?'line':'bar')):'bar',
       yAxisIndex:dualAxis&&setting?.axis==='right'?1:0,
       data:rows.value.map(row=>numeric(row[metric.name])),
       itemStyle:setting?.color?{color:setting.color}:undefined,
       smooth:!!style.smooth,areaStyle:t==='area'?{opacity:0.14}:undefined,
       label:{show:setting?.showLabel??!!style.dataLabel,position:'top',formatter:(p:any)=>formatMeasure(p.value,metric.id)},
       tooltip:{valueFormatter:(value:unknown)=>formatMeasure(value,metric.id)}
     }
   })
 }
 error.value='';chart.setOption(base,{notMerge:true});
 chart.off('click')
 chart.on('click',params=>{if(typeof params.dataIndex==='number')selectRow(params.dataIndex);if(props.navigable)emit('navigate')})
 chart.resize()
}
const queryKey=computed(()=>{
 const b=bindings.value
 return JSON.stringify([props.widget.datasetId,b?{
   usePipeline:b.usePipeline??false,pipelineRevision:b.pipelineRevision,
   dimensions:b.dimensions,
   measures:b.measures.map(m=>({id:m.id,fieldId:m.fieldId,aggregate:m.aggregate,distinct:m.distinct,alias:m.alias})),
   isPivot:props.widget.type==='pivot',filters:b.filters,linkedFilter:props.linkedFilter,rank:b.rank,share:b.share,running:b.running,periodCompare:b.periodCompare,sort:b.sort,limit:b.limit
 }:null])
})
watch(queryKey,load,{immediate:true})
watch(()=>JSON.stringify(bindings.value?.pivot),()=>{pivotPage.value=1})
watch(()=>JSON.stringify([props.widget.type,props.widget.style,bindings.value?.measures.map(m=>({
 color:m.color,axis:m.axis,seriesType:m.seriesType,decimals:m.decimals,showLabel:m.showLabel
}))]),()=>nextTick(render))
watch(element,el=>{
 observer?.disconnect()
 if(el){observer=new ResizeObserver(()=>chart?.resize());observer.observe(el);nextTick(render)}
},{flush:'post'})
onBeforeUnmount(()=>{generation++;activeRequest?.abort();observer?.disconnect();dispose()})
</script>
<template>
 <div class="v2-analysis-widget">
  <div v-if="loading" class="v2-chart-notice">正在查询 StarRocks…</div>
  <div v-else-if="error" class="v2-chart-notice v2-chart-error">{{error}}</div>
  <div v-else-if="!rows.length" class="v2-chart-notice">暂无查询结果</div>
  <template v-else-if="widget.type==='metric'">
    <div class="v2-metric-value" :role="navigable?'button':undefined" :tabindex="navigable?0:undefined"
     @click="navigable&&emit('navigate')" @keydown.enter="navigable&&emit('navigate')">{{formatMeasure(metricValue,measureNames[0]?.id||'')}}</div>
    <div class="v2-metric-label">{{measureNames[0]?.label}}</div>
  </template>
  <div v-else-if="widget.type==='table'" class="v2-table-wrap">
   <div class="v2-table-controls">
    <input v-model="tableSearch" aria-label="搜索当前表格结果" placeholder="搜索当前结果" />
    <details><summary>显示列</summary>
     <label v-for="c in result?.columns||[]" :key="c.id">
      <input type="checkbox" :checked="!hiddenColumnIds.includes(c.id)"
       :disabled="!hiddenColumnIds.includes(c.id)&&visibleColumns.length===1"
       @change="toggleColumn(c.id)" />{{c.label}}
     </label>
    </details>
   </div>
   <table><thead><tr><th v-for="c in visibleColumns" :key="c.name">{{c.label}}</th></tr></thead>
   <tbody><tr v-for="(row,index) in tableRows" :key="index"
    :role="navigable?'button':undefined" :tabindex="navigable?0:undefined"
    @click="selectRow(index);navigable&&emit('navigate')" @keydown.enter="selectRow(index);navigable&&emit('navigate')"><td v-for="c in visibleColumns" :key="c.name">{{formatCell(row,c)}}</td></tr></tbody></table>
  </div>
  <div v-else-if="widget.type==='pivot'" class="v2-table-wrap v2-pivot-wrap">
   <div v-if="pivotModel?.error" class="v2-chart-notice v2-chart-error">{{pivotModel.error}}</div>
   <template v-else-if="pivotModel">
    <div v-if="pivotModel.warning" class="v2-pivot-warning" role="status">{{pivotModel.warning}}</div>
    <table aria-label="V2 交叉表">
     <thead>
      <tr><th :rowspan="2" class="v2-pivot-corner">
       {{result?.columns.find(c=>c.kind==='dimension'&&c.id===bindings?.dimensions[0]?.fieldId)?.label||'行'}}
       /
       {{result?.columns.find(c=>c.kind==='dimension'&&c.id===bindings?.dimensions[1]?.fieldId)?.label||'列'}}
      </th><th v-for="column in pivotModel.columns" :key="column.key" :colspan="pivotModel.measures.length">{{column.label}}</th>
       <th v-if="pivotShowTotals" :colspan="pivotModel.measures.length">行合计</th>
      </tr>
      <tr><template v-for="column in pivotModel.columns" :key="column.key">
       <th v-for="metric in pivotModel.measures" :key="metric.id">{{metric.label}}</th>
      </template><template v-if="pivotShowTotals">
       <th v-for="metric in pivotModel.measures" :key="metric.id">{{metric.label}}</th>
      </template></tr>
     </thead>
     <tbody><tr v-for="axis in visiblePivotRows" :key="axis.key">
      <th scope="row" :tabindex="navigable?0:undefined" @click="selectPivotRow(axis)"
       @keydown.enter="selectPivotRow(axis)">{{axis.label}}</th>
      <template v-for="column in pivotModel.columns" :key="column.key">
       <td v-for="metric in pivotModel.measures" :key="metric.id">{{formatMeasure(pivotCell(pivotModel,axis.key,column.key,metric.id),metric.id)}}</td>
      </template>
      <template v-if="pivotShowTotals">
       <td v-for="metric in pivotModel.measures" :key="metric.id" class="v2-pivot-total">
        {{pivotModel.additive.has(metric.id)?formatTotal(pivotRowTotal(pivotModel,axis.key,metric.id),metric.id):'—'}}
       </td>
      </template>
     </tr></tbody>
     <tfoot v-if="pivotShowTotals"><tr><th>列合计</th>
      <template v-for="column in pivotModel.columns" :key="column.key">
       <th v-for="metric in pivotModel.measures" :key="metric.id">
        {{pivotModel.additive.has(metric.id)?formatTotal(pivotColumnTotal(pivotModel,column.key,metric.id),metric.id):'—'}}
       </th>
      </template>
      <th v-for="metric in pivotModel.measures" :key="metric.id">
       {{pivotModel.additive.has(metric.id)?formatTotal(pivotGrandTotal(pivotModel,metric.id),metric.id):'—'}}
      </th>
     </tr></tfoot>
    </table>
    <div class="v2-pivot-caption">
     <span>{{pivotModel.rows.length}} 行组 × {{pivotModel.columns.length}} 列组 × {{pivotModel.measures.length}} 指标
      <span v-if="pivotShowTotals"> · {{pivotExact?'由服务端重新聚合全部指标':'兼容模式：仅合计可加指标'}}</span>
     </span>
     <span v-if="pivotPageCount>1" class="v2-pivot-pager">
      <button type="button" :disabled="pivotPage<=1" @click="pivotPage--">上一页</button>
      {{pivotPage}} / {{pivotPageCount}}
      <button type="button" :disabled="pivotPage>=pivotPageCount" @click="pivotPage++">下一页</button>
     </span>
    </div>
   </template>
  </div>
  <div v-show="!loading&&!error&&rows.length>0&&!['metric','table','pivot'].includes(widget.type)" ref="element" class="v2-echart-canvas"></div>
  <div v-if="result&&!loading&&!error&&rows.length" class="v2-query-meta">
   <button type="button" aria-label="导出当前查询结果CSV" @click="downloadCsv">{{widget.type==='pivot'?'导出分组查询 CSV':'导出 CSV'}}（当前 {{rows.length}} 行）</button>
  </div>
  <div v-if="result&&!loading" class="v2-query-meta">
    {{rows.length}} 行 · {{result.executionMs}} ms <span v-if="result.truncated">· 已达到查询行数上限</span>
  </div>
 </div>
</template>
