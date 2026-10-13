package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * V2 query sources are required to parse as a single SELECT statement.
 * Note: read-only JDBC credentials are still mandatory: SELECT UDF side effects
 * cannot be excluded by checking the root AST alone.
 */
public final class ReadOnlySqlGuard {
  private ReadOnlySqlGuard(){}
  public static String validate(String sourceSql){
    String sql=DatabaseStore.safeSql(sourceSql);
    // Fail closed for locking clauses and all multi-statement attempts.
    if(sql.matches("(?is).*\\bfor\\s+update\\b.*")||
       sql.matches("(?is).*\\block\\s+in\\s+share\\s+mode\\b.*"))
      throw new IllegalArgumentException("不允许数据库加锁查询");
    try{
      Statement ast=CCJSqlParserUtil.parse(sql);
      if(!(ast instanceof Select))throw new IllegalArgumentException("仅允许 SELECT 数据集");
      return sql;
    }catch(IllegalArgumentException e){throw e;}
    catch(Exception e){throw new IllegalArgumentException("SQL 语法无法通过只读 AST 检查，请调整 SQL 数据集");}
  }
}
