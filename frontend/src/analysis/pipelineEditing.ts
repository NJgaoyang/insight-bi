/** UI helpers only; SQL semantics and field provenance stay validated by the server. */
export type PipelineStepIdentity={id:string;type:string}
export function copyEditablePipelineStep<T extends PipelineStepIdentity>(
 step:T,newId:string,newFieldId:string
):T|null{
 if(!['formula','filter','case'].includes(step.type)||!newId||!newFieldId||newId===step.id)return null
 const copied=structuredClone(step) as T&{fieldId?:string;name?:string}
 copied.id=newId
 if(step.type==='formula'||step.type==='case'){
  if(!newFieldId||newFieldId===(step as T&{fieldId?:string}).fieldId)return null
  copied.fieldId=newFieldId
  if(typeof copied.name==='string')copied.name=(copied.name+' 副本').slice(0,100)
 }
 return copied as T
}

/** Preview exactly the chosen prefix, with no mutation to saved full steps. */
export function prefixForPipelinePreview<T extends PipelineStepIdentity>(steps:readonly T[],index:number):T[]{
 if(!Number.isInteger(index)||index<0||index>=steps.length)
  throw new Error('无效的加工步骤位置')
 return structuredClone(steps.slice(0,index+1))
}
