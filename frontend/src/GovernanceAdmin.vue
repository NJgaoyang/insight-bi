<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,put,remove} from './api'

const props=defineProps<{dashboards:{id:string;name:string;status:string}[]}>()
type User={id:string;username:string;display_name:string;role:'VIEWER'|'EDITOR';enabled:boolean;department_id?:string|null}
type Department={id:string;name:string;parent_id:string|null}
type Grant={dashboard_id:string;permission:string}
const departments=ref<Department[]>([])
const departmentDialog=ref(false)
const departmentDraft=ref<{id:string;name:string;parentId:string}>({id:'',name:'',parentId:''})
const departmentRows=computed(()=>{
 const rows:(Department&{depth:number})[]=[],seen=new Set<string>()
 function visit(parent:string,depth:number){
  for(const d of departments.value.filter(item=>(item.parent_id??'')===parent)){
   if(seen.has(d.id))continue
   seen.add(d.id);rows.push({...d,depth})
   if(depth<10)visit(d.id,depth+1)
  }
 }
 visit('',0)
 return rows
})
const users=ref<User[]>([])
const busy=ref(false),createOpen=ref(false),grantsOpen=ref(false)
const newUser=ref({username:'',displayName:'',role:'VIEWER' as 'VIEWER'|'EDITOR'})
const selected=ref<User|null>(null),grants=ref<Grant[]>([]),newReport=ref('')
const published=()=>props.dashboards.filter(d=>d.status==='published')
async function refresh(){
 try{
  const [people,org]=await Promise.all([api<User[]>('/governance/users'),
   api<Department[]>('/governance/departments')])
  users.value=people;departments.value=org
 }
 catch(e){ElMessage.error(e instanceof Error?e.message:'用户列表加载失败')}
}
function openDepartment(department?:Department){
 departmentDraft.value={id:department?.id||'',name:department?.name||'',parentId:department?.parent_id||''}
 departmentDialog.value=true
}
async function saveDepartment(){
 const data=departmentDraft.value
 if(!data.name.trim()){ElMessage.warning('请输入部门名称');return}
 busy.value=true
 try{
  if(data.id)await put('/governance/departments/'+encodeURIComponent(data.id),
    {name:data.name.trim(),parentId:data.parentId||null})
  else await post('/governance/departments',{name:data.name.trim(),parentId:data.parentId||null})
  departmentDialog.value=false;await refresh()
 }catch(e){ElMessage.error(e instanceof Error?e.message:'部门保存失败')}
 finally{busy.value=false}
}
async function deleteDepartment(department:Department){
 try{
  await ElMessageBox.confirm('删除空部门「'+department.name+'」？包含子部门或用户时不可删除。',
    '删除部门',{type:'warning'})
  await remove('/governance/departments/'+encodeURIComponent(department.id))
  await refresh()
 }catch(e){if(e!=='cancel')ElMessage.error(e instanceof Error?e.message:'删除失败')}
}
async function changeDepartment(user:User,id:string){
 try{
  await put('/governance/users/'+encodeURIComponent(user.id)+'/department',
    {departmentId:id||null})
  await refresh()
 }catch(e){ElMessage.error(e instanceof Error?e.message:'调整用户归属失败');await refresh()}
}
function addUser(){
 newUser.value={username:'',displayName:'',role:'VIEWER'}
 createOpen.value=true
}
async function saveUser(){
 busy.value=true
 try{
  await post('/governance/users',newUser.value)
  createOpen.value=false;await refresh();ElMessage.success('账号已建立，当前不可登录')
 }catch(e){ElMessage.error(e instanceof Error?e.message:'创建失败')}
 finally{busy.value=false}
}
async function changeRole(u:User,role:'VIEWER'|'EDITOR'){
 try{
  await put('/governance/users/'+encodeURIComponent(u.id)+'/role',{role})
  await refresh()
 }catch(e){ElMessage.error(e instanceof Error?e.message:'修改角色失败');await refresh()}
}
async function deleteUser(u:User){
 try{
  await ElMessageBox.confirm('删除「'+u.username+'」及全部预配置的报表授权？','删除账号',{type:'warning'})
  await remove('/governance/users/'+encodeURIComponent(u.id))
  await refresh();ElMessage.success('已删除')
 }catch(e){if(e!=='cancel')ElMessage.error(e instanceof Error?e.message:'删除失败')}
}
async function openGrants(u:User){
 selected.value=u;newReport.value='';grantsOpen.value=true
 await refreshGrants()
}
async function refreshGrants(){
 if(!selected.value)return
 try{grants.value=await api<Grant[]>('/governance/users/'+encodeURIComponent(selected.value.id)+'/grants')}
 catch(e){ElMessage.error(e instanceof Error?e.message:'读取授权失败')}
}
async function grant(){
 if(!selected.value||!newReport.value)return
 busy.value=true
 try{
  await post('/governance/users/'+encodeURIComponent(selected.value.id)+'/grants',{dashboardId:newReport.value})
  newReport.value='';await refreshGrants();ElMessage.success('预配置授权已保存')
 }catch(e){ElMessage.error(e instanceof Error?e.message:'授权失败')}
 finally{busy.value=false}
}
async function revoke(dashboardId:string){
 if(!selected.value)return
 try{
  await remove('/governance/users/'+encodeURIComponent(selected.value.id)+'/grants/'+encodeURIComponent(dashboardId))
  await refreshGrants()
 }catch(e){ElMessage.error(e instanceof Error?e.message:'撤销失败')}
}
onMounted(refresh)
</script>

<template>
 <section class="resource-table">
  <div class="fine-list-tabs">
   <strong>部门管理</strong><span class="fine-list-count">{{departments.length}} 个部门</span>
   <button class="primary-soft" @click="openDepartment()">＋ 新建部门</button>
  </div>
  <table>
   <thead><tr><th>部门</th><th>上级部门</th><th>操作</th></tr></thead>
   <tbody>
    <tr v-for="d in departmentRows" :key="d.id">
     <td><span :style="{paddingLeft:(d.depth*20)+'px'}">{{d.depth?'↳ ':''}}{{d.name}}</span></td>
     <td>{{departments.find(parent=>parent.id===d.parent_id)?.name||'根目录'}}</td>
     <td><button @click="openDepartment(d)">编辑 / 调整层级</button>
      <button class="danger-link" @click="deleteDepartment(d)">删除</button></td>
    </tr>
    <tr v-if="!departments.length"><td colspan="3">暂无部门</td></tr>
   </tbody>
  </table>
 </section>
 <section class="resource-table">
  <div class="fine-list-tabs">
   <strong>用户与报表授权</strong>
   <span class="fine-list-count">{{users.length}} 个预配置账号</span>
   <button class="primary-soft" @click="addUser">＋ 预配置用户</button>
  </div>
  <p class="fine-dialog-tip">安全预配置阶段：普通账号尚不能登录，也不能读取数据。启用前必须完成服务端权限控制和登录验收。</p>
  <table>
   <thead><tr><th>账号</th><th>显示名称</th><th>归属部门</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
   <tbody>
    <tr v-for="u in users" :key="u.id">
     <td>{{u.username}}</td><td>{{u.display_name}}</td>
     <td><el-select :model-value="u.department_id||''" size="small" clearable placeholder="未分配"
      style="width:150px" @update:model-value="v=>changeDepartment(u,String(v||''))">
      <el-option label="未分配" value=""/>
      <el-option v-for="d in departmentRows" :key="d.id" :value="d.id"
       :label="'　'.repeat(d.depth)+d.name"/>
     </el-select></td>
     <td><el-select :model-value="u.role" size="small" style="width:112px"
      @update:model-value="v=>changeRole(u,v as 'VIEWER'|'EDITOR')">
      <el-option label="查看者" value="VIEWER"/><el-option label="编辑者" value="EDITOR"/>
     </el-select></td>
     <td>未启用</td>
     <td><button @click="openGrants(u)">预配置授权</button>
      <button class="danger-link" @click="deleteUser(u)">删除</button></td>
    </tr>
    <tr v-if="!users.length"><td colspan="6">尚无预配置账号</td></tr>
   </tbody>
  </table>
 </section>

 <el-dialog v-model="departmentDialog" :title="departmentDraft.id?'编辑部门':'新建部门'" width="460px">
  <div class="modal-fields">
   <label>部门名称</label><el-input v-model="departmentDraft.name" maxlength="100"
    placeholder="例如：研发部"/>
   <label>上级部门</label>
   <el-select v-model="departmentDraft.parentId" filterable placeholder="根目录">
    <el-option label="根目录" value=""/>
    <el-option v-for="d in departmentRows" :key="d.id" :label="'　'.repeat(d.depth)+d.name"
     :value="d.id" :disabled="d.id===departmentDraft.id"/>
   </el-select>
   <small>不允许循环层级；部门归属目前仅用于组织管理，不自动授予报表权限。</small>
  </div>
  <template #footer>
   <button @click="departmentDialog=false">取消</button>
   <button class="primary-button" :disabled="busy" @click="saveDepartment">保存部门</button>
  </template>
 </el-dialog>
 <el-dialog v-model="createOpen" title="预配置普通账号" width="440px">
  <div class="modal-fields">
   <label>账号（小写英文、数字、点、短横线）</label>
   <el-input v-model="newUser.username" maxlength="80" placeholder="例如 analyst01"/>
   <label>显示名称</label><el-input v-model="newUser.displayName" maxlength="100"/>
   <label>初始角色</label>
   <el-select v-model="newUser.role"><el-option label="查看者" value="VIEWER"/><el-option label="编辑者" value="EDITOR"/></el-select>
   <small>不会生成登录密码；账号保持禁用，不能进入系统。</small>
  </div>
  <template #footer><button @click="createOpen=false">取消</button>
   <button class="primary-button" :disabled="busy" @click="saveUser">创建预配置账号</button></template>
 </el-dialog>
 <el-dialog v-model="grantsOpen" :title="'预配置报表授权 · '+(selected?.username||'')" width="540px">
  <p class="fine-dialog-tip">仅允许预配置已发布报表的读取权限；此设置不会立即开放普通用户访问。</p>
  <div class="horizontal-form">
   <el-select v-model="newReport" filterable placeholder="选择已发布报表">
    <el-option v-for="d in published()" :key="d.id" :value="d.id" :label="d.name"/>
   </el-select>
   <button class="primary-button" :disabled="busy||!newReport" @click="grant">添加</button>
  </div>
  <div v-if="!grants.length" class="fine-dialog-tip">暂无授权</div>
  <table v-else class="bi-table">
   <thead><tr><th>已发布报表</th><th>权限</th><th>操作</th></tr></thead>
   <tbody><tr v-for="g in grants" :key="g.dashboard_id">
    <td>{{dashboards.find(d=>d.id===g.dashboard_id)?.name||g.dashboard_id}}</td>
    <td>只读（预配置）</td><td><button class="danger-link" @click="revoke(g.dashboard_id)">撤销</button></td>
   </tr></tbody>
  </table>
 </el-dialog>
</template>
