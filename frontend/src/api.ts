import {ref} from 'vue'
export const csrf=ref('');
export async function api<T=any>(path:string,options:RequestInit={}):Promise<T>{
 const headers:Record<string,string>={...(options.headers as Record<string,string>||{})};
 if(options.body && !(options.body instanceof FormData))headers['Content-Type']='application/json';
 if(options.method && !['GET','HEAD'].includes(options.method.toUpperCase()) && csrf.value)headers['X-CSRF-Token']=csrf.value;
 const res=await fetch('/api'+path,{credentials:'same-origin',...options,headers});
 if(!res.ok){const type=res.headers.get('content-type')||'';const response=type.includes('application/json')?await res.json().catch(()=>({})):{};if(res.status===401)window.dispatchEvent(new Event('bi-unauthorized'));throw new Error(response.error||('请求失败 HTTP '+res.status));}
 return res.json();
}
export const post=<T=any>(p:string,v:any)=>api<T>(p,{method:'POST',body:JSON.stringify(v)});
export const put=<T=any>(p:string,v:any)=>api<T>(p,{method:'PUT',body:JSON.stringify(v)});
export const remove=<T=any>(p:string)=>api<T>(p,{method:'DELETE'});
export interface Field {name:string;type:string;numeric:boolean}
export interface Dataset {id:string;name:string;connection_id:string;sql_body:string;fields:Field[]}
export interface Widget {id:string;type:string;title:string;datasetId:string;dimension:string;columnDimension?:string;measure:string;secondMeasure?:string;agg:string;columns:string[];x:number;y:number;w:number;h:number;px:number;py:number;width:number;height:number;style:Record<string,any>;columnStyles:Record<string,any>;drillDimensions?:string[];pivotMeasures?:string[];sort?:string;direction?:'asc'|'desc';topN?:number}
export interface Dashboard {id:string;name:string;mode:'grid'|'free';revision:number;status:string;content:{mode:'grid'|'free';widgets:Widget[];theme:string}}

/** New semantic query contracts: old Widget payloads continue to work unchanged. */
export interface FieldV2 {id:string;name:string;sourceColumn:string|null;logicalType:string;role:string;numeric:boolean;calculated:boolean;phase:string;expression:string|null;hidden?:boolean}
export interface DimensionV2 {fieldId:string;granularity?:'year'|'month'|'day'}
export interface MeasureV2 {id:string;fieldId:string;aggregate:'SUM'|'AVG'|'COUNT'|'MIN'|'MAX';alias:string;distinct?:boolean;seriesType?:'bar'|'line';axis?:'left'|'right';decimals?:number;color?:string;showLabel?:boolean}
export interface FilterV2 {fieldId?:string;ref?:string;op:string;value?:string|number;values?:(string|number)[]}
export interface AnalysisV2Bindings {
  /** Explicitly use a saved and revision-pinned data processing result. */
  usePipeline?:boolean
  pipelineRevision?:number
  /** Non-executable metadata-only semantic candidate reference in a draft. */
  semanticRelation?:{relationId:string;readerId:string;releaseNo:number}
  dimensions:DimensionV2[]
  measures:MeasureV2[]
  filters:{pre:FilterV2[];post:FilterV2[]}
  rank?:{ref:string;id?:string;direction?:'asc'|'desc'}
  share?:{ref:string;id?:string}
  running?:{ref:string;id?:string;direction?:'asc'|'desc'}
  periodCompare?:{ref:string;mode:'mom'|'yoy';id?:string}
  /** V2 cross-table display only; first dimension is row, second column. */
  pivot?:{rowOrder:'asc'|'desc';columnOrder:'asc'|'desc';showTotals:boolean;showSubtotals?:boolean}
  tableHiddenColumnIds?:string[]
  sort:{ref:string;direction:'asc'|'desc'}[]
  limit:number
}
export type AnalysisWidgetV2 = Widget & {analysisV2?:AnalysisV2Bindings}
export interface PivotTotalsV2 {
 exact:boolean;reason?:string
 rows?:Record<string,unknown>[];columns?:Record<string,unknown>[]
 grand?:Record<string,unknown>;parents?:Record<string,unknown>[];parentCells?:Record<string,unknown>[]
}
export interface QueryV2Result {pivotTotals?:PivotTotalsV2;rows:Record<string,unknown>[];columns:{id:string;name:string;label:string;type:string;kind:string;additive?:boolean}[];executionMs:number;truncated:boolean;warnings:string[]}
