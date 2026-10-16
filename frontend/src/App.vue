<script setup lang="ts">
import {ref,computed,onMounted,onBeforeUnmount} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,put,remove,csrf,type Dataset} from './api'
import Designer from './Designer.vue'
import AnalysisSubject from './AnalysisSubject.vue'
import PublishedReport from './PublishedReport.vue'
import PipelineEditor from './PipelineEditor.vue'
import ModelRelations from './ModelRelations.vue'
import GovernanceAdmin from './GovernanceAdmin.vue'
import MetadataBackupPanel from './MetadataBackupPanel.vue'
import OperationsPanel from './OperationsPanel.vue'
import ReaderHome from './ReaderHome.vue'
const logged=ref(false),loading=ref(true),username=ref('admin'),password=ref(''),loginBusy=ref(false)
const readerMode=ref(false)
const route=ref('dashboards'),activeId=ref(''),view=ref(false)
const systemTab=ref<'connections'|'governance'|'backup'|'operations'>('connections')
const connections=ref<any[]>([]),datasets=ref<Dataset[]>([]),dashboards=ref<any[]>([])
const modelRelationsOpen=ref(false)
type Folder={id:string;name:string;parent_id:string|null;created_at?:string}
const folders=ref<Folder[]>([])
const dataFolders=ref<Folder[]>([])
const selectedDataFolder=ref('')
const dataFolderRows=computed(()=>{
 const result:Array<Folder&{depth:number}>=[]
 const seen=new Set<string>()
 function walk(parent:string,depth:number){
  for(const f of dataFolders.value.filter(f=>(f.parent_id??'')===parent)){
   if(seen.has(f.id))continue
   seen.add(f.id);result.push({...f,depth})
   if(depth<9)walk(f.id,depth+1)
  }
 }
 walk('',0)
 return result
})
const publicDataItems=computed(()=>datasets.value.filter(d=>(String((d as any).folder_id??''))===selectedDataFolder.value))
const publicFolderName=computed(()=>dataFolders.value.find(f=>f.id===selectedDataFolder.value)?.name??'公共数据')
async function createDataFolder(){
 try{
  const {value}=await ElMessageBox.prompt('输入公共数据文件夹名称','新建文件夹',{
    inputPattern:/^.{1,100}$/,inputErrorMessage:'名称为1到100字符'})
  const created=await post<{id:string}>('/data-folders',{name:value.trim(),parentId:selectedDataFolder.value||null})
  await refresh();selectedDataFolder.value=created.id
 }catch(e:any){if(e!=='cancel')showError(e)}
}
async function renameDataFolder(f:Folder){
 try{
  const {value}=await ElMessageBox.prompt('修改文件夹名称','重命名',{inputValue:f.name})
  await put('/data-folders/'+f.id,{name:value.trim()});await refresh()
 }catch(e:any){if(e!=='cancel')showError(e)}
}
async function deleteDataFolder(f:Folder){
 try{
  await ElMessageBox.confirm('只能删除不包含数据集和子文件夹的空目录。','删除文件夹',{type:'warning'})
  await remove('/data-folders/'+f.id)
  if(selectedDataFolder.value===f.id)selectedDataFolder.value=f.parent_id??''
  await refresh()
 }catch(e:any){if(e!=='cancel')showError(e)}
}
const moveDataDialog=ref(false),moveDataId=ref(''),moveDataTarget=ref('')
function startMoveData(ds:any){moveDataId.value=ds.id;moveDataTarget.value=ds.folder_id??'';moveDataDialog.value=true}
async function confirmMoveData(){
 saving.value=true
 try{
  await put('/datasets/'+moveDataId.value+'/folder',{parentId:moveDataTarget.value||null})
  moveDataDialog.value=false;await refresh()
 }catch(e){showError(e)}finally{saving.value=false}
}

const selectedFolder=ref('')
const moveDialog=ref(false),movingDashboard=ref(''),moveTarget=ref('')
const folderRows=computed(()=>{
 const result:Array<Folder&{depth:number}>=[]
 const visited=new Set<string>()
 function append(parent:string,depth:number){
  for(const folder of folders.value.filter(f=>(f.parent_id??'')===parent)){
   if(visited.has(folder.id))continue
   visited.add(folder.id);result.push({...folder,depth})
   if(depth<9)append(folder.id,depth+1)
  }
 }
 append('',0)
 return result
})
const folderChildren=computed(()=>folders.value.filter(f=>(f.parent_id??'')===selectedFolder.value))
const visibleDashboards=computed(()=>dashboards.value.filter(d=>{
 if(route.value==='catalog')return d.status==='published'
 return (d.folder_id??'')===selectedFolder.value
}))
const folderName=computed(()=>folders.value.find(f=>f.id===selectedFolder.value)?.name??'我的分析')
const isAnalysisRoute=computed(()=>['dashboards','catalog'].includes(route.value))
async function refreshFolders(){
 try{folders.value=await api<Folder[]>('/folders')}catch(e){showError(e)}
}
async function createFolder(){
 try{
  const result=await ElMessageBox.prompt('请输入文件夹名称','新建文件夹',{inputPattern:/^.{1,100}$/,inputErrorMessage:'名称为1到100字符'})
  const created=await post<{id:string}>('/folders',{name:result.value.trim(),parentId:selectedFolder.value||null})
  await refreshFolders();selectedFolder.value=created.id
  ElMessage.success('文件夹已创建')
 }catch(e:any){if(e!=='cancel')showError(e)}
}
async function renameFolder(folder:Folder){
 try{
  const {value}=await ElMessageBox.prompt('请输入新的文件夹名称','重命名',{inputValue:folder.name,inputPattern:/^.{1,100}$/,inputErrorMessage:'名称为1到100字符'})
  await put('/folders/'+folder.id,{name:value.trim()});await refreshFolders()
 }catch(e:any){if(e!=='cancel')showError(e)}
}
async function deleteFolder(folder:Folder){
 try{
  await ElMessageBox.confirm('删除空文件夹「'+folder.name+'」？包含下级文件夹或分析主题时不可删除。','删除文件夹',{type:'warning'})
  await remove('/folders/'+folder.id)
  if(selectedFolder.value===folder.id)selectedFolder.value=folder.parent_id??''
  await refreshFolders();ElMessage.success('文件夹已删除')
 }catch(e:any){if(e!=='cancel')showError(e)}
}
function startMove(d:any){movingDashboard.value=d.id;moveTarget.value=d.folder_id??'';moveDialog.value=true}
async function confirmMove(){
 saving.value=true
 try{await put('/dashboards/'+movingDashboard.value+'/folder',{parentId:moveTarget.value||null})
  moveDialog.value=false;await refresh();ElMessage.success('分析主题已移动')
 }catch(e){showError(e)}finally{saving.value=false}
}

const aliasRenames=ref<{oldName:string;newName:string}[]>([])
const originalDatasetFields=ref<string[]>([])
const connDialog=ref(false),dataDialog=ref(false),dashDialog=ref(false),previewDialog=ref(false)
const conn=ref<any>({}),data=ref<any>({}),dash=ref({name:'',mode:'grid'}),tables=ref<string[]>([]),chosenTable=ref('')
const dataPreview=ref<Record<string,any>[]>([]),previewFields=ref<string[]>([])
const validating=ref(false),saving=ref(false),error=ref('')
const importInput=ref<HTMLInputElement|null>(null)
const menu=[
 {key:'catalog',label:'目录',icon:'☷'},
 {key:'dashboards',label:'我的分析',icon:'▥'},
 {key:'datasets',label:'公共数据',icon:'▤'},
 {key:'connections',label:'系统管理',icon:'⚙'}
]
const editor=computed(()=>['designer','view','analysis','pipeline'].includes(route.value))
function handleHash(){
 const hash=window.location.hash.replace(/^#\/?/,'').split('/').filter(Boolean)
 route.value=hash[0]||'dashboards';activeId.value=hash[1]||'';view.value=route.value==='view'
 if(readerMode.value){
  if(!['reader','reader-view'].includes(route.value)||(route.value==='reader-view'&&!activeId.value)){
   route.value='reader';activeId.value='';window.location.hash='/reader'
  }
  return
 }
 if(!['designer','view','analysis','pipeline','datasets','dashboards','connections','catalog'].includes(route.value))route.value='dashboards'
 if(logged.value&&!editor.value)refresh()
}
function navigate(path:string){window.location.hash='/'+path;handleHash()}
async function check(){
 try{
  const r=await api<{csrf:string}>('/me')
  readerMode.value=false;csrf.value=r.csrf;logged.value=true;handleHash()
 }catch{
  try{
   const r=await api<{csrf:string}>('/reader/me')
   readerMode.value=true;csrf.value=r.csrf;logged.value=true;handleHash()
  }catch{logged.value=false;readerMode.value=false}
 }finally{loading.value=false}
}
async function login(){
 loginBusy.value=true;error.value=''
 try{
  const isReader=username.value.trim().toLowerCase()!=='admin'
  const x=await post<{csrf:string}>(isReader?'/reader/login':'/login',{
   username:username.value.trim(),password:password.value})
  readerMode.value=isReader;csrf.value=x.csrf;logged.value=true;password.value=''
  if(isReader)window.location.hash='/reader'
  handleHash()
 }catch(e:any){error.value=e.message}
 finally{loginBusy.value=false}
}
async function logout(){
 try{await post(readerMode.value?'/reader/logout':'/logout',{})}catch{}
 logged.value=false;readerMode.value=false;csrf.value='';password.value=''
}
async function refresh(){
 try{const [a,b,c,d,e]=await Promise.all([api('/connections'),api('/datasets'),api('/dashboards'),api('/folders'),api('/data-folders')]);connections.value=a;datasets.value=b;dashboards.value=c;folders.value=d as Folder[];dataFolders.value=e as Folder[]}
 catch(e:any){if(e.message!=='请求失败 HTTP 401')ElMessage.error(e.message)}
}
const showError=(e:any)=>ElMessage.error(e?.message||String(e))
function newConnection(){
 conn.value={name:'',engine:'starrocks',host:'172.17.0.12',port:9030,database:'ods',username:'root',password:''};connDialog.value=true
}
function editConnection(c:any){
 conn.value={...c,database:c.db_name,password:''};connDialog.value=true
}
function engineChange(){
 conn.value.port=conn.value.engine==='starrocks'?9030:3306
 conn.value.database=conn.value.engine==='starrocks'?'ods':'datasphere'
}
async function testConnection(){
 validating.value=true
 try{await post('/connections/test',conn.value);ElMessage.success('连接成功')}
 catch(e){showError(e)}finally{validating.value=false}
}
async function saveConnection(){
 saving.value=true;try{await post('/connections',conn.value);connDialog.value=false;await refresh();ElMessage.success('已保存连接')}catch(e){showError(e)}finally{saving.value=false}
}
async function deleteConnection(c:any){
 try{await ElMessageBox.confirm('将删除连接「'+c.name+'」，不会删除源数据库。','删除数据连接',{type:'warning'});await remove('/connections/'+c.id);await refresh()}catch(e:any){if(e!=='cancel')showError(e)}
}
function newDataset(){
 data.value={name:'',connectionId:'',sql:'',parentId:selectedDataFolder.value||null}
 tables.value=[];chosenTable.value='';aliasRenames.value=[];originalDatasetFields.value=[]
 dataDialog.value=true
}
async function editDataset(ds:Dataset){
 data.value={id:ds.id,name:ds.name,connectionId:ds.connection_id,sql:ds.sql_body,parentId:(ds as any).folder_id??null}
 chosenTable.value='';aliasRenames.value=[];originalDatasetFields.value=[];dataDialog.value=true
 await readTables()
 try{
  const detail=await api<{fields:{name:string}[]}>('/datasets/'+ds.id)
  if(data.value.id===ds.id)originalDatasetFields.value=detail.fields.map(x=>x.name)
 }catch(e){showError(e)}
}
async function readTables(){
 tables.value=[];if(!data.value.connectionId)return
 try{tables.value=await api('/connections/'+data.value.connectionId+'/tables')}catch(e){showError(e)}
}
function selectTable(){
 if(!chosenTable.value)return
 data.value.sql='SELECT * FROM '+ '\x60'+chosenTable.value.replaceAll('\x60','\x60\x60')+'\x60'
 if(!data.value.name)data.value.name=chosenTable.value
}
async function saveDataset(){
 saving.value=true
 try{
  const mapping:Record<string,string>={}
  for(const item of aliasRenames.value){
   const before=item.oldName.trim(),after=item.newName.trim()
   if(!before||!after||before===after)throw Error('请填写不同的旧列名与新列名')
   if(Object.hasOwn(mapping,before))throw Error('同一个旧源列只能映射一次')
   if(Object.values(mapping).includes(after))throw Error('多个旧源列不能映射到同一新列名')
   mapping[before]=after
  }
  await post('/datasets',{...data.value,fieldRenames:mapping})
  dataDialog.value=false;await refresh();ElMessage.success('数据集已保存并获取真实字段')
 }catch(e){showError(e)}finally{saving.value=false}
}
async function deleteDataset(ds:Dataset){
 try{await ElMessageBox.confirm('将删除数据集「'+ds.name+'」，源表不会被删除。','确认删除',{type:'warning'});await remove('/datasets/'+ds.id);await refresh()}
 catch(e:any){if(e!=='cancel')showError(e)}
}
async function showPreview(ds:Dataset){
 try{const x=await api<{rows:Record<string,any>[],fields:{name:string}[]}>('/datasets/'+ds.id+'/preview?limit=30');dataPreview.value=x.rows;previewFields.value=x.fields.map(f=>f.name);previewDialog.value=true}
 catch(e){showError(e)}
}
async function exportDashboard(item:any){
 try{
  const x=await api<any>('/dashboards/'+item.id)
  const data={format:'insight-bi-dashboard',version:1,name:x.name,mode:x.mode,content:x.content}
  const file=new Blob([JSON.stringify(data,null,2)],{type:'application/json'})
  const href=URL.createObjectURL(file),a=document.createElement('a');a.href=href;a.download=(x.name||'Insight BI 报表').replace(/[\\/:*?"<>|]/g,'_')+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(href),1000)
  ElMessage.success('报表配置已导出（不包含数据源密码及业务数据）')
 }catch(e){showError(e)}
}
async function importDashboard(event:Event){
 const input=event.target as HTMLInputElement, file=input.files?.[0];if(!file)return
 input.value=''
 if(file.size>2_000_000)return ElMessage.warning('报表配置文件不能超过 2MB')
 let createdId=''
 try{
  const data=JSON.parse(await file.text())
  if(data.format!=='insight-bi-dashboard'||data.version!==1||!['grid','free'].includes(data.mode)||!data.content||!Array.isArray(data.content.widgets)||data.content.widgets.length>300)throw Error('不是有效的 Insight BI 报表文件')
  const name=String(data.name||'导入的报表').slice(0,150)
  const res=await post<{id:string}>('/dashboards',{name,mode:data.mode});createdId=res.id
  await put('/dashboards/'+createdId,{name,mode:data.mode,revision:1,content:data.content})
  await refresh();ElMessage.success('报表已导入。请检查数据集绑定后再发布。');navigate('designer/'+createdId)
 }catch(e){
  if(createdId)try{await remove('/dashboards/'+createdId)}catch{}
  showError(e)
 }
}
async function createAnalysisSubject(){
 try{
  const {value}=await ElMessageBox.prompt('输入分析主题名称','新建分析主题',{
    inputPattern:/^.{1,100}$/,inputErrorMessage:'名称必须是1到100个字符',confirmButtonText:'创建'})
  if(!value.trim())return
  saving.value=true
  const created=await post<{id:string}>('/dashboards',{
    name:value.trim(),mode:'grid',kind:'analysis',parentId:selectedFolder.value||null})
  await refresh()
  navigate('analysis/'+created.id)
 }catch(e:any){if(e!=='cancel')showError(e)}finally{saving.value=false}
}
function openReport(item:any){
 navigate((item.kind==='analysis'?'analysis/':'designer/')+item.id)
}
function newDashboard(){dash.value={name:'',mode:'grid'};dashDialog.value=true}
async function createDashboard(){
 if(!dash.value.name.trim())return ElMessage.warning('请填写报表名称')
 saving.value=true;try{const x=await post<{id:string}>('/dashboards',{...dash.value,parentId:selectedFolder.value||null});dashDialog.value=false;await refresh();navigate('designer/'+x.id)}
 catch(e){showError(e)}finally{saving.value=false}
}
async function deleteDashboard(x:any){
 try{await ElMessageBox.confirm('确定删除「'+x.name+'」？此操作无法恢复。','删除报表',{type:'warning'});await remove('/dashboards/'+x.id);await refresh()}
 catch(e:any){if(e!=='cancel')showError(e)}
}
function unauth(){logged.value=false;readerMode.value=false;csrf.value=''}
onMounted(()=>{window.addEventListener('hashchange',handleHash);window.addEventListener('bi-unauthorized',unauth);check()})
onBeforeUnmount(()=>{window.removeEventListener('hashchange',handleHash);window.removeEventListener('bi-unauthorized',unauth)})
</script>
<template>
 <div v-if="loading" class="startup-loading">正在连接 Insight BI…</div>
 <div v-else-if="!logged" class="login-page">
  <div class="login-ambient a1"></div><div class="login-ambient a2"></div>
  <form class="login-panel" @submit.prevent="login">
   <div class="brand-mark">▦</div><h1>Insight <span>BI</span></h1><p>面向企业的数据分析与可视化设计平台</p>
   <label>账号</label><el-input v-model="username" size="large" autocomplete="username"/>
   <label>密码</label><el-input v-model="password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入账号密码"/>
   <div v-if="error" class="login-error">{{error}}</div><button class="primary-button login-submit" type="submit" :disabled="loginBusy">{{loginBusy?'正在登录…':'登录工作台 →'}}</button>
   <small>仅限授权用户访问 · 所有业务数据来自真实数据源</small>
  </form>
 </div>
 <PublishedReport v-else-if="readerMode&&route==='reader-view'&&activeId"
  :key="activeId+'reader'" :id="activeId" :viewer-mode="true" @back="navigate('reader')"/>
 <ReaderHome v-else-if="readerMode" @open="id=>navigate('reader-view/'+id)" @logout="logout"/>
 <AnalysisSubject v-else-if="route==='analysis'&&activeId" :key="activeId" :id="activeId" @back="navigate('dashboards')"/>
 <PipelineEditor v-else-if="route==='pipeline'&&activeId" :key="activeId" :id="activeId" @back="navigate('datasets')"/>
 <PublishedReport v-else-if="route==='view'&&activeId" :key="activeId+route" :id="activeId" @back="navigate('dashboards')"/>
 <Designer v-else-if="editor&&activeId" :key="activeId+route" :id="activeId" :readonly="view" @back="navigate('dashboards')"/>
 <div v-else class="app-shell">
  <aside class="nav-panel">
   <div class="nav-brand"><div class="brand-icon">▦</div><strong>Insight <span>BI</span></strong></div>
   <div class="nav-group-title">导航</div>
   <nav><button v-for="item in menu" :key="item.key" :class="{active:route===item.key}" @click="navigate(item.key)"><span>{{item.icon}}</span>{{item.label}}</button></nav>
   <div class="nav-bottom"><div class="account-avatar">A</div><div class="account-name"><b>admin</b><small>系统管理员</small></div><button title="退出登录" class="signout" @click="logout">↪</button></div>
  </aside>
  <aside v-if="route==='datasets'" class="fine-folder-pane" aria-label="公共数据文件夹">
   <div class="fine-folder-heading"><strong>公共数据</strong><button title="新建文件夹" @click="createDataFolder">＋</button></div>
   <div class="fine-folder-actions"><button @click="createDataFolder">新建文件夹</button><button class="fine-primary" @click="newDataset">添加数据集</button></div>
   <div class="fine-folder-search">StarRocks 直连数据集</div>
   <div class="fine-folder-root" :class="{active:!selectedDataFolder}" @click="selectedDataFolder=''">▤　全部数据</div>
   <div v-for="folder in dataFolderRows" :key="folder.id" class="fine-folder-row" :class="{active:selectedDataFolder===folder.id}"
      :style="{paddingLeft:(12+folder.depth*18)+'px'}">
    <button class="fine-folder-open" @click="selectedDataFolder=folder.id"><span>▸</span> 📁 {{folder.name}}</button>
    <button class="fine-mini-action" title="重命名" @click="renameDataFolder(folder)">✎</button>
    <button class="fine-mini-action" title="删除空文件夹" @click="deleteDataFolder(folder)">×</button>
   </div>
  </aside>
  <aside v-if="route==='dashboards'" class="fine-folder-pane" aria-label="我的分析文件夹">
   <div class="fine-folder-heading"><strong>我的分析</strong><button title="新建文件夹" @click="createFolder">＋</button></div>
   <div class="fine-folder-actions"><button @click="createFolder">新建文件夹</button><button class="fine-primary" @click="createAnalysisSubject">新建分析主题</button></div>
   <div class="fine-folder-search">文件夹与分析主题</div>
   <div class="fine-folder-root" :class="{active:!selectedFolder}" @click="selectedFolder=''">▤　全部分析</div>
   <div v-for="f in folderRows" :key="f.id" class="fine-folder-row" :class="{active:selectedFolder===f.id}"
      :style="{paddingLeft:(12+f.depth*18)+'px'}">
     <button class="fine-folder-open" @click="selectedFolder=f.id"><span>▸</span> 📁 {{f.name}}</button>
     <button title="重命名" class="fine-mini-action" @click="renameFolder(f)">✎</button>
     <button title="删除空文件夹" class="fine-mini-action" @click="deleteFolder(f)">×</button>
   </div>
  </aside>
  <div class="work-area">
   <header class="work-topbar"><div><span class="location">工作空间</span><span class="separator">/</span><strong>{{menu.find(m=>m.key===route)?.label}}</strong></div><span class="session-pill">● 服务已连接</span></header>
   <main class="work-main fine-work-main">
    <section class="page-title fine-page-heading"><div><h1>{{route==='dashboards'?folderName:route==='datasets'?publicFolderName:route==='catalog'?'目录':(systemTab==='governance'?'系统管理 / 权限治理':systemTab==='backup'?'系统管理 / 备份恢复':systemTab==='operations'?'系统管理 / 运行监控':'系统管理 / 数据连接')}}</h1></div>
     <input ref="importInput" type="file" accept=".json,application/json" style="display:none" aria-label="导入报表配置" @change="importDashboard"/>
     <button v-if="route==='dashboards'" class="primary-soft" @click="importInput?.click()">导入分析主题</button>
     <button v-if="route!=='catalog' && !(route==='connections'&&systemTab!=='connections')" class="primary-button" @click="route==='dashboards'?createAnalysisSubject():route==='datasets'?newDataset():newConnection()">＋ {{route==='dashboards'?'新建分析主题':route==='datasets'?'添加数据集':'添加数据连接'}}</button>
    </section>
    <template v-if="isAnalysisRoute">
     <div class="fine-list-tabs"><button class="selected">{{route==='catalog'?'已发布分析':'全部'}}</button><button v-if="route==='dashboards'" @click="selectedFolder=''">全部分析</button><span class="fine-list-count">{{visibleDashboards.length}} 个分析主题</span></div>
     <div v-if="route==='dashboards' && folderChildren.length" class="fine-folder-grid">
       <button v-for="f in folderChildren" :key="f.id" @click="selectedFolder=f.id"><span class="fine-big-folder">📁</span><span>{{f.name}}</span></button>
     </div>
     <div v-if="!visibleDashboards.length && !folderChildren.length" class="fine-empty-workspace">
       <span>▤</span><p>{{route==='catalog'?'暂无已发布的分析主题':'此文件夹暂无分析主题'}}</p>
       <button v-if="route==='dashboards'" class="primary-soft" @click="createAnalysisSubject">新建分析主题</button>
     </div>
     <div v-else class="fine-subject-grid">
       <article v-for="item in visibleDashboards" :key="item.id" class="fine-subject">
         <button class="fine-subject-cover" @click="openReport(item)"><span>▦</span><small>{{item.kind==='analysis'?'分析主题':item.mode==='free'?'自由画布':'仪表板'}}</small></button>
         <div class="fine-subject-title" :title="item.name">{{item.name}}</div>
         <div class="fine-subject-status">{{item.status==='published'?'已发布':'草稿'}}</div>
         <div class="fine-subject-actions">
           <button @click="openReport(item)">编辑</button>
           <button v-if="item.status==='published'" @click="navigate('view/'+item.id)">查看</button>
           <button @click="startMove(item)">移动</button><button @click="exportDashboard(item)">导出</button>
           <button class="danger-link" @click="deleteDashboard(item)">删除</button>
         </div>
       </article>
     </div>
    </template>
    <template v-if="route==='connections'">
     <div class="fine-list-tabs">
      <button :class="{selected:systemTab==='connections'}" @click="systemTab='connections'">数据连接</button>
      <button :class="{selected:systemTab==='governance'}" @click="systemTab='governance'">权限治理</button>
      <button :class="{selected:systemTab==='backup'}" @click="systemTab='backup'">备份恢复</button>
      <button :class="{selected:systemTab==='operations'}" @click="systemTab='operations'">运行监控</button>
     </div>
     <GovernanceAdmin v-if="systemTab==='governance'" :dashboards="dashboards" :datasets="datasets" :folders="folders"/>
     <MetadataBackupPanel v-else-if="systemTab==='backup'"/>
     <OperationsPanel v-else-if="systemTab==='operations'"/>
     <template v-else>
     <div v-if="!connections.length" class="empty-panel"><div class="empty-symbol">◫</div><h3>还没有数据连接</h3><p>添加 StarRocks 或 MySQL 连接后，即可发现真实表结构。</p><button class="primary-soft" @click="newConnection">＋ 添加数据连接</button></div>
     <div v-else class="resource-table"><table><thead><tr><th>连接名称</th><th>类型</th><th>主机</th><th>数据库</th><th>操作</th></tr></thead><tbody><tr v-for="c in connections" :key="c.id"><td><b>{{c.name}}</b></td><td><span class="engine-tag">{{c.engine==='starrocks'?'StarRocks':'MySQL'}}</span></td><td>{{c.host}}:{{c.port}}</td><td>{{c.db_name}}</td><td><button @click="editConnection(c)">编辑</button><button class="danger-link" @click="deleteConnection(c)">删除</button></td></tr></tbody></table></div>
     </template>
    </template>
    <template v-if="route==='datasets'">
      <div class="fine-list-tabs"><button class="selected">数据集</button><span class="fine-list-count">{{publicDataItems.length}} 个</span>
       <button type="button" class="model-relations-open" @click="modelRelationsOpen=true">模型关系</button>
      </div>
      <div v-if="dataFolders.some(f=>(f.parent_id??'')===selectedDataFolder)" class="fine-folder-grid">
        <button v-for="folder in dataFolders.filter(f=>(f.parent_id??'')===selectedDataFolder)" :key="folder.id"
         @click="selectedDataFolder=folder.id"><span class="fine-big-folder">📁</span><span>{{folder.name}}</span></button>
      </div>
      <div v-if="!publicDataItems.length" class="fine-empty-workspace"><span>▤</span><p>暂无数据集</p><button class="primary-soft" @click="newDataset">添加 SQL 数据集</button></div>
      <div v-else class="resource-table"><table><thead><tr><th>数据集名称</th><th>来源连接</th><th>字段数</th><th>操作</th></tr></thead>
        <tbody><tr v-for="ds in publicDataItems" :key="ds.id"><td><b>{{ds.name}}</b></td>
          <td>{{connections.find(c=>c.id===ds.connection_id)?.name||'未知连接'}}</td><td>{{ds.fields.length}}</td>
          <td><button @click="showPreview(ds)">预览数据</button><button @click="navigate('pipeline/'+ds.id)">加工</button><button @click="editDataset(ds)">编辑</button>
              <button @click="startMoveData(ds)">移动</button><button class="danger-link" @click="deleteDataset(ds)">删除</button></td>
        </tr></tbody></table></div>
    </template>
   </main>
  </div>
 </div>
 <el-dialog v-model="modelRelationsOpen" title="数据模型关系" width="min(1040px, 92%)">
  <ModelRelations v-if="modelRelationsOpen" @open-pipeline="id=>{modelRelationsOpen=false;navigate('pipeline/'+id)}"/>
 </el-dialog>
 <el-dialog v-model="moveDataDialog" title="移动数据集" width="420px">
   <p class="fine-dialog-tip">移动只更改归档位置，不影响 StarRocks 中的真实业务数据。</p>
   <el-select v-model="moveDataTarget" style="width:100%">
     <el-option label="公共数据（根目录）" value=""/>
     <el-option v-for="f in dataFolderRows" :key="f.id" :label="'　'.repeat(f.depth)+f.name" :value="f.id"/>
   </el-select>
   <template #footer><button class="subtle-button" @click="moveDataDialog=false">取消</button>
    <button class="primary-button" :disabled="saving" @click="confirmMoveData">确认移动</button></template>
 </el-dialog>
 <el-dialog v-model="moveDialog"  title="移动分析主题" width="420px">
   <p class="fine-dialog-tip">将分析主题移动到指定文件夹，不会更改图表与业务数据。</p>
   <el-select v-model="moveTarget" style="width:100%" placeholder="选择目标文件夹">
     <el-option label="我的分析（根目录）" value=""/>
     <el-option v-for="folder in folderRows" :key="folder.id" :label="'　'.repeat(folder.depth)+folder.name" :value="folder.id"/>
   </el-select>
   <template #footer><button class="subtle-button" @click="moveDialog=false">取消</button><button class="primary-button" :disabled="saving" @click="confirmMove">确认移动</button></template>
 </el-dialog>
 <el-dialog v-model="connDialog" :title="conn.id?'编辑数据连接':'新建数据连接'" width="550px" destroy-on-close>
  <div class="modal-fields"><label>连接名称</label><el-input v-model="conn.name" placeholder="例如：生产数仓"/><label>数据库类型</label><el-select v-model="conn.engine" @change="engineChange"><el-option label="StarRocks" value="starrocks"/><el-option label="MySQL" value="mysql"/></el-select><label>服务器地址</label><el-input v-model="conn.host" placeholder="内网 IP 或域名"/><label>端口</label><el-input-number v-model="conn.port" :min="1" :max="65535"/><label>数据库名</label><el-input v-model="conn.database"/><label>用户名</label><el-input v-model="conn.username"/><label>密码</label><el-input v-model="conn.password" type="password" show-password :placeholder="conn.id?'留空则保持已有密码':'请输入连接密码'"/></div>
  <template #footer><button class="subtle-button" @click="connDialog=false">取消</button><button class="primary-soft" :disabled="validating" @click="testConnection">{{validating?'正在连接…':'测试连接'}}</button><button class="primary-button" :disabled="saving" @click="saveConnection">保存连接</button></template>
 </el-dialog>
 <el-dialog v-model="dataDialog" :title="data.id?'编辑数据集':'创建数据集'" width="740px" destroy-on-close>
  <div class="modal-fields"><label>数据集名称</label><el-input v-model="data.name" placeholder="输入描述性名称"/><label>关联数据连接</label><el-select v-model="data.connectionId" placeholder="选择数据库连接" @change="readTables"><el-option v-for="c in connections" :key="c.id" :label="c.name+' ('+c.db_name+')'" :value="c.id"/></el-select><label>选择数据库表（可选）</label><div class="horizontal-form"><el-select v-model="chosenTable" filterable placeholder="选择真实数据表"><el-option v-for="t in tables" :key="t" :label="t" :value="t"/></el-select><button class="primary-soft" @click="selectTable">生成 SQL</button></div><label>只读 SELECT SQL</label><el-input v-model="data.sql" type="textarea" :rows="7" placeholder="SELECT * FROM \x60table_name\x60"/><small>保存时连接数据库读取真实字段；仅允许单条 SELECT，限制单次查询量及超时。</small>
   <template v-if="data.id">
    <label>源字段别名迁移（可选）</label>
    <small>仅适用于同一业务字段更换 SQL 输出别名：旧列名 → 新列名。确认后保留字段 ID、计算公式及图表绑定，不修改数据库物理列。</small>
    <div v-for="(mapping,index) in aliasRenames" :key="index" class="horizontal-form">
     <el-select v-model="mapping.oldName" filterable placeholder="旧列名">
      <el-option v-for="field in originalDatasetFields" :key="field" :label="field" :value="field"/>
     </el-select>
     <el-input v-model="mapping.newName" maxlength="128" placeholder="新 SQL 输出列名"/>
     <button type="button" class="subtle-button" @click="aliasRenames.splice(index,1)">移除</button>
    </div>
    <button type="button" class="primary-soft" @click="aliasRenames.push({oldName:'',newName:''})">＋ 添加别名映射</button>
   </template>
  </div>
  <template #footer><button class="subtle-button" @click="dataDialog=false">取消</button><button class="primary-button" :disabled="saving" @click="saveDataset">校验并保存</button></template>
 </el-dialog>
 <el-dialog v-model="dashDialog" title="新建报表" width="510px"><div class="modal-fields"><label>报表名称</label><el-input v-model="dash.name" placeholder="请输入报表名称"/><label>设计器模式</label><div class="mode-pick"><button :class="{selected:dash.mode==='grid'}" @click="dash.mode='grid'"><span>▦</span><strong>常规仪表板</strong><small>栅格编排，自适应宽度</small></button><button :class="{selected:dash.mode==='free'}" @click="dash.mode='free'"><span>▧</span><strong>自由画布</strong><small>像素精排，适合驾驶舱</small></button></div></div><template #footer><button class="subtle-button" @click="dashDialog=false">取消</button><button class="primary-button" :disabled="saving" @click="createDashboard">创建并编辑 →</button></template></el-dialog>
 <el-dialog v-model="previewDialog" title="数据集真实数据预览" width="85%"><div class="table-overflow"><table class="bi-table"><thead><tr><th v-for="f in previewFields" :key="f">{{f}}</th></tr></thead><tbody><tr v-for="(r,i) in dataPreview" :key="i"><td v-for="f in previewFields" :key="f">{{r[f]??'—'}}</td></tr></tbody></table></div><p class="helper-text">仅显示前 30 行 · 数据从目标数据库实时读取</p></el-dialog>
</template>
