<script setup lang="ts">
import {ref} from 'vue'
import {ElMessage} from 'element-plus'
import {csrf} from './api'

type Result={valid:boolean;format:number;createdAt:string;tableCount:number;rowCount:number}
const busy=ref(false),inspection=ref<Result|null>(null)

async function backup(){
 if(busy.value)return
 busy.value=true
 try{
  const response=await fetch('/api/admin/metadata-backup',
    {credentials:'same-origin',cache:'no-store'})
  if(!response.ok)throw new Error('备份下载失败：HTTP '+response.status)
  const blob=await response.blob()
  const link=document.createElement('a'),url=URL.createObjectURL(blob)
  link.href=url
  link.download=/filename="([^"]+\.ibak)"/.exec(response.headers.get('content-disposition')||'')?.[1]
    ||'insight-bi-metadata.ibak'
  document.body.appendChild(link)
  link.click();link.remove()
  setTimeout(()=>URL.revokeObjectURL(url),1000)
  ElMessage.success('加密备份已生成，请妥善离线保管原始密钥')
 }catch(e){ElMessage.error(e instanceof Error?e.message:'备份失败')}
 finally{busy.value=false}
}

async function inspect(event:Event){
 const input=event.target as HTMLInputElement
 const file=input.files?.[0]
 inspection.value=null
 if(!file)return
 input.value=''
 if(file.size>32*1024*1024+512){
  ElMessage.error('文件超过32MB备份上限');return
 }
 busy.value=true
 try{
  const response=await fetch('/api/admin/metadata-backup/verify',{
   method:'POST',credentials:'same-origin',cache:'no-store',
   headers:{'Content-Type':'application/octet-stream','X-CSRF-Token':csrf.value},
   body:file
  })
  if(!response.ok){
   const message=await response.json().catch(()=>({error:'备份文件校验失败'}))
   throw new Error(message.error||'备份文件校验失败')
  }
  inspection.value=await response.json() as Result
  ElMessage.success('备份文件格式与完整性已通过校验')
 }catch(e){ElMessage.error(e instanceof Error?e.message:'备份校验失败')}
 finally{busy.value=false}
}
</script>

<template>
 <section class="resource-table" style="max-width:920px">
  <div class="fine-list-tabs"><strong>元数据备份与恢复</strong></div>
  <p class="fine-dialog-tip">对系统元数据进行加密备份，包括分析主题、数据源连接配置、用户、授权、审批和审计记录。不会备份 StarRocks / MySQL 数据源中的业务数据。</p>
  <div class="horizontal-form">
   <button class="primary-button" :disabled="busy" @click="backup">下载加密备份（.ibak）</button>
   <label class="primary-soft" style="cursor:pointer">
    校验已有备份
    <input type="file" accept=".ibak,application/octet-stream"
     :disabled="busy" style="display:none" @change="inspect"/>
   </label>
  </div>
  <div v-if="inspection" class="fine-dialog-tip" style="margin-top:18px">
   <strong>校验成功</strong>：{{inspection.tableCount}} 张表，{{inspection.rowCount}} 条元数据记录。
   <span>备份时间：{{inspection.createdAt}}</span>
  </div>
  <p class="fine-dialog-tip" style="margin-top:18px">
   备份使用服务端密钥加密。请将备份与原始 BI_CREDENTIAL_KEY 分开安全保存。
   恢复操作只支持停机后通过离线命令导入全新空库，不提供网页直接覆盖恢复，避免误操作。
  </p>
 </section>
</template>
