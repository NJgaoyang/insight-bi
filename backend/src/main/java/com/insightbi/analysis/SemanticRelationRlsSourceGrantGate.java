package com.insightbi.analysis;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Fail-closed MySQL SHOW GRANTS allowlist and principal equality proof.
 * Only called after the server-side CURRENT_USER() and approved endpoint
 * have been verified. Never grants query access by itself.
 */
final class SemanticRelationRlsSourceGrantGate {
  private static final String ID="[A-Za-z_][A-Za-z_0-9]{0,63}";
  private static final Pattern GRANTEE=Pattern.compile(
    "^(?:'("+ID+")'|\\x60("+ID+")\\x60)@"+
    "(?:'([^']{1,255})'|\\x60([^\\x60]{1,255})\\x60)$");
  private static final Pattern USAGE=Pattern.compile(
    "(?i)^GRANT USAGE ON \\*\\.\\* TO (.+)$");
  private static final Pattern SELECT=Pattern.compile(
    "(?i)^GRANT SELECT ON (?:\\x60("+ID+")\\x60|("+ID+"))\\.\\* TO (.+)$");

  private SemanticRelationRlsSourceGrantGate(){}

  static void require(Connection source,String engine,String catalog,
                      String effectivePrincipal)throws SQLException{
    if(source==null||source.isClosed()||source.getAutoCommit()||
       !"mysql".equalsIgnoreCase(engine)||catalog==null||
       !catalog.matches(ID)||!catalog.equalsIgnoreCase(source.getCatalog())||
       effectivePrincipal==null||effectivePrincipal.isBlank())
      throw new IllegalStateException("来源连接、有效账号或引擎未通过授权检查");
    List<String> grants=new ArrayList<>();
    try(var stmt=source.createStatement();var rows=stmt.executeQuery("SHOW GRANTS")){
      while(rows.next()){
        if(grants.size()>=30)
          throw new IllegalStateException("来源数据库授权记录过多");
        grants.add(rows.getString(1));
      }
    }
    validate(catalog,grants,effectivePrincipal);
  }

  static void validate(String catalog,List<String> lines){
    validate(catalog,lines,null); // Isolated grammar-only tests only.
  }

  static void validate(String catalog,List<String> lines,String effectivePrincipal){
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
      else if(select.matches()&&
              catalog.equalsIgnoreCase(
                select.group(1)==null?select.group(2):select.group(1))){
        user=select.group(3);
        scopedSelect=true;
      }else throw new IllegalStateException("数据库存在额外、跨库或角色授权");
      var match=GRANTEE.matcher(user);
      if(!match.matches())
        throw new IllegalStateException("授权身份格式无效");
      String id=match.group(1)==null?match.group(2):match.group(1);
      String host=match.group(3)==null?match.group(4):match.group(3);
      String principal=id+"@"+host;
      if((grantee!=null&&!grantee.equals(principal))||
         (effectivePrincipal!=null&&!effectivePrincipal.equals(principal)))
        throw new IllegalStateException("授权账号与当前数据库有效身份不一致");
      grantee=principal;
    }
    if(!scopedSelect)
      throw new IllegalStateException("缺少当前数据库范围内的 SELECT 权限");
  }
}
