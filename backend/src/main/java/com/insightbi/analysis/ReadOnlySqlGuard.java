package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Restrict persisted dataset SQL used by V2 analysis to one parsed SELECT.
 *
 * A SELECT root is not sufficient: vendor UDFs may have side effects, block
 * threads or disclose server state. Function invocations therefore require a
 * small, reviewed allowlist after parsing. Unsupported functions fail closed.
 * Real database accounts must still have read-only privileges.
 */
public final class ReadOnlySqlGuard {
  private static final Set<String> FUNCTIONS=Set.of(
      "COUNT","SUM","AVG","MIN","MAX","ABS","ROUND","TRUNCATE","COALESCE",
      "NULLIF","IFNULL","IF","CONCAT","SUBSTR","SUBSTRING","DATE_FORMAT",
      "STR_TO_DATE","CAST","CONVERT","YEAR","MONTH","DAY","DATE","DATE_ADD",
      "DATE_SUB","EXTRACT","TO_DATE","UPPER","LOWER","LENGTH","TRIM",
      "LTRIM","RTRIM","FLOOR","CEIL","CEILING","POWER","POW","MOD",
      "GREATEST","LEAST","LEFT","RIGHT","REPLACE","CURDATE","NOW",
      "CURRENT_DATE","CURRENT_TIMESTAMP","DATEDIFF","TIMESTAMPDIFF",
      "ADDDATE","SUBDATE","CONCAT_WS","GROUP_CONCAT");
  private static final Set<String> SYNTAX=Set.of(
      "SELECT","FROM","WHERE","HAVING","AND","OR","NOT","IN","EXISTS",
      "DISTINCT","ON","AS","BY","ORDER","GROUP","OVER","WITH","JOIN",
      "UNION","ALL","LIMIT","WHEN","THEN","ELSE","CASE","VALUES",
      "DECIMAL","NUMERIC","VARCHAR","CHAR","INT","INTEGER","BIGINT",
      "DOUBLE","FLOAT","SIGNED","UNSIGNED","DATETIME","TIMESTAMP",
      "BOOLEAN","BINARY","VARBINARY");
  private ReadOnlySqlGuard(){}

  public static String validate(String sourceSql){
    String sql=DatabaseStore.safeSql(sourceSql);
    if(sql.matches("(?is).*\\bfor\\s+update\\b.*")||
       sql.matches("(?is).*\\block\\s+in\\s+share\\s+mode\\b.*"))
      throw new IllegalArgumentException("不允许数据库加锁查询");
    try{
      Statement ast=CCJSqlParserUtil.parse(sql);
      if(!(ast instanceof Select))throw new IllegalArgumentException("仅允许 SELECT 数据集");
    }catch(IllegalArgumentException e){throw e;}
    catch(Exception e){throw new IllegalArgumentException("SQL 语法无法通过只读 AST 检查，请调整 SQL 数据集");}
    checkFunctions(sql);
    return sql;
  }

  /**
   * Scan only unquoted identifiers followed by '('. Literal strings, quoted
   * column names and escaped quotes must not trigger false UDF matches.
   */
  private static void checkFunctions(String sql){
    int i=0;
    while(i<sql.length()){
      char c=sql.charAt(i);
      if(c=='\''||c=='"'||c=='`'){
        char quote=c;
        i++;
        boolean closed=false;
        while(i<sql.length()){
          char next=sql.charAt(i++);
          if(next=='\\'&&i<sql.length()){i++;continue;}
          if(next==quote){
            if(i<sql.length()&&sql.charAt(i)==quote){i++;continue;}
            closed=true;break;
          }
        }
        if(!closed)throw new IllegalArgumentException("SQL 存在未闭合的字符串或标识符");
        continue;
      }
      if(Character.isLetter(c)||c=='_'){
        int start=i++;
        while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'))i++;
        String identifier=sql.substring(start,i).toUpperCase(Locale.ROOT);
        int look=i;
        while(look<sql.length()&&Character.isWhitespace(sql.charAt(look)))look++;
        if(look<sql.length()&&sql.charAt(look)=='('&&
            !SYNTAX.contains(identifier)&&!FUNCTIONS.contains(identifier))
          throw new IllegalArgumentException("SQL 数据集包含未批准函数: "+identifier);
        continue;
      }
      i++;
    }
  }
}
