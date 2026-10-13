<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import type {AnalysisV2Bindings,FieldV2,FilterV2,MeasureV2} from '../api'

const props=defineProps<{bindings:AnalysisV2Bindings;fields:FieldV2[]}>()
const emit=defineEmits<{(e:'change'):void}>()
type Stage='pre'|'post'
const preField=ref(''),postMetric=ref(''),preOperator=ref('eq'),postOperator=ref('gte')
const preValue=ref(''),postValue=ref('')
const operators=[
 {value:'eq',label:'等于'},{value:'ne',label:'不等于'},
 {value:'gt',label:'大于'},{value:'gte',label:'大于等于'},
 {value:'lt',label:'小于'},{value:'lte',label:'小于等于'},
 {value:'contains',label:'包含'},{value:'in',label:'包含任一（IN）'},
 {value:'isNull',label:'为空'},{value:'notNull',label:'不为空'}
]
const noValue=new Set(['isNull','notNull'])
const preFields=computed(()=>props.fields.filter(f=>(!f.calculated||f.phase==='ROW')&&!f.hidden))
const preSelected=computed(()=>preFields.value.find(f=>f.id===preField.value))
const postSelected=computed(()=>props.bindings.measures.find(m=>m.id===postMetric.value))
function typeOfMeasure(m:MeasureV2|undefined):string {
 if(!m)return 'DECIMAL'
 const field=props.fields.find(f=>f.id===m.fieldId)
 if(m.aggregate==='COUNT')return 'INTEGER'
 if(field?.calculated&&field.phase==='AGGREGATE')return field.logicalType
 if(m.aggregate==='MIN'||m.aggregate==='MAX')return field?.logicalType||'UNKNOWN'
 return 'DECIMAL'
}
function allowed(type:string):string[]{
 const nulls=['isNull','notNull']
 if(type==='STRING')return ['eq','ne','contains','in',...nulls]
 if(type==='BOOLEAN')return ['eq','ne',...nulls]
 if(type==='DATE'||type==='DATETIME')return ['eq','ne','gt','gte','lt','lte','in',...nulls]
 if(['INTEGER','DOUBLE','DECIMAL'].includes(type))return ['eq','ne','gt','gte','lt','lte','in',...nulls]
 return ['eq','ne',...nulls]
}
const preOptions=computed(()=>operators.filter(x=>allowed(preSelected.value?.logicalType||'UNKNOWN').includes(x.value)))
const postOptions=computed(()=>operators.filter(x=>allowed(typeOfMeasure(postSelected.value)).includes(x.value)))
watch(preField,()=>{if(!preOptions.value.some(x=>x.value===preOperator.value))preOperator.value='eq'})
watch(postMetric,()=>{if(!postOptions.value.some(x=>x.value===postOperator.value))postOperator.value='eq'})
watch(()=>props.bindings,()=>{
 preField.value='';postMetric.value='';preValue.value='';postValue.value=''
 preOperator.value='eq';postOperator.value='gte'
})
function add(stage:Stage){
 const fieldId=stage==='pre'?preField.value:postMetric.value
 const operator=stage==='pre'?preOperator.value:postOperator.value
 const raw=(stage==='pre'?preValue.value:postValue.value).trim()
 const options=stage==='pre'?preOptions.value:postOptions.value
 if(!fieldId||!options.some(x=>x.value===operator)){
  ElMessage.warning('请选择有效字段和筛选方式');return
 }
 const filter:FilterV2={op:operator}
 if(stage==='pre')filter.fieldId=fieldId
 else filter.ref=fieldId
 if(!noValue.has(operator)){
  if(!raw){ElMessage.warning('请输入筛选值');return}
  if(operator==='in'){
   const values=raw.split(',').map(x=>x.trim())
   if(values.length>50||values.some(x=>!x)){ElMessage.warning('IN 需要 1 到 50 个非空值，以英文逗号分隔');return}
   filter.values=values
  }else filter.value=raw
 }
 props.bindings.filters[stage].push(filter)
 emit('change')
 if(stage==='pre')preValue.value=''
 else postValue.value=''
}
function remove(stage:Stage,index:number){
 props.bindings.filters[stage].splice(index,1)
 emit('change')
}
function caption(item:FilterV2,stage:Stage){
 const id=stage==='pre'?item.fieldId:item.ref
 const label=stage==='pre'?props.fields.find(f=>f.id===id)?.name:
   props.bindings.measures.find(m=>m.id===id)?.alias
 const operator=operators.find(x=>x.value===item.op)?.label||item.op
 if(noValue.has(item.op))return (label||id||'已删除字段')+' · '+operator
 const value=item.op==='in'?(item.values||[]).join('、'):String(item.value??'')
 return (label||id||'已删除字段')+' · '+operator+' · '+value
}
</script>

<template>
 <div class="v2-filter-editor">
  <label>明细前过滤（WHERE）</label>
  <el-select v-model="preField" filterable clearable placeholder="选择明细字段" size="small">
   <el-option v-for="field in preFields" :key="field.id" :label="field.name" :value="field.id"/>
  </el-select>
  <el-select v-model="preOperator" :disabled="!preField" size="small" aria-label="明细过滤方式">
   <el-option v-for="item in preOptions" :key="item.value" :value="item.value" :label="item.label"/>
  </el-select>
  <el-input v-if="!noValue.has(preOperator)" v-model="preValue" size="small"
   :placeholder="preOperator==='in'?'英文逗号分隔多个值':'输入过滤值'"
   :maxlength="500" :disabled="!preField"/>
  <button class="analysis-formula-add" @click="add('pre')">添加明细过滤</button>
  <div v-for="(item,index) in bindings.filters.pre" :key="'pre'+index" class="v2-applied-filter">
   <span>{{caption(item,'pre')}}</span>
   <button :aria-label="'移除明细过滤 '+(index+1)" @click="remove('pre',index)">×</button>
  </div>

  <label>聚合后过滤（HAVING）</label>
  <el-select v-model="postMetric" clearable placeholder="选择结果指标" size="small">
   <el-option v-for="metric in bindings.measures" :key="metric.id" :label="metric.alias" :value="metric.id"/>
  </el-select>
  <el-select v-model="postOperator" :disabled="!postMetric" size="small" aria-label="结果过滤方式">
   <el-option v-for="item in postOptions" :key="item.value" :value="item.value" :label="item.label"/>
  </el-select>
  <el-input v-if="!noValue.has(postOperator)" v-model="postValue" size="small"
   :placeholder="postOperator==='in'?'英文逗号分隔多个值':'输入过滤值'"
   :maxlength="500" :disabled="!postMetric"/>
  <button class="analysis-formula-add" @click="add('post')">添加结果过滤</button>
  <div v-for="(item,index) in bindings.filters.post" :key="'post'+index" class="v2-applied-filter">
   <span>{{caption(item,'post')}}</span>
   <button :aria-label="'移除结果过滤 '+(index+1)" @click="remove('post',index)">×</button>
  </div>
 </div>
</template>
