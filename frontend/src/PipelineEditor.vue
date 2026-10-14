<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,put,type FieldV2} from './api'

const props=defineProps<{id:string}>()
const emit=defineEmits<{(e:'back'):void}>()

type FormulaStep={id:string;type:'formula';fieldId:string;name:string;expression:string}
type FilterStep={id:string;type:'filter';fieldId:string;op:string;value?:string;values?:string[]}
type Measure={id:string;fieldId:string;aggregate:'SUM'|'AVG'|'COUNT'|'MIN'|'MAX';name:string}
type GroupStep={id:string;type:'group';dimensions:string[];measures:Measure[]}
type CaseStep={id:string;type:'case';fieldId:string;sourceFieldId:string;name:string;
 op:string;value?:string;outputType:'STRING'|'DECIMAL';thenValue:string;elseValue:string}
type UnionMapping={leftFieldId:string;rightFieldId:string}
type UnionStep={id:string;type:'union';sourceDatasetId:string;mappings?:UnionMapping[]}
type JoinKey={leftFieldId:string;rightFieldId:string}
type JoinOutput={sourceFieldId:string;fieldId:string;name:string}
type JoinStep={id:string;type:'join';sourceDatasetId:string;joinType:'INNER'|'LEFT';
 conditions:JoinKey[];fields:JoinOutput[];acknowledgeFanout:boolean}
type Step=FormulaStep|FilterStep|GroupStep|CaseStep|UnionStep|JoinStep
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
const queryLimit=ref(30)
const sqlPreview=ref('')
const joinProfile=ref<{duplicateGroups:number;extraRightRows:number;hasDuplicateKeys:boolean}|null>(null)
const profiling=ref(false)
const previewRows=ref<Record<string,unknown>[]>([])
const previewColumns=ref<Column[]>([])
const truncated=ref(false)
let savedSteps='[]'

const selectable=(index:number):Column[]=>{
 let fields:Column[]=baseFields.value.filter(f=>!f.calculated||f.phase==='ROW')
  .map(f=>({id:f.id,name:f.sourceColumn||f.id,label:f.name,type:f.logicalType,numeric:f.numeric}))
 for(const step of steps.value.slice(0,index)){
  if(step.type==='formula'){
   fields=[...fields,{id:step.fieldId,name:'step_'+step.fieldId,label:step.name,type:'DECIMAL',numeric:true}]
  }
  if(step.type==='case'){
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
function changed(){
 joinProfile.value=null
 dirty.value=JSON.stringify(steps.value)!==savedSteps
 sqlPreview.value='';previewRows.value=[];previewColumns.value=[]
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
 changed()
}
function addJoinOutput(step:JoinStep){
 if(step.fields.length>=30)return
 step.fields.push(joinOutputFor(step.sourceDatasetId,step.conditions));changed()
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
  savedSteps=JSON.stringify(steps.value);dirty.value=false
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'读取步骤失败')}
 finally{loading.value=false}
}
async function checkJoinKeys(step:JoinStep){
 profiling.value=true;joinProfile.value=null
 try{
  joinProfile.value=await post<{duplicateGroups:number;extraRightRows:number;hasDuplicateKeys:boolean}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/join-key-profile',{step})
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'关联键检查失败')}
 finally{profiling.value=false}
}
async function compile(){
 busy.value=true
 try{
  const result=await post<{sqlPreview:string;columns:Column[]}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/compile',request())
  sqlPreview.value=result.sqlPreview;previewColumns.value=result.columns
  ElMessage.success('步骤编译通过')
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'步骤编译失败')}
 finally{busy.value=false}
}
async function preview(){
 busy.value=true
 try{
  const result=await post<{rows:Record<string,unknown>[];columns:Column[];truncated:boolean}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline/preview',request())
  previewRows.value=result.rows;previewColumns.value=result.columns
  truncated.value=result.truncated;sqlPreview.value=''
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'真实数据预览失败')}
 finally{busy.value=false}
}
async function save(){
 busy.value=true
 try{
  const result=await put<{revision:number;stale:boolean}>(
   '/datasets/'+encodeURIComponent(props.id)+'/pipeline',{
    steps:steps.value,revision:revision.value})
  revision.value=result.revision;stale.value=result.stale
  savedSteps=JSON.stringify(steps.value);dirty.value=false
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
    <button :disabled="busy||loading" @click="compile">SQL 预览</button>
    <button :disabled="busy||loading" @click="preview">数据预览</button>
    <button class="pipeline-save" :disabled="busy||loading||!dirty" @click="save">保存步骤</button>
   </div>
  </header>
  <div v-if="loading" class="pipeline-empty">正在读取加工配置…</div>
  <div v-else class="pipeline-content">
   <div class="pipeline-steps">
    <div class="pipeline-toolbar">
     <strong>加工步骤</strong><small>按顺序执行 · 最多 12 步</small>
     <div>
      <button @click="addFormula">＋ 计算列</button>
      <button @click="addFilter">＋ 过滤</button>
      <button @click="addCase">＋ 条件列</button>
      <button :disabled="steps.some(x=>x.type==='union'||x.type==='join')||!unionCandidates.length"
       @click="addUnion">＋ 上下合并</button>
      <button :disabled="steps.some(x=>x.type==='union'||x.type==='join')||!unionCandidates.length"
       @click="addJoin">＋ 关联</button>
      <button :disabled="isGroupLimit" @click="addGroup">＋ 分组汇总</button>
     </div>
    </div>
    <div v-if="!steps.length" class="pipeline-empty">暂无加工步骤，可从上方添加</div>
    <section v-for="(step,index) in steps" :key="step.id" class="pipeline-step">
     <header class="pipeline-step-header">
      <strong>{{index+1}}. {{step.type==='formula'?'计算列':step.type==='filter'?'数据过滤':step.type==='case'?'条件列':step.type==='union'?'上下合并':step.type==='join'?'数据关联':'分组汇总'}}</strong>
      <button title="步骤前移" :disabled="index===0||(index===1&&['union','join'].includes(steps[0]?.type||''))" @click="move(index,-1)">↑</button>
      <button title="步骤后移" :disabled="index===steps.length-1||step.type==='union'||step.type==='join'" @click="move(index,1)">↓</button>
      <button title="删除步骤" @click="removeStep(index)">删除</button>
     </header>
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
      <small>仅允许同一数据连接；映射需覆盖全部字段且类型一致。UNION ALL 保留重复记录。</small>
     </div>
     <div v-else-if="step.type==='join'" class="pipeline-fields">
      <label>关联数据集（同一数据连接）</label>
      <el-select :model-value="step.sourceDatasetId" filterable
       @update:model-value="v=>changeJoinSource(step,String(v))">
       <el-option v-for="source in unionCandidates" :key="source.id"
        :label="source.name" :value="source.id"/>
      </el-select>
      <label>关联方式</label>
      <el-select v-model="step.joinType" @change="changed">
       <el-option label="LEFT JOIN（保留左表全部记录）" value="LEFT"/>
       <el-option label="INNER JOIN（只保留匹配记录）" value="INNER"/>
      </el-select>
      <label>关联条件（等值匹配，最多 4 组）</label>
      <div v-for="(condition,ci) in step.conditions" :key="ci" class="pipeline-measure">
       <el-select v-model="condition.leftFieldId" filterable @change="changed">
        <el-option v-for="field in baseColumns()" :key="field.id" :label="field.label"
         :value="field.id"/>
       </el-select>
       <span>＝</span>
       <el-select v-model="condition.rightFieldId" filterable @change="changed">
        <el-option v-for="field in rightFields(step.sourceDatasetId)" :key="field.id"
         :label="field.label" :value="field.id"/>
       </el-select>
       <button :disabled="step.conditions.length===1"
        title="删除关联条件" @click="step.conditions.splice(ci,1);changed()">×</button>
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
      <small v-if="joinProfile">
       {{joinProfile.hasDuplicateKeys
        ?'发现 '+joinProfile.duplicateGroups+' 组重复键，右表多出 '+joinProfile.extraRightRows+' 行重复匹配候选'
        :'右表未发现重复关联键'}}
       （仅统计右表，不代表 JOIN 最终行数）
      </small>
      <el-checkbox v-model="step.acknowledgeFanout" @change="changed">
       我已了解：右表关联键重复可能放大结果行数
      </el-checkbox>
      <small>必须确认行数放大风险后才能保存；不自动去重，也不改动原始数据。</small>
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
      <label>判断条件</label><el-select v-model="step.op" @change="changed">
       <el-option v-for="o in [{id:'eq',name:'等于'},{id:'ne',name:'不等于'},
        {id:'gt',name:'大于'},{id:'gte',name:'大于等于'},
        {id:'lt',name:'小于'},{id:'lte',name:'小于等于'},
        {id:'contains',name:'包含'},{id:'isNull',name:'为空'},{id:'notNull',name:'不为空'}]"
        :key="o.id" :label="o.name" :value="o.id"/>
      </el-select>
      <template v-if="!['isNull','notNull'].includes(step.op)">
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
    <div class="pipeline-result-head"><strong>预览结果</strong>
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
