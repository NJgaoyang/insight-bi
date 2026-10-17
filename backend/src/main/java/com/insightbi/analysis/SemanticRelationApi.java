package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Administrator-only, metadata-only relationship drafts.
 * These definitions are NEVER injected into AnalysisPipelineCompiler or reader SQL.
 */
@RestController
@RequestMapping("/api/model/semantic-relations")
public class SemanticRelationApi {
  public record Pair(String leftFieldId,String rightFieldId){}
  public record Relation(String id,String name,String sourceDatasetId,String targetDatasetId,
                         List<Pair> pairs,int revision,String status,String reason){}
  private final DatabaseStore store;
  private final JdbcTemplate db;
  private final FieldCatalog catalog;
  private static final AnalysisQueryLimiter CARDINALITY_LIMITER=new AnalysisQueryLimiter(2,250);

  public SemanticRelationApi(DatabaseStore store,JdbcTemplate db,FieldCatalog catalog){
    this.store=store;this.db=db;this.catalog=catalog;
  }
  @GetMapping
  public List<Relation> list(){
    var saved=db.queryForList(
      "SELECT id,name,source_dataset_id,target_dataset_id,pairs_json,revision,approved_revision,approved_source_hash "+
      "FROM insight_semantic_relation ORDER BY name,id LIMIT 101");
    if(saved.size()>100)throw new IllegalStateException("草稿关系超过安全展示上限");
    List<Relation> result=new ArrayList<>();
    for(var row:saved){
      var pairs=readPairs(String.valueOf(row.get("pairs_json")));
      String source=String.valueOf(row.get("source_dataset_id"));
      String target=String.valueOf(row.get("target_dataset_id"));
      int rev=((Number)row.get("revision")).intValue();
      int approved=row.get("approved_revision") instanceof Number number?number.intValue():0;
      String status="DRAFT",reason="";
      try{
        validate(source,target,pairs);
        if(approved==rev){
          if(sourceFingerprint(source,target).equals(row.get("approved_source_hash")))
            status="APPROVED";
          else reason="源 SQL、连接或字段定义已变化，请重新确认当前版本";
        }
      }catch(RuntimeException invalid){
        status="INVALID";reason="字段或数据集已失效，请编辑并重新确认";
      }
      result.add(new Relation(String.valueOf(row.get("id")),String.valueOf(row.get("name")),
        source,target,pairs,rev,status,reason));
    }
    return result;
  }
  @PostMapping
  @Transactional
  public Relation create(@RequestBody Map<String,Object> body){
    String name=name(body.get("name"));
    String source=id(body.get("sourceDatasetId"));
    String target=id(body.get("targetDatasetId"));
    var pairs=bodyPairs(body.get("pairs"));
    validate(source,target,pairs);
    Integer count=db.queryForObject(
      "SELECT COUNT(*) FROM insight_semantic_relation WHERE source_dataset_id=?",Integer.class,source);
    if(count!=null&&count>=12)throw new IllegalStateException("单个主数据集最多维护 12 条草稿关系");
    String relationId=UUID.randomUUID().toString();
    try{
      db.update("INSERT INTO insight_semantic_relation(id,name,source_dataset_id,target_dataset_id,pairs_json,revision) "+
        "VALUES(?,?,?,?,?,1)",relationId,name,source,target,store.json(pairs));
    }catch(DuplicateKeyException e){
      throw new IllegalStateException("相同数据集对已存在关系草稿，请编辑现有定义");
    }
    return new Relation(relationId,name,source,target,pairs,1,"DRAFT","");
  }
  @PutMapping("/{relationId}")
  @Transactional
  public Relation update(@PathVariable String relationId,@RequestBody Map<String,Object> body){
    String id=id(relationId);
    int expected=revision(body.get("revision"));
    var current=byId(id);
    String name=name(body.get("name"));
    // Changing the source/target requires creating a new, explicitly reviewed draft.
    var pairs=bodyPairs(body.get("pairs"));
    validate(current.sourceDatasetId(),current.targetDatasetId(),pairs);
    int changed=db.update(
      "UPDATE insight_semantic_relation SET name=?,pairs_json=?,revision=revision+1,approved_revision=NULL,approved_at=NULL,approved_source_hash=NULL,updated_at=CURRENT_TIMESTAMP "+
      "WHERE id=? AND revision=?",name,store.json(pairs),id,expected);
    if(changed!=1)throw new IllegalStateException("关系草稿版本已变化，请刷新");
    return new Relation(id,name,current.sourceDatasetId(),current.targetDatasetId(),
      pairs,expected+1,"DRAFT","");
  }
  /** Explicit administrator confirmation of a validated, immutable draft revision. */
  @PostMapping("/{relationId}/approve")
  @Transactional
  public Relation approve(@PathVariable String relationId,@RequestBody Map<String,Object> body){
    String relation=id(relationId);
    int expected=revision(body.get("revision"));
    // Lock this model row through the revalidation/approval write. Concurrent
    // edits cannot sneak an obsolete confirmation onto a later revision.
    var rows=db.queryForList(
      "SELECT id,name,source_dataset_id,target_dataset_id,pairs_json,revision "+
      "FROM insight_semantic_relation WHERE id=? FOR UPDATE",relation);
    if(rows.size()!=1)throw new IllegalArgumentException("关系草稿不存在");
    var row=rows.get(0);
    int actual=((Number)row.get("revision")).intValue();
    if(actual!=expected)throw new IllegalStateException("关系版本已变化，请刷新后重新确认");
    String source=String.valueOf(row.get("source_dataset_id"));
    String target=String.valueOf(row.get("target_dataset_id"));
    List<Pair> mapping=readPairs(String.valueOf(row.get("pairs_json")));
    validate(source,target,mapping);
    String fingerprint=sourceFingerprint(source,target);
    int saved=db.update(
      "UPDATE insight_semantic_relation SET approved_revision=?,approved_at=CURRENT_TIMESTAMP,approved_source_hash=? "+
      "WHERE id=? AND revision=?",expected,fingerprint,relation,expected);
    if(saved!=1)throw new IllegalStateException("关系草稿版本已变化，请刷新");
    return new Relation(relation,String.valueOf(row.get("name")),source,target,mapping,
      expected,"APPROVED","");
  }

  /** Admin-only, compile-only preview. No source JDBC or reader query path. */
  @PostMapping("/{relationId}/compile")
  public SemanticRelationSqlPreview.Preview compile(@PathVariable String relationId,
                                                     @RequestBody Map<String,Object> body){
    if(body==null||body.size()!=4||
       !Set.of("revision","joinType","leftFieldIds","rightFieldIds").containsAll(body.keySet()))
      throw new IllegalArgumentException("关系编译参数不合法");
    String relation=id(relationId);
    int expected=revision(body.get("revision"));
    var rows=db.queryForList(
      "SELECT source_dataset_id,target_dataset_id,pairs_json,revision,approved_revision,approved_source_hash "+
      "FROM insight_semantic_relation WHERE id=?",relation);
    if(rows.size()!=1)throw new IllegalArgumentException("关系草稿不存在");
    var row=rows.get(0);
    int actual=((Number)row.get("revision")).intValue();
    int approved=row.get("approved_revision") instanceof Number n?n.intValue():0;
    if(actual!=expected||approved!=expected)
      throw new IllegalStateException("关系尚未确认或版本已变化，请确认当前修订");
    String leftId=String.valueOf(row.get("source_dataset_id"));
    String rightId=String.valueOf(row.get("target_dataset_id"));
    var pairs=readPairs(String.valueOf(row.get("pairs_json")));
    validate(leftId,rightId,pairs);
    if(!sourceFingerprint(leftId,rightId).equals(row.get("approved_source_hash")))
      throw new IllegalStateException("关系源数据已变化，请重新确认当前版本");
    return SemanticRelationSqlPreview.compile(
      store.dataset(leftId),store.dataset(rightId),leftId,rightId,pairs,
      physicalFields(leftId),physicalFields(rightId),expected,body);
  }

  /** Right-source SAMPLE only; not a JOIN execution, not reader data. */
  @PostMapping("/{relationId}/join-key-profile")
  public SemanticRelationCardinalityProfile.Result joinKeyProfile(
      @PathVariable String relationId,@RequestBody Map<String,Object> body)throws Exception{
    if(body==null||body.size()!=2||
       !Set.of("revision","acknowledgeSourceScan").containsAll(body.keySet())||
       !Boolean.TRUE.equals(body.get("acknowledgeSourceScan")))
      throw new IllegalArgumentException("请明确同意本次右表只读抽样");
    int expected=revision(body.get("revision"));
    String relation=id(relationId);
    var rows=db.queryForList(
      "SELECT source_dataset_id,target_dataset_id,pairs_json,revision,approved_revision,approved_source_hash "+
      "FROM insight_semantic_relation WHERE id=?",relation);
    if(rows.size()!=1)throw new IllegalArgumentException("关系草稿不存在");
    var row=rows.get(0);
    int actual=((Number)row.get("revision")).intValue();
    int approved=row.get("approved_revision") instanceof Number n?n.intValue():0;
    if(actual!=expected||approved!=expected)
      throw new IllegalStateException("关系尚未确认或版本已变化");
    String leftId=String.valueOf(row.get("source_dataset_id"));
    String rightId=String.valueOf(row.get("target_dataset_id"));
    var pairs=readPairs(String.valueOf(row.get("pairs_json")));
    validate(leftId,rightId,pairs);
    if(!sourceFingerprint(leftId,rightId).equals(row.get("approved_source_hash")))
      throw new IllegalStateException("关系源数据已变化，请重新确认后再诊断");
    var right=store.dataset(rightId);
    var plan=SemanticRelationCardinalityProfile.plan(
      String.valueOf(right.get("sql_body")),pairs,physicalFields(rightId));
    // Admission before borrowing the external JDBC connection.
    try(AnalysisQueryLimiter.Lease lease=CARDINALITY_LIMITER.acquire();
        java.sql.Connection c=store.open(store.connection(String.valueOf(right.get("connection_id"))))){
      ReadOnlyConnectionPolicy.require(c);
      return SemanticRelationCardinalityProfile.run(c,plan);
    }
  }

  @DeleteMapping("/{relationId}")
  @Transactional
  public Map<String,Object> delete(@PathVariable String relationId,@RequestParam int revision){
    String id=id(relationId);
    if(revision<1)throw new IllegalArgumentException("版本号无效");
    int deleted=db.update("DELETE FROM insight_semantic_relation WHERE id=? AND revision=?",id,revision);
    if(deleted!=1)throw new IllegalStateException("关系草稿版本已变化或不存在，请刷新");
    return Map.of("ok",true);
  }
  private Relation byId(String id){
    var rows=db.queryForList(
      "SELECT id,name,source_dataset_id,target_dataset_id,pairs_json,revision FROM insight_semantic_relation WHERE id=?",id);
    if(rows.size()!=1)throw new IllegalArgumentException("关系草稿不存在");
    var row=rows.get(0);
    return new Relation(id,String.valueOf(row.get("name")),
      String.valueOf(row.get("source_dataset_id")),String.valueOf(row.get("target_dataset_id")),
      readPairs(String.valueOf(row.get("pairs_json"))),((Number)row.get("revision")).intValue(),"DRAFT","");
  }
  private void validate(String source,String target,List<Pair> pairs){
    if(source.equals(target))throw new IllegalArgumentException("关联数据集不能与主数据集相同");
    var s=store.dataset(source);
    var t=store.dataset(target);
    if(!Objects.equals(s.get("connection_id"),t.get("connection_id"))||
       s.get("connection_id")==null)
      throw new IllegalArgumentException("关系草稿只允许同一个数据连接");
    var left=physicalFields(source);
    var right=physicalFields(target);
    if(pairs.isEmpty()||pairs.size()>3)throw new IllegalArgumentException("只允许 1 至 3 组关联字段");
    Set<String> usedLeft=new HashSet<>(),usedRight=new HashSet<>();
    for(var pair:pairs){
      var a=left.get(pair.leftFieldId());
      var b=right.get(pair.rightFieldId());
      if(a==null||b==null||"UNKNOWN".equals(a.logicalType())||
         !a.logicalType().equals(b.logicalType()))
        throw new IllegalArgumentException("关联字段缺失、隐藏或类型不同");
      if(!usedLeft.add(pair.leftFieldId())||!usedRight.add(pair.rightFieldId()))
        throw new IllegalArgumentException("不能重复使用关联字段");
    }
  }
  /** Deterministic snapshot of both source SQL strings, connections and physical fields. */
  private String sourceFingerprint(String leftId,String rightId){
    return SemanticRelationSourceFingerprint.compute(
      store.dataset(leftId),store.dataset(rightId),
      catalog.fields(leftId),catalog.fields(rightId));
  }
  private Map<String,FieldCatalog.Field> physicalFields(String dataset){
    var fields=new HashMap<String,FieldCatalog.Field>();
    for(var f:catalog.fields(dataset))
      if(!f.calculated()&&!f.hidden())fields.put(f.id(),f);
    return fields;
  }
  private static List<Pair> bodyPairs(Object raw){
    if(!(raw instanceof List<?> entries)||entries.isEmpty()||entries.size()>3)
      throw new IllegalArgumentException("只允许 1 至 3 组关联字段");
    List<Pair> pairs=new ArrayList<>();
    for(Object entry:entries){
      if(!(entry instanceof Map<?,?> pair))throw new IllegalArgumentException("关联字段格式错误");
      pairs.add(new Pair(id(pair.get("leftFieldId")),id(pair.get("rightFieldId"))));
    }
    return List.copyOf(pairs);
  }
  private List<Pair> readPairs(String json){
    try{
      var parsed=store.map("{\"pairs\":"+json+"}").get("pairs");
      return bodyPairs(parsed);
    }catch(RuntimeException invalid){return List.of();}
  }
  private static String id(Object raw){
    if(!(raw instanceof String value)||value.isBlank()||value.length()>36)
      throw new IllegalArgumentException("数据集或关系标识无效");
    return value;
  }
  private static String name(Object raw){
    if(!(raw instanceof String value)||value.trim().isBlank()||value.trim().length()>100)
      throw new IllegalArgumentException("请输入 1 至 100 字的关系名称");
    return value.trim();
  }
  private static int revision(Object raw){
    if(!(raw instanceof Integer value)||value<1)
      throw new IllegalArgumentException("请提供正确的关系草稿版本号");
    return value;
  }
}
