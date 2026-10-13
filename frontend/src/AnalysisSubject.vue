<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,put,post,type Dataset,type Dashboard,type Widget,type FieldV2,type AnalysisWidgetV2} from './api'
import AnalysisV2Chart from './AnalysisV2Chart.vue'
import ChartWidget from './ChartWidget.vue'
import FormulaEditor from './analysis/FormulaEditor.vue'
import AnalysisFilters from './analysis/AnalysisFilters.vue'

const props=defineProps<{id:string}>()
const emit=defineEmits<{(e:'back'):void}>()
const report=ref<Dashboard|null>(null)
const datasets=ref<Dataset[]>([])
const components=ref<AnalysisWidgetV2[]>([])
type DashboardView={id:string;name:string;widgetIds:string[]}
const dashboardViews=ref<DashboardView[]>([])
const selectedViewId=ref('')
const activeView=computed(()=>dashboardViews.value.find(v=>v.id===selectedViewId.value))
const dashboardWidgets=computed(()=>components.value.filter(w=>activeView.value?.widgetIds.includes(w.id)))
const selectedId=ref('')
const name=ref('')
const busy=ref(false)
const dirty=ref(false)
const currentTab=ref<'component'|'dashboard'>('component')
const propTab=ref<'data'|'style'>('data')
const chosen=computed(()=>components.value.find(w=>w.id===selectedId.value))
const source=computed(()=>datasets.value.find(d=>d.id===chosen.value?.datasetId))
const dimensions=computed(()=>source.value?.fields.filter(f=>!f.numeric)??[])
const metrics=computed(()=>source.value?.fields.filter(f=>f.numeric)??[])
const v2Fields=ref<FieldV2[]>([])
const fieldSearch=ref(''),showHidden=ref(false)
const availableFields=computed(()=>v2Fields.value.filter(f=>showHidden.value||!f.hidden))
const v2Dimensions=computed(()=>availableFields.value.filter(f=>!f.calculated||f.phase==='ROW').filter(f=>f.name.includes(fieldSearch.value)))
const v2Metrics=computed(()=>availableFields.value.filter(f=>f.numeric).filter(f=>f.name.includes(fieldSearch.value)))
const v2CountFields=computed(()=>availableFields.value.filter(f=>!f.numeric&&!f.calculated).filter(f=>f.name.includes(fieldSearch.value)))
const v2DimensionLabels=computed(()=>chosen.value?.analysisV2?.dimensions.map(binding=>({binding,field:v2Fields.value.find(f=>f.id===binding.fieldId)}))??[])
const v2MeasureLabels=computed(()=>chosen.value?.analysisV2?.measures.map(binding=>({binding,field:v2Fields.value.find(f=>f.id===binding.fieldId)}))??[])
/** Local undo/redo snapshots never change server data before Save. */
type EditorSnapshot={name:string;components:AnalysisWidgetV2[];selectedId:string;dashboardViews:DashboardView[];selectedViewId:string}
const undoStack=ref<string[]>([]),redoStack=ref<string[]>([])
let historyCurrent='',savedFingerprint=''
function fingerprint(){return JSON.stringify({name:name.value.trim(),components:components.value,dashboards:dashboardViews.value})}
function snapshot(){return JSON.stringify({name:name.value,components:components.value,selectedId:selectedId.value,
 dashboardViews:dashboardViews.value,selectedViewId:selectedViewId.value})}
function resetHistory(){
 historyCurrent=snapshot();savedFingerprint=fingerprint();undoStack.value=[];redoStack.value=[]
}
function mark(){
 const updated=snapshot()
 if(historyCurrent&&updated!==historyCurrent){
  undoStack.value.push(historyCurrent)
  if(undoStack.value.length>40)undoStack.value.shift()
  redoStack.value=[]
 }
 historyCurrent=updated;dirty.value=fingerprint()!==savedFingerprint
}
function restoreHistory(serialized:string){
 const state=JSON.parse(serialized) as EditorSnapshot
 name.value=state.name;components.value=state.components
 dashboardViews.value=state.dashboardViews
 selectedViewId.value=dashboardViews.value.some(v=>v.id===state.selectedViewId)
  ?state.selectedViewId:(dashboardViews.value[0]?.id??'')
 selectedId.value=components.value.some(w=>w.id===state.selectedId)?state.selectedId:(components.value[0]?.id??'')
 currentTab.value='component';historyCurrent=serialized
 dirty.value=fingerprint()!==savedFingerprint
}
function undo(){
 if(!undoStack.value.length||busy.value)return
 redoStack.value.push(historyCurrent);restoreHistory(undoStack.value.pop()!)
}
function redo(){
 if(!redoStack.value.length||busy.value)return
 undoStack.value.push(historyCurrent);restoreHistory(redoStack.value.pop()!)
}
function moveV2Binding(kind:'dimension'|'metric',position:number,offset:number){
 const config=chosen.value?.analysisV2
 if(!config)return
 const arr=kind==='dimension'?config.dimensions:config.measures
 const next=position+offset
 if(next<0||next>=arr.length)return
 const [item]=arr.splice(position,1)
 arr.splice(next,0,item);mark()
}
let fieldRequest=0
watch(()=>chosen.value?.datasetId,async id=>{
 const revision=++fieldRequest;v2Fields.value=[]
 if(!id||!chosen.value?.analysisV2)return
 try{
  const fields=await api<FieldV2[]>('/datasets/'+encodeURIComponent(id)+'/fields')
  if(revision===fieldRequest)v2Fields.value=fields
 }catch(e:unknown){if(revision===fieldRequest)ElMessage.error(e instanceof Error?e.message:'字段元数据获取失败')}
},{immediate:true})
function addV2Field(field:FieldV2,kind:'dimension'|'metric'){
 const b=chosen.value?.analysisV2;if(!b)return
 if(kind==='dimension'){
  if(b.dimensions.some(x=>x.fieldId===field.id))return
  if(b.dimensions.length>=3){ElMessage.warning('维度最多 3 个');return}
  b.dimensions.push({fieldId:field.id})
 }else{
  if(!field.numeric&&field.calculated){ElMessage.warning('该计算字段不能用于计数');return}
  if(b.measures.length>=5){ElMessage.warning('指标最多 5 个');return}
  const used=new Set(b.measures.map(m=>m.id));let i=1;while(used.has('m'+i))i++
  b.measures.push({id:'m'+i,fieldId:field.id,aggregate:field.numeric?'SUM':'COUNT',alias:field.name})
 }
 mark()
}
function removeV2Field(id:string,kind:'dimension'|'metric'){
 const b=chosen.value?.analysisV2;if(!b)return
 if(kind==='dimension')b.dimensions=b.dimensions.filter(x=>x.fieldId!==id)
 else{
  b.measures=b.measures.filter(x=>x.id!==id)
  b.filters.post=b.filters.post.filter(x=>x.ref!==id)
 }
 mark()
}
function onV2Drag(e:DragEvent,field:FieldV2,kind:'dimension'|'metric'){
 e.dataTransfer?.setData('application/x-insight-field',JSON.stringify({id:field.id,kind}))
}
function onBindingDrag(e:DragEvent,kind:'dimension'|'metric',index:number){
 e.stopPropagation()
 e.dataTransfer?.setData('application/x-insight-binding',JSON.stringify({kind,index}))
 if(e.dataTransfer)e.dataTransfer.effectAllowed='move'
}
function onBindingDrop(e:DragEvent,kind:'dimension'|'metric',target:number){
 e.preventDefault();e.stopPropagation()
 const raw=e.dataTransfer?.getData('application/x-insight-binding')
 if(!raw){onV2Drop(e,kind);return}
 try{
  const payload=JSON.parse(raw) as {kind:string;index:number}
  const arr=kind==='dimension'?chosen.value?.analysisV2?.dimensions:chosen.value?.analysisV2?.measures
  if(payload.kind!==kind||!arr||!Number.isInteger(payload.index)||payload.index<0||payload.index>=arr.length)
   throw Error('无效的字段排序')
  if(payload.index===target)return
  const [item]=arr.splice(payload.index,1)
  arr.splice(target,0,item);mark()
 }catch{ElMessage.warning('只能调整同类型字段的顺序')}
}
function onV2Drop(e:DragEvent,kind:'dimension'|'metric'){
 e.preventDefault()
 try{
  const payload=JSON.parse(e.dataTransfer?.getData('application/x-insight-field')||'{}')
  const f=v2Fields.value.find(x=>x.id===payload.id)
  if(!f||payload.kind!==kind)throw Error('字段类型不匹配')
  addV2Field(f,kind)
 }catch{ElMessage.warning('请拖入匹配的字段')}
}
const formulaDialog=ref(false)
function openFormula(){formulaDialog.value=true}
async function refreshFormulaFields(){
 const id=chosen.value?.datasetId
 if(!id)return
 const version=++fieldRequest
 try{
  const fields=await api<FieldV2[]>('/datasets/'+encodeURIComponent(id)+'/fields')
  if(version===fieldRequest)v2Fields.value=fields
 }catch(e:unknown){
  if(version===fieldRequest)ElMessage.error(e instanceof Error?e.message:'字段刷新失败')
 }
}
async function renamePhysicalField(field:FieldV2){
 if(field.calculated)return
 const datasetId=chosen.value?.datasetId
 if(!datasetId)return
 try{
  const {value}=await ElMessageBox.prompt('修改字段展示名称，不影响源列及计算字段引用','字段重命名',{
   inputValue:field.name,
   inputValidator:(value:string)=>value.trim().length>0&&value.trim().length<=150||
      '展示名称须为 1 到 150 个字符',
   confirmButtonText:'保存',cancelButtonText:'取消'
  })
  await put('/datasets/'+encodeURIComponent(datasetId)+'/fields/'+encodeURIComponent(field.id),{
   displayName:String(value).trim()
  })
  await refreshFormulaFields()
  ElMessage.success('字段展示名称已更新')
 }catch(e:unknown){
  if(e==='cancel'||e==='close')return
  ElMessage.error(e instanceof Error?e.message:'字段重命名失败')
 }
}
async function togglePhysicalField(field:FieldV2){
 if(field.calculated)return
 const id=chosen.value?.datasetId
 if(!id)return
 try{
  await put('/datasets/'+encodeURIComponent(id)+'/fields/'+encodeURIComponent(field.id)+'/visibility',{
   hidden:!field.hidden
  })
  await refreshFormulaFields()
  ElMessage.success(field.hidden?'字段已显示':'字段已隐藏；已有图表和公式不受影响')
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'字段显示设置失败')}
}
function setV2Sort(ref:string){
 const b=chosen.value?.analysisV2;if(!b)return
 b.sort=ref?[{ref,direction:b.sort[0]?.direction||'desc'}]:[]
 mark()
}
function setV2Direction(direction:'asc'|'desc'){
 const b=chosen.value?.analysisV2;if(!b?.sort.length)return
 b.sort[0].direction=direction;mark()
}
const types=[
 {type:'bar',name:'柱状图',icon:'▥'},{type:'hbar',name:'条形图',icon:'▤'},
 {type:'line',name:'折线图',icon:'⌁'},{type:'area',name:'面积图',icon:'▰'},
 {type:'pie',name:'饼图',icon:'◕'},{type:'donut',name:'环形图',icon:'◌'},
 {type:'scatter',name:'散点图',icon:'∴'},{type:'combo',name:'组合图',icon:'⌁'},
 {type:'metric',name:'指标卡',icon:'◈'},{type:'table',name:'明细表',icon:'▦'},
 {type:'pivot',name:'交叉表',icon:'▤'}
]
async function load(){
 busy.value=true
 try{
  const [item,available]=await Promise.all([api<Dashboard>('/dashboards/'+props.id),api<Dataset[]>('/datasets')])
  if((item as any).kind!=='analysis')throw Error('这不是分析主题，请从仪表板工作区打开')
  report.value=item;datasets.value=available;name.value=item.name
  components.value=Array.isArray(item.content?.widgets)?item.content.widgets.map(w=>({...w,style:w.style||{},columns:w.columns||[]})):[]
  const stored=(item.content as typeof item.content & {dashboards?:DashboardView[]}).dashboards
  dashboardViews.value=Array.isArray(stored)&&stored.length
   ?stored.map(v=>({id:v.id,name:v.name,widgetIds:[...v.widgetIds]}))
   :[{id:'overview',name:'仪表板',widgetIds:components.value.map(w=>w.id)}]
  selectedViewId.value=dashboardViews.value[0]?.id??''
  selectedId.value=components.value[0]?.id??''
  dirty.value=false;resetHistory()
 }catch(e:any){ElMessage.error(e.message||'加载分析主题失败')}
 finally{busy.value=false}
}
function addComponent(){
 const index=components.value.length+1
 const widget:AnalysisWidgetV2={
  id:crypto.randomUUID(),type:'bar',title:'组件'+index,datasetId:'',dimension:'',measure:'',agg:'SUM',columns:[],
  analysisV2:{dimensions:[],measures:[],filters:{pre:[],post:[]},sort:[],limit:200},
  x:0,y:0,w:5,h:5,px:0,py:0,width:600,height:340,
  style:{background:'#fff',color:'#24344a',chartColor:'#2867db',legend:true,legendPosition:'bottom',dataLabel:false,
    showXAxis:true,showYAxis:true,showGrid:true,showTitle:true,fontSize:13,radius:0,padding:8},columnStyles:{}
 }
 components.value.push(widget)
 if(activeView.value)activeView.value.widgetIds.push(widget.id)
 selectedId.value=widget.id;currentTab.value='component';mark()
}
function removeComponent(){
 if(!chosen.value)return
 const name=chosen.value.title
 ElMessageBox.confirm('确定删除「'+name+'」？该操作在保存前不会影响服务器版本。','删除组件',{type:'warning'})
 .then(()=>{
  const removedId=selectedId.value
  components.value=components.value.filter(w=>w.id!==removedId)
  for(const view of dashboardViews.value)view.widgetIds=view.widgetIds.filter(id=>id!==removedId)
  selectedId.value=components.value[0]?.id??'';mark()
 })
 .catch(()=>{})
}
function datasetChanged(){
 if(!chosen.value)return
 chosen.value.dimension='';chosen.value.measure='';chosen.value.secondMeasure='';chosen.value.columns=[]
 if(chosen.value.analysisV2)chosen.value.analysisV2={dimensions:[],measures:[],filters:{pre:[],post:[]},sort:[],limit:200}
 mark()
}
function assignField(field:string,kind:'dimension'|'metric'){
 const widget=chosen.value
 if(!widget)return
 if(kind==='dimension')widget.dimension=field
 else widget.measure=field
 mark()
}
function handleDragStart(e:DragEvent,field:string,kind:'dimension'|'metric'){
 e.dataTransfer?.setData('application/json',JSON.stringify({field,kind}))
}
function handleDrop(e:DragEvent,target:'dimension'|'metric'){
 e.preventDefault()
 try{
  const raw=e.dataTransfer?.getData('application/json')||''
  const parsed=JSON.parse(raw)
  if(parsed.kind!==target)throw Error('字段类型不匹配')
  const allowed=target==='metric'?metrics.value:dimensions.value
  if(!allowed.some(f=>f.name===parsed.field))throw Error('该字段不属于当前数据集')
  assignField(parsed.field,target)
 }catch{ElMessage.warning('请从左侧拖入正确类型的字段')}
}
function switchKind(kind:string){
 if(!chosen.value)return
 if(chosen.value.analysisV2&&!['bar','hbar','line','area','pie','donut','combo','metric','table'].includes(kind)){
  ElMessage.warning('该图表类型尚未接入 V2 分析计算');return
 }
 if(chosen.value.analysisV2&&['pie','donut','metric'].includes(kind)&&chosen.value.analysisV2.measures.length>1){
  ElMessage.warning('该图表类型只能展示一个指标；请先调整指标槽，现有绑定已保留')
  return
 }
 chosen.value.type=kind;mark()
}
function addDashboardView(){
 if(dashboardViews.value.length>=30){ElMessage.warning('每个主题最多 30 个仪表板');return}
 const view:DashboardView={id:crypto.randomUUID(),name:'仪表板'+(dashboardViews.value.length+1),widgetIds:[]}
 dashboardViews.value.push(view);selectedViewId.value=view.id;currentTab.value='dashboard';mark()
}
async function renameDashboardView(){
 const view=activeView.value
 if(!view)return
 try{
  const {value}=await ElMessageBox.prompt('请输入仪表板名称','重命名仪表板',{
   inputValue:view.name,confirmButtonText:'保存',cancelButtonText:'取消',
   inputValidator:(text:string)=>!!text.trim()&&text.trim().length<=100||'名称须为 1–100 个字符'
  })
  view.name=String(value).trim();mark()
 }catch(e:unknown){if(e!=='cancel'&&e!=='close')ElMessage.error('重命名失败')}
}
async function removeDashboardView(){
 const view=activeView.value
 if(!view)return
 if(dashboardViews.value.length===1){ElMessage.warning('需要保留至少一个仪表板');return}
 try{
  await ElMessageBox.confirm('删除「'+view.name+'」视图？共享组件不会被删除。','删除仪表板',{type:'warning'})
  dashboardViews.value=dashboardViews.value.filter(v=>v.id!==view.id)
  selectedViewId.value=dashboardViews.value[0].id
  mark()
 }catch{}
}
function setDashboardWidgets(ids:string[]){
 const view=activeView.value
 if(!view)return
 const existing=new Set(components.value.map(w=>w.id))
 view.widgetIds=[...new Set(ids.filter(id=>existing.has(id)))]
 mark()
}
async function save():Promise<boolean>{
 if(!report.value)return false
 if(!name.value.trim()){ElMessage.warning('请输入分析主题名称');return false}
 busy.value=true
 try{
  const savedContent=fingerprint()
  const updated=await put<{revision:number}>('/dashboards/'+props.id,{
   name:name.value.trim(),mode:'grid',revision:report.value.revision,
   content:{mode:'grid',theme:'light',editorKind:'analysis',analysisSchemaVersion:2,
    widgets:components.value,dashboards:dashboardViews.value}
  })
  report.value.revision=updated.revision;savedFingerprint=savedContent
  dirty.value=fingerprint()!==savedFingerprint
  if(!dirty.value)resetHistory()
  ElMessage.success('分析主题已保存');return true
 }catch(e:any){ElMessage.error(e.message||'保存失败');return false}
 finally{busy.value=false}
}
async function publish(){
 if(!await save())return
 try{await post('/dashboards/'+props.id+'/publish',{});ElMessage.success('分析主题已发布')}
 catch(e:any){ElMessage.error(e.message||'发布失败')}
}
watch(selectedId,()=>{propTab.value='data'})
onMounted(load)
</script>
<template>
 <div class="analysis-editor">
  <header class="analysis-editor-header">
   <button class="analysis-back" @click="emit('back')">← 我的分析</button>
   <span class="analysis-divider">/</span>
   <input v-model="name" maxlength="100" aria-label="分析主题名称" @input="mark"/>
   <span class="analysis-save-indicator">{{dirty?'未保存':'已保存'}}</span>
   <div class="analysis-header-actions">
    <button @click="currentTab='component'" :class="{active:currentTab==='component'}">组件分析</button>
    <button @click="currentTab='dashboard'" :class="{active:currentTab==='dashboard'}">仪表板预览</button>
    <button title="撤销上一步操作" :disabled="busy||!undoStack.length" @click="undo">撤销</button>
    <button title="重做上一步操作" :disabled="busy||!redoStack.length" @click="redo">重做</button>
    <button class="analysis-primary" :disabled="busy" @click="save">保存</button>
    <button :disabled="busy" @click="publish">发布</button>
   </div>
  </header>
  <div v-if="busy&&!report" class="analysis-loading">正在读取分析主题…</div>
  <div v-else class="analysis-editor-body">
   <aside class="analysis-data-fields">
    <header><strong>数据</strong><small>StarRocks 直连</small></header>
    <div class="analysis-source-selector">
     <label>当前数据集</label>
     <el-select v-if="chosen" v-model="chosen.datasetId" filterable placeholder="选择数据集" @change="datasetChanged">
      <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id"/>
     </el-select>
    </div>
    <div v-if="!datasets.length" class="analysis-empty-data">当前还没有数据集，请先在「公共数据」连接 StarRocks 并创建 SQL 数据集。</div>
    <div v-else-if="!chosen" class="analysis-empty-data">在底部点击「＋组件」开始分析。</div>
    <template v-else-if="chosen.analysisV2">
     <div class="analysis-field-search">
       <el-input v-model="fieldSearch" clearable placeholder="搜索字段" size="small"/>
       <el-checkbox v-model="showHidden" size="small">管理隐藏字段</el-checkbox>
      </div>
     <div class="analysis-field-header">维度 <small>{{v2Dimensions.length}}</small></div>
     <div v-for="f in v2Dimensions" :key="f.id" class="analysis-field" :class="{'is-hidden':f.hidden}" :draggable="!f.hidden"
       @dragstart="onV2Drag($event,f,'dimension')" @click="!f.hidden&&addV2Field(f,'dimension')">
       <span class="field-dimension">{{f.logicalType==='DATE'||f.logicalType==='DATETIME'?'D':'A'}}</span>{{f.name}}
        <button v-if="!f.calculated" class="analysis-field-rename" draggable="false" title="修改展示名称" @click.stop="renamePhysicalField(f)">✎</button>
        <button v-if="!f.calculated" class="analysis-field-toggle" draggable="false" :title="f.hidden?'显示字段':'隐藏字段'" @click.stop="togglePhysicalField(f)">{{f.hidden?'显示':'隐藏'}}</button><small>⋮⋮</small>
     </div>
     <div class="analysis-field-header">数值指标 <small>{{v2Metrics.length}}</small></div>
     <div v-for="f in v2Metrics" :key="f.id" class="analysis-field" :class="{'is-hidden':f.hidden}" :draggable="!f.hidden"
       @dragstart="onV2Drag($event,f,'metric')" @click="!f.hidden&&addV2Field(f,'metric')">
       <span class="field-metric">∑</span>{{f.name}}
        <button v-if="!f.calculated" class="analysis-field-rename" draggable="false" title="修改展示名称" @click.stop="renamePhysicalField(f)">✎</button>
        <button v-if="!f.calculated" class="analysis-field-toggle" draggable="false" :title="f.hidden?'显示字段':'隐藏字段'" @click.stop="togglePhysicalField(f)">{{f.hidden?'显示':'隐藏'}}</button>
        <small>{{f.calculated?'ƒ':'⋮⋮'}}</small>
     </div>
     <div v-if="v2CountFields.length" class="analysis-field-header">可计数字段 <small>{{v2CountFields.length}}</small></div>
      <div v-for="f in v2CountFields" :key="'cnt-'+f.id" class="analysis-field" :class="{'is-hidden':f.hidden}" :draggable="!f.hidden"
       @dragstart="onV2Drag($event,f,'metric')" @click="!f.hidden&&addV2Field(f,'metric')">
       <span class="field-metric">#</span>{{f.name}}<small>COUNT</small>
      </div>
      <button class="analysis-formula-add" @click="openFormula">＋ 计算字段</button>
    </template>
    <template v-else>
     <div class="analysis-field-header">维度 <small>{{dimensions.length}}</small></div>
     <div v-for="f in dimensions" :key="'dim-'+f.name" class="analysis-field" draggable="true" @dragstart="handleDragStart($event,f.name,'dimension')" @click="assignField(f.name,'dimension')">
      <span class="field-dimension">A</span>{{f.name}}<small>⋮⋮</small>
     </div>
     <div class="analysis-field-header">数值指标 <small>{{metrics.length}}</small></div>
     <div v-for="f in metrics" :key="'metric-'+f.name" class="analysis-field" draggable="true" @dragstart="handleDragStart($event,f.name,'metric')" @click="assignField(f.name,'metric')">
      <span class="field-metric">∑</span>{{f.name}}<small>⋮⋮</small>
     </div>
    </template>
   </aside>
   <div class="analysis-center">
    <template v-if="currentTab==='component'">
     <div class="analysis-chart-types">
      <div class="analysis-label">图表</div>
      <button v-for="t in types" :key="t.type" :title="t.name" :class="{active:chosen?.type===t.type}"
       :disabled="!chosen" @click="switchKind(t.type)"><span>{{t.icon}}</span><small>{{t.name}}</small></button>
     </div>
     <div v-if="chosen?.analysisV2" class="analysis-field-shelves">
      <div class="analysis-shelf v2-shelf" @dragover.prevent @drop="onV2Drop($event,'dimension')">
       <span>维度</span>
       <span v-for="(item,i) in v2DimensionLabels" :key="item.binding.fieldId" class="analysis-chip" draggable="true"
         @dragstart="onBindingDrag($event,'dimension',i)" @dragover.prevent @drop="onBindingDrop($event,'dimension',i)">
        {{item.field?.name||'字段已删除'}}
        <el-select v-if="['DATE','DATETIME'].includes(item.field?.logicalType||'')" v-model="item.binding.granularity"
         size="small" style="width:78px" placeholder="原始" clearable @change="mark">
         <el-option label="年" value="year"/><el-option label="月" value="month"/><el-option label="日" value="day"/>
        </el-select>
        <button :disabled="i===0" title="维度前移" @click="moveV2Binding('dimension',i,-1)">↑</button>
        <button :disabled="i===v2DimensionLabels.length-1" title="维度后移" @click="moveV2Binding('dimension',i,1)">↓</button>
        <button title="移除维度" @click="removeV2Field(item.binding.fieldId,'dimension')">×</button>
       </span>
       <span v-if="!v2DimensionLabels.length" class="analysis-empty-shelf">拖入维度，可添加多个</span>
      </div>
      <div class="analysis-shelf v2-shelf" @dragover.prevent @drop="onV2Drop($event,'metric')">
       <span>指标</span>
       <span v-for="(item,i) in v2MeasureLabels" :key="item.binding.id" class="analysis-chip" draggable="true"
         @dragstart="onBindingDrag($event,'metric',i)" @dragover.prevent @drop="onBindingDrop($event,'metric',i)">
        {{item.field?.name||'字段已删除'}}
        <el-select v-model="item.binding.aggregate" size="small" style="width:82px" @change="item.binding.distinct=false;mark()">
         <el-option v-for="agg in item.field?.phase==='AGGREGATE'?['SUM']:item.field?.numeric?['SUM','AVG','COUNT','MIN','MAX']:['COUNT','MIN','MAX']" :key="agg" :value="agg" :label="agg"/>
        </el-select>
        <el-checkbox v-if="item.binding.aggregate==='COUNT'" v-model="item.binding.distinct" size="small" @change="mark">去重</el-checkbox>
        <button :disabled="i===0" title="指标前移" @click="moveV2Binding('metric',i,-1)">↑</button>
        <button :disabled="i===v2MeasureLabels.length-1" title="指标后移" @click="moveV2Binding('metric',i,1)">↓</button>
        <button title="移除指标" @click="removeV2Field(item.binding.id,'metric')">×</button>
       </span>
       <span v-if="!v2MeasureLabels.length" class="analysis-empty-shelf">拖入指标，可添加多个</span>
      </div>
     </div>
     <div v-else-if="chosen" class="analysis-field-shelves">
      <div class="analysis-shelf" @dragover.prevent @drop="handleDrop($event,'dimension')">
       <span>维度</span><span v-if="chosen.dimension" class="analysis-chip">{{chosen.dimension}} <button @click="chosen.dimension='';mark()">×</button></span>
       <span v-else class="analysis-empty-shelf">拖入维度字段</span>
      </div>
      <div class="analysis-shelf" @dragover.prevent @drop="handleDrop($event,'metric')">
       <span>指标</span><span v-if="chosen.measure" class="analysis-chip">{{chosen.measure}} <button @click="chosen.measure='';mark()">×</button></span>
       <span v-else class="analysis-empty-shelf">拖入数值字段</span>
       <el-select v-if="chosen.measure" v-model="chosen.agg" size="small" style="width:96px;margin-left:auto" @change="mark">
        <el-option v-for="agg in ['SUM','AVG','COUNT','MIN','MAX']" :key="agg" :label="agg" :value="agg"/>
       </el-select>
      </div>
     </div>
     <div class="analysis-stage">
      <div v-if="!chosen" class="analysis-stage-empty"><strong>暂无组件</strong><span>点击底部「＋组件」创建第一张图表</span><button @click="addComponent">新建组件</button></div>
      <div v-else class="analysis-stage-content">
       <div class="analysis-stage-title"><strong>{{chosen.title}}</strong><span>数据源实时查询</span></div>
       <AnalysisV2Chart v-if="chosen.analysisV2" :key="chosen.id" :widget="chosen"/>
       <ChartWidget v-else :key="chosen.id" :widget="chosen" :datasets="datasets" :readonly="false"/>
      </div>
     </div>
    </template>
    <div v-else class="analysis-dashboard-preview">
     <div class="analysis-dashboard-head">
      <strong>仪表板</strong>
      <el-select v-model="selectedViewId" size="small" class="analysis-view-picker" aria-label="选择仪表板">
       <el-option v-for="view in dashboardViews" :key="view.id" :label="view.name" :value="view.id"/>
      </el-select>
      <button title="新建仪表板" @click="addDashboardView">＋ 新建</button>
      <button v-if="activeView" title="重命名仪表板" @click="renameDashboardView">重命名</button>
      <button v-if="dashboardViews.length>1" title="删除仪表板" @click="removeDashboardView">删除</button>
     </div>
     <div v-if="activeView" class="analysis-view-components">
      <span>显示组件</span>
      <el-select :model-value="activeView.widgetIds" multiple filterable collapse-tags
       size="small" class="analysis-view-components-picker" placeholder="选择已有组件"
       aria-label="仪表板显示组件"
       @update:model-value="v=>setDashboardWidgets(v as string[])">
       <el-option v-for="component in components" :key="component.id"
        :label="component.title||component.id" :value="component.id"/>
      </el-select>
     </div>
     <div v-if="!dashboardWidgets.length" class="analysis-stage-empty">当前仪表板尚未选择组件</div>
     <div v-else class="analysis-dashboard-grid">
      <div v-for="component in dashboardWidgets" :key="component.id" class="analysis-dashboard-card">
       <strong>{{component.title}}</strong>
       <AnalysisV2Chart v-if="component.analysisV2" :widget="component" :readonly="true"/>
       <ChartWidget v-else :widget="component" :datasets="datasets" :readonly="true"/>
      </div>
     </div>
    </div>
    <div class="analysis-bottom-tabs">
     <button v-for="(w,index) in components" :key="w.id" :class="{active:selectedId===w.id&&currentTab==='component'}" @click="selectedId=w.id;currentTab='component'">{{w.title||'组件'+(index+1)}}</button>
     <button class="analysis-add-tab" @click="addComponent">＋ 组件</button>
    </div>
   </div>
   <aside class="analysis-properties">
    <header>组件设置</header>
    <div v-if="!chosen" class="analysis-empty-data">请选择一个组件</div>
    <template v-else>
     <div class="analysis-property-tabs"><button :class="{active:propTab==='data'}" @click="propTab='data'">数据</button><button :class="{active:propTab==='style'}" @click="propTab='style'">样式</button></div>
     <div class="analysis-property-body">
      <label>组件名称</label><el-input v-model="chosen.title" @input="mark"/>
      <template v-if="propTab==='data' && chosen.analysisV2">
       <label>图表类型</label>
       <el-select :model-value="chosen.type" @update:model-value="v=>switchKind(String(v))">
        <el-option v-for="t in types.filter(t=>['bar','hbar','line','area','pie','donut','combo','metric','table'].includes(t.type))"
         :key="t.type" :label="t.name" :value="t.type"/>
       </el-select>
       <label>查询行数上限</label><el-input-number v-model="chosen.analysisV2.limit" :min="1" :max="500" @change="mark"/>
       <label>排序指标</label>
       <el-select :model-value="chosen.analysisV2.sort[0]?.ref||''" clearable placeholder="不排序"
         @update:model-value="v=>setV2Sort(String(v||''))">
        <el-option v-for="m in chosen.analysisV2.measures" :key="m.id" :label="m.alias" :value="m.id"/>
       </el-select>
       <el-select v-if="chosen.analysisV2.sort.length" :model-value="chosen.analysisV2.sort[0].direction"
         @update:model-value="v=>setV2Direction(v as 'asc'|'desc')">
        <el-option label="降序" value="desc"/><el-option label="升序" value="asc"/>
       </el-select>
       <AnalysisFilters :key="chosen.id" :bindings="chosen.analysisV2" :fields="v2Fields" @change="mark"/>
       <label>各指标显示设置</label>
       <div v-for="item in v2MeasureLabels" :key="'style-'+item.binding.id" class="v2-measure-setting">
        <strong>{{item.field?.name||item.binding.id}}</strong>
        <el-input v-model="item.binding.alias" size="small" maxlength="80" placeholder="指标别名" @input="mark"/>
        <div class="v2-measure-inline">
         <span>小数位</span><el-input-number v-model="item.binding.decimals" :min="0" :max="6" size="small" @change="mark"/>
        </div>
        <div v-if="!['pie','donut','table','metric'].includes(chosen.type)" class="v2-measure-inline">
         <span>系列颜色</span><el-color-picker v-model="item.binding.color" size="small" @change="mark"/>
        </div>
        <div v-if="['bar','line','area','combo'].includes(chosen.type)" class="v2-measure-inline">
         <span>数值轴</span>
         <el-select v-model="item.binding.axis" size="small" placeholder="左轴" @change="mark">
          <el-option label="左轴" value="left"/><el-option label="右轴" value="right"/>
         </el-select>
        </div>
        <div v-if="chosen.type==='combo'" class="v2-measure-inline">
         <span>系列类型</span>
         <el-select v-model="item.binding.seriesType" size="small" placeholder="默认" clearable @change="mark">
          <el-option label="柱状图" value="bar"/><el-option label="折线图" value="line"/>
         </el-select>
        </div>
        <div v-if="!['metric','table'].includes(chosen.type)" class="v2-measure-inline">
         <span>数据标签</span><el-switch v-model="item.binding.showLabel" size="small" @change="mark"/>
        </div>
       </div>
      </template>
      <template v-else-if="propTab==='data'">
       <label>图表类型</label><el-select v-model="chosen.type" @change="mark"><el-option v-for="t in types" :key="t.type" :value="t.type" :label="t.name"/></el-select>
       <label>维度</label><el-select v-model="chosen.dimension" filterable clearable @change="mark"><el-option v-for="f in dimensions" :key="f.name" :label="f.name" :value="f.name"/></el-select>
       <label>数值指标</label><el-select v-model="chosen.measure" filterable clearable @change="mark"><el-option v-for="f in metrics" :key="f.name" :label="f.name" :value="f.name"/></el-select>
       <label>聚合方式</label><el-select v-model="chosen.agg" @change="mark"><el-option v-for="agg in ['SUM','AVG','COUNT','MIN','MAX']" :key="agg" :value="agg" :label="agg"/></el-select>
      </template>
      <template v-else>
       <label>主题色</label><el-color-picker v-model="chosen.style.chartColor" @change="mark"/>
       <label>显示图例</label><el-switch v-model="chosen.style.legend" @change="mark"/>
       <label>显示数据标签</label><el-switch v-model="chosen.style.dataLabel" @change="mark"/>
       <label>显示标题</label><el-switch v-model="chosen.style.showTitle" @change="mark"/>
       <label>显示网格线</label><el-switch v-model="chosen.style.showGrid" @change="mark"/>
      </template>
      <button class="analysis-delete-component" @click="removeComponent">删除当前组件</button>
     </div>
    </template>
   </aside>
  </div>
 </div>
 <FormulaEditor v-model="formulaDialog" :dataset-id="chosen?.datasetId||''"
  :fields="v2Fields" @created="refreshFormulaFields"/>
</template>
