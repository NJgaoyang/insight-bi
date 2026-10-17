package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal-only approval/source proof, never a reader query grant.
 * Must be called WITHIN the locked metadata lifecycle transaction before any
 * external source connection is opened.
 */
final class SemanticRelationRlsApprovedSourceGate {
  record VerifiedSource(String engine,String catalog,String host,int port,String username){}
  private SemanticRelationRlsApprovedSourceGate(){}

  static VerifiedSource require(JdbcTemplate db,DatabaseStore store,
      FieldCatalog catalog,String relationId,String reader,String leftId,
      String rightId,SemanticRelationRlsPlanPreview.Preview requested,
      SemanticRelationRlsBindPreflight.Attestation expected){
    if(!TransactionSynchronizationManager.isActualTransactionActive()||
       relationId==null||relationId.isBlank()||reader==null||
       requested==null||expected==null||catalog==null||store==null)
      throw new IllegalStateException("来源指纹门禁需要已开启的元数据授权事务");

    // Lock relationship, both datasets, their persisted physical field rows,
    // and connection definition while the source statement lifecycle runs.
    var relations=db.queryForList(
      "SELECT source_dataset_id,target_dataset_id,pairs_json,revision,"+
      "approved_revision,approved_source_hash FROM insight_semantic_relation "+
      "WHERE id=? FOR UPDATE",relationId);
    if(relations.size()!=1)throw new IllegalStateException("已确认的语义关系不存在");
    var relation=relations.get(0);
    if(!leftId.equals(relation.get("source_dataset_id"))||
       !rightId.equals(relation.get("target_dataset_id"))||
       !(relation.get("revision") instanceof Number revision)||
       !(relation.get("approved_revision") instanceof Number approved)||
       revision.intValue()!=requested.revision()||
       approved.intValue()!=requested.revision())
      throw new IllegalStateException("关系左右来源、修订或确认版本已变更");
    if(leftId.equals(rightId))
      throw new IllegalStateException("关联来源不能相同");

    // Consistent lock ordering reduces collisions with other gate users.
    var ids=new ArrayList<>(List.of(leftId,rightId));
    ids.sort(String::compareTo);
    Map<String,Map<String,Object>> locked=new HashMap<>();
    for(String id:ids){
      var sources=db.queryForList(
        "SELECT id,connection_id,sql_body,fields_json "+
        "FROM insight_dataset WHERE id=? FOR UPDATE",id);
      if(sources.size()!=1)throw new IllegalStateException("关系来源数据集缺失");
      locked.put(id,sources.get(0));
    }
    var left=locked.get(leftId);
    var right=locked.get(rightId);
    String connectionId=String.valueOf(left.get("connection_id"));
    if(!connectionId.equals(right.get("connection_id"))||
       connectionId.isBlank()||connectionId.equals("null"))
      throw new IllegalStateException("双方数据集不属于同一个注册连接");

    // Existing rows are locked; this is NOT a phantom-insert or cross-DB
    // snapshot guarantee. Do not claim runtime readiness based on this gate.
    db.queryForList("SELECT id FROM insight_dataset_field "+
      "WHERE dataset_id IN (?,?) ORDER BY id FOR UPDATE",leftId,rightId);
    var connections=db.queryForList(
      "SELECT id,engine,host,port,db_name,username,credential "+
      "FROM insight_connection WHERE id=? FOR UPDATE",connectionId);
    if(connections.size()!=1)
      throw new IllegalStateException("连接未登记，旧版来源批准不可供执行准备");
    var connection=connections.get(0);
    List<FieldCatalog.Field> lCatalog=catalog.fields(leftId);
    List<FieldCatalog.Field> rCatalog=catalog.fields(rightId);
    String hash=SemanticRelationSourceFingerprint.computeWithConnection(
      left,right,lCatalog,rCatalog,connection);
    if(!hash.equals(relation.get("approved_source_hash")))
      throw new IllegalStateException("批准时的 SQL、字段或连接配置已变化，须重新审批");

    var lf=active(lCatalog);
    var rf=active(rCatalog);
    var pairs=readPairs(store,relation.get("pairs_json"));
    List<String> lOut=new ArrayList<>(),rOut=new ArrayList<>();
    if(requested.columns()==null||requested.columns().size()<2||
       requested.columns().size()>20)
      throw new IllegalStateException("JOIN 输出绑定无效");
    for(var col:requested.columns()){
      if("LEFT".equals(col.side())&&leftId.equals(col.datasetId()))
        lOut.add(col.fieldId());
      else if("RIGHT".equals(col.side())&&rightId.equals(col.datasetId()))
        rOut.add(col.fieldId());
      else throw new IllegalStateException("JOIN 输出列的来源或侧别已漂移");
    }
    var selection=Map.<String,Object>of(
      "joinType",requested.joinType(),
      "leftFieldIds",lOut,"rightFieldIds",rOut);
    var fresh=SemanticRelationRlsPlanPreview.compile(db,reader,left,right,
      leftId,rightId,pairs,lf,rf,requested.revision(),selection);
    if(!fresh.sqlPreview().equals(requested.sqlPreview())||
       !fresh.columns().stream().map(SemanticRelationSqlPreview.Column::fieldId)
          .toList().equals(requested.columns().stream()
             .map(SemanticRelationSqlPreview.Column::fieldId).toList()))
      throw new IllegalStateException("当前批准关系编译结果与原始计划不一致");
    var bound=SemanticRelationRlsBindPreflight.inspect(
      db,reader,leftId,rightId,lf,rf,fresh);
    if(bound.leftCount()!=expected.leftCount()||
       bound.rightCount()!=expected.rightCount()||
       !bound.bindingHash().equals(expected.bindingHash()))
      throw new IllegalStateException("重新编译的 RLS 绑定与预期不一致");
    String engine=String.valueOf(connection.get("engine"));
    String dbName=String.valueOf(connection.get("db_name"));
    String host=String.valueOf(connection.get("host"));
    String username=String.valueOf(connection.get("username"));
    if(!"mysql".equals(engine)||dbName.isBlank()||
       host.isBlank()||username.isBlank()||
       !(connection.get("port") instanceof Number port)||
       port.intValue()<1||port.intValue()>65535)
      throw new IllegalStateException("当前来源引擎或连接配置不具备只读证明");
    return new VerifiedSource(engine,dbName,host,port.intValue(),username);
  }

  private static Map<String,FieldCatalog.Field> active(List<FieldCatalog.Field> list){
    var fields=new LinkedHashMap<String,FieldCatalog.Field>();
    for(var f:list)if(!f.calculated()&&!f.hidden())fields.put(f.id(),f);
    return fields;
  }

  private static List<SemanticRelationApi.Pair> readPairs(DatabaseStore store,Object source){
    List<SemanticRelationApi.Pair> pairs=new ArrayList<>();
    for(var pair:store.arr(String.valueOf(source))){
      if(!pair.keySet().equals(Set.of("leftFieldId","rightFieldId"))||
         !(pair.get("leftFieldId") instanceof String l)||
         !(pair.get("rightFieldId") instanceof String r))
        throw new IllegalStateException("关系键目录结构异常");
      pairs.add(new SemanticRelationApi.Pair(l,r));
    }
    if(pairs.isEmpty()||pairs.size()>3)
      throw new IllegalStateException("已确认的 JOIN 关系键无效");
    return List.copyOf(pairs);
  }
}
