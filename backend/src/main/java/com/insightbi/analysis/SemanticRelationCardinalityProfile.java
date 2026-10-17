package com.insightbi.analysis;

import java.sql.*;
import java.util.*;

/**
 * Metadata-bound right-hand JOIN key SAMPLE. No JOIN is executed and
 * no external connection is opened by this compiler/runner.
 * Even a limited source SELECT may incur large scans before LIMIT:
 * callers must use SELECT-only DB grants, admission and timeouts.
 */
public final class SemanticRelationCardinalityProfile {
  public static final int SAMPLE_LIMIT=2000;
  public record Plan(String sql,int limit){}
  public record Result(long sampledRows,long nullKeyRows,long keyGroups,
      long duplicateGroups,long extraRightRows,long maxRightMultiplicity,
      boolean hasDuplicateKeys,boolean sampleCapReached,int sampleLimit,
      boolean exactSourceCount,boolean exactJoinRowCount,String scope,
      long executionMs,String warning){}
  private SemanticRelationCardinalityProfile(){}
  public static Plan plan(String rightSql,List<SemanticRelationApi.Pair> pairs,
                          Map<String,FieldCatalog.Field> rightFields){
    return plan(rightSql,pairs,rightFields,SAMPLE_LIMIT);
  }
  static Plan plan(String rightSql,List<SemanticRelationApi.Pair> pairs,
                   Map<String,FieldCatalog.Field> rightFields,int limit){
    if(limit<1||limit>SAMPLE_LIMIT)throw new IllegalArgumentException("抽样行数超限");
    if(pairs==null||pairs.isEmpty()||pairs.size()>3)
      throw new IllegalArgumentException("关联键数量无效");
    String source=ReadOnlySqlGuard.validate(rightSql);
    List<String> columns=new ArrayList<>(),keys=new ArrayList<>(),nulls=new ArrayList<>();
    Set<String> used=new HashSet<>();
    for(int i=0;i<pairs.size();i++){
      String id=pairs.get(i).rightFieldId();
      var f=rightFields.get(id);
      if(f==null||f.calculated()||f.hidden()||f.sourceColumn()==null||!used.add(id))
        throw new IllegalArgumentException("关系键不存在、隐藏或重复");
      String key=FormulaCompiler.quote("bi_k"+(i+1));
      columns.add("bis."+FormulaCompiler.quote(f.sourceColumn())+" AS "+key);
      keys.add("sample."+key);
      nulls.add("sample."+key+" IS NULL");
    }
    String sampled="SELECT "+String.join(", ",columns)+" FROM ("+source+") bis LIMIT "+limit;
    String group="SELECT COUNT(*) AS c, CASE WHEN "+String.join(" OR ",nulls)+
      " THEN 1 ELSE 0 END AS invalid_key FROM ("+sampled+") sample GROUP BY "+
      String.join(", ",keys);
    String sql="SELECT COALESCE(SUM(c),0) AS sampled_rows, "+
      "COALESCE(SUM(CASE WHEN invalid_key=1 THEN c ELSE 0 END),0) AS null_key_rows, "+
      "COALESCE(SUM(CASE WHEN invalid_key=0 THEN 1 ELSE 0 END),0) AS key_groups, "+
      "COALESCE(SUM(CASE WHEN invalid_key=0 AND c>1 THEN 1 ELSE 0 END),0) AS duplicate_groups, "+
      "COALESCE(SUM(CASE WHEN invalid_key=0 AND c>1 THEN c-1 ELSE 0 END),0) AS extra_rows, "+
      "COALESCE(MAX(CASE WHEN invalid_key=0 THEN c ELSE 0 END),0) AS max_multiplicity "+
      "FROM ("+group+") bi_groups";
    if(sql.length()>30000)throw new IllegalArgumentException("诊断 SQL 太长");
    return new Plan(sql,limit);
  }
  public static Result run(Connection c,Plan plan)throws SQLException{
    long started=System.nanoTime();
    try(var stmt=c.prepareStatement(plan.sql())){
      stmt.setQueryTimeout(8);
      stmt.setMaxRows(1);
      try(var rs=stmt.executeQuery()){
        if(!rs.next())throw new IllegalStateException("关联风险诊断缺少统计结果");
        long rows=rs.getLong(1),nulls=rs.getLong(2),groups=rs.getLong(3),
          duplicates=rs.getLong(4),extra=rs.getLong(5),max=rs.getLong(6);
        if(rows<0||rows>plan.limit()||nulls<0||nulls>rows||
           groups<0||duplicates<0||extra<0||max<0)
          throw new IllegalStateException("关联风险统计结果不合法");
        return new Result(rows,nulls,groups,duplicates,extra,max,duplicates>0,
          rows>=plan.limit(),plan.limit(),false,false,"RIGHT_SOURCE_SAMPLE",
          (System.nanoTime()-started)/1_000_000,
          "仅代表右表至多 "+plan.limit()+" 行样本；无重复不等于全表唯一；并非 JOIN 最终行数");
      }
    }
  }
}
