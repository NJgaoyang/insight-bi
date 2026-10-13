<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {post,type FieldV2} from '../api'

/** Isolated formula create/validate editor. The parent owns data reload and schema state. */
const props=defineProps<{modelValue:boolean;datasetId:string;fields:FieldV2[]}>()
const emit=defineEmits<{
 (e:'update:modelValue',value:boolean):void
 (e:'created'):void
}>()
const opened=computed({get:()=>props.modelValue,set:(v:boolean)=>emit('update:modelValue',v)})
const formulaName=ref(''),formulaExpression=ref(''),formulaBusy=ref(false)
type FormulaCheck={phase:string;outputType:string;references:string[];canonicalAst:Record<string,unknown>}
const formulaValidation=ref<FormulaCheck|null>(null),formulaError=ref('')
let validationVersion=0
watch(()=>props.modelValue,open=>{
 validationVersion++
 if(open){
  formulaName.value='';formulaExpression.value=''
  formulaValidation.value=null;formulaError.value=''
 }
})
watch(formulaExpression,()=>{
 validationVersion++;formulaValidation.value=null;formulaError.value=''
})
function insertFormulaField(field:FieldV2){formulaExpression.value+='['+field.id+']'}
async function validateFormula(){
 if(!props.datasetId||!formulaExpression.value.trim()){
  formulaError.value='请选择数据集并填写公式';return
 }
 const version=++validationVersion
 formulaBusy.value=true;formulaError.value='';formulaValidation.value=null
 try{
  const result=await post<FormulaCheck>(
   '/datasets/'+encodeURIComponent(props.datasetId)+'/calculated-fields/validate',
   {expression:formulaExpression.value.trim()})
  if(version===validationVersion&&opened.value)formulaValidation.value=result
 }catch(e:unknown){
  if(version===validationVersion&&opened.value)
   formulaError.value=e instanceof Error?e.message:'公式校验失败'
 }finally{formulaBusy.value=false}
}
async function saveFormula(){
 if(!props.datasetId||!formulaName.value.trim()||!formulaExpression.value.trim()){
  ElMessage.warning('请输入名称和公式');return
 }
 formulaBusy.value=true;formulaError.value=''
 try{
  await post('/datasets/'+encodeURIComponent(props.datasetId)+'/calculated-fields',
   {name:formulaName.value.trim(),expression:formulaExpression.value.trim()})
  emit('created')
  opened.value=false
  ElMessage.success('计算字段已保存')
 }catch(e:unknown){
  formulaError.value=e instanceof Error?e.message:'公式保存失败'
  ElMessage.error(formulaError.value)
 }finally{formulaBusy.value=false}
}
</script>

<template>
 <el-dialog v-model="opened" title="新增计算字段" width="540px" append-to-body>
  <div class="v2-formula-dialog">
   <label>字段名称</label><el-input v-model="formulaName" maxlength="100" placeholder="例如：利润率"/>
   <label>公式表达式</label>
   <el-input v-model="formulaExpression" type="textarea" :rows="4"
    placeholder="例如：SUM([字段]) / NULLIF(SUM([字段]),0)"/>
   <div class="v2-formula-hint">支持 CASE WHEN … THEN … ELSE … END、IF、SUM、AVG、COUNT、MIN、MAX、NULLIF、COALESCE、ROUND；点击字段可插入引用。</div>
   <div v-if="formulaError" class="v2-formula-error" role="alert">{{formulaError}}</div>
   <div v-if="formulaValidation" class="v2-formula-validation" role="status">
    校验通过 · {{formulaValidation.phase==='AGGREGATE'?'聚合计算':'行级计算'}} · {{formulaValidation.outputType}}
    · 引用 {{formulaValidation.references.length}} 个字段
   </div>
   <div class="v2-formula-fields"><button v-for="f in fields" :key="f.id" @click="insertFormulaField(f)">{{f.name}}</button></div>
  </div>
  <template #footer>
   <button class="subtle-button" @click="opened=false">取消</button>
   <button class="subtle-button" :disabled="formulaBusy||!formulaExpression.trim()" @click="validateFormula">校验公式</button>
   <button class="primary-button" :disabled="formulaBusy" @click="saveFormula">保存计算字段</button>
  </template>
 </el-dialog>
</template>
