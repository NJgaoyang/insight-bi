/**
 * Conservative editor-side field lineage check for ordered processing steps.
 * Only stable IDs are tracked; the authorized server compiler is final authority.
 * The check intentionally cannot generate or execute SQL.
 */
export type DependencyField={id:string;type?:string;numeric?:boolean}
export type DependencyStep={
 id:string;type:string;fieldId?:string;sourceFieldId?:string;outputType?:string;expression?:string
 conditions?:Array<{leftFieldId:string;rightFieldId:string}>
 fields?:Array<{fieldId:string;sourceFieldId:string}>
 dimensions?:string[]
 measures?:Array<{id:string;fieldId:string;aggregate:string}>
}
export type StepDependencyIssue={index:number;stepId:string;message:string}
export type DependencyReport={issues:StepDependencyIssue[];byStep:Record<string,string[]>}

/**
 * Mimic the FormulaCompiler's [stableFieldId] token references, excluding
 * single-quoted text literals (including SQL-style doubled single quotes).
 * Function syntax/valid types are still checked by the server compiler.
 */
export function formulaFieldReferences(expression:string):string[]{
 const ids:string[]=[]
 for(let i=0;i<expression.length;){
  const char=expression[i]
  if(char==="'"){
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
  if(char==='['){
   const end=expression.indexOf(']',i+1)
   if(end<0)break
   ids.push(expression.slice(i+1,end))
   i=end+1
   continue
  }
  i++
 }
 return [...new Set(ids)]
}
const isNumeric=(type?:string)=>['INTEGER','DECIMAL','DOUBLE'].includes(type||'')
const outputIdentifier=/^[A-Za-z_][A-Za-z0-9_]{0,48}$/
export function checkPipelineDependencies(
 base:readonly DependencyField[],steps:readonly DependencyStep[]
):DependencyReport{
 const issues:StepDependencyIssue[]=[]
 const byStep:Record<string,string[]>={}
 const current=new Map<string,DependencyField>(base.map(field=>[field.id,field]))
 const usedSteps=new Set<string>()
 const add=(index:number,step:DependencyStep,message:string)=>{
  issues.push({index,stepId:step.id,message})
  ;(byStep[step.id]??=[]).push(message)
 }
 const requireId=(index:number,step:DependencyStep,id:string|undefined,role:string)=>{
  if(!id||!current.has(id)){
   add(index,step,role+'引用的字段 '+(id||'(空)')+' 在该步骤之前不存在')
   return false
  }
  return true
 }
 const register=(index:number,step:DependencyStep,id:string|undefined,type?:string)=>{
  if(!id||!outputIdentifier.test(id)||current.has(id)){
   add(index,step,'输出字段 ID 无效或已存在：'+(id||'(空)'))
   return
  }
  current.set(id,{id,type,numeric:isNumeric(type)})
 }
 for(const [index,step] of steps.entries()){
  if(!step.id||usedSteps.has(step.id))add(index,step,'步骤 ID 重复或为空')
  usedSteps.add(step.id)
  if(['join','union'].includes(step.type)&&index!==0)
   add(index,step,'关联和上下合并必须处于第一步')
  switch(step.type){
   case 'formula':
    for(const id of formulaFieldReferences(step.expression||''))requireId(index,step,id,'公式')
    register(index,step,step.fieldId)
    break
   case 'filter':
    requireId(index,step,step.fieldId,'过滤')
    break
   case 'case':
    requireId(index,step,step.sourceFieldId,'条件列')
    register(index,step,step.fieldId,step.outputType)
    break
   case 'cast':{
    const exists=requireId(index,step,step.sourceFieldId,'类型转换')
    const src=current.get(step.sourceFieldId||'')?.type
    const allowed=step.outputType==='STRING'
      ?['INTEGER','DECIMAL','DOUBLE','DATE','DATETIME','BOOLEAN']
      :step.outputType==='DECIMAL'?['INTEGER','DECIMAL','DOUBLE']:[]
    if(exists&&src&&!allowed.includes(src))
     add(index,step,'源字段类型 '+src+' 不支持转换到 '+step.outputType)
    register(index,step,step.fieldId,step.outputType)
    break
   }
   case 'join':
    for(const field of step.fields||[])register(index,step,field.fieldId)
    break
   case 'union':
    // UNION preserves left-hand IDs. Right-hand mapping is checked by backend.
    break
   case 'group':{
    if((step.dimensions||[]).length>3)add(index,step,'最多支持三个分组维度')
    if(!(step.measures?.length)||step.measures.length>5)add(index,step,'汇总指标必须为 1–5 个')
    const next=new Map<string,DependencyField>()
    for(const id of step.dimensions||[]){
     if(!requireId(index,step,id,'分组维度'))continue
     if(next.has(id))add(index,step,'分组维度重复：'+id)
     else next.set(id,current.get(id)!)
    }
    for(const measure of step.measures||[]){
     if(measure.fieldId!=='*'){
      const exists=requireId(index,step,measure.fieldId,'汇总指标')
      const field=current.get(measure.fieldId)
      if(exists&&field?.type&&!isNumeric(field.type)&&
        ['SUM','AVG'].includes(measure.aggregate))
       add(index,step,'SUM/AVG 只能使用数值字段：'+measure.fieldId)
     }else if(measure.aggregate!=='COUNT')add(index,step,'记录数 (*) 只能使用 COUNT')
     if(!measure.id||!outputIdentifier.test(measure.id)||next.has(measure.id))
       add(index,step,'汇总指标 ID 无效或与维度冲突：'+measure.id)
     else next.set(measure.id,{id:measure.id,type:measure.aggregate==='COUNT'?'INTEGER':undefined})
    }
    current.clear()
    for(const [id,field] of next)current.set(id,field)
    break
   }
  }
 }
 return {issues,byStep}
}
