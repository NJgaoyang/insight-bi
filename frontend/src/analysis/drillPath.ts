import type {AnalysisWidgetV2} from '../api'
import type {DashboardView} from './dashboardLayout'
export type DrillSelection={fieldId:string;value:string|number}
export type DrillTarget={targetViewId:string;value:string}

/** A click-to-drill is opt-in and bound to the target view's published filter. */
export function drillTarget(
 source:DashboardView|undefined,sourceWidget:AnalysisWidgetV2,
 selection:DrillSelection|null,
 views:readonly DashboardView[],widgets:readonly AnalysisWidgetV2[]
):DrillTarget|null{
 if(!source||!selection||!source.drills?.[sourceWidget.id]||
    !source.widgetIds.includes(sourceWidget.id))return null
 const targetId=source.jumps?.[sourceWidget.id]
 if(!targetId||targetId===source.id)return null
 const target=views.find(v=>v.id===targetId)
 if(!target?.filter)return null
 if(!sourceWidget.analysisV2?.dimensions.some(d=>d.fieldId===selection.fieldId)||
    !sourceWidget.datasetId||sourceWidget.datasetId!==target.filter.datasetId||
    selection.fieldId!==target.filter.fieldId)return null
 // No arbitrary cross-dataset field inference on a chart click.
 const value=String(selection.value)
 if(!value.trim()||value.length>120||/[\u0000-\u001f\u007f]/.test(value))return null
 // The target needs at least one actual pinned V2 widget to consume its filter.
 if(!target.widgetIds.some(id=>widgets.some(w=>w.id===id&&
  w.datasetId===target.filter!.datasetId&&
  w.analysisV2?.dimensions.some(d=>d.fieldId===target.filter!.fieldId))))return null
 return {targetViewId:target.id,value}
}

/** Editor preflight: opt-in drill links must be explicitly resolvable. */
export function invalidDrillLinks(
 views:readonly DashboardView[],widgets:readonly AnalysisWidgetV2[]
):Array<{viewId:string;widgetId:string}>{
 const invalid:Array<{viewId:string;widgetId:string}>=[]
 for(const view of views){
  for(const [widgetId,enabled] of Object.entries(view.drills||{})){
   if(!enabled)continue
   const widget=widgets.find(w=>w.id===widgetId)
   const target=views.find(v=>v.id===view.jumps?.[widgetId])
   if(!widget||!view.widgetIds.includes(widgetId)||!target?.filter||
      !widget.analysisV2?.dimensions.some(d=>d.fieldId===target.filter?.fieldId)||
      widget.datasetId!==target.filter.datasetId||
      !target.widgetIds.some(id=>widgets.some(w=>w.id===id&&
       w.datasetId===target.filter?.datasetId&&
       w.analysisV2?.dimensions.some(d=>d.fieldId===target.filter?.fieldId)))||
      target.id===view.id)
    invalid.push({viewId:view.id,widgetId})
  }
 }
 return invalid
}

export function removeInvalidDrillLinks(
 views:DashboardView[],widgets:AnalysisWidgetV2[]
):void{
 const invalid=invalidDrillLinks(views,widgets)
 for(const {viewId,widgetId} of invalid){
  const view=views.find(v=>v.id===viewId)
  if(view?.drills)delete view.drills[widgetId]
 }
}
