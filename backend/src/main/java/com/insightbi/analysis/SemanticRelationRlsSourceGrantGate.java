package com.insightbi.analysis;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Fail-closed MySQL SHOW GRANTS allowlist. This is not a query credential;
 * StarRocks privilege proofs are unsupported pending vendor verification.
 */
final class SemanticRelationRlsSourceGrantGate {
  private static final String ID="[A-Za-z_][A-Za-z_0-9]{0,63}";
  private static final String QUOTE="(?:'|\\x60)";
  private static final Pattern GRANTEE=Pattern.compile(
    "(?i)(?:"+QUOTE+ID+QUOTE+")@(?:"+QUOTE+"[^'\\x60]{1,255}"+QUOTE+")");
  private static final Pattern USAGE=Pattern.compile(
    "(?i)^GRANT USAGE ON \\*\\.\\* TO (.+)$");
  private static final Pattern SELECT=Pattern.compile(
    "(?i)^GRANT SELECT ON (?:\\x60)?("+ID+")(?:\\x60)?\\.\\* TO (.+)$");

  private SemanticRelationRlsSourceGrantGate(){}

  static void require(Connection source,String engine,String catalog)throws SQLException{
    if(source==null||source.isClosed()||source.getAutoCommit()||
       !"mysql".equalsIgnoreCase(engine)||catalog==null||
       !catalog.matches(ID)||!catalog.equalsIgnoreCase(source.getCatalog()))
      throw new IllegalStateException("来源连接、事务、引擎或数据库名称不满足账号核验条件");
    List<String> grants=new ArrayList<>();
    try(var stmt=source.createStatement();var rows=stmt.executeQuery("SHOW GRANTS")){
      while(rows.next()){
        if(grants.size()>=30)
          throw new IllegalStateException("来源数据库授权记录过多");
        grants.add(rows.getString(1));
      }
    }
    validate(catalog,grants);
  }

  static void validate(String catalog,List<String> lines){
    if(catalog==null||!catalog.matches(ID)||lines==null||
       lines.isEmpty()||lines.size()>30)
      throw new IllegalStateException("数据库授权记录不存在或超限");
    String grantee=null;
    boolean scopedSelect=false;
    for(String grant:lines){
      if(grant==null||grant.length()>1024||!grant.equals(grant.trim()))
        throw new IllegalStateException("数据库授权格式不可信");
      var usage=USAGE.matcher(grant);
      var select=SELECT.matcher(grant);
      String user;
      if(usage.matches())user=usage.group(1);
      else if(select.matches()&&catalog.equalsIgnoreCase(select.group(1))){
        user=select.group(2);
        scopedSelect=true;
      }else throw new IllegalStateException("数据库存在额外、跨库或角色授权");
      if(!GRANTEE.matcher(user).matches()||
         (grantee!=null&&!grantee.equals(user)))
        throw new IllegalStateException("授权身份格式或一致性校验失败");
      grantee=user;
    }
    if(!scopedSelect)
      throw new IllegalStateException("缺少当前数据库范围内的 SELECT 权限");
  }
}
