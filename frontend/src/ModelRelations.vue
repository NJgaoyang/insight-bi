<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {api} from './api'
type Pair={leftId:string;leftName:string;rightId:string;rightName:string;compatible:boolean}
type Relation={
 datasetId:string;datasetName:string;targetDatasetId:string;targetDatasetName:string;
 mode:'JOIN'|'UNION';joinType:string;revision:number;status:'ACTIVE'|'STALE'|'INVALID';
 reason:string;fields:Pair[];outputFields:number
}
const emit=defineEmits<{(event:'open-pipeline',id:string):void}>()
const relations=ref<Relation[]>([])
const loading=ref(false),error=ref('')
async function load(){
 loading.value=true;error.value=''
 try{relations.value=await api<Relation[]>('/model/relations')}
 catch(e:unknown){relations.value=[];error.value=e instanceof Error?e.message:'模型关系获取失败'}
 finally{loading.value=false}
}
onMounted(load)
</script>
<template>
 <section class="model-relations">
  <div class="model-relations-toolbar">
   <p>从已保存的 JOIN / UNION 加工步骤生成，只读展示，不会执行源数据库 SQL。</p>
   <button type="button" @click="load" :disabled="loading">{{loading?'加载中…':'刷新关系'}}</button>
  </div>
  <p v-if="error" role="alert" class="pipeline-relation-warning">{{error}}</p>
  <p v-else-if="!loading&&!relations.length" class="model-relations-empty">暂无已保存的 JOIN / UNION 数据集关系。</p>
  <div v-else class="model-relations-list">
   <article v-for="relation in relations" :key="relation.datasetId" class="model-relations-entry">
    <header>
     <strong>{{relation.datasetName}}</strong>
     <span class="model-relation-arrow">→</span>
     <strong>{{relation.targetDatasetName}}</strong>
     <small>{{relation.mode==='JOIN'?relation.joinType+' JOIN':'UNION'}} · 版本 {{relation.revision}}</small>
     <span class="model-relations-status" :class="'model-relations-'+relation.status.toLowerCase()">
      {{relation.status==='ACTIVE'?'有效':relation.status==='STALE'?'源已变更':'配置失效'}}
     </span>
    </header>
    <div v-if="relation.fields.length" class="model-relations-fields">
     <span v-for="(pair,index) in relation.fields" :key="index"
      :class="{'model-relations-bad-pair':!pair.compatible}">
      {{pair.leftName}} ↔ {{pair.rightName}}
      <small v-if="!pair.compatible">字段失效或类型不匹配</small>
     </span>
    </div>
    <p v-if="relation.reason" role="status">{{relation.reason}}</p>
    <footer>
     <span v-if="relation.mode==='JOIN'">右表投影：{{relation.outputFields}} 字段</span>
     <button type="button" @click="emit('open-pipeline',relation.datasetId)">查看加工步骤</button>
    </footer>
   </article>
  </div>
  <p class="model-relations-note">仅展示保存的关系配置；不代表数据库外键、跨连接关系或完整多表语义模型。</p>
 </section>
</template>
