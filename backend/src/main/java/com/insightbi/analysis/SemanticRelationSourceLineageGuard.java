package com.insightbi.analysis;

import java.util.*;
import java.util.regex.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;

/**
 * Narrow, fail-closed provenance proof for compile-only bilateral RLS plans.
 *
 * Every output is a direct base-table column (optionally renamed with AS).
 * WHERE is limited to <=4 AND-combined physical-column equality predicates
 * against bounded literals. No joins, expressions, subqueries, placeholders,
 * aggregation, OR, functions or partial best-effort rewriting are admitted.
 */
public final class SemanticRelationSourceLineageGuard {
  private static final String ID="[A-Za-z_][A-Za-z_0-9]*";
  private static final String REF=ID+"(?:\\."+ID+")?";
  private static final String OUT=REF+"(?:\\s+AS\\s+"+ID+")?";
  // Avoid escapes, comments, client-supplied placeholders and control chars.
  private static final String VALUE="(?:'[\\p{L}\\p{N} _./-]{1,100}'|[0-9]{1,12})";
  private static final String PRED=REF+"\\s*=\\s*"+VALUE;
  private static final Pattern SAFE=Pattern.compile(
    "^SELECT\\s+("+OUT+"(?:\\s*,\\s*"+OUT+")*)\\s+FROM\\s+("+REF+")"+
    "(?:\\s+(?:AS\\s+)?("+ID+"))?"+
    "(?:\\s+WHERE\\s+("+PRED+"(?:\\s+AND\\s+"+PRED+"){0,3}))?$",
    Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
  private SemanticRelationSourceLineageGuard(){}

  public static void requireDirectPhysicalSource(
      String sourceSql,Map<String,FieldCatalog.Field> fields){
    String sql=ReadOnlySqlGuard.validate(sourceSql);
    Matcher syntax=SAFE.matcher(sql);
    if(!syntax.matches())
      throw new IllegalStateException("双侧 RLS 只支持单表直接列投影及最多四条 AND 连接的字段等值 WHERE");
    try{
      Object stmt=CCJSqlParserUtil.parse(sql);
      if(!(stmt instanceof PlainSelect select)||
         !(select.getFromItem() instanceof Table table))
        throw new IllegalStateException("RLS 来源必须是直接单表 SELECT");
      String source=syntax.group(2);
      String alias=syntax.group(3);
      String tableName=source.substring(source.lastIndexOf('.')+1);
      String qualifier=alias==null?tableName:alias;
      if(table.getName()==null||!table.getName().equalsIgnoreCase(tableName)||
         ((table.getAlias()==null)!=(alias==null))||
         (alias!=null&&!table.getAlias().getName().equalsIgnoreCase(alias)))
        throw new IllegalStateException("来源表标识或别名与 SQL AST 不一致");
      Set<String> projected=new HashSet<>(),rawColumns=new HashSet<>();
      for(SelectItem<?> item:select.getSelectItems()){
        if(!(item.getExpression() instanceof Column col))
          throw new IllegalStateException("RLS 来源只能投影直接物理字段");
        String physical=validateColumn(col,qualifier);
        String output=item.getAlias()==null?col.getColumnName():item.getAlias().getName();
        if(output==null||!output.matches(ID)||
           !projected.add(output.toLowerCase(Locale.ROOT))||
           !rawColumns.add(physical))
          throw new IllegalStateException("RLS 来源存在重复物理列或不合法/重复输出别名");
      }
      if(projected.size()!=select.getSelectItems().size())
        throw new IllegalStateException("来源字段投影数量异常");
      if(fields==null||fields.isEmpty())
        throw new IllegalStateException("来源物理字段目录为空");
      for(var f:fields.values()){
        if(f.calculated()||f.hidden())continue;
        String output=f.sourceColumn();
        if(output==null||!output.matches(ID)||
           !projected.contains(output.toLowerCase(Locale.ROOT)))
          throw new IllegalStateException("来源字段无法追踪到直接物理列投影");
      }
      Expression condition=select.getWhere();
      if((condition==null)!=(syntax.group(4)==null))
        throw new IllegalStateException("WHERE AST 与完整 SQL 子语法不一致");
      if(condition!=null)validateWhere(condition,qualifier,rawColumns);
    }catch(IllegalStateException invalid){
      throw invalid;
    }catch(Exception malformed){
      throw new IllegalStateException("RLS 来源 SQL 解析或物理字段血缘验证失败",malformed);
    }
  }

  private static String validateColumn(Column col,String qualifier){
    String name=col.getColumnName();
    if(name==null||!name.matches(ID))
      throw new IllegalStateException("RLS 物理列标识不合法");
    String full=col.getFullyQualifiedName();
    if(full.contains(".")){
      String prefix=full.substring(0,full.lastIndexOf('.'));
      if(!prefix.equalsIgnoreCase(qualifier))
        throw new IllegalStateException("RLS 列引用了其他表或未知别名");
    }
    return name.toLowerCase(Locale.ROOT);
  }

  private static void validateWhere(Expression condition,String qualifier,Set<String> rawColumns){
    if(condition instanceof AndExpression and){
      validateWhere(and.getLeftExpression(),qualifier,rawColumns);
      validateWhere(and.getRightExpression(),qualifier,rawColumns);
    }else if(condition instanceof EqualsTo eq){
      if(!(eq.getLeftExpression() instanceof Column col)||
         !(eq.getRightExpression() instanceof StringValue||
           eq.getRightExpression() instanceof LongValue)||
         !rawColumns.contains(validateColumn(col,qualifier)))
        throw new IllegalStateException("RLS WHERE 只允许已投影物理列的等值字面量筛选");
    }else throw new IllegalStateException("RLS WHERE 不允许 OR、子查询或表达式");
  }
}
