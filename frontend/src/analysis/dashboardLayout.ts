import type {AnalysisWidgetV2} from '../api'

/** P1 view-local layout; component query/visual config stays in shared widgets[]. */
export type WidgetLayout={columns:6|12;height:320|480|640}
export type DashboardView={id:string;name:string;widgetIds:string[];layout?:Record<string,WidgetLayout>;jumps?:Record<string,string>}

// Old snapshots use the original 380px visual height. New choices are 320/480/640.
export function viewLayout(view:DashboardView|undefined,id:string){
 const stored=view?.layout?.[id]
 const columns:6|12=stored?.columns===12?12:6
 const height=stored?.height===320||stored?.height===480||stored?.height===640
  ?stored.height:380
 return {columns,height}
}
export function cardStyle(view:DashboardView|undefined,id:string){
 const layout=viewLayout(view,id)
 return {gridColumn:'span '+layout.columns,height:layout.height+'px'}
}
export function orderedViewWidgets(view:DashboardView|undefined,widgets:AnalysisWidgetV2[]){
 if(!view)return []
 const index=new Map(widgets.map(w=>[w.id,w]))
 return view.widgetIds.map(id=>index.get(id)).filter((w):w is AnalysisWidgetV2=>!!w)
}
export function removeUnusedLayout(view:DashboardView){
 const selected=new Set(view.widgetIds)
 if(view.jumps)view.jumps=Object.fromEntries(Object.entries(view.jumps).filter(([id])=>selected.has(id)))
 if(!view.layout)return
 view.layout=Object.fromEntries(Object.entries(view.layout).filter(([id])=>selected.has(id)))
}
