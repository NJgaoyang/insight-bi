<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {api} from './api'
import {ElMessage} from 'element-plus'

type Metrics={
 startedAt:string;uptimeSeconds:number;requestCount:number;successCount:number;
 clientErrorCount:number;serverErrorCount:number;accessDeniedCount:number;
 slowRequestCount:number;lastFailureAt:string;lastSlowRequestAt:string
}
type Status={
 state:'healthy'|'degraded';
 database:{ready:boolean;probeMs:number};
 requests:Metrics;
 analysis:{concurrencyLimit:number;activeQueries:number;waitingQueries:number;rejectedQueries:number};
 connectionPool?:{active:number;idle:number;pending:number;total:number}
}
const data=ref<Status|null>(null)
const loading=ref(false)
const error=ref('')
const measuredAt=ref('')
async function refresh(){
 if(loading.value)return
 loading.value=true;error.value=''
 try{
  data.value=await api<Status>('/admin/operations/status')
  measuredAt.value=new Date().toLocaleString()
 }catch(e){
  data.value=null
  error.value=e instanceof Error?e.message:'无法读取运行状态'
  ElMessage.error(error.value)
 }finally{loading.value=false}
}
const duration=(s:number)=>{
 const value=Math.max(0,Math.floor(s))
 return Math.floor(value/86400)+'天 '+Math.floor(value%86400/3600)+'小时 '+Math.floor(value%3600/60)+'分钟'
}
onMounted(refresh)
</script>

<template>
 <section class="resource-table" style="max-width:1040px">
  <div class="fine-list-tabs">
   <strong>系统运行状态</strong>
   <span class="fine-list-count">{{measuredAt?'上次刷新：'+measuredAt:'尚未读取'}}</span>
   <button class="primary-soft" :disabled="loading" @click="refresh">
    {{loading?'正在检测':'刷新状态'}}
   </button>
  </div>
  <p class="fine-dialog-tip">
   本页显示当前服务进程的运行指标，重启后重新计数。指标不包含查询 SQL、数据源地址、账号或访问者信息。
   不会自动触发数据源查询。
  </p>
  <div v-if="error" class="fine-dialog-tip">{{error}}</div>
  <template v-if="data">
   <div class="fine-list-tabs">
    <strong>{{data.state==='healthy'?'服务运行正常':'服务状态异常'}}</strong>
    <span class="fine-list-count">元数据库：{{data.database.ready?'可用':'不可用'}}
     · 检测耗时 {{data.database.probeMs}} ms</span>
   </div>
   <div class="fine-list-tabs">
    <strong>请求运行指标</strong>
   </div>
   <table>
    <thead><tr><th>已处理请求</th><th>成功</th><th>客户端错误</th><th>服务端错误</th><th>拒绝访问</th><th>慢请求（≥3秒）</th></tr></thead>
    <tbody><tr>
     <td>{{data.requests.requestCount}}</td>
     <td>{{data.requests.successCount}}</td>
     <td>{{data.requests.clientErrorCount}}</td>
     <td>{{data.requests.serverErrorCount}}</td>
     <td>{{data.requests.accessDeniedCount}}</td>
     <td>{{data.requests.slowRequestCount}}</td>
    </tr></tbody>
   </table>
   <div class="fine-list-tabs"><strong>分析查询并发</strong></div>
   <table>
    <thead><tr><th>并发上限</th><th>正在执行</th><th>正在等待</th><th>累计被限流</th></tr></thead>
    <tbody><tr><td>{{data.analysis.concurrencyLimit}}</td>
     <td>{{data.analysis.activeQueries}}</td>
     <td>{{data.analysis.waitingQueries}}</td>
     <td>{{data.analysis.rejectedQueries}}</td></tr></tbody>
   </table>
   <template v-if="data.connectionPool">
    <div class="fine-list-tabs"><strong>元数据库连接池</strong></div>
    <table><thead><tr><th>活跃连接</th><th>空闲连接</th><th>等待连接</th><th>连接总数</th></tr></thead>
     <tbody><tr><td>{{data.connectionPool.active}}</td><td>{{data.connectionPool.idle}}</td>
      <td>{{data.connectionPool.pending}}</td><td>{{data.connectionPool.total}}</td></tr></tbody>
    </table>
   </template>
   <p class="fine-dialog-tip">
    启动时间：{{data.requests.startedAt}} · 运行时长：{{duration(data.requests.uptimeSeconds)}}
    <template v-if="data.requests.lastFailureAt"> · 最近失败：{{data.requests.lastFailureAt}}</template>
    <template v-if="data.requests.lastSlowRequestAt"> · 最近慢请求：{{data.requests.lastSlowRequestAt}}</template>
   </p>
  </template>
  <div v-else-if="loading" class="fine-dialog-tip">正在读取运行指标…</div>
 </section>
</template>
