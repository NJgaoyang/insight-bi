package com.insightbi.analysis;

import java.util.*;

/**
 * Read-only SQL PREVIEW for approved same-connection relationship metadata.
 * Never executes business queries, enables reader access or persists SQL.
 */
public final class SemanticRelationSqlPreview {
  public record Column(String name,String datasetId,String fieldId,String label,
                       String logicalType,String side){}
  public record Preview(int revision,String joinType,String sqlPreview,
                        List<Column> columns,List<String> warnings){}
  private SemanticRelationSqlPreview(){}

  public static Preview compile(Map<String,Object> leftDataset,Map<String,Object> rightDataset,
      String leftId,String rightId,List<SemanticRelationApi.Pair> pairs,
      Map<String,FieldCatalog.Field> left,Map<String,FieldCatalog.Field> right,
      int revision,Map<String,Object> request){
    if(!(request.get("joinType") instanceof String joinType)||
       !Set.of("INNER","LEFT").contains(joinType))
      throw new IllegalArgumentException("只支持 INNER 和 LEFT JOIN");
    List<String> select=new ArrayList<>();
    List<Column> cols=new ArrayList<>();
    project(request.get("leftFieldIds"),left,leftId,"LEFT","bi_left",select,cols);
    project(request.get("rightFieldIds"),right,rightId,"RIGHT","bi_right",select,cols);
    if(pairs==null||pairs.isEmpty()||pairs.size()>3)
      throw new IllegalArgumentException("关系关联字段无效");
    List<String> conditions=new ArrayList<>();
    for(var p:pairs){
      var l=left.get(p.leftFieldId());
      var r=right.get(p.rightFieldId());
      if(l==null||r==null||!l.logicalType().equals(r.logicalType()))
        throw new IllegalArgumentException("关系字段已失效");
      conditions.add("bi_left."+FormulaCompiler.quote(l.sourceColumn())+
        " = bi_right."+FormulaCompiler.quote(r.sourceColumn()));
    }
    String lhs=ReadOnlySqlGuard.validate(String.valueOf(leftDataset.get("sql_body")));
    String rhs=ReadOnlySqlGuard.validate(String.valueOf(rightDataset.get("sql_body")));
    String sql="SELECT "+String.join(", ",select)+" FROM ("+lhs+") bi_left "+
      joinType+" JOIN ("+rhs+") bi_right ON "+String.join(" AND ",conditions)+" LIMIT 200";
    if(sql.length()>100000)throw new IllegalArgumentException("关系编译 SQL 过长");
    return new Preview(revision,joinType,sql,List.copyOf(cols),
      List.of("仅供管理员核对；未执行也未授权读者访问",
              "JOIN 可能因重复键扩大行数，LIMIT 200 只限制返回行数"));
  }

  private static void project(Object raw,Map<String,FieldCatalog.Field> fields,
      String datasetId,String side,String alias,List<String> select,List<Column> columns){
    if(!(raw instanceof List<?> ids)||ids.isEmpty()||ids.size()>10)
      throw new IllegalArgumentException("每侧必须选择 1～10 个物理输出字段");
    Set<String> used=new HashSet<>();
    for(Object value:ids){
      if(!(value instanceof String fieldId)||!used.add(fieldId))
        throw new IllegalArgumentException("输出字段 ID 无效或重复");
      var field=fields.get(fieldId);
      if(field==null||field.calculated()||field.hidden()||field.sourceColumn()==null)
        throw new IllegalArgumentException("输出字段不存在、隐藏或非物理字段");
      String name=side.toLowerCase(Locale.ROOT)+"_"+(columns.size()+1);
      select.add(alias+"."+FormulaCompiler.quote(field.sourceColumn())+
        " AS "+FormulaCompiler.quote(name));
      columns.add(new Column(name,datasetId,fieldId,field.name(),field.logicalType(),side));
    }
  }
}
