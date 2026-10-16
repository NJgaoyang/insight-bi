// Lightweight read-only reference collector. Full formula validity remains
// the responsibility of FormulaCompiler and the processing dependency checker.
function relationFieldReferences(expression:string):string[]{
 const ids:string[]=[]
 for(let i=0;i<expression.length;){
  if(expression[i]==="'"){
   i++
   while(i<expression.length){
    if(expression[i]==="'"){
     if(expression[i+1]==="'"){i+=2;continue}
     i++;break
    }
    i++
   }
   continue
  }
  if(expression[i]==='['){
   const end=expression.indexOf(']',i+1)
   if(end<0)break
   ids.push(expression.slice(i+1,end));i=end+1
  }else i++
 }
 return [...new Set(ids)]
}

export type RelationField={id:string;label:string;type:string}
export type RelationDataset={id:string;name:string}
export type RelationStep={
 id:string;type:string;sourceDatasetId?:string;joinType?:string;
 conditions?:Array<{leftFieldId:string;rightFieldId:string}>
 mappings?:Array<{leftFieldId:string;rightFieldId:string}>
 fields?:Array<{sourceFieldId:string;fieldId:string;name:string}>
 distinct?:boolean;acknowledgeFanout?:boolean
 fieldId?:string;sourceFieldId?:string;expression?:string;
 dimensions?:string[];measures?:Array<{id?:string;fieldId:string}>
}
export type RelationGraph={
 mode:'JOIN'|'UNION';source:RelationDataset;target:RelationDataset;
 title:string;links:Array<{from:string;to:string;compatible:boolean}>;
 outputs:Array<{name:string;fieldId:string;sourceFieldId:string}>;
 downstreamSteps:number;warning:string;
 impacts:Array<{outputFieldId:string;outputName:string;steps:Array<{index:number;type:string;stepId:string}>}>
}
/**
 * Conservative, bounded dependency tracing (not a SQL optimizer).
 * Only explicit field references are tracked. GROUP drops unavailable
 * row-level IDs; derived field IDs inherit their referenced source path.
 */
function traceRelationImpacts(steps:readonly RelationStep[],start:number,
 outputs:readonly {fieldId:string;name:string}[]):RelationGraph['impacts']{
 return outputs.map(output=>{
  let reachable=new Set([output.fieldId])
  const affected:RelationGraph['impacts'][number]['steps']=[]
  for(let i=start+1;i<steps.length;i++){
   const step=steps[i]
   let used:string[]=[]
   if(step.type==='formula')used=relationFieldReferences(step.expression||'')
   else if(step.type==='filter')used=step.fieldId?[step.fieldId]:[]
   else if(step.type==='cast'||step.type==='case')used=step.sourceFieldId?[step.sourceFieldId]:[]
   else if(step.type==='group')used=[
    ...(step.dimensions||[]),...(step.measures||[]).map(m=>m.fieldId)]
   const touched=used.some(id=>reachable.has(id))
   if(touched)affected.push({index:i+1,type:step.type,stepId:step.id})
   if(touched&&['formula','case','cast'].includes(step.type)&&step.fieldId)
    reachable.add(step.fieldId)
   if(step.type==='group'){
    const next=new Set<string>()
    for(const id of step.dimensions||[])if(reachable.has(id))next.add(id)
    for(const metric of step.measures||[])
     if(reachable.has(metric.fieldId)&&metric.id)next.add(metric.id)
    reachable=next
   }
  }
  return {outputFieldId:output.fieldId,outputName:output.name,steps:affected}
 })
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
   ...relationFieldReferences(step.expression||'')
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
  links,outputs,downstreamSteps,warning,
  impacts:traceRelationImpacts(steps,index,outputs)
 }
}
