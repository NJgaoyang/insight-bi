/** Preview-only JOIN checks. The API's trusted source resolver and SQL compiler remain authoritative. */
export type JoinField={id:string;type:string}
export type JoinCondition={leftFieldId:string;rightFieldId:string}
export type JoinProjection={sourceFieldId:string;fieldId:string;name:string}
export type JoinSettings={
 sourceDatasetId:string;joinType:string;acknowledgeFanout:boolean
 conditions:JoinCondition[];fields:JoinProjection[]
}
export function joinPreflight(
 step:JoinSettings,left:readonly JoinField[],right:readonly JoinField[],allowedSources:readonly string[]
):string[]{
 const issues:string[]=[]
 if(!allowedSources.includes(step.sourceDatasetId))issues.push('关联数据集必须与当前数据集使用相同连接')
 if(!['LEFT','INNER'].includes(step.joinType))issues.push('关联方式必须是 LEFT 或 INNER')
 if(!Array.isArray(step.conditions)||step.conditions.length<1||step.conditions.length>4){
  issues.push('关联条件必须为 1–4 组')
 }else{
  const leftMap=new Map(left.map(field=>[field.id,field.type]))
  const rightMap=new Map(right.map(field=>[field.id,field.type]))
  const used=new Set<string>()
  for(const [i,condition] of step.conditions.entries()){
   const a=leftMap.get(condition.leftFieldId),b=rightMap.get(condition.rightFieldId)
   if(!a||!b)issues.push('第 '+(i+1)+' 组关联键不存在')
   else if(a!==b)issues.push('第 '+(i+1)+' 组关联键类型不匹配')
   const key=JSON.stringify([condition.leftFieldId,condition.rightFieldId])
   if(used.has(key))issues.push('关联条件不能重复')
   used.add(key)
  }
 }
 if(!Array.isArray(step.fields)||step.fields.length<1||step.fields.length>30){
  issues.push('右表必须输出 1–30 个字段')
 }else{
  const rightIds=new Set(right.map(x=>x.id)),outputIds=new Set(left.map(x=>x.id))
  for(const [i,field] of step.fields.entries()){
   if(!rightIds.has(field.sourceFieldId))issues.push('第 '+(i+1)+' 个右表输出字段不存在')
   if(!/^[A-Za-z_][A-Za-z0-9_]{0,48}$/.test(field.fieldId)||outputIds.has(field.fieldId))
    issues.push('右表输出字段 ID 不合法或重复')
   outputIds.add(field.fieldId)
   if(!field.name?.trim()||field.name.length>100)
    issues.push('右表输出字段名称无效')
  }
 }
 if(!step.acknowledgeFanout)
  issues.push('请重新确认当前关联键可能导致行数放大')
 return [...new Set(issues)]
}
