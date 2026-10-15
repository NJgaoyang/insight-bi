<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import {api} from './api'
const emit=defineEmits<{(e:'open',id:string):void;(e:'logout'):void}>()
type ReaderDashboard={id:string;name:string;kind:string;status:string}
const reports=ref<ReaderDashboard[]>([])
const loading=ref(true)
async function refresh(){
 loading.value=true
 try{reports.value=await api<ReaderDashboard[]>('/reader/catalog')}
 catch(e){ElMessage.error(e instanceof Error?e.message:'读取授权目录失败')}
 finally{loading.value=false}
}
onMounted(refresh)
</script>
<template>
 <div class="app-shell">
  <aside class="nav-panel">
   <div class="nav-brand"><div class="brand-icon">▦</div><strong>Insight <span>BI</span></strong></div>
   <div class="nav-group-title">只读工作区</div>
   <nav><button class="active">▤　授权报表</button></nav>
   <div class="nav-bottom"><div class="account-avatar">R</div>
    <div class="account-name"><b>普通用户</b><small>仅查看获授权内容</small></div>
    <button class="signout" title="退出登录" @click="emit('logout')">↪</button></div>
  </aside>
  <div class="work-area">
   <header class="work-topbar"><div><span class="location">只读工作区</span>
    <span class="separator">/</span><strong>授权报表</strong></div></header>
   <main class="work-main fine-work-main">
    <section class="page-title fine-page-heading">
     <div><h1>已授权分析</h1></div>
     <button class="primary-soft" :disabled="loading" @click="refresh">刷新</button>
    </section>
    <div v-if="loading" class="analysis-published-loading">正在读取授权目录…</div>
    <div v-else-if="!reports.length" class="fine-empty-workspace">
     <span>▤</span><p>当前没有已授权的已发布分析主题</p>
    </div>
    <div v-else class="fine-subject-grid">
     <article v-for="report in reports" :key="report.id" class="fine-subject">
      <button class="fine-subject-cover" @click="emit('open',report.id)">
       <span>▦</span><small>只读分析主题</small></button>
      <div class="fine-subject-title">{{report.name}}</div>
      <div class="fine-subject-status">已授权 · 已发布</div>
      <div class="fine-subject-actions"><button @click="emit('open',report.id)">查看分析</button></div>
     </article>
    </div>
   </main>
  </div>
 </div>
</template>
