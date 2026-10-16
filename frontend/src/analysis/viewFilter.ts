import type {AnalysisWidgetV2,FilterV2,FieldV2} from '../api'

/** Only dimensions already bound on visible same-dataset V2 widgets may be filtered. */
export type CrossDatasetMapping={widgetId:string;fieldId:string}
export type ViewFilterConfig={datasetId:string;fieldId:string;label?:string;mappings?:CrossDatasetMapping[]}
export type DatasetConnection={id:string;connection_id:string}
export type FieldDirectory=Record<string,readonly Pick<FieldV2,'id'|'logicalType'|'calculated'|'phase'>[]>
export type FilterView={widgetIds:string[];filter?:ViewFilterConfig}
export type ViewFilterCandidate={datasetId:string;fieldId:string;label:string;key:string}
export function viewFilterCandidates(view:FilterView|undefined,widgets:readonly AnalysisWidgetV2[]):ViewFilterCandidate[]{
 if(!view)return []
 const index=new Map(widgets.map(w=>[w.id,w]))
 const unique=new Map<string,ViewFilterCandidate>()
 for(const widgetId of view.widgetIds){
  const widget=index.get(widgetId)
  if(!widget?.datasetId||!widget.analysisV2)continue
  for(const dim of widget.analysisV2.dimensions){
   if(!dim.fieldId)continue
   const key=JSON.stringify([widget.datasetId,dim.fieldId])
   if(!unique.has(key))unique.set(key,{
    datasetId:widget.datasetId,fieldId:dim.fieldId,
    label:widget.title+' · '+dim.fieldId,key})
  }
 }
 return [...unique.values()]
}
export function isValidViewFilter(view:FilterView|undefined,widgets:readonly AnalysisWidgetV2[]):boolean{
 if(!view?.filter)return true
 const {datasetId,fieldId}=view.filter
 if(!viewFilterCandidates(view,widgets).some(c=>c.datasetId===datasetId&&c.fieldId===fieldId))return false
 const mappings=view.filter.mappings||[]
 if(!Array.isArray(mappings)||mappings.length>20)return false
 const targetIds=new Set<string>()
 for(const m of mappings){
  if(!m||typeof m.widgetId!=='string'||typeof m.fieldId!=='string'||targetIds.has(m.widgetId))return false
  targetIds.add(m.widgetId)
  const target=widgets.find(w=>w.id===m.widgetId)
  if(!target||!view.widgetIds.includes(m.widgetId)||target.datasetId===datasetId||
     !target.analysisV2?.dimensions.some(d=>d.fieldId===m.fieldId))return false
 }
 return true
}
export function filterForViewWidget(
 view:FilterView|undefined,widget:AnalysisWidgetV2,value:string
):FilterV2|null{
 const config=view?.filter
 if(!view?.widgetIds.includes(widget.id)||!config)return null
 let targetFieldId=config.fieldId
 if(widget.datasetId!==config.datasetId){
  const mapping=config.mappings?.find(m=>m.widgetId===widget.id)
  if(!mapping)return null
  targetFieldId=mapping.fieldId
 }
 if(!widget.analysisV2?.dimensions.some(d=>d.fieldId===targetFieldId))return null
 if(typeof value!=='string'||!value.trim()||value.length>120)return null
 return {fieldId:targetFieldId,op:'eq',value}
}
export function setViewFilter(
 view:FilterView,choice:ViewFilterCandidate|null
):void{
 if(choice)view.filter={datasetId:choice.datasetId,fieldId:choice.fieldId,label:choice.label}
 else delete view.filter
}


/** Only explicit, same-connection, identical logical-type dimension mappings. */
export function mappingCandidates(
 view:FilterView|undefined,target:AnalysisWidgetV2,widgets:readonly AnalysisWidgetV2[],
 datasets:readonly DatasetConnection[],directory:FieldDirectory
):Array<{fieldId:string;label:string}>{
 const source=view?.filter
 if(!source||!view?.widgetIds.includes(target.id)||!target.analysisV2||
    !target.datasetId||target.datasetId===source.datasetId)return []
 const left=datasets.find(x=>x.id===source.datasetId)
 const right=datasets.find(x=>x.id===target.datasetId)
 if(!left?.connection_id||left.connection_id!==right?.connection_id)return []
 const src=directory[source.datasetId]?.find(f=>f.id===source.fieldId&&!f.calculated)
 if(!src||src.logicalType==='UNKNOWN')return []
 return target.analysisV2.dimensions
  .filter(d=>directory[target.datasetId]?.some(f=>f.id===d.fieldId&&!f.calculated&&
   f.logicalType===src.logicalType))
  .map(d=>({fieldId:d.fieldId,label:d.fieldId}))
}

export function updateViewFilterMapping(
 view:FilterView,widgetId:string,fieldId:string
):void{
 if(!view.filter)return
 const retained=(view.filter.mappings||[]).filter(m=>m.widgetId!==widgetId)
 if(fieldId)retained.push({widgetId,fieldId})
 if(retained.length)view.filter.mappings=retained
 else delete view.filter.mappings
}
