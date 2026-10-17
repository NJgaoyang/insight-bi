package com.insightbi.analysis;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Compile-only dual-sided row-level-security JOIN plan.
 *
 * Reader-supplied values are NEVER accepted. Metadata policies are validated
 * and represented by JDBC '?' placeholders, and never returned as bind values.
 * This class does not execute any SQL, grant access, or alter publish gates.
 */
public final class SemanticRelationRlsPlanPreview {
  public record Preview(int revision,String joinType,String sqlPreview,
                        List<SemanticRelationSqlPreview.Column> columns,
                        int leftBindCount,int rightBindCount,int totalBindCount,
                        boolean leftPolicyApplied,boolean rightPolicyApplied,
                        int readerAuthVersion,String scope,List<String> warnings){}
  private record Policy(String where,int bindCount){}
  private SemanticRelationRlsPlanPreview(){}

  public static Preview compile(JdbcTemplate db,String readerId,
      Map<String,Object> leftDataset,Map<String,Object> rightDataset,
      String leftId,String rightId,List<SemanticRelationApi.Pair> pairs,
      Map<String,FieldCatalog.Field> leftFields,Map<String,FieldCatalog.Field> rightFields,
      int revision,Map<String,Object> selection){
    var reader=db.queryForList(
      "SELECT role,enabled,auth_version FROM insight_governance_user WHERE id=?",readerId);
    if(reader.size()!=1)throw new IllegalStateException("读者账号不存在");
    var identity=reader.get(0);
    Object enabled=identity.get("enabled"),version=identity.get("auth_version");
    boolean active=Boolean.TRUE.equals(enabled)||
      (enabled instanceof Number n&&n.intValue()==1);
    if(!active||!Set.of("VIEWER","EDITOR").contains(String.valueOf(identity.get("role")))||
       !(version instanceof Number n)||n.intValue()<0)
      throw new IllegalStateException("读者身份或认证版本已失效");

    // No field-level lineage exists for source SQL; any column deny is fatal.
    Integer denies=db.queryForObject("SELECT COUNT(*) FROM insight_reader_column_deny "+
      "WHERE user_id=? AND dataset_id IN (?,?)",Integer.class,readerId,leftId,rightId);
    if(denies==null||denies!=0)
      throw new IllegalStateException("任一侧存在列拒绝，尚不能保证安全的源字段血缘");

    // Reject sources whose output cannot be traced back to direct physical
    // columns BEFORE compiling RLS outside the source SELECT. This prevents
    // filtering pre-aggregated rows or computed/renamed tenant identifiers.
    SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(
      String.valueOf(leftDataset.get("sql_body")),leftFields);
    SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(
      String.valueOf(rightDataset.get("sql_body")),rightFields);

    var base=SemanticRelationSqlPreview.compile(leftDataset,rightDataset,leftId,rightId,
      pairs,leftFields,rightFields,revision,selection);
    Policy left=policy(db,readerId,leftId,leftFields,"bi_rls_left");
    Policy right=policy(db,readerId,rightId,rightFields,"bi_rls_right");

    String lhs=wrap(ReadOnlySqlGuard.validate(String.valueOf(leftDataset.get("sql_body"))),
      "bi_rls_left",left);
    String rhs=wrap(ReadOnlySqlGuard.validate(String.valueOf(rightDataset.get("sql_body"))),
      "bi_rls_right",right);
    List<String> outputs=new ArrayList<>();
    for(var col:base.columns()){
      boolean leftSide="LEFT".equals(col.side());
      var map=leftSide?leftFields:rightFields;
      var field=map.get(col.fieldId());
      if(field==null||field.calculated()||field.hidden()||field.sourceColumn()==null)
        throw new IllegalArgumentException("候选输出字段已失效");
      outputs.add((leftSide?"bi_left.":"bi_right.")+
        FormulaCompiler.quote(field.sourceColumn())+" AS "+FormulaCompiler.quote(col.name()));
    }
    List<String> on=new ArrayList<>();
    for(var p:pairs){
      FieldCatalog.Field a=leftFields.get(p.leftFieldId());
      FieldCatalog.Field z=rightFields.get(p.rightFieldId());
      if(a==null||z==null||a.sourceColumn()==null||z.sourceColumn()==null)
        throw new IllegalArgumentException("关联字段已失效");
      on.add("bi_left."+FormulaCompiler.quote(a.sourceColumn())+
        " = bi_right."+FormulaCompiler.quote(z.sourceColumn()));
    }
    String sql="SELECT "+String.join(", ",outputs)+" FROM "+lhs+" bi_left "+
      base.joinType()+" JOIN "+rhs+" bi_right ON "+String.join(" AND ",on)+" LIMIT 200";
    if(sql.length()>100000)
      throw new IllegalArgumentException("双侧行权限预览 SQL 过长");
    return new Preview(revision,base.joinType(),sql,base.columns(),
      left.bindCount(),right.bindCount(),left.bindCount()+right.bindCount(),
      left.bindCount()>0,right.bindCount()>0,n.intValue(),"COMPILE_ONLY_DUAL_SOURCE_RLS",
      List.of("仅编译预览，未执行 JOIN、未下发权限参数或授权读者",
              "占位符按左数据集后右数据集顺序排列；实际执行端尚未接入",
              "各侧 RLS 在 JOIN 前生效；RIGHT RLS 不得放在 LEFT JOIN 的最终 WHERE",
              "LIMIT 200 只限制返回结果，不限制源 SQL 或 JOIN 的扫描量"));
  }

  private static String wrap(String sql,String alias,Policy p){
    if(p.bindCount()==0)return "("+sql+")";
    return "(SELECT * FROM ("+sql+") "+alias+" WHERE "+p.where()+")";
  }

  /** Enforce exactly the reader policy table's singleton + 1..20 values. */
  private static Policy policy(JdbcTemplate db,String reader,String dataset,
                               Map<String,FieldCatalog.Field> fields,String alias){
    var rows=db.queryForList(
      "SELECT field_id,allowed_value FROM insight_reader_row_policy "+
      "WHERE user_id=? AND dataset_id=?",reader,dataset);
    if(rows.isEmpty())return new Policy("",0);
    if(rows.size()!=1)throw new IllegalStateException("行权限元数据数量异常");
    var row=rows.get(0);
    String id=String.valueOf(row.get("field_id"));
    var field=fields.get(id);
    if(field==null||field.calculated()||field.hidden()||field.sourceColumn()==null||
       !"STRING".equals(field.logicalType()))
      throw new IllegalStateException("行权限字段已删除、隐藏、非物理字段或不是文本字段");
    String legacy=validValue(row.get("allowed_value"));
    List<String> values=db.queryForList(
      "SELECT allowed_value FROM insight_reader_row_policy_value "+
      "WHERE user_id=? AND dataset_id=? ORDER BY allowed_value LIMIT 21",
      String.class,reader,dataset);
    if(values.isEmpty())values=List.of(legacy);
    if(values.size()>20||!values.contains(legacy)||new HashSet<>(values).size()!=values.size())
      throw new IllegalStateException("行权限允许值配置异常");
    for(String value:values)validValue(value);
    String fieldSql=alias+"."+FormulaCompiler.quote(field.sourceColumn());
    String predicate=values.size()==1?fieldSql+" = ?":
      fieldSql+" IN ("+String.join(",",Collections.nCopies(values.size(),"?"))+")";
    return new Policy(predicate,values.size());
  }
  private static String validValue(Object value){
    if(!(value instanceof String s)||s.isBlank()||s.length()>255||
       s.chars().anyMatch(Character::isISOControl))
      throw new IllegalStateException("行权限允许值格式异常");
    return s;
  }
}
