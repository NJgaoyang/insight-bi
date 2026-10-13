package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.sql.Connection;
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
  private static final AnalysisQueryLimiter LIMITER=new AnalysisQueryLimiter(2,250);

  public AnalysisPipelineApi(DatabaseStore store,FieldCatalog catalog){
    this.store=store;this.catalog=catalog;
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
