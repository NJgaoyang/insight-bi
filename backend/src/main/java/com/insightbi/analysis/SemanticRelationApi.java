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

  /**
   * Administrator-only dual-sided row-policy plan preview. This is not the
   * prior conservative access review, not a release credential or a query API.
   * Neither JDBC source connections nor permission values leave this endpoint.
   */
  @PostMapping("/{relationId}/rls-plan-preview")
  public SemanticRelationRlsPlanPreview.Preview rlsPlanPreview(
      @PathVariable String relationId,@RequestBody Map<String,Object> body){
    if(body==null||body.size()!=5||
       !Set.of("revision","readerId","joinType","leftFieldIds","rightFieldIds")
         .equals(body.keySet()))
      throw new IllegalArgumentException("双侧行权限预览参数不合法");
    String relation=id(relationId),reader=id(body.get("readerId"));
    int version=revision(body.get("revision"));
    CheckedRelation checked=checkedForReview(relation,version);
    Map<String,Object> projection=new LinkedHashMap<>(body);
    projection.remove("revision");
    projection.remove("readerId");
    return SemanticRelationRlsPlanPreview.compile(db,reader,
      store.dataset(checked.leftId()),store.dataset(checked.rightId()),
      checked.leftId(),checked.rightId(),checked.pairs(),
      physicalFields(checked.leftId()),physicalFields(checked.rightId()),version,projection);
  }

  /**
   * Short-lived encrypted compile-only receipt. Identity, source revision,
   * SQL shape and all effective row/column ACL metadata must be rechecked.
   * No source JDBC, grants, publish state or executable SQL are created.
   */
  @PostMapping("/{relationId}/rls-plan-snapshot")
  @Transactional(readOnly=true,
    isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String,Object> rlsPlanSnapshot(@PathVariable String relationId,
                                            @RequestBody Map<String,Object> body){
    if(body==null||!body.keySet().equals(
        Set.of("revision","readerId","joinType","leftFieldIds","rightFieldIds")))
      throw new IllegalArgumentException("计划快照参数不合法");
    String relation=id(relationId),reader=id(body.get("readerId"));
    int version=revision(body.get("revision"));
    CheckedRelation checked=checkedForReview(relation,version);
    String before=SemanticRelationRlsPlanSnapshot.policyEvidence(
      db,store,reader,checked.leftId(),checked.rightId());
    var plan=rlsPlanPreview(relation,body);
    CheckedRelation after=checkedForReview(relation,version);
    String policy=SemanticRelationRlsPlanSnapshot.policyEvidence(
      db,store,reader,after.leftId(),after.rightId());
    if(!after.sourceHash().equals(checked.sourceHash())||!before.equals(policy))
      throw new IllegalStateException("编译期间来源或读者策略发生变化，请重新检查");
    var binding=new LinkedHashMap<String,Object>();
    for(String key:List.of("joinType","leftFieldIds","rightFieldIds"))
      binding.put(key,body.get(key));
    String ticket=SemanticRelationRlsPlanSnapshot.seal(store,relation,reader,version,
      checked.sourceHash(),policy,binding,plan);
    return Map.of("status","CAPTURED_ONLY","executable",false,
      "ttlSeconds",SemanticRelationRlsPlanSnapshot.TTL_SECONDS,"ticket",ticket);
  }

  /** Revalidate the metadata receipt against CURRENT source and ACL state. */
  @PostMapping("/{relationId}/rls-plan-verify")
  @Transactional(readOnly=true,
    isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public SemanticRelationRlsPlanSnapshot.Verification rlsPlanVerify(
      @PathVariable String relationId,@RequestBody Map<String,Object> body){
    if(body==null||!body.keySet().equals(Set.of("ticket"))||
       !(body.get("ticket") instanceof String ticket))
      throw new IllegalArgumentException("计划核验参数不合法");
    String relation=id(relationId);
    var snapshot=SemanticRelationRlsPlanSnapshot.open(store,ticket);
    if(!relation.equals(snapshot.get("relation")))
      throw new IllegalArgumentException("票据与关系标识不匹配");
    if(SemanticRelationRlsPlanSnapshot.expired(snapshot))
      return new SemanticRelationRlsPlanSnapshot.Verification(
        "STALE","计划票据已过期，请重新生成",false);
    try{
      String reader=id(snapshot.get("reader"));
      int version=revision(snapshot.get("revision"));
      var checked=checkedForReview(relation,version);
      if(!checked.sourceHash().equals(snapshot.get("sourceHash")))
        return new SemanticRelationRlsPlanSnapshot.Verification(
          "STALE","关系数据来源已变化",false);
      String before=SemanticRelationRlsPlanSnapshot.policyEvidence(
        db,store,reader,checked.leftId(),checked.rightId());
      if(!before.equals(snapshot.get("policyHash")))
        return new SemanticRelationRlsPlanSnapshot.Verification(
          "STALE","读者权限配置已变化",false);
      @SuppressWarnings("unchecked")
      Map<String,Object> binding=(Map<String,Object>)snapshot.get("binding");
      var request=new LinkedHashMap<String,Object>(binding);
      request.put("readerId",reader);
      request.put("revision",version);
      var compiled=rlsPlanPreview(relation,request);
      var again=checkedForReview(relation,version);
      var policyAgain=SemanticRelationRlsPlanSnapshot.policyEvidence(
        db,store,reader,again.leftId(),again.rightId());
      if(!again.sourceHash().equals(checked.sourceHash())||
         !before.equals(policyAgain)||
         !SemanticRelationRlsPlanSnapshot.hash(compiled.sqlPreview())
           .equals(snapshot.get("planHash")))
        return new SemanticRelationRlsPlanSnapshot.Verification(
          "STALE","源数据、权限或编译形状已变化",false);
      return new SemanticRelationRlsPlanSnapshot.Verification(
        "CURRENT","当前仅证明元数据计划一致；仍禁止读者 JOIN 执行及发布",false);
    }catch(RuntimeException invalid){
      return new SemanticRelationRlsPlanSnapshot.Verification(
        "STALE","关系修订、字段、读者策略或计划已失效",false);
    }
  }

  /**
   * Review-only metadata binding. This does NOT publish a dashboard, expose
   * model SQL to a reader or grant any reader access.
   */
  @PostMapping("/{relationId}/access-review")
  @Transactional
  public Map<String,Object> reviewAccess(@PathVariable String relationId,
                                          @RequestBody Map<String,Object> body){
    if(body==null||body.size()!=5||
       !Set.of("revision","readerId","joinType","leftFieldIds","rightFieldIds")
         .containsAll(body.keySet()))
      throw new IllegalArgumentException("预发布审查参数不合法");
    int revision=revision(body.get("revision"));
    String readerId=id(body.get("readerId"));
    String relation=id(relationId);
    var checked=checkedForReview(relation,revision);
    var projection=Map.<String,Object>of("joinType",body.get("joinType"),
      "leftFieldIds",body.get("leftFieldIds"),"rightFieldIds",body.get("rightFieldIds"));
    // Reuse the exact physical-field and read-only SELECT compiler. It only
    // compiles SQL locally; the generated SQL is never stored or executed.
    SemanticRelationSqlPreview.compile(store.dataset(checked.leftId()),
      store.dataset(checked.rightId()),checked.leftId(),checked.rightId(),
      checked.pairs(),physicalFields(checked.leftId()),
      physicalFields(checked.rightId()),revision,projection);
    var allowed=SemanticRelationAccessReview.evaluate(db,readerId,checked.leftId(),checked.rightId());
    if(!allowed.eligible())throw new IllegalStateException(allowed.reason());
    String binding=store.json(projection);
    if(binding.length()>4000)throw new IllegalArgumentException("预发布字段绑定过长");
    // Metadata-only upsert. Concurrent reviewers cannot accidentally overwrite
    // another reader's row due to the (relation_id,reader_id) composite key.
    int written=db.update("UPDATE insight_semantic_access_review SET "+
      "relation_revision=?,source_hash=?,policy_hash=?,binding_json=?,reviewed_at=CURRENT_TIMESTAMP "+
      "WHERE relation_id=? AND reader_id=?",
      revision,checked.sourceHash(),allowed.fingerprint(),binding,relation,readerId);
    if(written==0)db.update("INSERT INTO insight_semantic_access_review "+
      "(relation_id,reader_id,relation_revision,source_hash,policy_hash,binding_json) "+
      "VALUES(?,?,?,?,?,?)",relation,readerId,revision,checked.sourceHash(),
      allowed.fingerprint(),binding);
    return Map.of("relationId",relation,"readerId",readerId,"revision",revision,
      "status","READY_FOR_REVIEW","reason","仅审查元数据；未授予读者或报表发布权限");
  }

  @GetMapping("/{relationId}/access-review")
  public Map<String,Object> reviewStatus(@PathVariable String relationId,
                                          @RequestParam String readerId){
    String relation=id(relationId),reader=id(readerId);
    var review=db.queryForList(
      "SELECT relation_revision,source_hash,policy_hash,binding_json "+
      "FROM insight_semantic_access_review WHERE relation_id=? AND reader_id=?",
      relation,reader);
    if(review.isEmpty())return reviewResult(relation,reader,0,"MISSING","尚无审查记录");
    if(review.size()!=1)throw new IllegalStateException("预发布审查记录重复");
    var row=review.get(0);
    int revision=((Number)row.get("relation_revision")).intValue();
    try{
      var checked=checkedForReview(relation,revision);
      if(!checked.sourceHash().equals(row.get("source_hash")))
        return reviewResult(relation,reader,revision,"STALE","来源已变化，需重新审查");
      var binding=store.map(String.valueOf(row.get("binding_json")));
      if(binding.size()!=3||
         !Set.of("joinType","leftFieldIds","rightFieldIds").equals(binding.keySet()))
        return reviewResult(relation,reader,revision,"STALE","已保存的字段绑定无效");
      SemanticRelationSqlPreview.compile(store.dataset(checked.leftId()),
        store.dataset(checked.rightId()),checked.leftId(),checked.rightId(),checked.pairs(),
        physicalFields(checked.leftId()),physicalFields(checked.rightId()),revision,binding);
      var policy=SemanticRelationAccessReview.evaluate(db,reader,checked.leftId(),checked.rightId());
      if(!policy.eligible())
        return reviewResult(relation,reader,revision,"BLOCKED",policy.reason());
      if(!policy.fingerprint().equals(row.get("policy_hash")))
        return reviewResult(relation,reader,revision,"STALE","读者授权状态已改变，需重新审查");
      return reviewResult(relation,reader,revision,"READY_FOR_REVIEW",
        "仍需独立完成真实报表授权、双侧行列权限及发布绑定");
    }catch(RuntimeException outdated){
      return reviewResult(relation,reader,revision,"STALE","关系修订、字段或源快照已失效");
    }
  }

  private record CheckedRelation(String leftId,String rightId,List<Pair> pairs,String sourceHash){}
  private CheckedRelation checkedForReview(String relationId,int expected){
    var rows=db.queryForList(
      "SELECT source_dataset_id,target_dataset_id,pairs_json,revision,approved_revision,approved_source_hash "+
      "FROM insight_semantic_relation WHERE id=?",relationId);
    if(rows.size()!=1)throw new IllegalStateException("关系不存在或已删除");
    var row=rows.get(0);
    int actual=((Number)row.get("revision")).intValue();
    int approved=row.get("approved_revision") instanceof Number n?n.intValue():0;
    if(actual!=expected||approved!=expected)
      throw new IllegalStateException("关系未确认或修订已变化");
    String left=String.valueOf(row.get("source_dataset_id"));
    String right=String.valueOf(row.get("target_dataset_id"));
    var pairs=readPairs(String.valueOf(row.get("pairs_json")));
    validate(left,right,pairs);
    String source=sourceFingerprint(left,right);
    if(!source.equals(row.get("approved_source_hash")))
      throw new IllegalStateException("关系源数据变化，需重新确认");
    return new CheckedRelation(left,right,pairs,source);
  }
  private static Map<String,Object> reviewResult(String relation,String reader,int revision,
                                                 String status,String reason){
    return Map.of("relationId",relation,"readerId",reader,"revision",revision,
      "status",status,"reason",reason);
  }

  /**
   * Immutable model release CANDIDATE bound to one reviewed reader. This has
   * no dashboard version or Reader API execution binding; "sealed" is not "published".
   */
  @PostMapping("/{relationId}/release-snapshots")
  @Transactional
  public Map<String,Object> sealSnapshot(@PathVariable String relationId,
                                          @RequestBody Map<String,Object> body){
    if(body==null||body.size()!=3||
       !Set.of("revision","readerId","acknowledgeNotPublished").equals(body.keySet())||
       !Boolean.TRUE.equals(body.get("acknowledgeNotPublished")))
      throw new IllegalArgumentException("必须确认仅封存版本，不对读者发布");
    String relation=id(relationId),reader=id(body.get("readerId"));
    int expected=revision(body.get("revision"));
    // Serialize release_no generation and concurrent relation edits.
    var lock=db.queryForList("SELECT id FROM insight_semantic_relation WHERE id=? FOR UPDATE",relation);
    if(lock.size()!=1)throw new IllegalStateException("关系草稿已删除");
    CheckedRelation checked=checkedForReview(relation,expected);
    var status=reviewStatus(relation,reader);
    if(!"READY_FOR_REVIEW".equals(status.get("status"))||
       !Objects.equals(status.get("revision"),expected))
      throw new IllegalStateException("当前读者的权限审查已失效，请重新审查");
    var reviewed=db.queryForList(
      "SELECT relation_revision,source_hash,policy_hash,binding_json "+
      "FROM insight_semantic_access_review WHERE relation_id=? AND reader_id=?",
      relation,reader);
    if(reviewed.size()!=1)throw new IllegalStateException("读者审查记录不存在");
    var reviewedRow=reviewed.get(0);
    if(!Objects.equals(reviewedRow.get("source_hash"),checked.sourceHash())||
       !Objects.equals(reviewedRow.get("relation_revision"),expected))
      throw new IllegalStateException("审查版本已变化");
    String binding=String.valueOf(reviewedRow.get("binding_json"));
    String policy=String.valueOf(reviewedRow.get("policy_hash"));
    if(binding.length()>4000||policy.length()!=64)
      throw new IllegalStateException("审查快照格式不合法");
    // reviewStatus already recompiled trusted field IDs, checked source hash,
    // and checked this reader's deny/row policy and auth_version.
    Integer next=db.queryForObject(
      "SELECT COALESCE(MAX(release_no),0)+1 FROM insight_semantic_release_snapshot "+
      "WHERE relation_id=? AND reader_id=?",Integer.class,relation,reader);
    if(next==null||next<1||next>1000)throw new IllegalStateException("模型快照版本数量超限");
    String hash=releaseDigest(relation,reader,next,expected,checked.sourceHash(),policy,binding);
    db.update("INSERT INTO insight_semantic_release_snapshot "+
      "(id,relation_id,reader_id,release_no,relation_revision,source_hash,policy_hash,binding_json,snapshot_hash) "+
      "VALUES(?,?,?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(),relation,reader,next,expected,checked.sourceHash(),
      policy,binding,hash);
    return snapshotResult(next,expected,"SEALED_ONLY",
      "不可变候选版本已封存；未绑定报表发布，未开放读者执行");
  }

  @GetMapping("/{relationId}/release-snapshots")
  public List<Map<String,Object>> releaseSnapshots(@PathVariable String relationId,
                                                     @RequestParam String readerId){
    String relation=id(relationId),reader=id(readerId);
    var rows=db.queryForList(
      "SELECT release_no,relation_revision,source_hash,policy_hash,binding_json,snapshot_hash "+
      "FROM insight_semantic_release_snapshot WHERE relation_id=? AND reader_id=? "+
      "ORDER BY release_no DESC LIMIT 51",relation,reader);
    if(rows.size()>50)throw new IllegalStateException("模型候选版本较多，已超过单次展示上限");
    List<Map<String,Object>> result=new ArrayList<>();
    for(var row:rows){
      int release=((Number)row.get("release_no")).intValue();
      int revision=((Number)row.get("relation_revision")).intValue();
      String status="SEALED_ONLY",reason="仅为候选封存版本，不授予访问权";
      try{
        String source=String.valueOf(row.get("source_hash"));
        String policyHash=String.valueOf(row.get("policy_hash"));
        String json=String.valueOf(row.get("binding_json"));
        if(!releaseDigest(relation,reader,release,revision,source,policyHash,json)
          .equals(row.get("snapshot_hash")))
          throw new IllegalStateException("候选版本完整性校验失败");
        CheckedRelation checked=checkedForReview(relation,revision);
        if(!checked.sourceHash().equals(source))
          throw new IllegalStateException("两侧数据集元数据变化");
        var binding=store.map(json);
        if(binding.size()!=3||
           !Set.of("joinType","leftFieldIds","rightFieldIds").equals(binding.keySet()))
          throw new IllegalStateException("候选字段映射不合法");
        SemanticRelationSqlPreview.compile(store.dataset(checked.leftId()),
          store.dataset(checked.rightId()),checked.leftId(),checked.rightId(),
          checked.pairs(),physicalFields(checked.leftId()),physicalFields(checked.rightId()),
          revision,binding);
        var policy=SemanticRelationAccessReview.evaluate(db,reader,checked.leftId(),checked.rightId());
        if(!policy.eligible()){
          status="BLOCKED";reason=policy.reason();
        }else if(!policy.fingerprint().equals(policyHash)){
          status="STALE";reason="读者权限版本变化，须重新审查并封存新版本";
        }
      }catch(RuntimeException stale){
        status="STALE";reason="关系、来源或候选版本已变化，须重新封存";
      }
      result.add(snapshotResult(release,revision,status,reason));
    }
    return List.copyOf(result);
  }

  /** Exact, non-paginated candidate validation for a draft widget. Not a grant. */
  public void requireSealedCandidate(String relationId,String readerId,int releaseNo,
                                     String expectedPrimaryDatasetId){
    String relation=id(relationId),reader=id(readerId);
    if(releaseNo<1||releaseNo>1000)throw new IllegalArgumentException("候选版本号无效");
    var rows=db.queryForList(
      "SELECT relation_revision,source_hash,policy_hash,binding_json,snapshot_hash "+
      "FROM insight_semantic_release_snapshot WHERE relation_id=? AND reader_id=? AND release_no=?",
      relation,reader,releaseNo);
    if(rows.size()!=1)throw new IllegalStateException("语义候选版本不存在");
    try{
      var row=rows.get(0);
      int rev=((Number)row.get("relation_revision")).intValue();
      String source=String.valueOf(row.get("source_hash"));
      String policyHash=String.valueOf(row.get("policy_hash"));
      String json=String.valueOf(row.get("binding_json"));
      if(json.length()>4000||!releaseDigest(relation,reader,releaseNo,
          rev,source,policyHash,json).equals(row.get("snapshot_hash")))
        throw new IllegalStateException("候选版本完整性校验失败");
      CheckedRelation checked=checkedForReview(relation,rev);
      if(!checked.leftId().equals(expectedPrimaryDatasetId)||
         !checked.sourceHash().equals(source))
        throw new IllegalStateException("候选版本来源不匹配");
      var binding=store.map(json);
      if(binding.size()!=3||
         !Set.of("joinType","leftFieldIds","rightFieldIds").equals(binding.keySet()))
        throw new IllegalStateException("候选字段绑定无效");
      SemanticRelationSqlPreview.compile(store.dataset(checked.leftId()),
        store.dataset(checked.rightId()),checked.leftId(),checked.rightId(),checked.pairs(),
        physicalFields(checked.leftId()),physicalFields(checked.rightId()),rev,binding);
      var policy=SemanticRelationAccessReview.evaluate(db,reader,checked.leftId(),checked.rightId());
      if(!policy.eligible()||!policy.fingerprint().equals(policyHash))
        throw new IllegalStateException("读者权限已变化");
    }catch(RuntimeException stale){
      throw new IllegalStateException("候选版本已失效，请重新审查并封存",stale);
    }
  }

  private static String releaseDigest(String relation,String reader,int release,int revision,
                                      String source,String policy,String binding){
    try{
      var digest=java.security.MessageDigest.getInstance("SHA-256");
      for(String part:List.of("semantic-release-v1",relation,reader,
          Integer.toString(release),Integer.toString(revision),source,policy,binding)){
        byte[] bytes=part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    }catch(java.security.NoSuchAlgorithmException e){
      throw new IllegalStateException("SHA-256 不可用",e);
    }
  }
  private static Map<String,Object> snapshotResult(int release,int revision,
                                                   String status,String reason){
    return Map.of("releaseNo",release,"relationRevision",revision,
      "status",status,"reason",reason);
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
