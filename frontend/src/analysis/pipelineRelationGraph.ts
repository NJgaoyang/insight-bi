import {formulaFieldReferences} from './pipelineDependencies'

export type RelationField={id:string;label:string;type:string}
export type RelationDataset={id:string;name:string}
export type RelationStep={
 id:string;type:string;sourceDatasetId?:string;joinType?:string;
 conditions?:Array<{leftFieldId:string;rightFieldId:string}>
 mappings?:Array<{leftFieldId:string;rightFieldId:string}>
 fields?:Array<{sourceFieldId:string;fieldId:string;name:string}>
 distinct?:boolean;acknowledgeFanout?:boolean
 fieldId?:string;sourceFieldId?:string;expression?:string;
 dimensions?:string[];measures?:Array<{fieldId:string}>
}
export type RelationGraph={
 mode:'JOIN'|'UNION';source:RelationDataset;target:RelationDataset;
 title:string;links:Array<{from:string;to:string;compatible:boolean}>;
 outputs:Array<{name:string;fieldId:string;sourceFieldId:string}>;
 downstreamSteps:number;warning:string
}
/** Read-only relation preview. No permission claims and no SQL compilation. */
export function pipelineRelationGraph(
 primary:RelationDataset,sources:readonly RelationDataset[],
 columns:readonly RelationField[],right:Record<string,readonly RelationField[]>,
 steps:readonly RelationStep[]
):RelationGraph|null{
 const index=steps.findIndex(s=>s.type==='join'||s.type==='union')
 if(index<0)return null
 const relation=steps[index]
 if(!relation.sourceDatasetId)return null
 const target=sources.find(ds=>ds.id===relation.sourceDatasetId)
 if(!target)return null
 const leftFields=new Map(columns.map(field=>[field.id,field]))
 const rightFields=new Map((right[relation.sourceDatasetId]||[]).map(field=>[field.id,field]))
 const pairs=relation.type==='join'?relation.conditions||[]:relation.mappings||[]
 const links=pairs.map(pair=>{
  const from=leftFields.get(pair.leftFieldId),to=rightFields.get(pair.rightFieldId)
  return {from:from?.label||pair.leftFieldId||'缺失字段',
   to:to?.label||pair.rightFieldId||'缺失字段',
   compatible:!!from&&!!to&&from.type===to.type}
 })
 const outputs=relation.type==='join'?(relation.fields||[]).map(field=>({
  name:field.name,fieldId:field.fieldId,sourceFieldId:field.sourceFieldId
 })):[]
 const produced=new Set(outputs.map(o=>o.fieldId))
 const downstreamSteps=steps.slice(index+1).filter(step=>{
  const referenced=[
   step.fieldId,step.sourceFieldId,...(step.dimensions||[]),
   ...(step.measures||[]).map(m=>m.fieldId),
   ...formulaFieldReferences(step.expression||'')
  ]
  return referenced.some(field=>field&&produced.has(field))
 }).length
 const warning=relation.type==='join'
  ?!relation.acknowledgeFanout?'尚未确认右表重复键可能造成行数放大'
   :links.some(l=>!l.compatible)?'有失效或类型不一致的关联键':''
  :!relation.mappings?.length?'按字段位置上下合并；字段对应以服务端校验为准'
   :links.some(l=>!l.compatible)?'有失效或类型不一致的合并字段':''
 return {
  mode:relation.type==='join'?'JOIN':'UNION',source:primary,target,
  title:relation.type==='join'?(relation.joinType||'LEFT')+' 数据关联'
   :(relation.distinct?'去重上下合并':'保留重复的上下合并'),
  links,outputs,downstreamSteps,warning
 }
}
