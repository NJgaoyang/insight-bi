import type {AnalysisWidgetV2,FilterV2} from '../api'

/** Only dimensions already bound on visible same-dataset V2 widgets may be filtered. */
export type ViewFilterConfig={datasetId:string;fieldId:string;label?:string}
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
 return viewFilterCandidates(view,widgets).some(c=>c.datasetId===datasetId&&c.fieldId===fieldId)
}
export function filterForViewWidget(
 view:FilterView|undefined,widget:AnalysisWidgetV2,value:string
):FilterV2|null{
 const config=view?.filter
 if(!view?.widgetIds.includes(widget.id)||!config||config.datasetId!==widget.datasetId||
    !widget.analysisV2?.dimensions.some(d=>d.fieldId===config.fieldId))return null
 if(typeof value!=='string'||!value.trim()||value.length>120)return null
 return {fieldId:config.fieldId,op:'eq',value}
}
export function setViewFilter(
 view:FilterView,choice:ViewFilterCandidate|null
):void{
 if(choice)view.filter={datasetId:choice.datasetId,fieldId:choice.fieldId,label:choice.label}
 else delete view.filter
}
