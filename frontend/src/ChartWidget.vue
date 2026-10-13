<script setup lang="ts">
import {ref,computed,onMounted,onBeforeUnmount,watch,nextTick} from 'vue'
import * as echarts from 'echarts'
import {api,type Widget,type Dataset} from './api'
const props=defineProps<{widget:Widget,datasets:Dataset[],filters?:{field:string;op:string;value?:string;values?:string[]}[],readonly?:boolean}>()
const emit=defineEmits<{(e:'filter',value:{field:string;op:string;value?:string;values?:string[]}):void}>()
const loading=ref(false),error=ref(''),rows=ref<Record<string,any>[]>([]),element=ref<HTMLElement|null>(null)
const filterOptions=computed(()=>[...new Set(rows.value.map(row=>String(row[props.widget.dimension]??'')))] )
function filterChange(event:Event){
 const input=event.target as HTMLSelectElement
 const values=[...input.selectedOptions].map(o=>o.value)
 if(style.value.filterMultiple)emit('filter',{field:props.widget.dimension,op:'in',values})
 else emit('filter',{field:props.widget.dimension,op:'eq',value:input.value})
}
const ds=computed(()=>props.datasets.find(x=>x.id===props.widget.datasetId))
const cols=computed(()=>{const selected=props.widget.columns||[];return selected.length?selected:ds.value?.fields.slice(0,6).map(f=>f.name)||[]})
const measure=computed(()=>props.widget.measure||ds.value?.fields.find(f=>f.numeric)?.name||'')
const drillPath=ref<{field:string;value:string}[]>([])
const drillFields=computed(()=>[props.widget.dimension,...(props.widget.drillDimensions||[]).filter(f=>f!==props.widget.dimension&&ds.value?.fields.some(x=>x.name===f))].filter(Boolean))
const activeDimension=computed(()=>drillFields.value[Math.min(drillPath.value.length,drillFields.value.length-1)]||props.widget.dimension)
const allowsDrill=computed(()=>['bar','hbar','line','area','pie','donut','combo'].includes(props.widget.type)&&drillFields.value.length>1)
function drillTo(level:number){drillPath.value=drillPath.value.slice(0,level)}
function chartSelect(event:any){
 const key=activeDimension.value
 const index=Number(event.dataIndex)
 const value=String((Number.isInteger(index)&&index>=0?rows.value[index]?.[key]:undefined)??event.name??'')
 if(!value||!key)return
 if(allowsDrill.value&&drillPath.value.length<drillFields.value.length-1){
  drillPath.value=[...drillPath.value,{field:key,value}]
 }else if(props.widget.style?.linkOnClick){
  emit('filter',{field:key,op:'eq',value})
 }
}
watch(()=>JSON.stringify([props.widget.datasetId,props.widget.dimension,props.widget.drillDimensions]),()=>{drillPath.value=[]})
watch(drillPath,()=>load(),{deep:true})
let chart:echarts.ECharts|null=null,resizeObserver:ResizeObserver|null=null,request=0
const style=computed(()=>props.widget.style||{})
const pageIndex=ref(1),pageSize=ref(20),sortKey=ref(''),sortAsc=ref(true)
const tableRows=computed(()=>{const arr=[...rows.value];if(sortKey.value)arr.sort((a,b)=>{const x=a[sortKey.value],y=b[sortKey.value];const n=Number(x)-Number(y);return (sortAsc.value?1:-1)*(Number.isFinite(n)&&x!==null&&y!==null?n:String(x??'').localeCompare(String(y??''),'zh-CN'))});return arr})
const pageRows=computed(()=>tableRows.value.slice((pageIndex.value-1)*pageSize.value,pageIndex.value*pageSize.value))
function toggleSort(col:string){if(sortKey.value===col)sortAsc.value=!sortAsc.value;else{sortKey.value=col;sortAsc.value=true}pageIndex.value=1}
function exportCsv(){const esc=(v:any)=>{let str=String(v??'');if(/^[=+\-@\t\r]/.test(str))str="'"+str;return '"'+str.replaceAll('"','""')+'"'};const body=[cols.value.map(c=>props.widget.columnStyles?.[c]?.alias||c),...tableRows.value.map(r=>cols.value.map(c=>r[c]))].map(row=>row.map(esc).join(',')).join('\r\n');const blob=new Blob(['\uFEFF'+body],{type:'text/csv;charset=utf-8'});const href=URL.createObjectURL(blob);const el=document.createElement('a');el.href=href;el.download=(props.widget.title||'明细数据')+'.csv';el.click();URL.revokeObjectURL(href)}
function freezeOffset(col:string){let n=0;for(const c of cols.value){if(c===col)break;if(props.widget.columnStyles?.[c]?.frozen)n+=props.widget.columnStyles[c]?.width||125}return n}
const isTable=computed(()=>props.widget.type==='table')
const isPivot=computed(()=>props.widget.type==='pivot')
const pivotColumns=computed(()=>[...new Set(rows.value.map(r=>String(r[props.widget.columnDimension||'']??'')))])
const pivotMetrics=computed(()=>[measure.value,...(props.widget.pivotMeasures||[]).filter(f=>f!==measure.value&&ds.value?.fields.some(z=>z.name===f&&z.numeric))].filter(Boolean))
const pivotRows=computed(()=>[...new Set(rows.value.map(r=>String(r[props.widget.dimension]??'')))])
const pivotValue=(row:string,column:string,metric:string)=>rows.value.filter(r=>String(r[props.widget.dimension]??'')===row&&String(r[props.widget.columnDimension||'']??'')===column).reduce((sum,r)=>sum+(Number(r[metric])||0),0)
const pivotRowTotal=(row:string,metric:string)=>pivotColumns.value.reduce((sum,c)=>sum+pivotValue(row,c,metric),0)
const pivotColumnTotal=(column:string,metric:string)=>pivotRows.value.reduce((sum,r)=>sum+pivotValue(r,column,metric),0)
const displayColumns=computed(()=>isTable.value?cols.value:[props.widget.dimension,measure.value].filter(Boolean))
const formatted=(v:any,column:string)=>{
 if(v===null||v===undefined)return '—'
 const c=props.widget.columnStyles?.[column]||{}
 if((c.format==='number'||c.format==='currency'||c.format==='percent')&&Number.isFinite(Number(v))){
  let n=Number(v);if(c.format==='percent')n*=100
  return (c.format==='currency'?'¥':'')+new Intl.NumberFormat('zh-CN',{maximumFractionDigits:c.decimals??2,minimumFractionDigits:c.decimals??0}).format(n)+(c.format==='percent'?'%':'')
 }
 return String(v)
}
function matchingRule(v:any,column:string){
 const rules=props.widget.columnStyles?.[column]?.conditionalRules||[]
 for(const r of rules){
  if(r.value===undefined||r.value==='')continue
  const num=Number(v),target=Number(r.value)
  const numeric=Number.isFinite(num)&&Number.isFinite(target)&&v!==null&&v!==undefined
  const matches=r.op==='eq'?String(v??'')===String(r.value):r.op==='ne'?String(v??'')!==String(r.value):
   numeric&&(r.op==='gte'?num>=target:r.op==='gt'?num>target:r.op==='lte'?num<=target:r.op==='lt'?num<target:false)
  if(matches)return r
 }
 return null
}
function backgroundFor(v:any,column:string){return matchingRule(v,column)?.background||props.widget.columnStyles?.[column]?.background||undefined}
function colorFor(v:any,column:string){
 const conditional=matchingRule(v,column)
 if(conditional)return conditional.color||props.widget.columnStyles?.[column]?.color||'';
 const c=props.widget.columnStyles?.[column];if(c?.threshold!==undefined && c?.threshold!==''&&Number.isFinite(Number(v)))return Number(v)>=Number(c.threshold)?c.positiveColor||'#15a779':c.negativeColor||'#dc626b'
 return c?.color||''
}
async function load(){
 if(props.widget.type==='text'){rows.value=[];return}
 if(!ds.value){rows.value=[];error.value='请选择数据集';dispose();return}
 if(!isTable.value && props.widget.type!=='filter' && !measure.value){rows.value=[];error.value='请选择指标';dispose();return}
 if(!isTable.value && !['metric','filter'].includes(props.widget.type)&&!activeDimension.value){rows.value=[];error.value='请选择维度';dispose();return}
 if(isPivot.value&&!props.widget.columnDimension){rows.value=[];error.value='请选择列分组维度';dispose();return}
 const id=++request;loading.value=true;error.value=''
 try{
  const dimensions=isTable.value?cols.value:(props.widget.type==='metric'?[]:isPivot.value?[props.widget.dimension,props.widget.columnDimension!]:[activeDimension.value].filter(Boolean))
  const measures=(isTable.value||props.widget.type==='filter')?[]:[{field:measure.value,agg:props.widget.agg||'SUM',alias:measure.value||'数量'},...(isPivot.value?pivotMetrics.value.slice(1).map(field=>({field,agg:props.widget.agg||'SUM',alias:field})):[]),...(props.widget.type==='combo'&&props.widget.secondMeasure?[{field:props.widget.secondMeasure,agg:props.widget.agg||'SUM',alias:props.widget.secondMeasure}]:[])]
  const filters=[...(props.filters||[]).filter(f=>ds.value?.fields.some(c=>c.name===f.field)&& !(props.widget.type==='filter'&&f.field===props.widget.dimension) &&(f.op==='in'?Array.isArray(f.values)&&f.values.length>0:f.value!=='')),...drillPath.value.map(f=>({...f,op:'eq'}))]
  const result=await api<{rows:Record<string,any>[]}>('/query',{method:'POST',body:JSON.stringify({datasetId:ds.value.id,dimensions,measures,filters,
    ...(!isTable.value&&!isPivot.value&&!['filter','metric'].includes(props.widget.type)?{
      sort:props.widget.topN&&Number(props.widget.topN)>0?(props.widget.sort===props.widget.dimension?activeDimension.value:props.widget.sort||measure.value):(props.widget.sort===props.widget.dimension?activeDimension.value:props.widget.sort||''),
      direction:props.widget.direction||'desc'}:{}),
    limit:isTable.value?200:!isPivot.value&&Number(props.widget.topN)>0?Math.min(300,Math.max(1,Number(props.widget.topN))):300})})
  if(id!==request)return
  rows.value=result.rows;pageIndex.value=1;await nextTick();draw()
 }catch(e:any){if(id===request){error.value=e.message||'数据加载失败';rows.value=[];dispose()}}
 finally{if(id===request)loading.value=false}
}
function dispose(){if(chart){chart.dispose();chart=null}}
function draw(){
 if(['table','pivot','metric','text','filter'].includes(props.widget.type)||!element.value||!rows.value.length){dispose();return}
 if(!chart)chart=echarts.init(element.value,undefined,{renderer:'canvas'})
 const t=props.widget.type,dim=activeDimension.value,key=measure.value,values=rows.value.map(r=>Number(r[key])||0),names=rows.value.map(r=>String(r[dim]??''))
 const color=style.value.chartColor||'#4169e1'
 const base:any={animationDuration:300,color:[color,'#23b9aa','#f1a85c','#a278e7'],tooltip:{trigger:t==='pie'||t==='donut'?'item':'axis'},legend:{show:style.value.legend!==false,top:style.value.legendPosition==='top'?'top':style.value.legendPosition==='bottom'||!style.value.legendPosition?'bottom':'middle',left:style.value.legendPosition==='left'?'left':style.value.legendPosition==='right'?'right':'center',orient:['left','right'].includes(style.value.legendPosition)?'vertical':'horizontal',textStyle:{color:style.value.color||'#64748b',fontSize:11}},grid:{left:style.value.legendPosition==='left'?100:45,right:style.value.legendPosition==='right'?110:18,top:style.value.legendPosition==='top'?43:18,bottom:style.value.legend===false||style.value.legendPosition==='top'?30:52,containLabel:true},textStyle:{color:style.value.color||'#667085'}}
 const item={name:key,type:['line','area'].includes(t)?'line':'bar',data:values,itemStyle:{borderRadius:style.value.barRadius??4},label:{show:!!style.value.dataLabel,position:'top',fontSize:10},smooth:!!style.value.smooth,lineStyle:{width:style.value.lineWidth||2},areaStyle:t==='area'?{opacity:.15}:undefined}
 if(t==='pie'||t==='donut'){
  base.series=[{type:'pie',radius:t==='donut'?['48%','72%']:'70%',center:['50%','45%'],data:rows.value.map(r=>({name:String(r[dim]??''),value:Number(r[key])||0})),label:{show:!!style.value.dataLabel,formatter:'{b}: {d}%'}}]
 }else if(t==='scatter'){
  base.xAxis={type:'value',name:dim};base.yAxis={type:'value',name:key};base.series=[{type:'scatter',symbolSize:13,data:rows.value.map((r,i)=>[Number(r[dim])||i,Number(r[key])||0])}]
 }else if(t==='combo'){
  base.xAxis={type:'category',data:names};base.yAxis=[{type:'value',name:key},{type:'value',name:props.widget.secondMeasure||''}]
  base.series=[{...item,type:'bar',yAxisIndex:0},{name:props.widget.secondMeasure||'第二指标',type:'line',yAxisIndex:1,smooth:true,data:rows.value.map(r=>Number(r[props.widget.secondMeasure||''])||0)}]
 }else{
  const horizontal=t==='hbar'
  base.xAxis=horizontal?{type:'value',splitLine:{lineStyle:{type:'dashed'}}}:{type:'category',data:names,axisLabel:{hideOverlap:true}}
  base.yAxis=horizontal?{type:'category',data:names}:{type:'value',splitLine:{lineStyle:{type:'dashed',color:'#edf0f4'}}}
  base.series=[{...item,data:values,type:horizontal?'bar':item.type}]
 }
 if(!['pie','donut','scatter'].includes(t)){
  base.xAxis.show=style.value.showXAxis!==false
  if(base.xAxis.type==='category')base.xAxis.axisLabel={...(base.xAxis.axisLabel||{}),rotate:style.value.labelRotate||0}
  const axes=Array.isArray(base.yAxis)?base.yAxis:[base.yAxis]
  for(const axis of axes){axis.show=style.value.showYAxis!==false;axis.splitLine={...(axis.splitLine||{}),show:style.value.showGrid!==false}}
 }
 chart.setOption(base,{notMerge:true});chart.resize()
 chart.off('click')
 chart.on('click',chartSelect)
}
watch(()=>JSON.stringify([props.widget.datasetId,props.widget.type,props.widget.dimension,props.widget.measure,props.widget.agg,props.widget.columns,props.widget.secondMeasure,props.widget.columnDimension,props.widget.pivotMeasures,props.widget.sort,props.widget.direction,props.widget.topN,props.filters]),load,{immediate:true})
watch(()=>JSON.stringify(props.widget.style),()=>nextTick(draw))
watch(element,el=>{resizeObserver?.disconnect();if(el){resizeObserver=new ResizeObserver(()=>chart?.resize());resizeObserver.observe(el);nextTick(draw)}},{flush:'post'})
onMounted(()=>nextTick(draw))
onBeforeUnmount(()=>{request++;resizeObserver?.disconnect();dispose()})
</script>
<template>
 <div class="widget-content" :style="{color:style.color||undefined,fontSize:(style.fontSize||13)+'px'}">
  <div v-if="allowsDrill" class="bi-drill-path" style="display:flex;align-items:center;gap:4px;flex-wrap:wrap;font-size:11px;padding:2px 0 5px">
   <button type="button" style="border:0;background:transparent;cursor:pointer;color:#4169e1;padding:2px" @click.stop="drillTo(0)">{{drillFields[0]}}</button>
   <template v-for="(item,i) in drillPath" :key="i"><span>›</span><button type="button" style="border:0;background:transparent;cursor:pointer;color:#4169e1;padding:2px" @click.stop="drillTo(i+1)">{{item.value}}</button></template>
   <span style="opacity:.6">· 当前：{{activeDimension}} <template v-if="drillPath.length<drillFields.length-1">（点击图表下钻）</template></span>
  </div>
  <div v-if="widget.type==='text'" class="text-widget">{{widget.style?.text||'双击右侧编辑文本内容'}}</div>
  <div v-else-if="!widget.datasetId" class="widget-hint"><span class="hint-icon">▤</span><span>请选择数据集绑定真实字段</span></div>
  <div v-else-if="loading" class="widget-hint">正在查询数据…</div>
  <div v-else-if="error" class="widget-hint error">{{error}}</div>
  <div v-else-if="!rows.length" class="widget-hint">当前条件下没有数据</div>
  <template v-else-if="widget.type==='table'">
   <div class="table-overflow">
    <table class="bi-table" :class="{zebra:style.zebra!==false}" :style="{'--thead-bg':style.headerBg||'#f4f6fb','--row-height':(style.rowHeight||36)+'px'}">
     <thead><tr><th v-for="col in displayColumns" :key="col" class="sortable-head" @click.stop="toggleSort(col)" :style="{minWidth:(widget.columnStyles?.[col]?.width||125)+'px',textAlign:widget.columnStyles?.[col]?.align||'left',background:widget.columnStyles?.[col]?.headerBg||undefined,position:widget.columnStyles?.[col]?.frozen?'sticky':undefined,left:widget.columnStyles?.[col]?.frozen?freezeOffset(col)+'px':undefined,zIndex:widget.columnStyles?.[col]?.frozen?3:1}">{{widget.columnStyles?.[col]?.alias||col}} <span v-if="sortKey===col">{{sortAsc?'↑':'↓'}}</span></th></tr></thead>
     <tbody><tr v-for="(r,i) in pageRows" :key="i"><td v-for="col in displayColumns" :key="col" :style="{textAlign:widget.columnStyles?.[col]?.align||'left',color:colorFor(r[col],col),background:backgroundFor(r[col],col),position:widget.columnStyles?.[col]?.frozen?'sticky':undefined,left:widget.columnStyles?.[col]?.frozen?freezeOffset(col)+'px':undefined}">{{formatted(r[col],col)}}</td></tr></tbody>
     <tfoot v-if="style.showTotal"><tr><th v-for="(col,i) in displayColumns" :key="col">{{i===0?'本次查询合计':rows.some(r=>typeof r[col]==='number')?formatted(rows.reduce((sum,r)=>sum+(Number(r[col])||0),0),col):''}}</th></tr></tfoot>
    </table>
   </div>
   <div class="table-footer table-pagination"><span>{{rows.length}} 行 · 最多查询 200 行 <button @click.stop="exportCsv">导出 CSV</button></span><span><button :disabled="pageIndex===1" @click.stop="pageIndex--">‹</button> {{pageIndex}} / {{Math.max(1,Math.ceil(rows.length/pageSize))}} <button :disabled="pageIndex*pageSize>=rows.length" @click.stop="pageIndex++">›</button></span></div>
  </template>
  <template v-else-if="widget.type==='pivot'">
   <div class="table-overflow">
    <table class="bi-table" :style="{'--thead-bg':style.headerBg||'#f4f6fb'}">
     <thead><tr><th :rowspan="pivotMetrics.length>1?2:1">{{widget.dimension}} / {{widget.columnDimension}}</th><th v-for="c in pivotColumns" :key="c" :colspan="pivotMetrics.length">{{c}}</th><th v-if="style.showTotal" :colspan="pivotMetrics.length">行合计</th></tr>
      <tr v-if="pivotMetrics.length>1"><template v-for="c in pivotColumns" :key="c"><th v-for="m in pivotMetrics" :key="c+m">{{m}}</th></template><th v-if="style.showTotal" v-for="m in pivotMetrics" :key="'sum-'+m">{{m}}</th></tr>
     </thead>
     <tbody><tr v-for="r in pivotRows" :key="r"><th>{{r}}</th><template v-for="c in pivotColumns" :key="c"><td v-for="m in pivotMetrics" :key="c+m">{{formatted(pivotValue(r,c,m),m)}}</td></template><td v-if="style.showTotal" v-for="m in pivotMetrics" :key="'total-'+m"><strong>{{formatted(pivotRowTotal(r,m),m)}}</strong></td></tr></tbody>
     <tfoot v-if="style.showTotal"><tr><th>列合计</th><template v-for="c in pivotColumns" :key="c"><th v-for="m in pivotMetrics" :key="c+m">{{formatted(pivotColumnTotal(c,m),m)}}</th></template><th v-for="m in pivotMetrics" :key="'all-'+m">{{formatted(rows.reduce((sum,r)=>sum+(Number(r[m])||0),0),m)}}</th></tr></tfoot>
    </table>
   </div><div class="table-footer">交叉分析 · {{pivotRows.length}} 行维度 × {{pivotColumns.length}} 列维度 × {{pivotMetrics.length}} 个指标 · 最多查询 300 组</div>
  </template>
  <div v-else-if="widget.type==='metric'" class="metric-panel">
   <div class="metric-value">{{formatted(rows[0]?.[measure]??0,measure)}}</div><div class="metric-name">{{measure}}</div>
  </div>
  <div v-else-if="widget.type==='filter'" class="filter-panel">
   <select :multiple="!!style.filterMultiple" :size="style.filterMultiple?Math.max(2,Math.min(5,filterOptions.length)):1" @change="filterChange">
    <option v-if="!style.filterMultiple" value="">全部{{widget.dimension}}</option>
    <option v-for="v in filterOptions" :key="v" :value="v">{{v}}</option>
   </select>
  </div>
  <div v-else ref="element" class="echart-surface"></div>
 </div>
</template>
