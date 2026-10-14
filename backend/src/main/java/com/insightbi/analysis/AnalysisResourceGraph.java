package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;

/**
 * P1-01 additive theme resource graph. Derive references from actual widgets,
 * never use text matching on arbitrary JSON or generated SQL.
 *
 * Old V1 widgets and modern analysisV2 widgets both own a datasetId.
 * Published versions remain immutable; their graph is derived on demand.
 */
public final class AnalysisResourceGraph {
  private AnalysisResourceGraph(){}

  public record Graph(List<String> datasetIds,List<String> widgetIds,int widgetCount,
                      List<String> dashboardIds,int dashboardCount) {}

  public static Graph of(Object snapshot){
    if(!(snapshot instanceof Map<?,?> root))
      throw new IllegalArgumentException("主题内容必须为对象");
    Object rawWidgets=root.get("widgets");
    if(!(rawWidgets instanceof List<?> widgets))
      throw new IllegalArgumentException("主题必须包含组件列表");
    if(widgets.size()>200)
      throw new IllegalArgumentException("单主题组件不能超过 200 个");

    LinkedHashSet<String> datasets=new LinkedHashSet<>();
    LinkedHashSet<String> ids=new LinkedHashSet<>();
    for(Object raw:widgets){
      if(!(raw instanceof Map<?,?> widget))
        throw new IllegalArgumentException("组件数据必须为对象");
      Object rawId=widget.get("id");
      if(!(rawId instanceof String id)||id.isBlank()||id.length()>128)
        throw new IllegalArgumentException("组件 ID 缺失或无效");
      if(!ids.add(id))
        throw new IllegalArgumentException("组件 ID 重复："+id);
      Object rawDataset=widget.get("datasetId");
      if(rawDataset==null||"".equals(rawDataset))continue; // blank new component
      if(!(rawDataset instanceof String datasetId)||datasetId.isBlank()||datasetId.length()>128)
        throw new IllegalArgumentException("组件数据集 ID 无效："+id);
      datasets.add(datasetId);
    }
    LinkedHashSet<String> dashboardIds=new LinkedHashSet<>();
    Object dashboardRaw=root.get("dashboards");
    if(dashboardRaw!=null){
      if(!(dashboardRaw instanceof List<?> views)||views.isEmpty()||views.size()>30)
        throw new IllegalArgumentException("仪表板视图必须为 1 到 30 个");
      for(Object viewRaw:views){
        if(!(viewRaw instanceof Map<?,?> view))throw new IllegalArgumentException("仪表板视图必须是对象");
        Object rawViewId=view.get("id");
        Object rawViewName=view.get("name");
        if(!(rawViewId instanceof String viewId)||viewId.isBlank()||viewId.length()>128||
           !dashboardIds.add(viewId))
          throw new IllegalArgumentException("仪表板视图 ID 无效或重复");
        if(!(rawViewName instanceof String name)||name.isBlank()||name.length()>100)
          throw new IllegalArgumentException("仪表板视图名称无效");
        Object refs=view.get("widgetIds");
        if(!(refs instanceof List<?> list))throw new IllegalArgumentException("视图组件必须为列表");
        Set<String> unique=new HashSet<>();
        for(Object item:list){
          if(!(item instanceof String widgetId)||!ids.contains(widgetId)||!unique.add(widgetId))
            throw new IllegalArgumentException("视图包含不存在或重复的组件 ID");
        }
        // Optional, per-view layout. Legacy views without layout are unchanged.
        // Layout keys must refer to widgets selected in THIS view, not merely
        // other widgets somewhere in the same theme.
        Object layoutRaw=view.get("layout");
        if(layoutRaw!=null){
          if(!(layoutRaw instanceof Map<?,?> layout))
            throw new IllegalArgumentException("视图布局必须为组件 ID 映射");
          for(var placement:layout.entrySet()){
            if(!(placement.getKey() instanceof String widgetId)||!unique.contains(widgetId))
              throw new IllegalArgumentException("布局引用了当前视图之外的组件");
            if(!(placement.getValue() instanceof Map<?,?> cell)||
                cell.size()!=2||!cell.containsKey("columns")||!cell.containsKey("height"))
              throw new IllegalArgumentException("布局项必须包含列宽和高度");
            Object columns=cell.get("columns"),height=cell.get("height");
            if(!(columns instanceof Number cw)||!(height instanceof Number ch)||
                !(cw instanceof Integer||cw instanceof Long)||
                !(ch instanceof Integer||ch instanceof Long)||
                !(cw.longValue()==6||cw.longValue()==12)||
                !(ch.longValue()==320||ch.longValue()==480||ch.longValue()==640))
              throw new IllegalArgumentException("布局列宽或高度超出允许范围");
          }
        }
      }
      // A jump target must be another view in this same theme. Validate
      // after all view IDs are collected so forward references are legal.
      for(Object viewRaw:views){
        Map<?,?> view=(Map<?,?>)viewRaw;
        Object jumpsRaw=view.get("jumps");
        if(jumpsRaw==null)continue;
        if(!(jumpsRaw instanceof Map<?,?> jumps))
          throw new IllegalArgumentException("视图跳转配置必须是组件映射");
        Set<?> members=new HashSet<>((List<?>)view.get("widgetIds"));
        for(var jump:jumps.entrySet()){
          if(!(jump.getKey() instanceof String widgetId)||!members.contains(widgetId)||
             !(jump.getValue() instanceof String target)||!dashboardIds.contains(target)||
             target.equals(view.get("id")))
            throw new IllegalArgumentException("视图跳转引用了无效组件或目标仪表板");
        }
      }
    }
    return new Graph(List.copyOf(datasets),List.copyOf(ids),widgets.size(),
      List.copyOf(dashboardIds),dashboardIds.size());
  }

  public static boolean references(Object snapshot,String datasetId){
    return of(snapshot).datasetIds().contains(datasetId);
  }

  /**
   * Resource metadata is only derived information; clients cannot supply
   * authoritative dataset references. V1 snapshots remain readable as-is.
   */
  public static Map<String,Object> indexedContent(Map<String,Object> original){
    var graph=of(original);
    Map<String,Object> copy=new LinkedHashMap<>(original);
    copy.put("resourceIndex",Map.of(
      "schemaVersion",1,
      "datasetIds",graph.datasetIds(),
      "widgetIds",graph.widgetIds(),
      "dashboardIds",graph.dashboardIds()));
    return copy;
  }

  /**
   * An analysis snapshot opts into processing per widget. Saved and published
   * snapshots must bind an actual processing revision; otherwise a dashboard
   * could silently query a different pipeline after an editor change.
   */
  public static void requirePinnedPipelines(Object snapshot,AnalysisPipelineApi pipeline){
    if(!(snapshot instanceof Map<?,?> root)||
       !(root.get("widgets") instanceof List<?> widgets))
      throw new IllegalArgumentException("分析主题组件列表无效");
    for(Object raw:widgets){
      if(!(raw instanceof Map<?,?> widget))continue;
      Object config=widget.get("analysisV2");
      if(!(config instanceof Map<?,?> analysis))continue;
      Object mode=analysis.get("usePipeline");
      if(mode==null||Boolean.FALSE.equals(mode))continue;
      if(!Boolean.TRUE.equals(mode))
        throw new IllegalArgumentException("加工数据源开关必须为布尔值");
      Object dataset=widget.get("datasetId"),revision=analysis.get("pipelineRevision");
      if(!(dataset instanceof String id)||id.isBlank()||
         !(revision instanceof Integer version)||version<1)
        throw new IllegalArgumentException("加工数据源缺少有效数据集或绑定版本");
      pipeline.reusableSource(id,version);
    }
  }

  /** Validates source existence for actual referenced IDs, not commentary text. */
  public static void requireExistingDatasets(Graph graph,org.springframework.jdbc.core.JdbcTemplate db){
    for(String datasetId:graph.datasetIds()){
      Integer found=db.queryForObject(
        "SELECT COUNT(*) FROM insight_dataset WHERE id=?",Integer.class,datasetId);
      if(found==null||found!=1)
        throw new IllegalArgumentException("组件引用的数据集不存在："+datasetId);
    }
  }

  /**
   * Do not remove a dataset while any draft, published snapshot or saved
   * history relies on it. Corrupt history fails closed instead of deleting
   * its source metadata.
   */
  public static void rejectDatasetDeletion(
      String datasetId,DatabaseStore store,org.springframework.jdbc.core.JdbcTemplate db){
    for(var row:db.queryForList("SELECT content_json,published_json FROM insight_dashboard")){
      if(referencesStored(row.get("content_json"),datasetId,store)||
         referencesStored(row.get("published_json"),datasetId,store))
        throw new IllegalStateException("数据集被仪表板草稿或已发布版本引用，不能删除");
    }
    for(var row:db.queryForList("SELECT content_json FROM insight_dashboard_version")){
      if(referencesStored(row.get("content_json"),datasetId,store))
        throw new IllegalStateException("数据集被仪表板历史版本引用，不能删除");
    }
  }

  private static boolean referencesStored(Object raw,String datasetId,DatabaseStore store){
    if(raw==null)return false;
    final Map<String,Object> snapshot;
    try{snapshot=store.map(String.valueOf(raw));}
    catch(Exception e){throw new IllegalStateException("已有仪表板快照损坏，拒绝删除数据集");}
    try{return references(snapshot,datasetId);}
    catch(IllegalArgumentException e){
      throw new IllegalStateException("已有仪表板快照结构异常，拒绝删除数据集");
    }
  }
}
