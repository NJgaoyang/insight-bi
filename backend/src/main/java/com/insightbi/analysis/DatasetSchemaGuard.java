package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Preflight before overwriting an existing dataset's SQL/fields_json.
 * Reject changes that would leave saved calculation dependencies or
 * published, draft, or historical analysis bindings dangling.
 *
 * No business-source SQL is executed here: readFields() ran first.
 */
public final class DatasetSchemaGuard {
  private final DatabaseStore store;
  private final JdbcTemplate db;

  public DatasetSchemaGuard(DatabaseStore store,JdbcTemplate db){
    this.store=store;this.db=db;
  }

  public void check(String datasetId,List<Map<String,Object>> proposed){
    check(datasetId,proposed,Map.of());
  }

  public void check(String datasetId,List<Map<String,Object>> proposed,Map<String,String> fieldRenames){
    List<FieldCatalog.Field> previous=new FieldCatalog(store,db).fields(datasetId);
    Map<String,FieldCatalog.Field> previousPhysical=new HashMap<>();
    for(var f:previous)if(!f.calculated())previousPhysical.put(f.sourceColumn(),f);

    Set<String> requestedNames=new HashSet<>();
    for(Map<String,Object> col:proposed)requestedNames.add(String.valueOf(col.get("name")));
    // Explicit mappings only: never guess whether a removed alias and a new
    // alias describe the same underlying SQL expression.
    Set<String> targets=new HashSet<>();
    if(fieldRenames.size()>50)throw new IllegalArgumentException("字段更名映射最多支持 50 项");
    for(var pair:fieldRenames.entrySet()){
      String old=pair.getKey(),target=pair.getValue();
      if(old==null||target==null||old.isBlank()||target.isBlank()||
         !previousPhysical.containsKey(old)||requestedNames.contains(old)||
         !requestedNames.contains(target)||previousPhysical.containsKey(target)||
         !targets.add(target)||target.length()>128)
        throw new IllegalArgumentException("源字段更名映射无效: "+old+" -> "+target);
    }
    Map<String,FieldCatalog.Field> renamedTargets=new HashMap<>();
    for(var pair:fieldRenames.entrySet())
      renamedTargets.put(pair.getValue(),previousPhysical.get(pair.getKey()));

    Set<String> seenNames=new HashSet<>();
    List<FieldCatalog.Field> candidate=new ArrayList<>();
    for(Map<String,Object> col:proposed){
      String name=String.valueOf(col.get("name"));
      if(name.isBlank()||name.length()>128||!seenNames.add(name))
        throw new IllegalArgumentException("数据集返回重复或无效的源列名: "+name);
      String type=FieldCatalog.typeOf(String.valueOf(col.get("type")));
      boolean numeric=Set.of("INTEGER","DOUBLE","DECIMAL").contains(type);
      FieldCatalog.Field old=previousPhysical.get(name);
      if(old==null)old=renamedTargets.get(name);
      String id=old==null?UUID.nameUUIDFromBytes((datasetId+"\u0000"+name)
        .getBytes(StandardCharsets.UTF_8)).toString():old.id();
      candidate.add(new FieldCatalog.Field(id,old==null?name:old.name(),name,type,
        numeric?"BOTH":"DIMENSION",numeric,false,"ROW",null));
    }

    Map<String,FieldCatalog.Field> next=FieldCatalog.index(candidate);
    Set<String> removedIds=new HashSet<>(),removedNames=new HashSet<>();
    for(var old:previousPhysical.values()){
      if(!seenNames.contains(old.sourceColumn())&&!fieldRenames.containsKey(old.sourceColumn())){
        removedIds.add(old.id());removedNames.add(old.sourceColumn());
      }
    }

    // Existing expressions are stored as stable [fieldId] references.
    // Compile every formula with the prospective physical catalog, including
    // dependencies between formulas. Do not silently change a saved type/phase.
    List<FieldCatalog.Field> calculated=previous.stream().filter(FieldCatalog.Field::calculated).toList();
    for(var f:calculated)next.put(f.id(),f);
    var compiler=new FormulaCompiler(next);
    for(var formula:calculated){
      try{
        var compiled=compiler.compile(formula.expression());
        if(!formula.phase().equals(compiled.phase())||
           !formula.logicalType().equals(compiled.outputType()))
          throw new IllegalArgumentException("原计算阶段或返回类型发生变化");
      }catch(IllegalArgumentException e){
        throw new IllegalArgumentException("源字段变更将破坏计算字段「"+formula.name()+"」: "+e.getMessage(),e);
      }
    }

    // Check drafts, published snapshots and historical versions; removing a
    // source column must never silently break a saved graph configuration.
    List<Snapshot> snapshots=new ArrayList<>();
    for(var row:db.queryForList("SELECT content_json,published_json FROM insight_dashboard")){
      snapshots.add(new Snapshot(row.get("content_json"),"分析主题草稿"));
      snapshots.add(new Snapshot(row.get("published_json"),"已发布分析主题"));
    }
    for(var row:db.queryForList("SELECT content_json FROM insight_dashboard_version"))
      snapshots.add(new Snapshot(row.get("content_json"),"历史分析主题"));

    Set<String> unavailableLegacyNames=new HashSet<>(removedNames);
    unavailableLegacyNames.addAll(fieldRenames.keySet());
    for(var snap:snapshots){
      if(snap.content()==null)continue;
      Object tree=store.map(String.valueOf(snap.content()));
      checkSnapshot(tree,datasetId,removedIds,unavailableLegacyNames,next,snap.kind());
    }
  }

  /** Persist validated alias mapping without replacing field IDs or display labels. */
  public void applyRenames(String datasetId,Map<String,String> fieldRenames){
    for(var entry:fieldRenames.entrySet()){
      int changed=db.update(
        "UPDATE insight_dataset_field SET source_name=? WHERE dataset_id=? AND source_name=?",
        entry.getValue(),datasetId,entry.getKey());
      if(changed!=1)throw new IllegalStateException("字段更名映射已失效，请刷新数据集重试");
    }
  }

  private record Snapshot(Object content,String kind){}

  @SuppressWarnings("unchecked")
  private void checkSnapshot(Object node,String datasetId,Set<String> removedIds,
                             Set<String> removedNames,Map<String,FieldCatalog.Field> next,
                             String snapshotKind){
    if(node instanceof List<?> items){
      for(Object item:items)checkSnapshot(item,datasetId,removedIds,removedNames,next,snapshotKind);
      return;
    }
    if(!(node instanceof Map<?,?> map))return;
    if(datasetId.equals(String.valueOf(map.get("datasetId")))){
      // Old V1 widgets bind physical column names, not immutable field IDs.
      // Even an explicit V2 ID-preserving rename cannot safely rewrite these
      // stored names (especially published and historical snapshots).
      validateLegacyBindings(map,removedNames,snapshotKind);
      Object schema=map.get("analysisV2");
      if(schema instanceof Map<?,?> config){
        ensureSafeReferences(config,removedIds,snapshotKind);
        Object metrics=config.get("measures");
        if(metrics instanceof List<?> ms)for(Object raw:ms){
          if(!(raw instanceof Map<?,?> metric))continue;
          FieldCatalog.Field field=next.get(String.valueOf(metric.get("fieldId")));
          String agg=String.valueOf(metric.get("aggregate"));
          if(field!=null&&!field.numeric()&&Set.of("SUM","AVG").contains(agg))
            throw new IllegalArgumentException(snapshotKind+"中的数值指标已变为非数值源列: "+field.name());
        }
        Object dimensions=config.get("dimensions");
        if(dimensions instanceof List<?> ds)for(Object raw:ds){
          if(!(raw instanceof Map<?,?> dimension))continue;
          FieldCatalog.Field field=next.get(String.valueOf(dimension.get("fieldId")));
          Object granularity=dimension.get("granularity");
          if(field!=null&&granularity!=null&&!String.valueOf(granularity).isBlank()&&
             !Set.of("DATE","DATETIME").contains(field.logicalType()))
            throw new IllegalArgumentException(snapshotKind+"的日期分组字段类型已发生变化: "+field.name());
        }
        // Recompile saved V2 bindings against the proposed types so existing
        // pre/post filters, date values, aggregation and sorts remain valid.
        // SELECT 1 is only a placeholder: no data-source query is executed.
        Map<String,Object> req=AnalysisQueryPlanner.object(config,"已保存分析配置");
        boolean hasDimensions=req.get("dimensions") instanceof List<?> d&&!d.isEmpty();
        boolean hasMeasures=req.get("measures") instanceof List<?> m&&!m.isEmpty();
        if(hasDimensions||hasMeasures){
          try{
            new AnalysisQueryPlanner(new ArrayList<>(next.values())).compile("SELECT 1",req);
          }catch(IllegalArgumentException ex){
            throw new IllegalArgumentException(snapshotKind+"存在与新字段类型不兼容的查询配置: "+
              ex.getMessage(),ex);
          }
        }
      }
    }
    for(Object child:map.values())if(child instanceof Map<?,?>||child instanceof List<?>)
      checkSnapshot(child,datasetId,removedIds,removedNames,next,snapshotKind);
  }

  private static void validateLegacyBindings(Map<?,?> widget,Set<String> missingNames,String snapshotKind){
    // Also check cross-tab and drill lists, legacy sort, and the old filter field.
    // This is a conservative rejection, never a silent V1->V2 rewrite.
    for(String key:List.of("dimension","measure","secondMeasure","columnDimension","sort")){
      Object value=widget.get(key);
      if(value instanceof String name&&missingNames.contains(name))
        throw new IllegalArgumentException(snapshotKind+"的旧版字段引用将因源列更名或移除失效: "+name);
    }
    for(String key:List.of("columns","drillDimensions","pivotMeasures")){
      Object value=widget.get(key);
      if(value instanceof List<?> items)for(Object item:items){
        if(item instanceof String name&&missingNames.contains(name))
          throw new IllegalArgumentException(snapshotKind+"的旧版字段列表将因源列更名或移除失效: "+name);
      }
    }
    Object legacyFilters=widget.get("filters");
    if(legacyFilters instanceof List<?> list)for(Object item:list){
      if(item instanceof Map<?,?> filter &&
         filter.get("field") instanceof String name && missingNames.contains(name))
        throw new IllegalArgumentException(snapshotKind+"的旧版筛选将因源列更名或移除失效: "+name);
    }
  }

  private static void ensureSafeReferences(Object node,Set<String> removedIds,String source){
    if(node instanceof List<?> list){
      for(Object item:list)ensureSafeReferences(item,removedIds,source);
    }else if(node instanceof Map<?,?> map){
      Object id=map.get("fieldId");
      if(id!=null&&removedIds.contains(String.valueOf(id)))
        throw new IllegalArgumentException(source+"仍引用被移除的源字段 ID: "+id);
      for(Object value:map.values())
        if(value instanceof List<?>||value instanceof Map<?,?>)
          ensureSafeReferences(value,removedIds,source);
    }
  }
}
