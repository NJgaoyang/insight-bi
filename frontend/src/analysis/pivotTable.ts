import type {AnalysisV2Bindings, QueryV2Result} from '../api'

export interface PivotAxis {key:string; value:unknown; label:string}
export type PivotMeasure = QueryV2Result['columns'][number]
export interface PivotModel {
 rows:PivotAxis[]; columns:PivotAxis[]; measures:PivotMeasure[]
 cells:Map<string,unknown>
 rowTotals:Map<string,unknown>; columnTotals:Map<string,unknown>; grandTotals:Map<string,unknown>
 additive:Set<string>; error:string; warning:string
}
export const PIVOT_MAX_ROWS=100
export const PIVOT_MAX_COLUMNS=24
const collator=new Intl.Collator('zh-CN',{numeric:true,sensitivity:'base'})
const empty=():PivotModel=>({rows:[],columns:[],measures:[],cells:new Map(),
 rowTotals:new Map(),columnTotals:new Map(),grandTotals:new Map(),
 additive:new Set(),error:'',warning:''})
const key=(value:unknown)=>JSON.stringify([value===undefined?null:value])
const label=(value:unknown)=>value==null?'(空值)':String(value)
const cellKey=(row:string,column:string,metric:string)=>JSON.stringify([row,column,metric])
const sumKey=(axis:string,metric:string)=>JSON.stringify([axis,metric])
export function pivotCell(model:PivotModel,row:string,column:string,metric:string):unknown{
 return model.cells.get(cellKey(row,column,metric))??null
}
export function pivotRowTotal(model:PivotModel,row:string,metric:string){
 return model.rowTotals.get(sumKey(row,metric))??null
}
export function pivotColumnTotal(model:PivotModel,column:string,metric:string){
 return model.columnTotals.get(sumKey(column,metric))??null
}
export function pivotGrandTotal(model:PivotModel,metric:string){
 return model.grandTotals.get(metric)??null
}
// Use decimal strings and BigInt rather than floating-point addition for
// server-aggregated DECIMAL groups. Refuse unsafe JS numbers and exponents.
function decimal(value:unknown):{integer:bigint;scale:number}|null{
 if(typeof value==='number'&&(!Number.isFinite(value)||Math.abs(value)>=Number.MAX_SAFE_INTEGER))return null
 if(typeof value!=='number'&&typeof value!=='string')return null
 const text=String(value)
 if(!/^-?\d+(?:\.\d{1,18})?$/.test(text)||text.length>55)return null
 const [whole,fraction='']=text.split('.')
 return {integer:BigInt(whole+fraction),scale:fraction.length}
}
function add(a:string|null,b:unknown):string|null{
 const d=decimal(b)
 if(!d)return a
 const left=a===null?{integer:0n,scale:0}:decimal(a)
 if(!left)return null
 const scale=Math.max(left.scale,d.scale)
 const sum=left.integer*10n**BigInt(scale-left.scale)+d.integer*10n**BigInt(scale-d.scale)
 const negative=sum<0n
 const abs=(negative?-sum:sum).toString().padStart(scale+1,'0')
 return (negative?'-':'')+(scale?abs.slice(0,-scale)+'.'+abs.slice(-scale):abs)
}
function total(values:unknown[]):string|null{
 let acc:string|null=null
 for(const v of values){
  if(v==null)continue // SQL SUM ignores NULL groups
  if(!decimal(v))return null // no pretend 0 / imprecise total
  acc=add(acc,v)
 }
 return acc
}
const sortAxes=(values:PivotAxis[],direction:'asc'|'desc')=>values.sort((a,b)=>{
 if(a.value==null)return b.value==null?0:1
 if(b.value==null)return -1
 const compared=collator.compare(String(a.value),String(b.value))
 return direction==='desc'?-compared:compared
})

/** Only pivot already-aggregated, bounded V2 rows; SQL calculation and ACL stay on the server. */
export function buildPivot(result:QueryV2Result,config:AnalysisV2Bindings):PivotModel{
 const model=empty()
 if(config.dimensions.length!==2||!config.measures.length){
  model.error='交叉表需要恰好两个维度（行、列）及至少一个指标'
  return model
 }
 const [rowBinding,colBinding]=config.dimensions
 const rowColumn=result.columns.find(c=>c.kind==='dimension'&&c.id===rowBinding.fieldId)
 const colColumn=result.columns.find(c=>c.kind==='dimension'&&c.id===colBinding.fieldId)
 model.measures=config.measures.map(m=>result.columns.find(c=>c.kind==='measure'&&c.id===m.id)).filter((m):m is PivotMeasure=>!!m)
 if(!rowColumn||!colColumn||model.measures.length!==config.measures.length||
  new Set(result.columns.map(c=>c.name)).size!==result.columns.length){
  model.error='交叉表字段与查询结果不一致，请检查字段绑定'
  return model
 }
 const rowAxes=new Map<string,PivotAxis>(),colAxes=new Map<string,PivotAxis>()
 for(const record of result.rows){
  if(!Object.prototype.hasOwnProperty.call(record,rowColumn.name)||
    !Object.prototype.hasOwnProperty.call(record,colColumn.name)||
    model.measures.some(m=>!Object.prototype.hasOwnProperty.call(record,m.name))){
   model.error='交叉表查询结果缺少必要字段';return model
  }
  const rv=record[rowColumn.name],cv=record[colColumn.name]
  const rk=key(rv),ck=key(cv)
  if(!rowAxes.has(rk))rowAxes.set(rk,{key:rk,value:rv,label:label(rv)})
  if(!colAxes.has(ck))colAxes.set(ck,{key:ck,value:cv,label:label(cv)})
  for(const metric of model.measures){
   const k=cellKey(rk,ck,metric.id)
   if(model.cells.has(k)){model.error='交叉表返回了重复分组，无法安全展示';return model}
   model.cells.set(k,record[metric.name])
  }
 }
 if(rowAxes.size>PIVOT_MAX_ROWS||colAxes.size>PIVOT_MAX_COLUMNS){
  model.error='交叉表行分组上限 '+PIVOT_MAX_ROWS+'、列分组上限 '+PIVOT_MAX_COLUMNS+'；请添加过滤条件缩小结果'
  return model
 }
 model.rows=sortAxes([...rowAxes.values()],config.pivot?.rowOrder??'asc')
 model.columns=sortAxes([...colAxes.values()],config.pivot?.columnOrder??'asc')
 if(result.truncated){
  model.warning='查询结果已截断；当前仅显示部分分组，合计已禁用'
 }else if(config.pivot?.showTotals!==false){
  // New servers provide exact SQL rollups (including AVG / COUNT DISTINCT);
  // retain the legacy additive-only result path for old deployments.
  if(result.pivotTotals){
   const totals=result.pivotTotals
   if(!totals.exact){
    model.warning=totals.reason||'服务端无法安全生成合计'
    return model
   }
   const serverRows=totals.rows,serverCols=totals.columns,grand=totals.grand
   const rowKeys=new Set(model.rows.map(row=>row.key))
   const colKeys=new Set(model.columns.map(col=>col.key))
   if(!Array.isArray(serverRows)||!Array.isArray(serverCols)||!grand||
      serverRows.length!==model.rows.length||serverCols.length!==model.columns.length){
    model.warning='服务端合计与分组结果不一致，已隐藏合计'
    return model
   }
   const seenRows=new Set<string>(),seenCols=new Set<string>()
   const hasMetrics=(r:Record<string,unknown>)=>model.measures.every(m=>Object.prototype.hasOwnProperty.call(r,m.name))
   for(const r of serverRows){
    const k=key(r.d1)
    if(!rowKeys.has(k)||seenRows.has(k)||!hasMetrics(r)){model.warning='行合计结果异常，已隐藏合计';return model}
    seenRows.add(k)
   }
   for(const c of serverCols){
    const k=key(c.d1)
    if(!colKeys.has(k)||seenCols.has(k)||!hasMetrics(c)){model.warning='列合计结果异常，已隐藏合计';return model}
    seenCols.add(k)
   }
   if(!hasMetrics(grand)){model.warning='总计结果异常，已隐藏合计';return model}
   for(const m of model.measures){
    model.additive.add(m.id) // all metric formulas are recalculated by SQL
    for(const r of serverRows)model.rowTotals.set(sumKey(key(r.d1),m.id),r[m.name])
    for(const c of serverCols)model.columnTotals.set(sumKey(key(c.d1),m.id),c[m.name])
    model.grandTotals.set(m.id,grand[m.name])
   }
   return model
  }
  for(const m of model.measures){
   if(m.additive!==true)continue
   model.additive.add(m.id)
   for(const r of model.rows)
    model.rowTotals.set(sumKey(r.key,m.id),total(model.columns.map(c=>pivotCell(model,r.key,c.key,m.id)))??'')
   for(const c of model.columns)
    model.columnTotals.set(sumKey(c.key,m.id),total(model.rows.map(r=>pivotCell(model,r.key,c.key,m.id)))??'')
   model.grandTotals.set(m.id,total(model.rows.flatMap(r=>model.columns.map(c=>pivotCell(model,r.key,c.key,m.id))))??'')
  }
 }
 return model
}


/** Format SQL-produced decimal strings without converting them to JS Number. */
export function formatPivotTotal(value:unknown,decimals?:number|null):string{
 if(value==null||value==='')return '—'
 const raw=String(value)
 if(!/^-?\d+(?:\.\d{1,18})?$/.test(raw)||raw.length>60)return '—'
 const digits=Number.isInteger(decimals)?Math.max(0,Math.min(6,decimals as number)):2
 const negative=raw.startsWith('-')
 const unsigned=negative?raw.slice(1):raw
 const [whole,fraction='']=unsigned.split('.')
 const normalized=fraction.padEnd(digits+1,'0')
 const rounding=normalized[digits]&&normalized[digits]>='5'?1n:0n
 const scale=10n**BigInt(digits)
 const integer=BigInt(whole)*scale+
   BigInt((digits?normalized.slice(0,digits):'')||0)+rounding
 const total=integer.toString().padStart(digits+1,'0')
 const wholePart=digits?total.slice(0,-digits):total
 const fractionPart=digits?'.'+total.slice(-digits):''
 const grouped=wholePart.replace(/\B(?=(\d{3})+(?!\d))/g,',')
 return (negative&&integer!==0n?'-':'')+grouped+fractionPart
}
