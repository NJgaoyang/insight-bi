<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,put,type FieldV2} from './api'
import {copyEditablePipelineStep,prefixForPipelinePreview} from './analysis/pipelineEditing'
import {joinPreflight} from './analysis/joinPreflight'
import {checkPipelineDependencies} from './analysis/pipelineDependencies'
import {pipelineRelationGraph} from './analysis/pipelineRelationGraph'
import {joinKeySignature,currentRightKeyProfile,cardinalitySummary,type RightKeyProfile,type ProfileSnapshot} from './analysis/joinCardinality'

const props=defineProps<{id:string}>()
const emit=defineEmits<{(e:'back'):void}>()

type FormulaStep={id:string;type:'formula';fieldId:string;name:string;expression:string}
type FilterStep={id:string;type:'filter';fieldId:string;op:string;value?:string;values?:string[]}
type CastStep={id:string;type:'cast';fieldId:string;sourceFieldId:string;name:string;outputType:'STRING'|'DECIMAL'}
type Measure={id:string;fieldId:string;aggregate:'SUM'|'AVG'|'COUNT'|'MIN'|'MAX';name:string}
type GroupStep={id:string;type:'group';dimensions:string[];measures:Measure[]}
type CaseStep={id:string;type:'case';fieldId:string;sourceFieldId:string;name:string;
 op:string;value?:string;values?:string[];outputType:'STRING'|'DECIMAL';thenValue:string;elseValue:string}
type UnionMapping={leftFieldId:string;rightFieldId:string}
type UnionStep={id:string;type:'union';sourceDatasetId:string;mappings?:UnionMapping[];distinct?:boolean}
type JoinKey={leftFieldId:string;rightFieldId:string}
type JoinOutput={sourceFieldId:string;fieldId:string;name:string}
type JoinStep={id:string;type:'join';sourceDatasetId:string;joinType:'INNER'|'LEFT';
 conditions:JoinKey[];fields:JoinOutput[];acknowledgeFanout:boolean}
type Step=FormulaStep|FilterStep|GroupStep|CaseStep|CastStep|UnionStep|JoinStep
type Column={id:string;name:string;label:string;type:string;numeric:boolean}

const loading=ref(true),busy=ref(false),dirty=ref(false),stale=ref(false)
const datasetName=ref('数据加工'),revision=ref(0)
type DatasetOption={id:string;name:string;connection_id:string}
const availableDatasets=ref<DatasetOption[]>([])
const primaryConnection=ref('')
const unionCandidates=computed(()=>availableDatasets.value.filter(d=>
 d.id!==props.id&&d.connection_id===primaryConnection.value))
const baseFields=ref<FieldV2[]>([])
const joinedFields=ref<Record<string,FieldV2[]>>({})
const rightFields=(id:string):Column[]=>(joinedFields.value[id]||[])
 .filter(f=>!f.calculated).map(f=>({id:f.id,name:f.sourceColumn||f.id,
 label:f.name,type:f.logicalType,numeric:f.numeric}))
const baseColumns=():Column[]=>baseFields.value.filter(f=>!f.calculated)
 .map(f=>({id:f.id,name:f.sourceColumn||f.id,label:f.name,type:f.logicalType,numeric:f.numeric}))
const steps=ref<Step[]>([])
const relationExpanded=ref(true)
const relationGraph=computed(()=>pipelineRelationGraph(
 {id:props.id,name:datasetName.value},availableDatasets.value,
 baseColumns().map(f=>({id:f.id,label:f.label,type:f.type})),
 Object.fromEntries(Object.entries(joinedFields.value).map(([id,fields])=>[
  id,fields.filter(f=>!f.calculated).map(f=>({
   id:f.id,label:f.name,type:f.logicalType
  }))
 ])),steps.value
))
const queryLimit=ref(30)
const sqlPreview=ref('')
const joinProfile=ref<ProfileSnapshot|null>(null)
const profiling=ref(false)
let profileRequestGeneration=0
const shownJoinProfile=computed(()=>{
 const join=steps.value.find((s):s is JoinStep=>s.type==='join')
 return join?currentRightKeyProfile(joinProfile.value,props.id,join):null
})
const previewRows=ref<Record<string,unknown>[]>([])
const previewColumns=ref<Column[]>([])
const truncated=ref(false)
let savedSteps='[]',lastSteps='[]',previewVersion=0
const undoHistory=ref<string[]>([]),redoHistory=ref<string[]>([])
const previewScope=ref('')
function clearPreview(){
 previewVersion++
 profileRequestGeneration++
 profiling.value=false
 joinProfile.value=null
 sqlPreview.value='';previewRows.value=[];previewColumns.value=[]
 truncated.value=false;previewScope.value=''
}
function restoreStepHistory(from:typeof undoHistory,to:typeof redoHistory){
 const snapshot=from.value.pop()
 if(snapshot===undefined)return
 to.value.push(JSON.stringify(steps.value))
 if(to.value.length>30)to.value.shift()
 steps.value=JSON.parse(snapshot) as Step[]
 lastSteps=snapshot
 dirty.value=snapshot!==savedSteps
 clearPreview()
}
function undoSteps(){restoreStepHistory(undoHistory,redoHistory)}
function redoSteps(){restoreStepHistory(redoHistory,undoHistory)}

const selectable=(index:number):Column[]=>{
 let fields:Column[]=baseFields.value.filter(f=>!f.calculated||f.phase==='ROW')
  .map(f=>({id:f.id,name:f.sourceColumn||f.id,label:f.name,type:f.logicalType,numeric:f.numeric}))
 for(const step of steps.value.slice(0,index)){
  if(step.type==='formula'){
   fields=[...fields,{id:step.fieldId,name:'step_'+step.fieldId,label:step.name,type:'DECIMAL',numeric:true}]
  }
  if(step.type==='case'||step.type==='cast'){
   fields=[...fields,{id:step.fieldId,name:'step_'+step.fieldId,label:step.name,
    type:step.outputType,numeric:step.outputType==='DECIMAL'}]
  }
  if(step.type==='join'){
   fields=[...fields,...step.fields.map(x=>{
    const source=rightFields(step.sourceDatasetId).find(f=>f.id===x.sourceFieldId)
    return {id:x.fieldId,name:'join_'+x.fieldId,label:x.name,
     type:source?.type||'STRING',numeric:source?.numeric||false}
   })]
  }
  if(step.type==='group'){
   fields=[
    ...step.dimensions.map(id=>fields.find(f=>f.id===id)).filter((x):x is Column=>!!x)
     .map(f=>({...f,name:'d_'+f.id})),
    ...step.measures.map(m=>({id:m.id,name:'m_'+m.id,label:m.name,type:'DECIMAL',numeric:true}))
   ]
  }
 }
 return fields
}
const isGroupLimit=computed(()=>steps.value.filter(s=>s.type==='group').length>=2)
const dependencyBase=()=>baseFields.value.filter(f=>!f.calculated||f.phase==='ROW')
 .map(f=>({id:f.id,type:f.logicalType,numeric:f.numeric}))
const dependencyReport=computed(()=>checkPipelineDependencies(dependencyBase(),steps.value))
function dependencyIssuesAt(index:number):string[]{
 return dependencyReport.value.issues.filter(issue=>issue.index===index).map(issue=>issue.message)
}
function previewBlocked(index?:number):boolean{
 const size=index===undefined?steps.value.length:index+1
 return checkPipelineDependencies(dependencyBase(),steps.value.slice(0,size)).issues.length>0
}
function joinIssues(step:JoinStep):string[]{
 return joinPreflight(step,baseColumns(),rightFields(step.sourceDatasetId),
  unionCandidates.value.map(d=>d.id))
}
const joinValidationProblems=computed(()=>steps.value
 .filter((s):s is JoinStep=>s.type==='join').flatMap(s=>joinIssues(s)))
function changeJoinKeys(step:JoinStep){
 // Acknowledgment applies only to the exact current key set and join type.
 step.acknowledgeFanout=false
 changed()
}
function changed(){
 const next=JSON.stringify(steps.value)
 if(next!==lastSteps){
  undoHistory.value.push(lastSteps)
  if(undoHistory.value.length>30)undoHistory.value.shift()
  redoHistory.value=[]
  lastSteps=next
 }
 dirty.value=next!==savedSteps
 clearPreview()
}
function nextId(prefix:string){return prefix+'_'+crypto.randomUUID().replace(/-/g,'').slice(0,12)}
function insertFormulaField(step:FormulaStep,id:string){
 step.expression+=(step.expression&&!step.expression.endsWith(' ')?' ':'')+'['+id+']'
 changed()
}

function addFormula(){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 const id=nextId('s')
 steps.value.push({id,type:'formula',fieldId:nextId('field'),name:'计算列',expression:''})
 changed()
}
function unionMappingFor(sourceId:string):UnionMapping[]{
 const used=new Set<string>()
 return baseColumns().map(left=>{
  const right=rightFields(sourceId).find(f=>f.type===left.type&&f.name===left.name&&!used.has(f.id))
   ||rightFields(sourceId).find(f=>f.type===left.type&&!used.has(f.id))
  if(right)used.add(right.id)
  return {leftFieldId:left.id,rightFieldId:right?.id||''}
 })
}
async function changeUnionSource(step:UnionStep,id:string){
 try{
  await ensureRightFields(id)
  step.sourceDatasetId=id
  if(step.mappings)step.mappings=unionMappingFor(id)
  changed()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'合并字段读取失败')}
}
async function enableUnionMapping(step:UnionStep){
 try{
  await ensureRightFields(step.sourceDatasetId)
  step.mappings=unionMappingFor(step.sourceDatasetId)
  changed()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'合并字段读取失败')}
}
function disableUnionMapping(step:UnionStep){delete step.mappings;changed()}
function addUnion(){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 if(steps.value.some(x=>x.type==='union'||x.type==='join')){
  ElMessage.warning('首批仅支持一次合并或关联');return
 }
 const candidate=unionCandidates.value[0]
 if(!candidate){ElMessage.warning('没有相同数据连接的其他数据集');return}
 steps.value.unshift({id:nextId('s'),type:'union',sourceDatasetId:candidate.id})
 changed()
}
async function ensureRightFields(id:string){
 if(!id||joinedFields.value[id])return
 joinedFields.value[id]=await api<FieldV2[]>('/datasets/'+encodeURIComponent(id)+'/fields')
}
function joinKeyFor(sourceId:string):JoinKey{
 const left=baseColumns(),right=rightFields(sourceId)
 const l=left.find(a=>right.some(b=>b.name===a.name&&b.type===a.type))||left[0]
 const r=right.find(b=>b.name===l?.name&&b.type===l?.type)||
  right.find(b=>b.type===l?.type)||right[0]
 return {leftFieldId:l?.id||'',rightFieldId:r?.id||''}
}
function joinOutputFor(sourceId:string,used:JoinKey[]):JoinOutput{
 const right=rightFields(sourceId)
 const keyIds=new Set(used.map(k=>k.rightFieldId))
 const src=right.find(x=>!keyIds.has(x.id))||right[0]
 return {sourceFieldId:src?.id||'',fieldId:nextId('field'),
  name:src?src.label+'（关联）':'关联字段'}
}
async function addJoin(){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 if(steps.value.some(x=>x.type==='union'||x.type==='join')){
  ElMessage.warning('首批仅支持一次合并或关联');return
 }
 const candidate=unionCandidates.value[0]
 if(!candidate){ElMessage.warning('没有相同数据连接的其他数据集');return}
 try{
  await ensureRightFields(candidate.id)
  const condition=joinKeyFor(candidate.id)
  steps.value.unshift({id:nextId('s'),type:'join',
   sourceDatasetId:candidate.id,joinType:'LEFT',conditions:[condition],
   fields:[joinOutputFor(candidate.id,[condition])],acknowledgeFanout:false})
  changed()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'关联字段读取失败')}
}
async function changeJoinSource(step:JoinStep,id:string){
 try{
  await ensureRightFields(id)
  const key=joinKeyFor(id)
  step.sourceDatasetId=id;step.conditions=[key]
  step.fields=[joinOutputFor(id,[key])]
  step.acknowledgeFanout=false
  changed()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'关联字段读取失败')}
}
function addJoinKey(step:JoinStep){
 if(step.conditions.length>=4)return
 const used=new Set(step.conditions.map(k=>k.leftFieldId+':'+k.rightFieldId))
 const right=rightFields(step.sourceDatasetId)
 const choices=baseColumns().flatMap(l=>right
  .filter(r=>l.type===r.type&&!used.has(l.id+':'+r.id))
  .map(r=>({leftFieldId:l.id,rightFieldId:r.id,matchingName:l.name===r.name})))
 const next=choices.find(x=>x.matchingName)||choices[0]
 if(!next){ElMessage.warning('没有更多类型兼容的关联字段组合');return}
 step.conditions.push({leftFieldId:next.leftFieldId,rightFieldId:next.rightFieldId})
 changeJoinKeys(step)
}
function addJoinOutput(step:JoinStep){
 if(step.fields.length>=30)return
 step.fields.push(joinOutputFor(step.sourceDatasetId,step.conditions));changed()
}
function changeCaseOperator(step:CaseStep){
 if(step.op==='in'){
  step.values=step.values?.length?step.values:(step.value?[step.value]:[])
  delete step.value
 }else if(step.op!=='isNull'&&step.op!=='notNull'&&step.values){
  step.value=step.values[0]||''
  delete step.values
 }
 changed()
}
function addCase(){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 const source=selectable(steps.value.length)[0]
 steps.value.push({id:nextId('s'),type:'case',fieldId:nextId('field'),
  sourceFieldId:source?.id||'',name:'条件列',op:'eq',value:'',
  outputType:'STRING',thenValue:'满足',elseValue:'其他'})
 changed()
}
function changeFilterOperator(step:FilterStep){
 if(step.op==='in'){
  step.values=step.values?.length?step.values:(step.value?[step.value]:[])
  delete step.value
 }else if(step.op!=='isNull'&&step.op!=='notNull'&&step.values){
  step.value=step.values[0]||''
  delete step.values
 }
 changed()
}
function addCast(){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 const available=selectable(steps.value.length)
 const source=available.find(f=>f.numeric)||available.find(f=>['DATE','DATETIME','BOOLEAN'].includes(f.type))
 if(!source){ElMessage.warning('当前步骤没有可安全转换的数值或日期字段');return}
 steps.value.push({id:nextId('s'),type:'cast',fieldId:nextId('field'),
  sourceFieldId:source.id,name:source.label+'（文本）',outputType:'STRING'})
 changed()
}
function castTargetsFor(index:number,sourceId:string):Array<'STRING'|'DECIMAL'>{
 const field=selectable(index).find(f=>f.id===sourceId)
 if(!field)return []
 if(['INTEGER','DECIMAL','DOUBLE'].includes(field.type))return ['STRING','DECIMAL']
 if(['DATE','DATETIME','BOOLEAN'].includes(field.type))return ['STRING']
 return []
}
function changeCastSource(step:CastStep,index:number){
 const allowed=castTargetsFor(index,step.sourceFieldId)
 if(!allowed.includes(step.outputType))step.outputType=allowed[0]||'STRING'
 changed()
}
function addFilter(){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 const id=nextId('s'),field=selectable(steps.value.length)[0]
 steps.value.push({id,type:'filter',fieldId:field?.id||'',op:'eq',value:''})
 changed()
}
function addGroup(){
 if(steps.value.length>=12||isGroupLimit.value){ElMessage.warning('最多支持两级分组汇总');return}
 const fields=selectable(steps.value.length)
 steps.value.push({id:nextId('s'),type:'group',dimensions:[],
  measures:[{id:nextId('m'),fieldId:fields.find(f=>f.numeric)?.id||'*',aggregate:'SUM',name:'汇总'}]})
 changed()
}
function addMeasure(step:GroupStep,index:number){
 if(step.measures.length>=5){ElMessage.warning('最多 5 个指标');return}
 const available=selectable(index)
 step.measures.push({id:nextId('m'),fieldId:available.find(f=>f.numeric)?.id||'*',
  aggregate:'SUM',name:'指标'+(step.measures.length+1)})
 changed()
}
function move(index:number,delta:number){
 const next=index+delta
 if(next<0||next>=steps.value.length)return
 if((index===0&&['union','join'].includes(steps.value[index]?.type||''))||
    (next===0&&['union','join'].includes(steps.value[0]?.type||''))){
  ElMessage.warning('关联或上下合并需要保留在第一步');return
 }
 const [item]=steps.value.splice(index,1)
 steps.value.splice(next,0,item)
 changed()
}
function removeStep(index:number){steps.value.splice(index,1);changed()}
function duplicateStep(index:number){
 if(steps.value.length>=12){ElMessage.warning('最多添加 12 个步骤');return}
 const original=steps.value[index]
 if(!original)return
 const copy=copyEditablePipelineStep(original,nextId('s'),nextId('field'))
 if(!copy){ElMessage.warning('仅支持复制计算列、条件列或过滤步骤');return}
 if(steps.value.some(step=>step.id===copy.id||
    ((step.type==='formula'||step.type==='case')&&
     (copy.type==='formula'||copy.type==='case')&&step.fieldId===copy.fieldId))){
  ElMessage.warning('无法生成唯一步骤标识');return
 }
 steps.value.splice(index+1,0,copy)
 changed()
}
function previewStep(index:number){void preview(index)}
function request(){return {steps:steps.value,limit:queryLimit.value}}
async function load(){
 loading.value=true
 try{
  const [all,fields,config]=await Promise.all([
   api<Array<{id:string;name:string}>>('/datasets'),
   api<FieldV2[]>('/datasets/'+encodeURIComponent(props.id)+'/fields'),
   api<{steps:Step[];revision:number;stale:boolean}>('/datasets/'+encodeURIComponent(props.id)+'/pipeline')
  ])
  const source=all.find(x=>x.id===props.id)
  datasetName.value=source?.name||'数据加工'
  primaryConnection.value=(source as DatasetOption|undefined)?.connection_id||''
  availableDatasets.value=all as DatasetOption[]
  baseFields.value=fields
  steps.value=structuredClone(config.steps)
  const savedJoin=steps.value.find((x):x is JoinStep=>x.type==='join')
  const savedUnion=steps.value.find((x):x is UnionStep=>x.type==='union'&&!!x.mappings)
  if(savedJoin)await ensureRightFields(savedJoin.sourceDatasetId)
  if(savedUnion)await ensureRightFields(savedUnion.sourceDatasetId)
  revision.value=config.revision;stale.value=config.stale
  savedSteps=JSON.stringify(steps.value);lastSteps=savedSteps;dirty.value=false
  undoHistory.value=[];redoHistory.value=[];clearPreview()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'读取步骤失败')}
 finally{loading.value=false}
}
async function checkJoinKeys(step:JoinStep){
 // Snapshot before the async call: a late result for an older key must never
 // show after an edit, undo, another check or switching to a new JOIN.
 const key=joinKeySignature(props.id,step)
 const generation=++profileRequestGeneration
 profiling.value=true
 joinProfile.value=null
 try{
  const result=await post<RightKeyProfile>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/join-key-profile',
   {step:JSON.parse(JSON.stringify(step))})
  if(generation!==profileRequestGeneration||
     !steps.value.some(s=>s.type==='join'&&joinKeySignature(props.id,s)===key))return
  const snapshot:ProfileSnapshot={key,result}
  if(!currentRightKeyProfile(snapshot,props.id,step)){
   ElMessage.warning('右表基数诊断返回数据无效，请重新检查')
   return
  }
  joinProfile.value=snapshot
 }catch(e:unknown){
  if(generation===profileRequestGeneration)
   ElMessage.error(e instanceof Error?e.message:'关联键检查失败')
 }finally{if(generation===profileRequestGeneration)profiling.value=false}
}
function acknowledgeJoinRisk(){
 const profile=joinProfile.value
 changed()
 if(profile)joinProfile.value=profile
}
async function compile(){
 if(dependencyReport.value.issues.length){
  ElMessage.warning('步骤依赖无效，请先修复标记的问题');return
 }
 busy.value=true
 try{
  const result=await post<{sqlPreview:string;columns:Column[]}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/compile',request())
  sqlPreview.value=result.sqlPreview;previewColumns.value=result.columns
  ElMessage.success('步骤编译通过')
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'步骤编译失败')}
 finally{busy.value=false}
}
async function preview(upTo?:number){
 if(busy.value)return
 if(previewBlocked(upTo)){ElMessage.warning('预览范围内存在失效字段，请先修复');return}
 let input:Step[]
 try{input=upTo===undefined?JSON.parse(JSON.stringify(steps.value)) as Step[]:prefixForPipelinePreview(steps.value,upTo)}
 catch{ElMessage.warning('无效的预览位置');return}
 const sequence=++previewVersion
 busy.value=true
 try{
  const result=await post<{rows:Record<string,unknown>[];columns:Column[];truncated:boolean}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/preview',{steps:input,limit:queryLimit.value})
  if(sequence!==previewVersion)return
  previewRows.value=result.rows;previewColumns.value=result.columns
  truncated.value=result.truncated;sqlPreview.value=''
  previewScope.value=upTo===undefined?'完整加工链':'截至第 '+(upTo+1)+' 步'
 }catch(e:unknown){
  if(sequence===previewVersion)ElMessage.error(e instanceof Error?e.message:'真实数据预览失败')
 }finally{busy.value=false}
}
type PublishedImpact={dashboardId:string;dashboardName:string;widgetCount:number}
type ChangeImpact={revision:number;reports:PublishedImpact[];token:string;requiresAcknowledgment:boolean}
async function save(){
 if(dependencyReport.value.issues.length||joinValidationProblems.value.length){
  ElMessage.warning('加工链存在无效配置，请先修复');return
 }
 if(busy.value)return
 busy.value=true
 const draft=JSON.stringify(steps.value)
 try{
  // A no-op / new pipeline does not require published impact confirmation.
  const impact=await api<ChangeImpact>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/change-impact?revision='+revision.value)
  if(impact.revision!==revision.value)
   throw new Error('模型版本已变化，请刷新后重新检查影响')
  if(impact.requiresAcknowledgment){
   const reports=impact.reports.map(r=>r.dashboardName+'（'+r.widgetCount+' 个组件）')
   const summary=reports.slice(0,5).join('、')+
    (reports.length>5?'等 '+reports.length+' 个分析主题':'')
   try{
    await ElMessageBox.confirm(
     '保存将升级加工版本，导致以下已发布组件的原版本绑定失效：'+summary+
     '。确认后需检查并重新发布受影响的分析主题。',
     '确认已发布模型变更影响',{
      type:'warning',confirmButtonText:'已了解影响，继续保存',cancelButtonText:'取消'
     })
   }catch{return}
  }
  if(draft!==JSON.stringify(steps.value))
   throw new Error('加工步骤已变化，请重新执行影响检查')
  const result=await put<{revision:number;stale:boolean}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline',{
    steps:JSON.parse(draft) as Step[],revision:revision.value,
    ...(impact.requiresAcknowledgment?{
     acknowledgePublishedImpact:true,impactToken:impact.token
    }:{})
   })
  revision.value=result.revision;stale.value=result.stale
  savedSteps=draft;lastSteps=savedSteps;dirty.value=false
  undoHistory.value=[];redoHistory.value=[]
  ElMessage.success('加工步骤已保存')
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'保存失败')}
 finally{busy.value=false}
}
async function goBack(){
 if(dirty.value){
  try{await ElMessageBox.confirm('未保存的加工步骤将丢失，确定离开？','离开数据加工',{type:'warning'})}
  catch{return}
 }
 emit('back')
}
onMounted(load)
</script>

<template>
 <div class="pipeline-page">
  <header class="pipeline-header">
   <button class="pipeline-link" @click="goBack">← 公共数据</button>
   <strong>{{datasetName}} / 数据加工</strong>
   <span v-if="dirty" class="pipeline-unsaved">未保存</span>
   <span v-if="stale" class="pipeline-warning">数据集 SQL 已变化，请重新校验保存</span>
   <div class="pipeline-actions">
    <button :disabled="busy||loading||dependencyReport.issues.length>0" @click="compile">SQL 预览</button>
    <button :disabled="busy||loading||dependencyReport.issues.length>0" @click="preview()">数据预览</button>
    <button title="撤销加工编辑" :disabled="busy||loading||!undoHistory.length" @click="undoSteps">撤销</button>
    <button title="重做加工编辑" :disabled="busy||loading||!redoHistory.length" @click="redoSteps">重做</button>
    <button class="pipeline-save" :disabled="busy||loading||!dirty||joinValidationProblems.length>0||dependencyReport.issues.length>0" @click="save">保存步骤</button>
   </div>
  </header>
  <div v-if="loading" class="pipeline-empty">正在读取加工配置…</div>
  <div v-else class="pipeline-content">
   <div class="pipeline-steps">
    <div v-if="dependencyReport.issues.length" class="pipeline-warning pipeline-dependency-summary" role="status">
     {{dependencyReport.issues.length}} 处字段依赖错误：下方已标明受影响的步骤，修复或撤销后才能保存。
    </div>
    <div class="pipeline-toolbar">
     <strong>加工步骤</strong><small>按顺序执行 · 最多 12 步</small>
     <div>
      <button @click="addFormula">＋ 计算列</button>
      <button @click="addCast">＋ 类型转换</button>
      <button @click="addFilter">＋ 过滤</button>
      <button @click="addCase">＋ 条件列</button>
      <button :disabled="steps.some(x=>x.type==='union'||x.type==='join')||!unionCandidates.length"
       @click="addUnion">＋ 上下合并</button>
      <button :disabled="steps.some(x=>x.type==='union'||x.type==='join')||!unionCandidates.length"
       @click="addJoin">＋ 关联</button>
      <button :disabled="isGroupLimit" @click="addGroup">＋ 分组汇总</button>
     </div>
    </div>
    <aside v-if="relationGraph" class="pipeline-relation-graph" aria-label="当前数据加工关系概览">
     <header class="pipeline-relation-head">
      <strong>数据模型关系</strong>
      <span>根据当前 JOIN / UNION 步骤配置生成 · 只读预览</span>
      <button type="button" :aria-expanded="relationExpanded" @click="relationExpanded=!relationExpanded">
       {{relationExpanded?'收起关系':'展开关系'}}
      </button>
     </header>
     <div v-if="relationExpanded" class="pipeline-relation-body">
      <div class="pipeline-relation-nodes">
       <div class="pipeline-relation-node"><small>主数据集</small><strong>{{relationGraph.source.name}}</strong></div>
       <div class="pipeline-relation-arrow" aria-hidden="true">
        <span>{{relationGraph.mode}}</span><span>→</span>
       </div>
       <div class="pipeline-relation-node"><small>关联 / 合并数据集</small><strong>{{relationGraph.target.name}}</strong></div>
      </div>
      <div class="pipeline-relation-details">
       <strong>{{relationGraph.title}}</strong>
       <div v-if="relationGraph.links.length" class="pipeline-relation-links">
        <div v-for="(link,index) in relationGraph.links" :key="index" class="pipeline-relation-link"
         :class="{'pipeline-relation-invalid':!link.compatible}">
         <span>{{link.from}}</span><span aria-hidden="true">↔</span><span>{{link.to}}</span>
         <small>{{link.compatible?'字段类型一致':'字段缺失或类型不一致'}}</small>
        </div>
       </div>
       <p v-else class="pipeline-relation-note">尚未设置逐字段对应；最终字段映射由服务端编译验证。</p>
       <p v-if="relationGraph.outputs.length" class="pipeline-relation-note">
        {{relationGraph.outputs.length}} 个右表输出字段；
        {{relationGraph.downstreamSteps}} 个后续步骤直接引用这些字段
       </p>
       <section v-if="relationGraph.impacts.length" class="pipeline-relation-impact" aria-label="字段下游影响">
        <strong>右表字段影响链（仅已配置的引用）</strong>
        <div v-for="item in relationGraph.impacts" :key="item.outputFieldId" class="pipeline-relation-impact-row">
         <span class="pipeline-relation-impact-field">{{item.outputName}} <small>{{item.outputFieldId}}</small></span>
         <span v-if="!item.steps.length" class="pipeline-relation-impact-empty">未发现直接或派生字段引用</span>
         <span v-for="step in item.steps" :key="step.stepId" class="pipeline-relation-impact-step">
          第{{step.index}}步 · {{step.type==='formula'?'计算列':step.type==='group'?'分组汇总':
          step.type==='filter'?'数据过滤':step.type==='case'?'条件列':
          step.type==='cast'?'类型转换':step.type}}
         </span>
        </div>
        <p>此列表仅显示明确字段引用路径，不包含过滤改变行集等隐式影响；保存仍以服务端编译校验为准。</p>
       </section>
       <p v-if="relationGraph.warning" class="pipeline-relation-warning" role="status">{{relationGraph.warning}}</p>
      </div>
     </div>
    </aside>
    <div v-if="!steps.length" class="pipeline-empty">暂无加工步骤，可从上方添加</div>
    <section v-for="(step,index) in steps" :key="step.id" class="pipeline-step">
     <header class="pipeline-step-header">
      <strong>{{index+1}}. {{step.type==='formula'?'计算列':step.type==='filter'?'数据过滤':step.type==='case'?'条件列':step.type==='cast'?'类型转换':step.type==='union'?'上下合并':step.type==='join'?'数据关联':'分组汇总'}}</strong>
      <button title="步骤前移" :disabled="index===0||(index===1&&['union','join'].includes(steps[0]?.type||''))" @click="move(index,-1)">↑</button>
      <button title="步骤后移" :disabled="index===steps.length-1||step.type==='union'||step.type==='join'" @click="move(index,1)">↓</button>
      <button title="复制步骤" :disabled="busy||steps.length>=12||!['formula','case','filter'].includes(step.type)" @click="duplicateStep(index)">复制</button>
      <button title="预览至此步骤" :disabled="busy||previewBlocked(index)" @click="previewStep(index)">预览到此步</button>
      <button title="删除步骤" @click="removeStep(index)">删除</button>
     </header>
     <div v-if="dependencyIssuesAt(index).length" class="pipeline-warning pipeline-dependency-issue" role="alert">
      <span v-for="(issue,i) in dependencyIssuesAt(index)" :key="i">{{issue}}</span>
     </div>
     <div v-if="step.type==='union'" class="pipeline-fields">
      <label>需要追加的数据集</label>
      <el-select :model-value="step.sourceDatasetId" filterable
       @update:model-value="v=>changeUnionSource(step,String(v))">
       <el-option v-for="source in unionCandidates" :key="source.id"
        :label="source.name" :value="source.id"/>
      </el-select>
      <button v-if="!step.mappings" @click="enableUnionMapping(step)">配置字段映射</button>
      <template v-else>
       <label>字段对应关系（左侧字段 → 追加数据集字段）</label>
       <div v-for="mapping in step.mappings" :key="mapping.leftFieldId" class="pipeline-measure">
        <span>{{baseColumns().find(f=>f.id===mapping.leftFieldId)?.label||mapping.leftFieldId}}</span>
        <span>→</span>
        <el-select v-model="mapping.rightFieldId" filterable @change="changed">
         <el-option v-for="right in rightFields(step.sourceDatasetId).filter(r=>
          r.type===baseColumns().find(f=>f.id===mapping.leftFieldId)?.type)"
          :key="right.id" :label="right.label+' ('+right.name+')'" :value="right.id"
          :disabled="step.mappings?.some(x=>x!==mapping&&x.rightFieldId===right.id)"/>
        </el-select>
       </div>
       <button @click="disableUnionMapping(step)">恢复同名字段匹配</button>
      </template>
      <el-checkbox v-model="step.distinct" @change="changed">合并后按整行去重（UNION DISTINCT）</el-checkbox>
      <small>默认 UNION ALL 保留重复记录；开启后仅移除所有输出字段完全相同的行。</small>
     </div>
     <div v-else-if="step.type==='join'" class="pipeline-fields">
      <label>关联数据集（同一数据连接）</label>
      <el-select :model-value="step.sourceDatasetId" filterable
       @update:model-value="v=>changeJoinSource(step,String(v))">
       <el-option v-for="source in unionCandidates" :key="source.id"
        :label="source.name" :value="source.id"/>
      </el-select>
      <label>关联方式</label>
      <el-select v-model="step.joinType" @change="changeJoinKeys(step)">
       <el-option label="LEFT JOIN（保留左表全部记录）" value="LEFT"/>
       <el-option label="INNER JOIN（只保留匹配记录）" value="INNER"/>
      </el-select>
      <label>关联条件（等值匹配，最多 4 组）</label>
      <div v-for="(condition,ci) in step.conditions" :key="ci" class="pipeline-measure">
       <el-select v-model="condition.leftFieldId" filterable @change="changeJoinKeys(step)">
        <el-option v-for="field in baseColumns()" :key="field.id" :label="field.label"
         :value="field.id"/>
       </el-select>
       <span>＝</span>
       <el-select v-model="condition.rightFieldId" filterable @change="changeJoinKeys(step)">
        <el-option v-for="field in rightFields(step.sourceDatasetId)" :key="field.id"
         :label="field.label" :value="field.id"/>
       </el-select>
       <button :disabled="step.conditions.length===1"
        title="删除关联条件" @click="step.conditions.splice(ci,1);changeJoinKeys(step)">×</button>
      </div>
      <button :disabled="step.conditions.length>=4" @click="addJoinKey(step)">＋ 添加关联条件</button>
      <label>右表输出字段（最多 30 列）</label>
      <div v-for="(field,fi) in step.fields" :key="field.fieldId" class="pipeline-measure">
       <el-select v-model="field.sourceFieldId" filterable @change="changed">
        <el-option v-for="right in rightFields(step.sourceDatasetId)" :key="right.id"
         :label="right.label" :value="right.id"/>
       </el-select>
       <el-input v-model="field.name" placeholder="输出列名" @input="changed"/>
       <button :disabled="step.fields.length===1"
        title="删除右表输出字段" @click="step.fields.splice(fi,1);changed()">×</button>
      </div>
      <button :disabled="step.fields.length>=30" @click="addJoinOutput(step)">＋ 添加输出字段</button>
      <button :disabled="profiling||busy" @click="checkJoinKeys(step)">
       {{profiling?'检查中…':'检查右表关联键重复'}}
      </button>
      <p v-if="shownJoinProfile" class="pipeline-join-cardinality" role="status">
       {{cardinalitySummary(shownJoinProfile)}}
      </p>
      <small v-else>仅扫描当前右侧数据集的关联键；修改关联条件后需重新检查。</small>
      <div v-if="joinIssues(step).length" class="pipeline-warning" role="alert">
       {{joinIssues(step).join('；')}}
      </div>
      <el-checkbox v-model="step.acknowledgeFanout" @change="acknowledgeJoinRisk">
       我已了解：右表关联键重复可能放大结果行数
      </el-checkbox>
      <small>必须确认行数放大风险后才能保存；不自动去重，也不改动原始数据。</small>
     </div>
     <div v-else-if="step.type==='cast'" class="pipeline-fields">
      <label>源字段（仅数值、日期、布尔字段）</label>
      <el-select :model-value="step.sourceFieldId" filterable
       @update:model-value="v=>{step.sourceFieldId=String(v);changeCastSource(step,index)}">
       <el-option v-for="field in selectable(index).filter(f=>['INTEGER','DECIMAL','DOUBLE','DATE','DATETIME','BOOLEAN'].includes(f.type))"
        :key="field.id" :label="field.label" :value="field.id"/>
      </el-select>
      <label>输出字段名称</label><el-input v-model="step.name" maxlength="100" @input="changed"/>
      <label>目标类型</label>
      <el-select v-model="step.outputType" @change="changed">
       <el-option v-for="type in castTargetsFor(index,step.sourceFieldId)"
        :key="type" :value="type" :label="type==='DECIMAL'?'数值 DECIMAL(38,12)':'文本 STRING'"/>
      </el-select>
      <small>生成新的稳定字段 ID，保留源字段。文本转数值不允许直接转换，以免无效数据被当作 0；数值转 DECIMAL 可能出现精度舍入。</small>
     </div>
     <div v-else-if="step.type==='formula'" class="pipeline-fields">
      <label>列名称</label><el-input v-model="step.name" maxlength="100" @input="changed"/>
      <label>计算表达式</label><el-input v-model="step.expression" type="textarea" :rows="2"
       placeholder="选择下方字段插入引用，例如：[字段ID]-[字段ID]" @input="changed"/>
      <div class="pipeline-formula-fields">
       <button v-for="field in selectable(index)" :key="field.id"
        :title="'插入字段：'+field.label" @click="insertFormulaField(step,field.id)">＋ {{field.label}}</button>
      </div>
      <small>点击字段插入稳定 ID；公式在后端校验，不执行任意 SQL。</small>
     </div>
     <div v-else-if="step.type==='filter'" class="pipeline-fields">
      <label>字段</label><el-select v-model="step.fieldId" filterable @change="changed">
       <el-option v-for="f in selectable(index)" :key="f.id" :label="f.label" :value="f.id"/>
      </el-select>
      <label>条件</label><el-select v-model="step.op" @change="changeFilterOperator(step)">
       <el-option v-for="o in [{id:'eq',name:'等于'},{id:'ne',name:'不等于'},
        {id:'gt',name:'大于'},{id:'gte',name:'大于等于'},
        {id:'lt',name:'小于'},{id:'lte',name:'小于等于'},
        {id:'in',name:'属于集合'},{id:'contains',name:'包含'},
        {id:'isNull',name:'为空'},{id:'notNull',name:'不为空'}]"
        :key="o.id" :label="o.name" :value="o.id"/>
      </el-select>
      <template v-if="step.op==='in'">
       <label>筛选值（1～30 个）</label>
       <el-select v-model="step.values" multiple filterable allow-create default-first-option
        :reserve-keyword="false" placeholder="输入一个值后按回车" @change="changed">
        <el-option v-for="v in step.values||[]" :key="v" :label="v" :value="v"/>
       </el-select>
      </template>
      <template v-else-if="!['isNull','notNull'].includes(step.op)">
       <label>值</label><el-input v-model="step.value" @input="changed"/>
      </template>
     </div>
     <div v-else-if="step.type==='case'" class="pipeline-fields">
      <label>新列名称</label><el-input v-model="step.name" maxlength="100" @input="changed"/>
      <label>判断字段</label><el-select v-model="step.sourceFieldId" filterable @change="changed">
       <el-option v-for="f in selectable(index)" :key="f.id" :label="f.label" :value="f.id"/>
      </el-select>
      <label>判断条件</label><el-select v-model="step.op" @change="changeCaseOperator(step)">
       <el-option v-for="o in [{id:'eq',name:'等于'},{id:'ne',name:'不等于'},
        {id:'gt',name:'大于'},{id:'gte',name:'大于等于'},
        {id:'lt',name:'小于'},{id:'lte',name:'小于等于'},
        {id:'contains',name:'包含'},{id:'in',name:'属于集合'},{id:'isNull',name:'为空'},{id:'notNull',name:'不为空'}]"
        :key="o.id" :label="o.name" :value="o.id"/>
      </el-select>
      <template v-if="step.op==='in'">
       <label>匹配值（1～30 个）</label>
       <el-select v-model="step.values" multiple filterable allow-create default-first-option
        :reserve-keyword="false" placeholder="输入值后按回车" @change="changed">
        <el-option v-for="v in step.values||[]" :key="v" :label="v" :value="v"/>
       </el-select>
      </template>
      <template v-else-if="!['isNull','notNull'].includes(step.op)">
       <label>比较值</label><el-input v-model="step.value" @input="changed"/>
      </template>
      <label>结果类型</label><el-select v-model="step.outputType" @change="changed">
       <el-option label="文本" value="STRING"/><el-option label="数值" value="DECIMAL"/>
      </el-select>
      <label>条件成立时</label><el-input v-model="step.thenValue" @input="changed"/>
      <label>其他情况</label><el-input v-model="step.elseValue" @input="changed"/>
     </div>
     <div v-else class="pipeline-fields">
      <label>分组维度</label><el-select v-model="step.dimensions" multiple filterable
       placeholder="选择 0–3 个维度" @change="changed">
       <el-option v-for="f in selectable(index)" :key="f.id" :label="f.label" :value="f.id"/>
      </el-select>
      <label>汇总指标</label>
      <div v-for="(m,i) in step.measures" :key="m.id" class="pipeline-measure">
       <el-select v-model="m.fieldId" filterable @change="changed">
        <el-option label="记录数 (*)" value="*"/>
        <el-option v-for="f in selectable(index)" :key="f.id" :label="f.label" :value="f.id"/>
       </el-select>
       <el-select v-model="m.aggregate" @change="changed">
        <el-option v-for="agg in ['SUM','AVG','COUNT','MIN','MAX']" :key="agg" :label="agg" :value="agg"/>
       </el-select>
       <el-input v-model="m.name" placeholder="名称" @input="changed"/>
       <button title="移除汇总指标" :disabled="step.measures.length===1"
        @click="step.measures.splice(i,1);changed()">×</button>
      </div>
      <button :disabled="step.measures.length>=5" @click="addMeasure(step,index)">＋ 添加指标</button>
     </div>
    </section>
   </div>
   <aside class="pipeline-result">
    <div class="pipeline-result-head"><strong>预览结果 <small v-if="previewScope">· {{previewScope}}</small></strong>
     <el-input-number v-model="queryLimit" :min="1" :max="200" size="small"/>
    </div>
    <pre v-if="sqlPreview" class="pipeline-sql">{{sqlPreview}}</pre>
    <div v-else-if="previewColumns.length" class="pipeline-table-wrap">
     <table><thead><tr><th v-for="c in previewColumns" :key="c.id">{{c.label}}</th></tr></thead>
      <tbody><tr v-for="(row,i) in previewRows" :key="i">
       <td v-for="c in previewColumns" :key="c.id">{{row[c.name]??'—'}}</td>
      </tr></tbody></table>
     <small v-if="truncated">仅显示前 {{queryLimit}} 行</small>
    </div>
    <div v-else class="pipeline-empty">选择 SQL 预览或数据预览</div>
   </aside>
  </div>
 </div>
</template>
