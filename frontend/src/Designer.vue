<script setup lang="ts">
import {ref,computed,watch,onMounted,onBeforeUnmount,nextTick} from 'vue'
import {GridStack} from 'gridstack'
import 'gridstack/dist/gridstack.min.css'
import Moveable from 'moveable'
import {ElMessage,ElMessageBox} from 'element-plus'
import {api,post,put,type Widget,type Dataset,type Dashboard} from './api'
import ChartWidget from './ChartWidget.vue'
const props=defineProps<{id:string,readonly?:boolean}>()
const emit=defineEmits<{(e:'back'):void}>()
const report=ref<Dashboard|null>(null),datasets=ref<Dataset[]>([]),widgets=ref<Widget[]>([])
const name=ref(''),mode=ref<'grid'|'free'>('grid'),theme=ref('light'),selectedId=ref(''),tab=ref('data')
const selected=computed(()=>widgets.value.find(x=>x.id===selectedId.value))
const currentDataset=computed(()=>datasets.value.find(x=>x.id===selected.value?.datasetId))
const fields=computed(()=>currentDataset.value?.fields||[])
const numericFields=computed(()=>fields.value.filter(f=>f.numeric))
const column=ref(''),preview=ref(false),busy=ref(false),dirty=ref(false),globalFilters=ref<any[]>([])
const historyVisible=ref(false),versions=ref<{version_no:number;name:string;mode:string;created_at:string}[]>([])
const copiedStyle=ref<Record<string,any>|null>(null)
const gridEl=ref<HTMLElement|null>(null),freeEl=ref<HTMLElement|null>(null)
let grid:GridStack|null=null,moveable:Moveable|null=null
let snapshots:string[]=[],future:string[]=[]
const chartTypes=[{type:'metric',label:'指标卡',icon:'◈'},{type:'bar',label:'柱状图',icon:'▥'},{type:'hbar',label:'条形图',icon:'▤'},{type:'line',label:'折线图',icon:'⌁'},{type:'area',label:'面积图',icon:'▰'},{type:'pie',label:'饼图',icon:'◕'},{type:'donut',label:'环形图',icon:'◎'},{type:'scatter',label:'散点图',icon:'⁘'},{type:'combo',label:'组合图',icon:'▥⌁'},{type:'table',label:'明细表',icon:'▦'},{type:'pivot',label:'交叉表',icon:'▦+'},{type:'filter',label:'筛选器',icon:'▽'},{type:'text',label:'文本',icon:'T'}]
function message(e:any){ElMessage.error(e?.message||String(e))}
async function load(){
 busy.value=true
 try{
  const [dash,ds]=await Promise.all([api<Dashboard>('/dashboards/'+props.id+(props.readonly?'?published=true':'')),api<Dataset[]>('/datasets')])
  report.value=dash;datasets.value=ds;name.value=dash.name;mode.value=dash.content?.mode||dash.mode;theme.value=dash.content?.theme||'light';widgets.value=(dash.content?.widgets||[]).map(w=>({...w,style:w.style||{},columnStyles:w.columnStyles||{},columns:w.columns||[]}))
  dirty.value=false;await nextTick();setupCanvas()
 }catch(e){message(e)}finally{busy.value=false}
}
const snapshot=()=>JSON.stringify({widgets:widgets.value,mode:mode.value,theme:theme.value})
function pushHistory(){snapshots.push(snapshot());if(snapshots.length>35)snapshots.shift();future=[];dirty.value=true}
function applySnapshot(raw:string){const state=JSON.parse(raw);widgets.value=state.widgets;mode.value=state.mode;theme.value=state.theme;dirty.value=true;nextTick(setupCanvas)}
function undo(){if(!snapshots.length)return;future.push(snapshot());applySnapshot(snapshots.pop()!)}
function redo(){if(!future.length)return;snapshots.push(snapshot());applySnapshot(future.pop()!)}
function create(type:string){
 if(props.readonly)return
 pushHistory()
 const count=widgets.value.length
 const widget:Widget={id:crypto.randomUUID(),type,title:chartTypes.find(x=>x.type===type)?.label||'新组件',datasetId:'',dimension:'',columnDimension:'',measure:'',secondMeasure:'',agg:'SUM',columns:[],x:(count%3)*4,y:Math.floor(count/3)*5,w:type==='table'?8:type==='metric'?3:4,h:type==='table'?6:type==='metric'?3:5,px:40+(count%3)*320,py:45+Math.floor(count/3)*280,width:type==='table'?690:type==='metric'?240:400,height:type==='table'?320:type==='metric'?170:280,style:{background:'#ffffff',color:'#364152',chartColor:'#4268e8',legendPosition:'bottom',showXAxis:true,showYAxis:true,showGrid:true,labelRotate:0,radius:10,padding:12,fontSize:13,showTitle:true,legend:true,rowHeight:36,zebra:true,headerBg:'#f4f6fb',text:''},columnStyles:{}}
 widgets.value.push(widget);selectedId.value=widget.id;column.value=''
 nextTick(()=>{if(mode.value==='grid'&&grid){const el=gridEl.value?.querySelector('[gs-id="'+widget.id+'"]') as HTMLElement;if(el)grid.makeWidget(el)}else setupMoveable()})
}
function removeWidget(){if(!selected.value)return;pushHistory();const id=selectedId.value;if(grid){const el=gridEl.value?.querySelector('[gs-id="'+id+'"]') as HTMLElement;if(el)grid.removeWidget(el,false)}widgets.value=widgets.value.filter(w=>w.id!==id);selectedId.value='';nextTick(setupCanvas)}
function copyStyle(){if(!selected.value)return;copiedStyle.value=JSON.parse(JSON.stringify(selected.value.style));ElMessage.success('组件样式已复制')}
function pasteStyle(){if(!selected.value||!copiedStyle.value)return;pushHistory();selected.value.style={...selected.value.style,...JSON.parse(JSON.stringify(copiedStyle.value))};dirty.value=true;ElMessage.success('已粘贴样式')}
function changeLayer(front:boolean){if(!selected.value||mode.value!=='free')return;pushHistory();const levels=widgets.value.filter(w=>w.id!==selectedId.value).map(w=>Number(w.style?.zIndex)||2);selected.value.style.zIndex=front?Math.min(999,Math.max(2,...levels)+1):Math.max(1,Math.min(2,...levels)-1);dirty.value=true}
function duplicate(){if(!selected.value)return;pushHistory();const w=JSON.parse(JSON.stringify(selected.value)) as Widget;w.id=crypto.randomUUID();w.title+=' 副本';w.x=Math.min(12-w.w,w.x+1);w.y+=1;w.px+=35;w.py+=35;widgets.value.push(w);selectedId.value=w.id;nextTick(setupCanvas)}
function chooseDataset(){
 if(!selected.value)return
 const ds=datasets.value.find(x=>x.id===selected.value!.datasetId)
 selected.value.dimension=ds?.fields.find(f=>!f.numeric)?.name||ds?.fields[0]?.name||''
 selected.value.measure=ds?.fields.find(f=>f.numeric)?.name||''
 selected.value.secondMeasure=ds?.fields.filter(f=>f.numeric)[1]?.name||''
 selected.value.columnDimension=ds?.fields.filter(f=>!f.numeric)[1]?.name||''
 selected.value.columns=ds?.fields.slice(0,6).map(f=>f.name)||[]
 selected.value.columnStyles={};selected.value.pivotMeasures=[];selected.value.drillDimensions=[];selected.value.sort='';selected.value.topN=0
 dirty.value=true
}
async function save():Promise<boolean>{
 if(!report.value||props.readonly)return false
 busy.value=true
 try{
  const res=await put<{revision:number}>('/dashboards/'+props.id,{name:name.value,mode:mode.value,revision:report.value.revision,content:{mode:mode.value,theme:theme.value,widgets:widgets.value}})
  report.value.revision=res.revision;dirty.value=false;ElMessage.success('保存成功');return true
 }catch(e){message(e);return false}finally{busy.value=false}
}
async function publish(){
 if(!report.value)return
 const saved=await save();if(!saved)return
 try{await post('/dashboards/'+props.id+'/publish',{});report.value.status='published';ElMessage.success('发布成功，可打开查看页')}catch(e){message(e)}
}
function openPublished(){window.open(window.location.origin+'/#/view/'+props.id,'_blank')}
async function openHistory(){
 try{versions.value=await api('/dashboards/'+props.id+'/versions');historyVisible.value=true}
 catch(e){message(e)}
}
async function restoreVersion(v:number){
 if(!report.value)return
 try{
  await ElMessageBox.confirm('将历史版本恢复到当前草稿，当前未保存修改会丢失。发布版本不受影响。','确认恢复历史版本',{type:'warning'})
  await post('/dashboards/'+props.id+'/versions/'+v+'/restore',{revision:report.value.revision})
  historyVisible.value=false;selectedId.value='';snapshots=[];future=[];await load()
  ElMessage.success('已恢复到第 '+v+' 版草稿，请检查后重新发布')
 }catch(e:any){if(e!=='cancel')message(e)}
}
function clearFilters(){globalFilters.value=[]}
function switchMode(to:'grid'|'free'){
 if(to===mode.value)return;pushHistory()
 if(to==='free')widgets.value.forEach(w=>{w.px=w.x*96+25;w.py=w.y*66+20;w.width=Math.max(160,w.w*94-16);w.height=Math.max(120,w.h*64-12)})
 else widgets.value.forEach(w=>{w.x=Math.min(11,Math.max(0,Math.round(w.px/96)));w.w=Math.min(12-w.x,Math.max(2,Math.round(w.width/96)));w.y=Math.max(0,Math.round(w.py/66));w.h=Math.max(2,Math.round(w.height/66))})
 mode.value=to;selectedId.value='';nextTick(setupCanvas)
}
function setupCanvas(){
 if(grid){grid.destroy(false);grid=null}
 if(moveable){moveable.destroy();moveable=null}
 if(mode.value==='grid'&&gridEl.value){
  grid=GridStack.init({column:12,cellHeight:60,margin:8,float:true,animate:false,disableDrag:!!props.readonly,disableResize:!!props.readonly,handle:'.widget-drag-handle'},gridEl.value)
  grid.on('dragstart resizestart',()=>{if(!props.readonly)pushHistory()})
  grid.on('change',(_e,items)=>{if(props.readonly)return;for(const item of items||[]){const w=widgets.value.find(z=>z.id===item.id);if(w){w.x=item.x||0;w.y=item.y||0;w.w=item.w||4;w.h=item.h||4}}dirty.value=true})
 }else setupMoveable()
}
function setupMoveable(){
 if(mode.value!=='free'||!freeEl.value||props.readonly)return
 if(moveable){moveable.destroy();moveable=null}
 const item=selected.value,el=item?freeEl.value.querySelector('[data-widget-id="'+item.id+'"]') as HTMLElement:null
 if(!el)return
 moveable=new Moveable(freeEl.value,{target:el,draggable:true,resizable:true,snappable:true,origin:false,keepRatio:false})
 moveable.on('dragStart',()=>pushHistory())
 moveable.on('resizeStart',()=>pushHistory())
 moveable.on('drag',e=>{const w=selected.value;if(!w)return;w.px=Math.max(0,Math.round(w.px+e.delta[0]));w.py=Math.max(0,Math.round(w.py+e.delta[1]));dirty.value=true})
 moveable.on('resize',e=>{const w=selected.value;if(!w)return;w.width=Math.max(160,Math.round(e.width));w.height=Math.max(100,Math.round(e.height));w.px=Math.max(0,Math.round(w.px+e.drag.delta[0]));w.py=Math.max(0,Math.round(w.py+e.drag.delta[1]));dirty.value=true})
}
watch(selectedId,()=>{column.value='';nextTick(setupMoveable)})
watch(mode,()=>nextTick(setupCanvas))
function changeColumnStyle(key:string,value:any){if(!selected.value||!column.value)return;selected.value.columnStyles||={};selected.value.columnStyles[column.value]||={};selected.value.columnStyles[column.value][key]=value;dirty.value=true}
function addRule(){if(!selected.value||!column.value)return;const rules=[...(colConfig.value.conditionalRules||[])];rules.push({op:'gte',value:0,color:'#15a779',background:'#ecfdf5'});changeColumnStyle('conditionalRules',rules)}
function editRule(index:number,key:string,value:any){const rules=[...(colConfig.value.conditionalRules||[])].map((v:any)=>({...v}));rules[index][key]=value;changeColumnStyle('conditionalRules',rules)}
function removeRule(index:number){const rules=[...(colConfig.value.conditionalRules||[])];rules.splice(index,1);changeColumnStyle('conditionalRules',rules)}
const colConfig=computed(()=>selected.value?.columnStyles?.[column.value]||{})
function onFilter(filter:any){
 globalFilters.value=globalFilters.value.filter(f=>f.field!==filter.field)
 if(filter.op==='in'&&Array.isArray(filter.values)&&filter.values.length)globalFilters.value.push(filter)
 else if(filter.value!==''&&filter.value!==undefined)globalFilters.value.push(filter)
}
function handleKey(e:KeyboardEvent){
 if(['INPUT','TEXTAREA','SELECT'].includes((e.target as HTMLElement)?.tagName))return
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();undo()}
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='y'){e.preventDefault();redo()}
 if(e.key==='Delete'&&selected.value&&!props.readonly)removeWidget()
}
onMounted(()=>{load();window.addEventListener('keydown',handleKey)})
onBeforeUnmount(()=>{grid?.destroy(false);moveable?.destroy();window.removeEventListener('keydown',handleKey)})
</script>
<template>
 <div class="designer">
  <header class="designer-toolbar">
   <div class="toolbar-left"><button class="icon-button" title="返回列表" @click="emit('back')">←</button><div class="toolbar-divider"></div><strong>Insight <span>BI</span></strong><span class="crumb">/</span><input v-model="name" :disabled="readonly" class="report-title" aria-label="报表名称" @input="dirty=true"/><span class="save-indicator">{{dirty?'● 未保存':'✓ 已保存'}}</span></div>
   <div class="toolbar-right">
    <button class="subtle-button" :disabled="!snapshots.length||readonly" @click="undo">↶ 撤销</button><button class="subtle-button" :disabled="!future.length||readonly" @click="redo">↷ 重做</button><span class="toolbar-divider"></span>
    <button class="subtle-button" :class="{selected:mode==='grid'}" :disabled="readonly" @click="switchMode('grid')">▦ 常规仪表板</button><button class="subtle-button" :class="{selected:mode==='free'}" :disabled="readonly" @click="switchMode('free')">▧ 自由画布</button>
    <span class="toolbar-divider"></span><button class="subtle-button" v-if="!readonly" @click="preview=!preview">{{preview?'退出预览':'预览'}}</button>
    <button class="subtle-button" v-if="!readonly" @click="openHistory">历史版本</button><button class="subtle-button" v-if="report?.status==='published'" @click="openPublished">查看发布版 ↗</button>
    <button class="primary-soft" v-if="!readonly" :disabled="busy" @click="save">保存</button><button class="primary-button" v-if="!readonly" :disabled="busy" @click="publish">发布</button>
   </div>
  </header>
  <div v-if="busy&&!report" class="page-loader">正在加载仪表板…</div>
  <div v-else class="designer-main">
   <aside v-if="!readonly&&!preview" class="widget-palette">
    <h4>组件库</h4><p class="palette-caption">选择组件添加到画布</p><div class="palette-heading">数据分析</div>
    <div class="widget-gallery"><button v-for="item in chartTypes" :key="item.type" class="widget-tile" @click="create(item.type)"><span class="glyph">{{item.icon}}</span><span>{{item.label}}</span></button></div>
    <div class="palette-heading">画布设置</div><div class="palette-control"><span>背景主题</span><el-select v-model="theme" size="small" @change="dirty=true"><el-option label="明亮主题" value="light"/><el-option label="深色主题" value="dark"/></el-select></div>
    <div class="palette-heading">组件图层 ({{widgets.length}})</div>
    <div class="layer-entry" v-for="item in widgets" :key="item.id" :class="{active:selectedId===item.id}" @click="selectedId=item.id"><span>▣</span><span class="truncate">{{item.title}}</span></div>
   </aside>
   <main class="canvas-area" :class="{dark:theme==='dark'}" @mousedown.self="selectedId=''">
    <div class="canvas-info"><span>{{mode==='grid'?'12 列栅格布局':'自由画布 1600 × 900'}}</span><span>{{widgets.length}} 个组件 · {{readonly||preview?'查看模式':'编辑模式'}} <template v-if="globalFilters.length"> · 已筛选：<span v-for="f in globalFilters" :key="f.field">{{f.field}}={{f.op==='in'?(f.values||[]).join('、'):f.value}} </span><button class="subtle-button" @click="clearFilters">清除筛选</button></template></span></div>
    <div class="canvas-wrap" :class="{preview:readonly||preview}">
     <div v-show="mode==='grid'" ref="gridEl" class="grid-stack bi-grid">
      <div v-for="w in mode==='grid'?widgets:[]" :key="w.id" class="grid-stack-item" :gs-id="w.id" :gs-x="w.x" :gs-y="w.y" :gs-w="w.w" :gs-h="w.h" @click.stop="selectedId=w.id">
       <div class="grid-stack-item-content widget-shell" :class="{active:selectedId===w.id&&!readonly&&!preview}" :style="{background:w.style?.background||'#fff',color:w.style?.color||'#364152',border:'1px solid '+(w.style?.borderColor||'#e6e9f0'),boxShadow:w.style?.shadow===false?'none':'0 3px 14px #1027520c',borderRadius:(w.style?.radius??10)+'px',padding:(w.style?.padding??12)+'px'}">
        <div v-if="w.style?.showTitle!==false" class="widget-head widget-drag-handle"><strong>{{w.title}}</strong><button v-if="!readonly&&!preview" @click.stop="selectedId=w.id;tab='style'">···</button></div>
        <ChartWidget :widget="w" :datasets="datasets" :filters="globalFilters" :readonly="readonly||preview" @filter="onFilter"/>
       </div>
      </div>
     </div>
     <div v-if="mode==='free'" ref="freeEl" class="free-canvas" :class="{dark:theme==='dark'}">
      <div v-for="w in widgets" :key="w.id" class="free-item widget-shell" :data-widget-id="w.id" :class="{active:selectedId===w.id&&!readonly&&!preview}" :style="{left:w.px+'px',top:w.py+'px',width:w.width+'px',height:w.height+'px',background:w.style?.background||'#fff',color:w.style?.color||'#364152',border:'1px solid '+(w.style?.borderColor||'#e6e9f0'),boxShadow:w.style?.shadow===false?'none':'0 3px 14px #1027520c',borderRadius:(w.style?.radius??10)+'px',padding:(w.style?.padding??12)+'px',zIndex:selectedId===w.id&&!readonly&&!preview?1001:(w.style?.zIndex??2)}" @mousedown.stop="selectedId=w.id">
       <div v-if="w.style?.showTitle!==false" class="widget-head"><strong>{{w.title}}</strong></div>
       <ChartWidget :widget="w" :datasets="datasets" :filters="globalFilters" :readonly="readonly||preview" @filter="onFilter"/>
      </div>
     </div>
     <div v-if="widgets.length===0" class="canvas-empty"><div class="canvas-empty-icon">▦</div><h3>从左侧选择一个组件</h3><p>搭建自己的数据报表。不会自动生成演示内容。</p></div>
    </div>
   </main>
   <aside v-if="!readonly&&!preview" class="property-panel">
    <template v-if="selected">
     <div class="property-header"><strong>组件属性</strong><div><button title="复制样式" @click="copyStyle">复制样式</button><button title="粘贴样式" :disabled="!copiedStyle" @click="pasteStyle">粘贴样式</button><button title="复制组件" @click="duplicate">▣</button><button title="删除组件" @click="removeWidget">▤</button></div></div>
     <div class="property-tabs"><button v-for="t in [{k:'data',n:'数据'},{k:'style',n:'样式'},{k:'event',n:'交互'}]" :key="t.k" :class="{active:tab===t.k}" @click="tab=t.k">{{t.n}}</button></div>
     <div class="properties-scroll">
      <div class="form-field"><label>组件名称</label><el-input v-model="selected.title" @input="dirty=true"/></div>
      <template v-if="tab==='data'">
       <div v-if="selected.type!=='text'" class="form-field"><label>关联数据集</label><el-select v-model="selected.datasetId" filterable clearable placeholder="选择数据集" @change="chooseDataset"><el-option v-for="ds in datasets" :key="ds.id" :label="ds.name" :value="ds.id"/></el-select></div>
       <div v-if="selected.datasetId&&selected.type!=='metric'&&selected.type!=='table'&&selected.type!=='text'" class="form-field"><label>{{selected.type==='filter'?'筛选字段':'维度 / X 轴'}}</label><el-select v-model="selected.dimension" filterable @change="dirty=true"><el-option v-for="f in fields" :key="f.name" :value="f.name" :label="f.name"/></el-select></div>
       <div v-if="selected.type==='pivot'&&selected.datasetId" class="form-field"><label>列分组维度</label><el-select v-model="selected.columnDimension" filterable @change="dirty=true"><el-option v-for="f in fields" :key="f.name" :label="f.name" :value="f.name"/></el-select></div>
       <div v-if="selected.datasetId&&!['table','text','filter'].includes(selected.type)" class="form-field"><label>指标 / Y 轴</label><el-select v-model="selected.measure" filterable @change="dirty=true"><el-option v-for="f in numericFields" :key="f.name" :value="f.name" :label="f.name"/></el-select></div>
       <div v-if="selected.type==='pivot'&&selected.datasetId" class="form-field"><label>附加数值指标</label><el-select v-model="selected.pivotMeasures" multiple filterable collapse-tags clearable placeholder="添加第二、第三指标" @change="dirty=true"><el-option v-for="f in numericFields.filter(f=>f.name!==selected?.measure)" :key="f.name" :label="f.name" :value="f.name"/></el-select><small>每个列维度分别展示各项指标，可按指标计算行、列合计。</small></div>
       <div v-if="selected.type==='combo'&&selected.datasetId" class="form-field"><label>第二指标（折线 / 右轴）</label><el-select v-model="selected.secondMeasure" filterable clearable @change="dirty=true"><el-option v-for="f in numericFields" :key="f.name" :label="f.name" :value="f.name"/></el-select></div>
       <div v-if="selected.datasetId&&!['table','text','filter'].includes(selected.type)" class="form-field"><label>聚合方式</label><el-select v-model="selected.agg" @change="dirty=true"><el-option v-for="v in ['SUM','AVG','COUNT','MIN','MAX']" :key="v" :value="v" :label="({SUM:'求和',AVG:'平均值',COUNT:'计数',MIN:'最小值',MAX:'最大值'} as any)[v]"/></el-select></div>
       <template v-if="selected.datasetId&&['bar','hbar','line','area','pie','donut','combo'].includes(selected.type)">
        <div class="prop-section">钻取与排序</div>
        <div class="form-field"><label>下钻维度（按选择顺序逐级进入）</label><el-select v-model="selected.drillDimensions" multiple filterable clearable collapse-tags placeholder="可选：地区 → 城市 → 门店" @change="dirty=true"><el-option v-for="f in fields.filter(f=>f.name!==selected?.dimension)" :key="f.name" :label="f.name" :value="f.name"/></el-select><small>点击图表数据项钻取下一维度，顶部面包屑可返回；只筛选当前组件，不影响其他组件。</small></div>
        <div class="form-field"><label>结果排序字段</label><el-select v-model="selected.sort" clearable placeholder="原始顺序" @change="dirty=true"><el-option v-if="selected.dimension" :label="selected.dimension+'（维度）'" :value="selected.dimension"/><el-option v-if="selected.measure" :label="selected.measure+'（指标）'" :value="selected.measure"/></el-select></div>
        <div class="form-field"><label>排序方向</label><el-select v-model="selected.direction" placeholder="降序" @change="dirty=true"><el-option label="降序" value="desc"/><el-option label="升序" value="asc"/></el-select></div>
        <div class="form-field"><label>Top N（0 表示不限制）</label><el-input-number v-model="selected.topN" :min="0" :max="300" :step="5" @change="dirty=true"/></div>
       </template>
       <div v-if="selected.datasetId&&selected.type==='table'" class="form-field"><label>显示字段 / 排列顺序</label><el-select v-model="selected.columns" multiple filterable collapse-tags :max-collapse-tags="3" @change="dirty=true"><el-option v-for="f in fields" :key="f.name" :label="f.name" :value="f.name"/></el-select><small>字段按选择顺序在明细表中显示</small></div>
       <div v-if="selected.type==='text'" class="form-field"><label>文本内容</label><el-input v-model="selected.style.text" type="textarea" :rows="5" @input="dirty=true"/></div>
       <div v-if="selected.datasetId" class="field-summary"><span>字段数量</span><strong>{{fields.length}}</strong><span>数值指标</span><strong>{{numericFields.length}}</strong></div>
      </template>
      <template v-if="tab==='style'">
       <div class="prop-section">基础样式</div>
       <div class="form-field"><label>背景颜色</label><el-color-picker v-model="selected.style.background" @change="dirty=true"/></div>
       <div class="form-field"><label>文字颜色</label><el-color-picker v-model="selected.style.color" @change="dirty=true"/></div>
       <div class="form-field"><label>字号 (px)</label><el-slider v-model="selected.style.fontSize" :min="11" :max="24" @change="dirty=true"/></div>
       <div class="form-field"><label>边框颜色</label><el-color-picker v-model="selected.style.borderColor" @change="dirty=true"/></div>
       <div class="form-field"><label>显示阴影</label><el-switch v-model="selected.style.shadow" @change="dirty=true"/></div>
       <div class="form-field"><label>显示标题</label><el-switch v-model="selected.style.showTitle" @change="dirty=true"/></div>
       <div class="form-field"><label>内边距 (px)</label><el-slider v-model="selected.style.padding" :min="0" :max="32" @change="dirty=true"/></div>
       <div class="form-field"><label>圆角 (px)</label><el-slider v-model="selected.style.radius" :min="0" :max="30" @change="dirty=true"/></div>
       <template v-if="!['table','pivot','text','filter','metric'].includes(selected.type)">
        <div class="prop-section">图表专属样式</div><div class="form-field"><label>主题颜色</label><el-color-picker v-model="selected.style.chartColor" @change="dirty=true"/></div>
        <div class="form-field"><label>显示图例</label><el-switch v-model="selected.style.legend" @change="dirty=true"/></div><div class="form-field"><label>数据标签</label><el-switch v-model="selected.style.dataLabel" @change="dirty=true"/></div>
        <div class="form-field"><label>图例位置</label><el-select v-model="selected.style.legendPosition" placeholder="底部" @change="dirty=true"><el-option label="底部" value="bottom"/><el-option label="顶部" value="top"/><el-option label="左侧" value="left"/><el-option label="右侧" value="right"/></el-select></div>
        <template v-if="!['pie','donut','scatter'].includes(selected.type)">
         <div class="form-field"><label>显示 X 轴</label><el-switch v-model="selected.style.showXAxis" :active-value="true" :inactive-value="false" @change="dirty=true"/></div>
         <div class="form-field"><label>显示 Y 轴</label><el-switch v-model="selected.style.showYAxis" :active-value="true" :inactive-value="false" @change="dirty=true"/></div>
         <div class="form-field"><label>背景网格线</label><el-switch v-model="selected.style.showGrid" @change="dirty=true"/></div>
         <div class="form-field"><label>X 轴标签旋转角度</label><el-slider v-model="selected.style.labelRotate" :min="0" :max="90" @change="dirty=true"/></div>
        </template>
        <div class="form-field" v-if="['line','area'].includes(selected.type)"><label>平滑曲线</label><el-switch v-model="selected.style.smooth" @change="dirty=true"/></div>
        <div class="form-field" v-if="['bar','hbar'].includes(selected.type)"><label>柱形圆角</label><el-slider v-model="selected.style.barRadius" :min="0" :max="18" @change="dirty=true"/></div>
       </template>
       <template v-if="selected.type==='filter'"><div class="prop-section">筛选器样式</div><div class="form-field"><label>允许多选</label><el-switch v-model="selected.style.filterMultiple" @change="dirty=true"/></div><p class="helper-text">多选时使用参数化 IN 查询，最多选择 50 项。</p></template>
       <template v-if="selected.type==='pivot'"><div class="prop-section">交叉表样式</div><div class="form-field"><label>表头背景</label><el-color-picker v-model="selected.style.headerBg" @change="dirty=true"/></div><div class="form-field"><label>显示小计 / 总计</label><el-switch v-model="selected.style.showTotal" @change="dirty=true"/></div></template>
       <template v-if="selected.type==='table'">
        <div class="prop-section">表格整体样式</div>
        <div class="form-field"><label>表头背景</label><el-color-picker v-model="selected.style.headerBg" @change="dirty=true"/></div>
        <div class="form-field"><label>隔行斑马纹</label><el-switch v-model="selected.style.zebra" @change="dirty=true"/></div>
        <div class="form-field"><label>行高 (px)</label><el-slider v-model="selected.style.rowHeight" :min="28" :max="64" @change="dirty=true"/></div>
        <div class="form-field"><label>显示本次查询合计</label><el-switch v-model="selected.style.showTotal" @change="dirty=true"/></div>
        <div class="prop-section">字段列属性</div>
        <div class="form-field"><label>选择需要单独调整的列</label><el-select v-model="column" placeholder="选择一列"><el-option v-for="c in selected.columns" :key="c" :label="c" :value="c"/></el-select></div>
        <template v-if="column">
         <div class="form-field"><label>列标题别名</label><el-input :model-value="colConfig.alias||''" @update:model-value="v=>changeColumnStyle('alias',v)"/></div>
         <div class="form-field"><label>列宽 (px)</label><el-input-number :model-value="colConfig.width||125" :min="60" :max="480" @update:model-value="v=>changeColumnStyle('width',v)"/></div>
         <div class="form-field"><label>对齐方式</label><el-select :model-value="colConfig.align||'left'" @update:model-value="v=>changeColumnStyle('align',v)"><el-option label="左对齐" value="left"/><el-option label="居中" value="center"/><el-option label="右对齐" value="right"/></el-select></div>
         <div class="form-field"><label>冻结此列</label><el-switch :model-value="!!colConfig.frozen" @update:model-value="v=>changeColumnStyle('frozen',v)"/></div>
         <div class="form-field"><label>数值格式</label><el-select :model-value="colConfig.format||'text'" @update:model-value="v=>changeColumnStyle('format',v)"><el-option label="原样显示" value="text"/><el-option label="数字" value="number"/><el-option label="货币 (¥)" value="currency"/><el-option label="百分比" value="percent"/></el-select></div>
         <div class="form-field"><label>保留小数位数</label><el-input-number :model-value="colConfig.decimals??2" :min="0" :max="8" @update:model-value="v=>changeColumnStyle('decimals',v)"/></div>
         <div class="form-field"><label>单元格文字颜色</label><el-color-picker :model-value="colConfig.color||'#364152'" @update:model-value="v=>changeColumnStyle('color',v)"/></div>
         <div class="form-field"><label>单元格背景</label><el-color-picker :model-value="colConfig.background||'#ffffff'" @update:model-value="v=>changeColumnStyle('background',v)"/></div>
         <div class="form-field"><label>条件格式阈值（≥）</label><el-input :model-value="colConfig.threshold??''" placeholder="不填则禁用" @update:model-value="v=>changeColumnStyle('threshold',v)"/></div>
         <div class="form-field"><label>≥ 阈值文字颜色</label><el-color-picker :model-value="colConfig.positiveColor||'#15a779'" @update:model-value="v=>changeColumnStyle('positiveColor',v)"/></div>
         <div class="form-field"><label>＜ 阈值文字颜色</label><el-color-picker :model-value="colConfig.negativeColor||'#dc626b'" @update:model-value="v=>changeColumnStyle('negativeColor',v)"/></div>
         <div class="prop-section">多条件格式规则（按顺序匹配）</div>
         <div v-for="(rule,idx) in (colConfig.conditionalRules||[])" :key="idx" class="form-field" style="border:1px solid #e6e9f0;border-radius:8px;padding:9px">
          <div style="display:flex;gap:5px;align-items:center"><el-select :model-value="rule.op||'gte'" style="width:125px" @update:model-value="v=>editRule(Number(idx),'op',v)"><el-option label="大于等于" value="gte"/><el-option label="大于" value="gt"/><el-option label="小于等于" value="lte"/><el-option label="小于" value="lt"/><el-option label="等于" value="eq"/><el-option label="不等于" value="ne"/></el-select>
          <el-input :model-value="String(rule.value??'')" placeholder="比较值" @update:model-value="v=>editRule(Number(idx),'value',v)"/>
          <button title="删除规则" @click="removeRule(Number(idx))">×</button></div>
          <div style="display:flex;align-items:center;gap:12px;margin-top:6px"><small>文字</small><el-color-picker :model-value="rule.color||'#15a779'" @update:model-value="v=>editRule(Number(idx),'color',v)"/><small>背景</small><el-color-picker :model-value="rule.background||'#ecfdf5'" @update:model-value="v=>editRule(Number(idx),'background',v)"/></div>
         </div>
         <button class="primary-soft" @click="addRule">＋ 添加条件格式规则</button>
        </template>
       </template>
       <div class="prop-section">画布定位</div>
       <div v-if="mode==='free'" class="form-field"><label>图层顺序</label><div style="display:flex;gap:8px"><button class="primary-soft" @click="changeLayer(true)">置于顶层</button><button class="primary-soft" @click="changeLayer(false)">置于底层</button></div></div><div class="position-grid" v-if="mode==='free'"><label>X <el-input-number v-model="selected.px" :min="0" @change="dirty=true"/></label><label>Y <el-input-number v-model="selected.py" :min="0" @change="dirty=true"/></label><label>宽 <el-input-number v-model="selected.width" :min="160" @change="dirty=true"/></label><label>高 <el-input-number v-model="selected.height" :min="100" @change="dirty=true"/></label></div>
       <p class="helper-text" v-else>栅格模式下可直接拖动标题栏和组件右下角调整布局。</p>
      </template>
      <template v-if="tab==='event'"><div class="prop-section">组件联动</div><p class="helper-text">筛选器组件和图表可以联动相同字段的数据集。</p><div class="form-field" v-if="!['table','pivot','metric','text','filter'].includes(selected.type)"><label>点击图表数据项联动筛选</label><el-switch v-model="selected.style.linkOnClick" @change="dirty=true"/></div><p class="helper-text">启用后点击图表的数据项，将所选维度值应用到同一报表中具有该字段的所有组件。可通过画布上方的“清除筛选”恢复。</p></template>
     </div>
    </template>
    <div v-else class="nothing-selected"><div class="selection-icon">⚙</div><h4>属性编辑器</h4><p>选中画布组件后，可以调整数据绑定、图表样式和字段级属性。</p></div>
   </aside>
  </div>
  <el-dialog v-model="historyVisible" title="报表历史发布版本" width="620px" append-to-body>
   <div v-if="!versions.length" class="helper-text">还没有历史版本。第一次发布后会自动生成可恢复的快照。</div>
   <div v-else class="resource-table"><table><thead><tr><th>版本</th><th>发布时名称</th><th>画布类型</th><th>发布时间</th><th>操作</th></tr></thead><tbody>
    <tr v-for="v in versions" :key="v.version_no"><td>V{{v.version_no}}</td><td>{{v.name}}</td><td>{{v.mode==='free'?'自由画布':'常规仪表板'}}</td><td>{{v.created_at}}</td><td><button class="primary-soft" @click="restoreVersion(v.version_no)">恢复为草稿</button></td></tr>
   </tbody></table></div>
  </el-dialog>
 </div>
</template>
