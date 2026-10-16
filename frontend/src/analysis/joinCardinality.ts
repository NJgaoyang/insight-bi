/** Stable identity of an on-demand right-key diagnostic. Not a database cache key. */
export type JoinKeyStep={
 id:string;type:string;sourceDatasetId:string;joinType:string;
 conditions:readonly {leftFieldId:string;rightFieldId:string}[]
}
export type RightKeyProfile={
 duplicateGroups:number;extraRightRows:number;hasDuplicateKeys:boolean;
 keyGroups:number;rightRows:number;maxRightMultiplicity:number;
 scope:'RIGHT_SOURCE';exactJoinRowCount:false;executionMs?:number
}
export type ProfileSnapshot={key:string;result:RightKeyProfile}
export function joinKeySignature(datasetId:string,step:JoinKeyStep):string{
 return JSON.stringify([
  datasetId,step.id,step.sourceDatasetId,step.joinType,
  step.conditions.map(k=>[k.leftFieldId,k.rightFieldId])
 ])
}
/** Fail closed: never represent results for another key configuration as current. */
export function currentRightKeyProfile(
 snapshot:ProfileSnapshot|null,datasetId:string,step:JoinKeyStep|null
):RightKeyProfile|null{
 if(!step||!snapshot||snapshot.key!==joinKeySignature(datasetId,step))return null
 const p=snapshot.result
 if(p.scope!=='RIGHT_SOURCE'||p.exactJoinRowCount!==false||
    !Number.isSafeInteger(p.keyGroups)||p.keyGroups<0||
    !Number.isSafeInteger(p.rightRows)||p.rightRows<0||
    !Number.isSafeInteger(p.duplicateGroups)||p.duplicateGroups<0||
    !Number.isSafeInteger(p.extraRightRows)||p.extraRightRows<0||
    !Number.isSafeInteger(p.maxRightMultiplicity)||p.maxRightMultiplicity<0||
    p.duplicateGroups>p.keyGroups||p.extraRightRows>p.rightRows||
    (p.rightRows===0?p.maxRightMultiplicity!==0:
     p.maxRightMultiplicity<1||p.maxRightMultiplicity>p.rightRows)||
    p.hasDuplicateKeys!==(p.duplicateGroups>0))return null
 return p
}
/** Right-side multiplicity is NOT the actual size or fanout of the JOIN result. */
export function cardinalitySummary(profile:RightKeyProfile):string{
 const hint=profile.hasDuplicateKeys
  ?'右侧存在重复键，部分左侧记录可能一对多匹配'
  :'检查时右侧未发现重复键（不能保证之后的数据不变化）'
 return '右表 '+profile.rightRows+' 行，'+profile.keyGroups+' 组关联键；'+
  '最大单键 '+profile.maxRightMultiplicity+' 行，重复键组 '+profile.duplicateGroups+
  '，重复额外行 '+profile.extraRightRows+'。'+hint+
  '。仅统计右表，NULL 分组可能不参与等值连接；不能推算最终 JOIN 行数。'
}
