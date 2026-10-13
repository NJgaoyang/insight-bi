package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.sql.Connection;
import java.util.*;

/** V2 endpoints are isolated: existing /api/query and legacy dashboard JSON are untouched. */
@RestController
@RequestMapping("/api")
public class AnalysisApi {
  private final DatabaseStore store;
  private final JdbcTemplate db;
  private final FieldCatalog catalog;
  public AnalysisApi(DatabaseStore store,JdbcTemplate db,FieldCatalog catalog){
    this.store=store;this.db=db;this.catalog=catalog;
  }
  @GetMapping("/datasets/{id}/fields")
  public List<FieldCatalog.Field> fields(@PathVariable String id){return catalog.fields(id);}

  @PostMapping("/analysis/v2/compile")
  public Map<String,Object> compile(@RequestBody Map<String,Object> request){
    String datasetId=AnalysisQueryPlanner.required(request,"datasetId");
    Map<String,Object> ds=store.dataset(datasetId);
    AnalysisQueryPlanner.Plan plan=new AnalysisQueryPlanner(catalog.fields(datasetId))
      .compile(String.valueOf(ds.get("sql_body")),request);
    return Map.of("columns",plan.columns(),"sqlPreview",plan.sql(),
       "bindCount",plan.params().size(),"warnings",List.of());
  }
  @PostMapping("/analysis/v2/query")
  public Map<String,Object> query(@RequestBody Map<String,Object> request)throws Exception{
    String datasetId=AnalysisQueryPlanner.required(request,"datasetId");
    Map<String,Object> ds=store.dataset(datasetId);
    AnalysisQueryPlanner.Plan plan=new AnalysisQueryPlanner(catalog.fields(datasetId))
      .compile(String.valueOf(ds.get("sql_body")),request);
    long start=System.nanoTime();
    List<Map<String,Object>> rows;
    try(Connection c=store.open(store.connection(String.valueOf(ds.get("connection_id"))))){
      rows=store.readRows(c,plan.sql(),plan.params(),plan.limit());
    }
    long elapsed=(System.nanoTime()-start)/1_000_000;
    return Map.of("rows",rows,"columns",plan.columns(),"executionMs",elapsed,
      "truncated",rows.size()>=plan.limit(),"warnings",List.of());
  }
  @GetMapping("/datasets/{id}/calculated-fields")
  public List<Map<String,Object>> formulas(@PathVariable String id){
    store.dataset(id);
    return db.queryForList("SELECT id,name,expression,phase,output_type,revision FROM insight_calculated_field WHERE dataset_id=? ORDER BY name",id);
  }
  @PostMapping("/datasets/{id}/calculated-fields/validate")
  public Map<String,Object> validate(@PathVariable String id,@RequestBody Map<String,Object> body){
    var formula=new FormulaCompiler(FieldCatalog.index(catalog.fields(id)))
      .compile(AnalysisQueryPlanner.required(body,"expression"));
    return Map.of("phase",formula.phase(),"outputType",formula.outputType(),
       "references",formula.references(),"sqlPreview",formula.sql());
  }
  @PostMapping("/datasets/{id}/calculated-fields")
  @Transactional
  public Map<String,Object> addFormula(@PathVariable String id,@RequestBody Map<String,Object> body){
    String name=AnalysisQueryPlanner.required(body,"name");
    if(name.length()>100)throw new IllegalArgumentException("计算字段名称过长");
    String expression=AnalysisQueryPlanner.required(body,"expression");
    var result=new FormulaCompiler(FieldCatalog.index(catalog.fields(id))).compile(expression);
    String expected=String.valueOf(body.getOrDefault("phase",result.phase()));
    if(!result.phase().equals(expected))throw new IllegalArgumentException("公式计算阶段与声明不一致");
    String generated=UUID.randomUUID().toString();
    db.update("INSERT INTO insight_calculated_field(id,dataset_id,name,expression,phase,output_type,revision) VALUES(?,?,?,?,?,?,1)",
       generated,id,name,expression,result.phase(),result.outputType());
    return Map.of("id",generated,"phase",result.phase(),"outputType",result.outputType(),"revision",1);
  }
  @PutMapping("/datasets/{id}/calculated-fields/{formulaId}")
  @Transactional
  public Map<String,Object> updateFormula(@PathVariable String id,@PathVariable String formulaId,@RequestBody Map<String,Object> body){
    String expression=AnalysisQueryPlanner.required(body,"expression"),
       name=AnalysisQueryPlanner.required(body,"name");
    int revision;
    try{revision=Integer.parseInt(String.valueOf(body.get("revision")));}catch(Exception e){throw new IllegalArgumentException("缺少公式版本号");}
    if(name.length()>100)throw new IllegalArgumentException("公式名称过长");
    List<Map<String,Object>> existing=db.queryForList(
       "SELECT revision FROM insight_calculated_field WHERE dataset_id=? AND id=?",id,formulaId);
    if(existing.isEmpty())throw new NoSuchElementException("计算字段不存在");
    var result=new FormulaCompiler(FieldCatalog.index(catalog.fields(id))).compile(expression);
    int count=db.update("UPDATE insight_calculated_field SET name=?,expression=?,phase=?,output_type=?,revision=revision+1 WHERE dataset_id=? AND id=? AND revision=?",
       name,expression,result.phase(),result.outputType(),id,formulaId,revision);
    if(count!=1)throw new IllegalStateException("计算字段已被其他操作修改");
    return Map.of("id",formulaId,"revision",revision+1,"phase",result.phase());
  }
  @DeleteMapping("/datasets/{id}/calculated-fields/{formulaId}")
  @Transactional
  public Map<String,Object> deleteFormula(@PathVariable String id,@PathVariable String formulaId){
    var list=db.queryForList("SELECT id FROM insight_calculated_field WHERE dataset_id=? AND id=?",id,formulaId);
    if(list.isEmpty())throw new NoSuchElementException("计算字段不存在");
    // Reject dependencies from other expressions and stored report snapshots.
    String ref="%["+formulaId+"]%";
    Integer used=db.queryForObject("SELECT COUNT(*) FROM insight_calculated_field WHERE expression LIKE ? AND id<>?",Integer.class,ref,formulaId);
    if(used!=null&&used>0)throw new IllegalStateException("此计算字段仍被其他公式引用");
    for(var report:db.queryForList("SELECT content_json,published_json FROM insight_dashboard")){
      for(String key:List.of("content_json","published_json")){
        Object raw=report.get(key);
        if(raw!=null&&String.valueOf(raw).contains(formulaId))
          throw new IllegalStateException("此计算字段仍被分析主题或发布快照引用");
      }
    }
    db.update("DELETE FROM insight_calculated_field WHERE dataset_id=? AND id=?",id,formulaId);
    return Map.of("ok",true);
  }
}
