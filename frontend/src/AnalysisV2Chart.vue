<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,ref,watch} from 'vue'
import * as echarts from 'echarts'
import {api,type AnalysisWidgetV2,type QueryV2Result} from './api'

const props=defineProps<{widget:AnalysisWidgetV2,readonly?:boolean}>()
const element=ref<HTMLElement|null>(null)
const loading=ref(false),error=ref(''),rows=ref<Record<string,unknown>[]>([])
const result=ref<QueryV2Result|null>(null)
const bindings=computed(()=>props.widget.analysisV2)
let chart:echarts.ECharts|null=null,observer:ResizeObserver|null=null,generation=0
let activeRequest:AbortController|null=null
const supported=['bar','hbar','line','area','pie','donut','combo','metric','table']
const dimNames=computed(()=>result.value?.columns.filter(c=>c.kind==='dimension')??[])
const measureNames=computed(()=>result.value?.columns.filter(c=>c.kind==='measure')??[])
const visibleColumns=computed(()=>result.value?.columns??[])
const metricValue=computed(()=>measureNames.value.length&&rows.value.length?rows.value[0][measureNames.value[0].name]:null)
const format=(v:unknown)=>v===undefined||v===null?'—':typeof v==='number'?new Intl.NumberFormat('zh-CN',{maximumFractionDigits:2}).format(v):String(v)
const numeric=(v:unknown):number|null=>{if(v===null||v===undefined||v==='')return null;const n=Number(v);return Number.isFinite(n)?n:null}
function dispose(){if(chart){chart.dispose();chart=null}}
async function load(){
 const gen=++generation,config=bindings.value
 activeRequest?.abort();activeRequest=null
 if(!props.widget.datasetId||!config||!config.measures.length){
  result.value=null;rows.value=[];error.value='请选择数据集并拖入至少一个指标';loading.value=false;dispose();return
 }
 if(!supported.includes(props.widget.type)){
  result.value=null;rows.value=[];error.value='当前 V2 图表类型尚未支持，请选择柱图、折线图、饼图、指标卡或明细表';dispose();return
 }
 loading.value=true;error.value=''
 const controller=new AbortController();activeRequest=controller
 try{
  const response=await api<QueryV2Result>('/analysis/v2/query',{method:'POST',body:JSON.stringify({datasetId:props.widget.datasetId,...config}),signal:controller.signal})
  if(gen!==generation)return
  result.value=response;rows.value=response.rows
  await nextTick();render()
 }catch(e:unknown){
  if(gen===generation){error.value=e instanceof Error?e.message:'查询失败';rows.value=[];result.value=null;dispose()}
 }finally{if(gen===generation){loading.value=false;if(activeRequest===controller)activeRequest=null}}
}
function render(){
 const t=props.widget.type
 if(!element.value||!rows.value.length||['table','metric'].includes(t)){dispose();return}
 const dims=dimNames.value,metrics=measureNames.value
 if(!metrics.length||(!dims.length&&t!=='metric')){dispose();return}
 if(!chart)chart=echarts.init(element.value,undefined,{renderer:'canvas'})
 const x=dims[0],categories=rows.value.map(row=>dims.map(d=>format(row[d.name])).join(' / '))
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
      label:{show:!!style.dataLabel}}]
 }else{
   const horizontal=t==='hbar'
   base.xAxis=horizontal?{type:'value'}:{type:'category',data:categories,axisLabel:{hideOverlap:true}}
   base.yAxis=horizontal?{type:'category',data:categories}:{type:'value'}
   base.series=metrics.map((metric,i)=>({
     name:metric.label,type:t==='line'||t==='area'?'line':t==='combo'&&i%2===1?'line':'bar',
     data:rows.value.map(row=>numeric(row[metric.name])),
     smooth:!!style.smooth,areaStyle:t==='area'?{opacity:0.14}:undefined,
     label:{show:!!style.dataLabel,position:'top'}
   }))
 }
 error.value='';chart.setOption(base,{notMerge:true});chart.resize()
}
watch(()=>JSON.stringify([props.widget.datasetId,props.widget.analysisV2,props.widget.type]),load,{immediate:true})
watch(()=>JSON.stringify(props.widget.style),()=>nextTick(render))
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
    <div class="v2-metric-value">{{format(metricValue)}}</div>
    <div class="v2-metric-label">{{measureNames[0]?.label}}</div>
  </template>
  <div v-else-if="widget.type==='table'" class="v2-table-wrap">
   <table><thead><tr><th v-for="c in visibleColumns" :key="c.name">{{c.label}}</th></tr></thead>
   <tbody><tr v-for="(row,index) in rows" :key="index"><td v-for="c in visibleColumns" :key="c.name">{{format(row[c.name])}}</td></tr></tbody></table>
  </div>
  <div v-show="!loading&&!error&&rows.length>0&&!['metric','table'].includes(widget.type)" ref="element" class="v2-echart-canvas"></div>
  <div v-if="result&&!loading" class="v2-query-meta">
    {{rows.length}} 行 · {{result.executionMs}} ms <span v-if="result.truncated">· 已达到查询行数上限</span>
  </div>
 </div>
</template>
