package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;

/**
 * Read-only index of existing, saved JOIN/UNION pipeline relationships.
 * It does not open a source connection, run dataset SQL, grant permissions,
 * or introduce a second independently mutable relationship catalog.
 */
@RestController
@RequestMapping("/api")
public class ModelRelationsApi {
  public record FieldPair(String leftId,String leftName,String rightId,String rightName,
                          boolean compatible){}
  public record Relation(String datasetId,String datasetName,String targetDatasetId,
                         String targetDatasetName,String mode,String joinType,int revision,
                         String status,String reason,List<FieldPair> fields,int outputFields){}
  private final JdbcTemplate jdbc;
  private final DatabaseStore store;
  private final FieldCatalog catalog;
  private final AnalysisPipelineApi pipeline;
  public ModelRelationsApi(JdbcTemplate jdbc,DatabaseStore store,FieldCatalog catalog,
                           AnalysisPipelineApi pipeline){
    this.jdbc=jdbc;this.store=store;this.catalog=catalog;this.pipeline=pipeline;
  }
  @GetMapping("/model/relations")
  public List<Relation> list(){
    var datasets=store.datasets();
    if(datasets.size()>500)throw new IllegalStateException("数据集较多，请缩小模型关系查询范围");
    Map<String,Map<String,Object>> index=new LinkedHashMap<>();
    for(var dataset:datasets)index.put(String.valueOf(dataset.get("id")),dataset);
    var saved=jdbc.queryForList(
      "SELECT dataset_id,config_json,source_sql_hash,revision FROM insight_dataset_pipeline ORDER BY dataset_id LIMIT 101");
    if(saved.size()>100)throw new IllegalStateException("关系数量超过单次安全展示限制");
    List<Relation> result=new ArrayList<>();
    for(var row:saved){
      String primaryId=String.valueOf(row.get("dataset_id"));
      Map<String,Object> primary=index.get(primaryId);
      if(primary==null)continue;
      Object json=row.get("config_json");
      if(!(json instanceof String config)||config.length()>50_000)continue;
      Map<String,Object> definition;
      try{definition=store.map(config);}
      catch(RuntimeException invalid){continue;}
      if(!(definition.get("steps") instanceof List<?> steps)||steps.isEmpty()||steps.size()>12)
        continue;
      if(!(steps.get(0) instanceof Map<?,?> first))continue;
      String mode=String.valueOf(first.get("type")).toUpperCase(Locale.ROOT);
      if(!Set.of("JOIN","UNION").contains(mode))continue;
      Object targetIdRaw=first.get("sourceDatasetId");
      String targetId=targetIdRaw instanceof String s?s:"";
      Map<String,Object> target=index.get(targetId);
      int revision=row.get("revision") instanceof Number num?num.intValue():0;
      String status="ACTIVE",reason="";
      List<FieldPair> pairs=new ArrayList<>();
      Object rawPairs="JOIN".equals(mode)?first.get("conditions"):first.get("mappings");
      if(rawPairs instanceof List<?> entries&&entries.size()<=30){
        Map<String,FieldCatalog.Field> left=fields(primaryId);
        Map<String,FieldCatalog.Field> right=target==null?Map.of():fields(targetId);
        for(var item:entries){
          if(!(item instanceof Map<?,?> m))continue;
          String a=m.get("leftFieldId") instanceof String x?x:"";
          String b=m.get("rightFieldId") instanceof String x?x:"";
          var la=left.get(a);var rb=right.get(b);
          pairs.add(new FieldPair(a,la==null?"字段已失效":la.name(),
            b,rb==null?"字段已失效":rb.name(),
            la!=null&&rb!=null&&la.logicalType().equals(rb.logicalType())));
        }
      }
      if(target==null){
        status="INVALID";reason="关联的数据集已不存在";
      }else if(!Objects.equals(primary.get("connection_id"),target.get("connection_id"))){
        status="INVALID";reason="关联数据集已不属于同一个连接";
      }else{
        try{
          var snapshot=pipeline.get(primaryId);
          if(Boolean.TRUE.equals(snapshot.get("stale"))){
            status="STALE";reason="上游 SQL 或关联字段已变更，请重新校验加工配置";
          }else{
            pipeline.compile(primaryId,Map.of("steps",steps,"limit",1));
            if(pairs.stream().anyMatch(p->!p.compatible())){
              status="INVALID";reason="关联字段缺失或类型发生变化";
            }
          }
        }catch(RuntimeException invalid){
          status="INVALID";reason="已保存关联配置未通过当前编译规则";
        }
      }
      int outputs=0;
      if(first.get("fields") instanceof List<?> selected)outputs=selected.size();
      result.add(new Relation(primaryId,String.valueOf(primary.get("name")),
        targetId,target==null?"数据集已删除":String.valueOf(target.get("name")),
        mode,"JOIN".equals(mode)?first.get("joinType") instanceof String joinMode?joinMode:"LEFT":"",
        revision,status,reason,List.copyOf(pairs),outputs));
    }
    return result;
  }
  private Map<String,FieldCatalog.Field> fields(String datasetId){
    Map<String,FieldCatalog.Field> result=new LinkedHashMap<>();
    try{
      for(var f:catalog.fields(datasetId))if(!f.calculated())result.put(f.id(),f);
    }catch(RuntimeException stale){
      // Invalid saved metadata must not fail the entire relationship directory.
      return Map.of();
    }
    return result;
  }
}
