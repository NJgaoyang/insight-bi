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

  public record Graph(List<String> datasetIds,List<String> widgetIds,int widgetCount) {}

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
    return new Graph(List.copyOf(datasets),List.copyOf(ids),widgets.size());
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
      "widgetIds",graph.widgetIds()));
    return copy;
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
