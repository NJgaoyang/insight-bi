<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,put,remove} from './api'

type Dataset={id:string;name:string;connection_id:string}
type Field={id:string;name:string;logicalType:string;calculated:boolean;hidden?:boolean}
type Pair={leftFieldId:string;rightFieldId:string}
type Draft={id:string;name:string;sourceDatasetId:string;targetDatasetId:string;
 pairs:Pair[];revision:number;status:'DRAFT'|'INVALID'|'APPROVED';reason:string}
const drafts=ref<Draft[]>([])
const datasets=ref<Dataset[]>([])
const source=ref(''),target=ref(''),name=ref('')
const editingId=ref(''),revision=ref(0),pairs=ref<Pair[]>([{leftFieldId:'',rightFieldId:''}])
const fields=ref<Record<string,Field[]>>({})
const busy=ref(false),error=ref(''),formOpen=ref(false)
type SampleProfile={sampledRows:number;nullKeyRows:number;keyGroups:number;
 duplicateGroups:number;extraRightRows:number;maxRightMultiplicity:number;
 hasDuplicateKeys:boolean;sampleCapReached:boolean;sampleLimit:number;
 exactSourceCount:boolean;exactJoinRowCount:boolean;scope:string;warning:string}
const profiling=ref(false),profile=ref<{id:string;revision:number;data:SampleProfile}|null>(null)
type Reader={id:string;display_name:string;role:string;enabled:boolean}
const reviewOpen=ref(false),reviewRow=ref<Draft|null>(null),readers=ref<Reader[]>([])
const reviewReader=ref(''),reviewJoin=ref<'INNER'|'LEFT'>('LEFT')
const reviewBusy=ref(false),reviewMessage=ref('')
const targets=computed(()=>datasets.value.filter(d=>d.id!==source.value&&
 !!source.value&&d.connection_id===datasets.value.find(x=>x.id===source.value)?.connection_id))
const left=computed(()=>fields.value[source.value]?.filter(f=>!f.calculated&&!f.hidden)||[])
const right=computed(()=>fields.value[target.value]?.filter(f=>!f.calculated&&!f.hidden)||[])
const normalized=(s:string)=>s.trim()
async function load(){
 busy.value=true;error.value='';profile.value=null
 try{
  const [list,sources]=await Promise.all([
   api<Draft[]>('/model/semantic-relations'),api<Dataset[]>('/datasets')])
  drafts.value=list;datasets.value=sources
 }catch(e:unknown){error.value=e instanceof Error?e.message:'关系草稿读取失败'}
 finally{busy.value=false}
}
async function loadFields(id:string){
 if(!id||fields.value[id])return
 try{fields.value={...fields.value,[id]:await api<Field[]>('/datasets/'+encodeURIComponent(id)+'/fields')}}
 catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'字段目录读取失败')}
}
async function chooseSource(value:string){
 // Resolve physical field metadata before updating the selected source so an
 // in-flight field refresh cannot close the adjacent target dropdown.
 await loadFields(value)
 source.value=value;target.value='';pairs.value=[{leftFieldId:'',rightFieldId:''}]
}
async function chooseTarget(value:string){
 await loadFields(value)
 target.value=value;pairs.value=[{leftFieldId:'',rightFieldId:''}]
}
function clear(){
 editingId.value='';revision.value=0;source.value='';target.value='';name.value=''
 pairs.value=[{leftFieldId:'',rightFieldId:''}]
}
function newDraft(){clear();formOpen.value=true}
async function edit(row:Draft){
 editingId.value=row.id;revision.value=row.revision;name.value=row.name
 source.value=row.sourceDatasetId;target.value=row.targetDatasetId
 pairs.value=row.pairs.length?row.pairs.map(p=>({...p})):[{leftFieldId:'',rightFieldId:''}]
 formOpen.value=true
 await Promise.all([loadFields(source.value),loadFields(target.value)])
}
const valid=computed(()=>!!normalized(name.value)&&!!source.value&&!!target.value&&
 pairs.value.length>=1&&pairs.value.length<=3&&pairs.value.every(p=>{
  const l=left.value.find(x=>x.id===p.leftFieldId)
  const r=right.value.find(x=>x.id===p.rightFieldId)
  return !!l&&!!r&&l.logicalType===r.logicalType&&l.logicalType!=='UNKNOWN'
 })&&new Set(pairs.value.map(p=>p.leftFieldId)).size===pairs.value.length&&
 new Set(pairs.value.map(p=>p.rightFieldId)).size===pairs.value.length)
async function save(){
 if(!valid.value||busy.value)return
 busy.value=true
 try{
  const body={name:normalized(name.value),sourceDatasetId:source.value,
   targetDatasetId:target.value,pairs:pairs.value.map(p=>({...p})),...(editingId.value?{revision:revision.value}:{})}
  if(editingId.value)await put('/model/semantic-relations/'+encodeURIComponent(editingId.value),body)
  else await post('/model/semantic-relations',body)
  ElMessage.success('关系草稿已保存（暂不参与查询）')
  formOpen.value=false;clear();await load()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'保存草稿失败');busy.value=false}
}
async function approve(row:Draft){
 if(busy.value||row.status!=='DRAFT')return
 try{
  await ElMessageBox.confirm(
   '确认当前 v'+row.revision+' 字段关系定义？仅标记元数据版本，不会启用 JOIN 或发布查询。',
   '确认语义关系版本',{type:'warning',confirmButtonText:'确认当前版本',cancelButtonText:'取消'})
 }catch{return}
 busy.value=true
 try{
  await post('/model/semantic-relations/'+encodeURIComponent(row.id)+'/approve',{revision:row.revision})
  ElMessage.success('关系版本已确认；暂不参与查询')
  await load()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'关系确认失败');busy.value=false}
}
async function inspectKeys(row:Draft){
 if(busy.value||profiling.value||row.status!=='APPROVED')return
 try{await ElMessageBox.confirm(
  '会对右侧数据集执行最多 2000 行的只读样本扫描；复杂源 SQL 仍可能进行较大扫描。结果不保证全表唯一性，也不是 JOIN 最终行数。继续？',
  '检查关联风险',{type:'warning',confirmButtonText:'同意只读抽样',cancelButtonText:'取消'})}
 catch{return}
 profiling.value=true;profile.value=null
 try{
  const data=await post<SampleProfile>('/model/semantic-relations/'+encodeURIComponent(row.id)+
   '/join-key-profile',{revision:row.revision,acknowledgeSourceScan:true})
  if(drafts.value.some(d=>d.id===row.id&&d.revision===row.revision&&d.status==='APPROVED'))
   profile.value={id:row.id,revision:row.revision,data}
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'关联风险诊断失败')}
 finally{profiling.value=false}
}
async function openAccessReview(row:Draft){
 if(busy.value||row.status!=='APPROVED')return
 reviewRow.value=row;reviewReader.value='';reviewJoin.value='LEFT';reviewMessage.value=''
 reviewOpen.value=true
 try{
  const users=await api<Reader[]>('/governance/users')
  readers.value=users.filter(u=>u.enabled&&['VIEWER','EDITOR'].includes(u.role))
 }catch(e:unknown){reviewMessage.value=e instanceof Error?e.message:'无法加载读者账号'}
}
async function submitAccessReview(){
 const row=reviewRow.value
 if(!row||!reviewReader.value||reviewBusy.value||row.status!=='APPROVED')return
 reviewBusy.value=true;reviewMessage.value=''
 try{
  const response=await post<{status:string;reason:string}>(
   '/model/semantic-relations/'+encodeURIComponent(row.id)+'/access-review',{
    revision:row.revision,readerId:reviewReader.value,joinType:reviewJoin.value,
    leftFieldIds:[...new Set(row.pairs.map(p=>p.leftFieldId))],
    rightFieldIds:[...new Set(row.pairs.map(p=>p.rightFieldId))]})
  reviewMessage.value=response.status==='READY_FOR_REVIEW'?
    '已记录预发布审查；没有授予读者访问权，也没有发布模型。':
    response.reason
 }catch(e:unknown){reviewMessage.value=e instanceof Error?e.message:'权限审查失败'}
 finally{reviewBusy.value=false}
}
async function checkAccessReview(){
 const row=reviewRow.value
 if(!row||!reviewReader.value||reviewBusy.value)return
 reviewBusy.value=true
 try{
  const report=await api<{status:string;reason:string}>(
   '/model/semantic-relations/'+encodeURIComponent(row.id)+
   '/access-review?readerId='+encodeURIComponent(reviewReader.value))
  reviewMessage.value=report.status+' · '+report.reason
 }catch(e:unknown){reviewMessage.value=e instanceof Error?e.message:'审查状态查询失败'}
 finally{reviewBusy.value=false}
}
async function erase(row:Draft){
 try{await ElMessageBox.confirm('删除关系草稿「'+row.name+'」？不会修改原有加工链。','删除草稿',{type:'warning'})}
 catch{return}
 busy.value=true
 try{
  await remove('/model/semantic-relations/'+encodeURIComponent(row.id)+'?revision='+row.revision)
  ElMessage.success('关系草稿已删除');await load()
 }catch(e:unknown){ElMessage.error(e instanceof Error?e.message:'删除失败');busy.value=false}
}
onMounted(load)
</script>
<template>
 <section class="semantic-draft">
  <div class="semantic-draft-heading">
   <div><strong>语义关系草稿</strong><p>同连接、稳定字段映射；仅保存元数据，不执行 JOIN。</p></div>
   <button type="button" @click="newDraft">＋ 新建关系</button>
  </div>
  <p v-if="error" role="alert">{{error}}</p>
  <div v-if="drafts.length" class="semantic-draft-list">
   <div v-for="row in drafts" :key="row.id" class="semantic-draft-item">
    <div><strong>{{row.name}}</strong>
     <small>{{datasets.find(d=>d.id===row.sourceDatasetId)?.name||'源数据集已删除'}}
      → {{datasets.find(d=>d.id===row.targetDatasetId)?.name||'目标数据集已删除'}}
      · {{row.pairs.length}} 组字段 · v{{row.revision}}</small>
     <span v-if="row.status==='INVALID'" class="semantic-draft-error">{{row.reason}}</span>
     <span v-else-if="row.status==='APPROVED'" class="semantic-draft-approved">已确认 v{{row.revision}} · 未用于查询</span>
     <span v-else class="semantic-draft-state">草稿 · 待确认<span v-if="row.reason"> · {{row.reason}}</span></span>
    </div>
    <div class="semantic-draft-actions">
     <button v-if="row.status==='DRAFT'" type="button" @click="approve(row)">确认版本</button>
     <button v-if="row.status==='APPROVED'" type="button" :disabled="busy" @click="openAccessReview(row)">预发布访问审查</button>
     <button v-if="row.status==='APPROVED'" type="button" :disabled="profiling||busy" @click="inspectKeys(row)">
      {{profiling?'检查中…':'检查关联风险'}}
     </button>
     <button type="button" @click="edit(row)">编辑</button>
     <button type="button" @click="erase(row)">删除</button>
    </div>
    <div v-if="profile&&profile.id===row.id&&profile.revision===row.revision" class="semantic-draft-profile" role="status">
     <strong>右表样本行数：{{profile.data.sampledRows}} / {{profile.data.sampleLimit}}</strong>
     <span>空值关联键：{{profile.data.nullKeyRows}} · 有效键组：{{profile.data.keyGroups}} · 重复键组：{{profile.data.duplicateGroups}}</span>
     <span>额外右表行：{{profile.data.extraRightRows}} · 最大单键行数：{{profile.data.maxRightMultiplicity}}</span>
     <span v-if="profile.data.sampleCapReached">已达到样本上限，不能推断全表完整性。</span>
     <span>{{profile.data.warning}}</span>
    </div>
   </div>
  </div>
  <p v-else-if="!busy" class="semantic-draft-empty">尚未创建语义关系草稿。</p>
  <el-dialog v-model="reviewOpen" title="语义关系 · 预发布访问审查" width="min(520px,95%)" append-to-body>
   <div class="semantic-access-review">
    <p>仅审查当前已确认关系的关联键输出。此步骤不是报表发布、SQL 查询，也不会赋予读者权限。</p>
    <label>选择已启用读者
     <el-select v-model="reviewReader" placeholder="选择需要检查的账号" filterable>
      <el-option v-for="u in readers" :key="u.id" :value="u.id"
       :label="u.display_name+' · '+u.role"/>
     </el-select>
    </label>
    <label>预期关联模式
     <el-select v-model="reviewJoin">
      <el-option label="左连接 LEFT" value="LEFT"/>
      <el-option label="内连接 INNER" value="INNER"/>
     </el-select>
    </label>
    <p v-if="reviewMessage" role="status">{{reviewMessage}}</p>
    <p>若此读者对任何一侧数据集存在列拒绝或行过滤规则，审查必定拒绝。即使审查通过，仍需单独实现安全发布流程。</p>
   </div>
   <template #footer>
    <button type="button" @click="reviewOpen=false">关闭</button>
    <button type="button" :disabled="!reviewReader||reviewBusy" @click="checkAccessReview">检查已有状态</button>
    <button type="button" :disabled="!reviewReader||reviewBusy" @click="submitAccessReview">
     {{reviewBusy?'检查中…':'生成审查记录'}}
    </button>
   </template>
  </el-dialog>
  <el-dialog v-model="formOpen" :title="editingId?'编辑语义关系草稿':'新建语义关系草稿'"
   width="min(700px, 95%)" append-to-body>
   <div class="semantic-draft-form">
    <label>关系名称<el-input v-model="name" maxlength="100" placeholder="例如：订单与城市信息"/></label>
    <div class="semantic-draft-sources">
     <label>主数据集
      <el-select :model-value="source" :disabled="!!editingId" filterable placeholder="选择主数据集" @update:model-value="v=>chooseSource(String(v))">
       <el-option v-for="d in datasets" :key="d.id" :value="d.id" :label="d.name"/>
      </el-select>
     </label>
     <label>关联数据集
      <el-select :model-value="target" :disabled="!!editingId||!source" filterable placeholder="同一连接下的数据集"
       @update:model-value="v=>chooseTarget(String(v))">
       <el-option v-for="d in targets" :key="d.id" :value="d.id" :label="d.name"/>
      </el-select>
     </label>
    </div>
    <label>关联字段（同类型，最多三组）</label>
    <div v-for="(pair,index) in pairs" :key="index" class="semantic-draft-pair">
     <el-select v-model="pair.leftFieldId" filterable placeholder="主表字段">
      <el-option v-for="f in left" :key="f.id" :label="f.name+' · '+f.logicalType" :value="f.id"/>
     </el-select>
     <span>↔</span>
     <el-select v-model="pair.rightFieldId" filterable placeholder="关联表字段">
      <el-option v-for="f in right" :key="f.id" :label="f.name+' · '+f.logicalType" :value="f.id"/>
     </el-select>
     <button type="button" :disabled="pairs.length===1" @click="pairs.splice(index,1)">移除</button>
    </div>
    <button type="button" :disabled="pairs.length>=3||!target" @click="pairs.push({leftFieldId:'',rightFieldId:''})">
     ＋ 添加字段对
    </button>
    <p class="semantic-draft-notice">关系仅作为草稿保存；不会自动改变仪表板、数据集 SQL 或当前加工链。</p>
   </div>
   <template #footer>
    <button type="button" @click="formOpen=false">取消</button>
    <button type="button" class="semantic-draft-primary" :disabled="!valid||busy" @click="save">
     {{busy?'保存中…':'保存草稿'}}
    </button>
   </template>
  </el-dialog>
 </section>
</template>
