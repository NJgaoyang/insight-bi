package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * P1-02 first batch: stateless compile/preview. An explicit persisted pipeline
 * contract is added only after its source/dependency semantics are accepted.
 * Legacy query and dashboard endpoints are unchanged.
 */
@RestController
@RequestMapping("/api")
public final class AnalysisPipelineApi {
  private final DatabaseStore store;
  private final FieldCatalog catalog;
  private final JdbcTemplate db;
  private static final AnalysisQueryLimiter LIMITER=new AnalysisQueryLimiter(2,250);

  public AnalysisPipelineApi(DatabaseStore store,FieldCatalog catalog,JdbcTemplate db){
    this.store=store;this.catalog=catalog;this.db=db;
  }

  /**
   * A missing pipeline is not an error: it has an empty step list at revision 0.
   * A changed SQL source marks the saved pipeline stale instead of silently
   * executing it against a different schema.
   */
  @GetMapping("/datasets/{id}/pipeline")
  public Map<String,Object> get(@PathVariable String id){
    var ds=store.dataset(id);
    var saved=db.queryForList(
      "SELECT config_json,source_sql_hash,revision FROM insight_dataset_pipeline WHERE dataset_id=?",id);
    if(saved.isEmpty())return Map.of("datasetId",id,"steps",List.of(),"revision",0,"stale",false);
    var row=saved.get(0);
    Map<String,Object> config=store.map(String.valueOf(row.get("config_json")));
    Object steps=config.get("steps");
    if(!(steps instanceof List<?>))
      throw new IllegalStateException("已有数据加工配置损坏");
    return Map.of("datasetId",id,"steps",steps,"revision",row.get("revision"),
      "stale",!sourceHash(id,ds,steps).equals(row.get("source_sql_hash")));
  }

  @PutMapping("/datasets/{id}/pipeline")
  @Transactional
  public Map<String,Object> save(@PathVariable String id,@RequestBody Map<String,Object> body){
    var ds=store.dataset(id);
    Object expected=body.get("revision");
    if(!(expected instanceof Integer revision)||revision<0)
      throw new IllegalArgumentException("必须提供加工配置版本号");
    Object steps=body.get("steps");
    if(!(steps instanceof List<?>))
      throw new IllegalArgumentException("加工步骤必须是列表");
    // Compile everything, including its forward-only field references, before
    // persisting. No source connection or query executes during Save.
    plan(id,Map.of("steps",steps,"limit",100));
    String config=store.json(Map.of("steps",steps));
    if(config.length()>50_000)throw new IllegalArgumentException("加工配置超过大小限制");
    String hash=sourceHash(id,ds,steps);
    if(revision==0){
      if(!db.queryForList("SELECT dataset_id FROM insight_dataset_pipeline WHERE dataset_id=?",id).isEmpty())
        throw new IllegalStateException("加工步骤已被其他编辑者保存，请刷新后重试");
      db.update("INSERT INTO insight_dataset_pipeline(dataset_id,config_json,source_sql_hash,revision) VALUES(?,?,?,1)",
        id,config,hash);
    }else{
      int affected=db.update("UPDATE insight_dataset_pipeline SET config_json=?,source_sql_hash=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE dataset_id=? AND revision=?",
        config,hash,id,revision);
      if(affected!=1)throw new IllegalStateException("加工步骤版本冲突，请刷新后重试");
    }
    return Map.of("datasetId",id,"revision",revision+1,"stale",false);
  }

  private Map<String,AnalysisPipelineCompiler.UnionSource> unionSources(
      String primaryId,Map<String,Object> primary,Object rawSteps){
    if(!(rawSteps instanceof List<?> steps))return Map.of();
    Map<String,AnalysisPipelineCompiler.UnionSource> sources=new LinkedHashMap<>();
    for(Object raw:steps){
      if(!(raw instanceof Map<?,?> step)||
         !Set.of("union","join").contains(step.get("type")))continue;
      Object value=step.get("sourceDatasetId");
      if(!(value instanceof String id)||id.isBlank()||id.equals(primaryId))
        throw new IllegalArgumentException("请选择另一个合法的数据集进行上下合并");
      Map<String,Object> secondary=store.dataset(id);
      if(!Objects.equals(primary.get("connection_id"),secondary.get("connection_id")))
        throw new IllegalArgumentException("上下合并只能引用相同数据连接中的数据集");
      sources.put(id,new AnalysisPipelineCompiler.UnionSource(
        String.valueOf(secondary.get("sql_body")),catalog.fields(id)));
    }
    if(sources.size()>1)throw new IllegalArgumentException("首批只支持一个合并或关联数据源");
    return Map.copyOf(sources);
  }

  /** Hash original SQL plus any referenced, authorized UNION source metadata. */
  private String sourceHash(String id,Map<String,Object> primary,Object steps){
    var sources=unionSources(id,primary,steps);
    String sql=String.valueOf(primary.get("sql_body"));
    if(sources.isEmpty())return sha256(sql); // preserve previous pipeline revisions
    var fingerprint=new StringBuilder(sql);
    for(var entry:sources.entrySet()){
      // Preserve the original UNION fingerprint so existing configurations
      // do not become stale merely because JOIN support was introduced.
      String kind="UNION";
      if(steps instanceof List<?> items){
        for(Object raw:items){
          if(raw instanceof Map<?,?> item&&
              Objects.equals(entry.getKey(),item.get("sourceDatasetId"))&&
              "join".equals(item.get("type"))){kind="JOIN";break;}
        }
      }
      fingerprint.append("\\n").append(kind).append(':').append(entry.getKey()).append(':')
        .append(entry.getValue().sourceSql());
      for(var field:entry.getValue().fields()){
        if(!field.calculated())fingerprint.append("|").append(field.sourceColumn())
          .append(":").append(field.logicalType());
      }
    }
    return sha256(fingerprint.toString());
  }

  private static String sha256(String sql){
    try{
      byte[] bytes=MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
  }

  @PostMapping("/datasets/{id}/pipeline/compile")
  public Map<String,Object> compile(@PathVariable String id,@RequestBody Map<String,Object> body){
    var plan=plan(id,body);
    return Map.of("sqlPreview",plan.sql(),"columns",plan.columns(),
      "bindCount",plan.params().size(),"stepCount",plan.stepCount(),
      "warnings",List.of());
  }

  @PostMapping("/datasets/{id}/pipeline/preview")
  public Map<String,Object> preview(@PathVariable String id,
                                     @RequestBody Map<String,Object> body)throws Exception{
    var ds=store.dataset(id);
    String source=String.valueOf(ds.get("sql_body"));
    var previous=db.queryForList(
      "SELECT source_sql_hash FROM insight_dataset_pipeline WHERE dataset_id=?",id);
    if(!previous.isEmpty()){
      var saved=db.queryForList("SELECT config_json FROM insight_dataset_pipeline WHERE dataset_id=?",id);
      if(saved.isEmpty())throw new IllegalStateException("加工配置与版本信息不一致");
      Object savedSteps=store.map(String.valueOf(saved.get(0).get("config_json"))).get("steps");
      if(!sourceHash(id,ds,savedSteps).equals(previous.get(0).get("source_sql_hash")))
        throw new IllegalStateException("参与加工的数据集 SQL 已变化，请重新校验并保存加工步骤后再预览");
    }
    var plan=plan(id,body);
    long started=System.nanoTime();
    List<Map<String,Object>> rows;
    try(AnalysisQueryLimiter.Lease lease=LIMITER.acquire();
        Connection c=store.open(store.connection(String.valueOf(ds.get("connection_id"))))){
      ReadOnlyConnectionPolicy.require(c);
      rows=store.readAnalysisRows(c,plan.sql(),plan.params(),plan.limit());
    }
    boolean truncated=rows.size()>plan.limit();
    if(truncated)rows=new ArrayList<>(rows.subList(0,plan.limit()));
    return Map.of("rows",rows,"columns",plan.columns(),"truncated",truncated,
      "executionMs",(System.nanoTime()-started)/1_000_000,
      "stepCount",plan.stepCount(),"warnings",List.of());
  }

  private AnalysisPipelineCompiler.Plan plan(String id,Map<String,Object> body){
    var ds=store.dataset(id);
    return new AnalysisPipelineCompiler(catalog.fields(id),unionSources(id,ds,body.get("steps")))
      .compile(String.valueOf(ds.get("sql_body")),body);
  }
}
