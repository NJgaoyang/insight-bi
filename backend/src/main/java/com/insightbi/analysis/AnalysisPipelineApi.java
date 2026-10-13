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
    String hash=sha256(String.valueOf(ds.get("sql_body")));
    var saved=db.queryForList(
      "SELECT config_json,source_sql_hash,revision FROM insight_dataset_pipeline WHERE dataset_id=?",id);
    if(saved.isEmpty())return Map.of("datasetId",id,"steps",List.of(),"revision",0,"stale",false);
    var row=saved.get(0);
    Map<String,Object> config=store.map(String.valueOf(row.get("config_json")));
    Object steps=config.get("steps");
    if(!(steps instanceof List<?>))
      throw new IllegalStateException("已有数据加工配置损坏");
    return Map.of("datasetId",id,"steps",steps,"revision",row.get("revision"),
      "stale",!hash.equals(row.get("source_sql_hash")));
  }

  @PutMapping("/datasets/{id}/pipeline")
  @Transactional
  public Map<String,Object> save(@PathVariable String id,@RequestBody Map<String,Object> body){
    var ds=store.dataset(id);
    Object expected=body.get("revision");
    if(!(expected instanceof Integer revision)||revision<0)
      throw new IllegalArgumentException("必须提供加工配置版本号");
    Object steps=body.get("steps");
    // Compile everything, including its forward-only field references, before
    // persisting. No source connection or query executes during Save.
    new AnalysisPipelineCompiler(catalog.fields(id)).compile(
      String.valueOf(ds.get("sql_body")),Map.of("steps",steps,"limit",100));
    String config=store.json(Map.of("steps",steps));
    if(config.length()>50_000)throw new IllegalArgumentException("加工配置超过大小限制");
    String hash=sha256(String.valueOf(ds.get("sql_body")));
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
    var plan=new AnalysisPipelineCompiler(catalog.fields(id))
      .compile(String.valueOf(ds.get("sql_body")),body);
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
    return new AnalysisPipelineCompiler(catalog.fields(id))
      .compile(String.valueOf(ds.get("sql_body")),body);
  }
}
