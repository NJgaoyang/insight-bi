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
  private final AnalysisPipelineApi pipeline;
  private final SourceRecoveryGuard recovery;
  private final AnalysisExecutionMetrics executionMetrics;
  private static final AnalysisQueryLimiter QUERY_LIMITER=new AnalysisQueryLimiter(4,250);
  public static AnalysisQueryLimiter.Snapshot queryAdmissionSnapshot(){return QUERY_LIMITER.snapshot();}
  public AnalysisApi(DatabaseStore store,JdbcTemplate db,FieldCatalog catalog){
    this(store,db,catalog,new SourceRecoveryGuard(),new AnalysisExecutionMetrics());
  }
  public AnalysisApi(DatabaseStore store,JdbcTemplate db,FieldCatalog catalog,
                     SourceRecoveryGuard recovery){
    this(store,db,catalog,recovery,new AnalysisExecutionMetrics());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public AnalysisApi(DatabaseStore store,JdbcTemplate db,FieldCatalog catalog,
                     SourceRecoveryGuard recovery,AnalysisExecutionMetrics executionMetrics){
    this.store=store;this.db=db;this.catalog=catalog;this.recovery=recovery;
    this.executionMetrics=executionMetrics;
    this.pipeline=new AnalysisPipelineApi(store,catalog,db);
  }
  @GetMapping("/datasets/{id}/fields")
  public List<FieldCatalog.Field> fields(@PathVariable String id){return catalog.fields(id);}

  @PutMapping("/datasets/{id}/fields/{fieldId}")
  @Transactional
  public FieldCatalog.Field renameField(@PathVariable String id,@PathVariable String fieldId,
      @RequestBody Map<String,Object> body){
    Object value=body.get("displayName");
    if(!(value instanceof String label))throw new IllegalArgumentException("展示名称必须为文本");
    return catalog.renameDisplayName(id,fieldId,label);
  }

  @PutMapping("/datasets/{id}/fields/{fieldId}/visibility")
  @Transactional
  public FieldCatalog.Field setFieldVisibility(@PathVariable String id,@PathVariable String fieldId,
      @RequestBody Map<String,Object> body){
    if(!(body.get("hidden") instanceof Boolean hidden))
      throw new IllegalArgumentException("hidden 必须是布尔值");
    return catalog.setHidden(id,fieldId,hidden);
  }

  @PostMapping("/analysis/v2/compile")
  public Map<String,Object> compile(@RequestBody Map<String,Object> request){
    ResolvedPlan resolved=resolvePlan(request);
    AnalysisQueryPlanner.Plan plan=resolved.plan();
    return Map.of("columns",plan.columns(),"sqlPreview",plan.sql(),
       "bindCount",resolved.params().size(),"warnings",List.of());
  }
  @PostMapping("/analysis/v2/query")
  public Map<String,Object> query(@RequestBody Map<String,Object> request)throws Exception{
    String datasetId=AnalysisQueryPlanner.required(request,"datasetId");
    Map<String,Object> ds=store.dataset(datasetId);
    ResolvedPlan resolved=resolvePlan(request);
    AnalysisQueryPlanner.Plan plan=resolved.plan();
    long start=System.nanoTime();
    List<Map<String,Object>> rows;
    try{
      try(AnalysisQueryLimiter.Lease slot=QUERY_LIMITER.acquire()){
        String sourceId=String.valueOf(ds.get("connection_id"));
        rows=recovery.execute(sourceId,()->{
          try(Connection connection=store.open(store.connection(sourceId))){
            ReadOnlyConnectionPolicy.require(connection);
            return store.readAnalysisRows(connection,plan.sql(),resolved.params(),plan.limit());
          }
        });
      }
    }catch(Exception e){
      executionMetrics.record(start,e);
      throw e;
    }
    executionMetrics.record(start,null);
    boolean truncated=rows.size()>plan.limit();
    // Do not expose the sentinel record used only to detect overflow.
    if(truncated)rows=new ArrayList<>(rows.subList(0,plan.limit()));
    long elapsed=(System.nanoTime()-start)/1_000_000;
    return Map.of("rows",rows,"columns",plan.columns(),"executionMs",elapsed,
      "truncated",truncated,"warnings",List.of());
  }
  private record ResolvedPlan(AnalysisQueryPlanner.Plan plan,List<Object> params) {}

  /**
   * V2 requests are unchanged unless usePipeline=true. In that opt-in mode
   * the dashboard pins a saved pipeline revision so published snapshots cannot
   * silently drift when data-processing steps are later edited.
   *
   * The pipeline source is nested inside V2's FROM, so its JDBC placeholders
   * precede semantic WHERE/HAVING and outer LIMIT placeholders.
   */
  private ResolvedPlan resolvePlan(Map<String,Object> request){
    String datasetId=AnalysisQueryPlanner.required(request,"datasetId");
    Map<String,Object> ds=store.dataset(datasetId);
    Object mode=request.get("usePipeline");
    if(mode!=null&&!(mode instanceof Boolean))
      throw new IllegalArgumentException("usePipeline 必须为布尔值");
    if(!Boolean.TRUE.equals(mode)){
      AnalysisQueryPlanner.Plan plan=new AnalysisQueryPlanner(catalog.fields(datasetId))
        .compile(String.valueOf(ds.get("sql_body")),request);
      return new ResolvedPlan(plan,plan.params());
    }
    Object version=request.get("pipelineRevision");
    if(!(version instanceof Integer revision)||revision<1)
      throw new IllegalArgumentException("加工数据源必须绑定有效版本号");
    AnalysisPipelineApi.ReusableSource source=pipeline.reusableSource(datasetId,revision);
    AnalysisQueryPlanner.Plan plan=new AnalysisQueryPlanner(source.fields())
      .compile(source.sql(),request);
    List<Object> all=new ArrayList<>(source.params());
    all.addAll(plan.params());
    return new ResolvedPlan(plan,List.copyOf(all));
  }

  @GetMapping("/datasets/{id}/calculated-fields")
  public List<Map<String,Object>> formulas(@PathVariable String id){
    store.dataset(id);
    return db.queryForList("SELECT id,name,expression,phase,output_type,revision FROM insight_calculated_field WHERE dataset_id=? ORDER BY name",id);
  }
  private static String requiredExpression(Map<String,Object> body){
    Object value=body.get("expression");
    if(!(value instanceof String text)||text.isBlank()||text.length()>1500)
      throw new IllegalArgumentException("公式不能为空或超过 1500 字符");
    return text.trim();
  }

  @PostMapping("/datasets/{id}/calculated-fields/validate")
  public Map<String,Object> validate(@PathVariable String id,@RequestBody Map<String,Object> body){
    var formula=new FormulaCompiler(FieldCatalog.index(catalog.fields(id)))
      .compile(requiredExpression(body));
    return Map.of("phase",formula.phase(),"outputType",formula.outputType(),
       "references",formula.references(),"sqlPreview",formula.sql(),
       "canonicalAst",formula.canonicalAst());
  }
  @PostMapping("/datasets/{id}/calculated-fields")
  @Transactional
  public Map<String,Object> addFormula(@PathVariable String id,@RequestBody Map<String,Object> body){
    String name=AnalysisQueryPlanner.required(body,"name");
    if(name.length()>100)throw new IllegalArgumentException("计算字段名称过长");
    String expression=requiredExpression(body);
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
    String expression=requiredExpression(body),
       name=AnalysisQueryPlanner.required(body,"name");
    int revision;
    try{revision=Integer.parseInt(String.valueOf(body.get("revision")));}catch(Exception e){throw new IllegalArgumentException("缺少公式版本号");}
    if(name.length()>100)throw new IllegalArgumentException("公式名称过长");
    List<Map<String,Object>> existing=db.queryForList(
       "SELECT revision FROM insight_calculated_field WHERE dataset_id=? AND id=?",id,formulaId);
    if(existing.isEmpty())throw new NoSuchElementException("计算字段不存在");
    new FormulaSnapshotGuard(store,db).rejectPublishedMutation(id,formulaId);
    var result=checkFormulaUpdate(id,formulaId,expression);
    int count=db.update("UPDATE insight_calculated_field SET name=?,expression=?,phase=?,output_type=?,revision=revision+1 WHERE dataset_id=? AND id=? AND revision=?",
       name,expression,result.phase(),result.outputType(),id,formulaId,revision);
    if(count!=1)throw new IllegalStateException("计算字段已被其他操作修改");
    return Map.of("id",formulaId,"revision",revision+1,"phase",result.phase());
  }
  /**
   * Validate the prospective field graph before committing. Compiling against the old
   * expression allowed self-reference to pass validation and persist a cycle.
   */
  private FormulaCompiler.Compiled checkFormulaUpdate(String datasetId,String formulaId,String expression){
    List<FieldCatalog.Field> all=catalog.fields(datasetId);
    Map<String,FieldCatalog.Field> indexed=FieldCatalog.index(all);
    FieldCatalog.Field old=indexed.get(formulaId);
    if(old==null||!old.calculated())throw new NoSuchElementException("计算字段不存在");
    indexed.put(formulaId,new FieldCatalog.Field(old.id(),old.name(),null,old.logicalType(),
        old.role(),old.numeric(),true,old.phase(),expression));
    var changed=new FormulaCompiler(indexed).compile(expression);
    indexed.put(formulaId,new FieldCatalog.Field(old.id(),old.name(),null,changed.outputType(),
        old.role(),Set.of("INTEGER","DECIMAL","DOUBLE").contains(changed.outputType()),
        true,changed.phase(),expression));
    for(FieldCatalog.Field dependent:all){
      if(!dependent.calculated()||dependent.id().equals(formulaId))continue;
      var compiled=new FormulaCompiler(indexed).compile(dependent.expression());
      if(!dependent.phase().equals(compiled.phase())||
         !dependent.logicalType().equals(compiled.outputType()))
        throw new IllegalArgumentException("修改会破坏计算字段依赖: "+dependent.name());
    }
    return changed;
  }

  @DeleteMapping("/datasets/{id}/calculated-fields/{formulaId}")
  @Transactional
  public Map<String,Object> deleteFormula(@PathVariable String id,@PathVariable String formulaId){
    var list=db.queryForList("SELECT id FROM insight_calculated_field WHERE dataset_id=? AND id=?",id,formulaId);
    if(list.isEmpty())throw new NoSuchElementException("计算字段不存在");
    // Structured direct and indirect checks include drafts and historical versions.
    new FormulaSnapshotGuard(store,db).rejectDeletion(id,formulaId);
    db.update("DELETE FROM insight_calculated_field WHERE dataset_id=? AND id=?",id,formulaId);
    return Map.of("ok",true);
  }
}
