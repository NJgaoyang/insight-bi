package com.insightbi.analysis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Server-side ONLY. Audits ordered JDBC bind values against a freshly compiled
 * bilateral policy plan within the caller's REPEATABLE_READ metadata transaction.
 * Returns only an opaque commitment, never a parameter list or executable SQL.
 *
 * No source connection is opened and this class MUST NOT be used to authorize
 * or execute Reader requests. Future execution needs an independent runtime gate.
 */
final class SemanticRelationRlsBindPreflight {
  record Attestation(int leftCount,int rightCount,String bindingHash){}
  private SemanticRelationRlsBindPreflight(){}

  static Attestation inspect(JdbcTemplate db,String reader,String left,String right,
                             Map<String,FieldCatalog.Field> leftFields,
                             Map<String,FieldCatalog.Field> rightFields,
                             SemanticRelationRlsPlanPreview.Preview preview){
    if(preview==null||!Set.of("LEFT","INNER").contains(preview.joinType()))
      throw new IllegalStateException("双侧 RLS 预检缺少有效计划");
    var users=db.queryForList(
      "SELECT role,enabled,auth_version FROM insight_governance_user WHERE id=?",reader);
    if(users.size()!=1)throw new IllegalStateException("读者身份不存在");
    var identity=users.get(0);
    Object enabled=identity.get("enabled"),version=identity.get("auth_version");
    boolean active=Boolean.TRUE.equals(enabled)||
      (enabled instanceof Number n&&n.intValue()==1);
    if(!active||!Set.of("VIEWER","EDITOR").contains(String.valueOf(identity.get("role")))||
       !(version instanceof Number auth)||auth.intValue()!=preview.readerAuthVersion())
      throw new IllegalStateException("RLS 预检中读者状态或认证版本已变化");
    Integer denies=db.queryForObject(
      "SELECT COUNT(*) FROM insight_reader_column_deny "+
      "WHERE user_id=? AND dataset_id IN (?,?)",Integer.class,reader,left,right);
    if(denies==null||denies!=0)
      throw new IllegalStateException("RLS 预检遇到列权限拒绝");

    Policy l=read(db,reader,left,leftFields);
    Policy r=read(db,reader,right,rightFields);
    if(l.values().size()!=preview.leftBindCount()||
       r.values().size()!=preview.rightBindCount()||
       l.values().size()+r.values().size()!=preview.totalBindCount()||
       (l.values().isEmpty()==preview.leftPolicyApplied())||
       (r.values().isEmpty()==preview.rightPolicyApplied()))
      throw new IllegalStateException("RLS 预检参数数量或侧别与编译计划不一致");
    // All supported source SELECTs are provenance-checked and reject '?';
    // SQL identifiers are quoted by FormulaCompiler. Only RLS supplies '?'.
    long placeholders=preview.sqlPreview().chars().filter(c->c=='?').count();
    if(placeholders!=preview.totalBindCount())
      throw new IllegalStateException("RLS 计划占位符与参数数量不一致");
    return new Attestation(l.values().size(),r.values().size(),
      fingerprint(reader,left,right,preview.readerAuthVersion(),l,r));
  }

  private record Policy(String fieldId,List<String> values){}
  private static Policy read(JdbcTemplate db,String reader,String dataset,
                             Map<String,FieldCatalog.Field> fields){
    var policies=db.queryForList(
      "SELECT field_id,allowed_value FROM insight_reader_row_policy "+
      "WHERE user_id=? AND dataset_id=?",reader,dataset);
    if(policies.isEmpty()){
      Integer orphans=db.queryForObject(
        "SELECT COUNT(*) FROM insight_reader_row_policy_value "+
        "WHERE user_id=? AND dataset_id=?",Integer.class,reader,dataset);
      if(orphans==null||orphans!=0)
        throw new IllegalStateException("缺少行策略主体但存在孤立允许值");
      return new Policy("",List.of());
    }
    if(policies.size()!=1)
      throw new IllegalStateException("行权限主体必须唯一");
    var entry=policies.get(0);
    String fieldId=String.valueOf(entry.get("field_id"));
    var f=fields.get(fieldId);
    if(f==null||f.hidden()||f.calculated()||f.sourceColumn()==null||
       !"STRING".equals(f.logicalType()))
      throw new IllegalStateException("行策略字段已失效或不是原始文本列");
    String legacy=validate(entry.get("allowed_value"));
    List<String> values=db.queryForList(
      "SELECT allowed_value FROM insight_reader_row_policy_value "+
      "WHERE user_id=? AND dataset_id=? ORDER BY allowed_value LIMIT 21",
      String.class,reader,dataset);
    if(values.isEmpty())values=List.of(legacy);
    if(values.size()>20||!values.contains(legacy)||
       new HashSet<>(values).size()!=values.size())
      throw new IllegalStateException("行策略允许值数量、去重或主值不一致");
    for(String value:values)validate(value);
    return new Policy(fieldId,List.copyOf(values));
  }
  private static String validate(Object value){
    if(!(value instanceof String s)||s.isBlank()||s.length()>255||
       s.chars().anyMatch(Character::isISOControl))
      throw new IllegalStateException("行策略绑定值格式异常");
    return s;
  }

  private static String fingerprint(String reader,String left,String right,int auth,
                                    Policy l,Policy r){
    try{
      var digest=MessageDigest.getInstance("SHA-256");
      for(String item:List.of("semantic-rls-binds-v1",reader,left,right,
          String.valueOf(auth))){
        add(digest,item);
      }
      addPolicy(digest,"LEFT",l);
      addPolicy(digest,"RIGHT",r);
      return HexFormat.of().formatHex(digest.digest());
    }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 不可用",e);}
  }
  private static void addPolicy(MessageDigest digest,String side,Policy p){
    add(digest,side);
    add(digest,p.fieldId());
    add(digest,Integer.toString(p.values().size()));
    for(String value:p.values())add(digest,value);
  }
  private static void add(MessageDigest digest,String s){
    byte[] data=s.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(4).putInt(data.length).array());
    digest.update(data);
  }
}
