package com.insightbi.analysis;

import java.util.*;
import java.util.regex.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.SelectItem;

/**
 * A deliberately small and provable source lineage subset for bilateral RLS.
 * No best-effort SQL rewriting: reject ambiguity before building any plan.
 *
 * A single base table with direct, non-aliased column projections is safe for
 * pushing exact-match row filters around that projection. All other source SQL
 * forms (including aggregation, expression aliasing, CTE, UNION and JOIN) are
 * unsupported until field-level provenance can be established.
 */
public final class SemanticRelationSourceLineageGuard {
  private static final String ID="[A-Za-z_][A-Za-z_0-9]*";
  private static final String REF=ID+"(?:\\."+ID+")?";
  // The textual allowlist eliminates clauses unsupported by the currently
  // narrow provenance proof, even if parser versions gain new AST features.
  private static final Pattern DIRECT=Pattern.compile(
    "(?i)^SELECT\\s+("+REF+"(?:\\s*,\\s*"+REF+")*)\\s+FROM\\s+("+REF+")"+
    "(?:\\s+(?:AS\\s+)?("+ID+"))?$");
  private SemanticRelationSourceLineageGuard(){}

  public static void requireDirectPhysicalSource(
      String sourceSql,Map<String,FieldCatalog.Field> fields){
    String sql=ReadOnlySqlGuard.validate(sourceSql);
    Matcher syntax=DIRECT.matcher(sql);
    if(!syntax.matches())
      throw new IllegalStateException(
        "双侧 RLS 仅支持单表直接列投影；源 SQL 含聚合、别名、筛选、关联或其他无法证明血缘的语法");
    try{
      Object stmt=CCJSqlParserUtil.parse(sql);
      if(!(stmt instanceof PlainSelect select)||
         !(select.getFromItem() instanceof Table table))
        throw new IllegalStateException("RLS 来源必须是直接单表 SELECT");
      String source=syntax.group(2);
      String alias=syntax.group(3);
      String qualifier=alias==null?
        source.substring(source.lastIndexOf('.')+1):alias;
      if(table.getName()==null||!table.getName().equalsIgnoreCase(
           source.substring(source.lastIndexOf('.')+1)))
        throw new IllegalStateException("来源表标识与 SQL AST 不一致");
      Set<String> projected=new HashSet<>();
      for(SelectItem<?> item:select.getSelectItems()){
        if(item.getAlias()!=null||!(item.getExpression() instanceof Column col))
          throw new IllegalStateException("RLS 来源只允许原始物理列");
        String colName=col.getColumnName();
        if(colName==null||!colName.matches(ID))
          throw new IllegalStateException("来源物理列名不合法");
        String full=col.getFullyQualifiedName();
        if(full.contains(".")){
          String prefix=full.substring(0,full.lastIndexOf('.'));
          if(!prefix.equalsIgnoreCase(qualifier))
            throw new IllegalStateException("投影列引用了非来源表");
        }
        if(!projected.add(colName.toLowerCase(Locale.ROOT)))
          throw new IllegalStateException("来源存在重复列输出");
      }
      if(projected.size()!=select.getSelectItems().size())
        throw new IllegalStateException("来源列目录数量不一致");
      if(fields==null||fields.isEmpty())
        throw new IllegalStateException("来源物理字段目录为空");
      for(var f:fields.values()){
        if(f.calculated()||f.hidden())continue;
        String name=f.sourceColumn();
        if(name==null||!name.matches(ID)||
           !projected.contains(name.toLowerCase(Locale.ROOT)))
          throw new IllegalStateException("来源字段无法追踪到原始物理列");
      }
    }catch(IllegalStateException invalid){
      throw invalid;
    }catch(Exception malformed){
      throw new IllegalStateException("RLS 来源 SQL 解析或字段血缘核验失败",malformed);
    }
  }
}
