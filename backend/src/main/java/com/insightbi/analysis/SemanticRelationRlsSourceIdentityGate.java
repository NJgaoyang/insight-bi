package com.insightbi.analysis;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Internal-only driver-reported JDBC identity and server CURRENT_USER() proof.
 *
 * Driver URL is NOT cryptographic host authentication: this must still be
 * paired with a trustworthy TLS connection and a real read-only MySQL account
 * before any production Reader query can be enabled.
 */
final class SemanticRelationRlsSourceIdentityGate {
  private static final Pattern ID=Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,63}");
  private static final Pattern GRANT_HOST=Pattern.compile("[A-Za-z0-9_.%:-]{1,255}");
  private SemanticRelationRlsSourceIdentityGate(){}

  static String require(Connection source,String host,int port,String catalog,
                        String username)throws SQLException{
    if(source==null||source.isClosed()||source.getAutoCommit()||
       host==null||host.isBlank()||catalog==null||!ID.matcher(catalog).matches()||
       username==null||!ID.matcher(username).matches()||
       port<1||port>65535||source.getMetaData()==null)
      throw new IllegalStateException("来源连接身份校验缺少可信配置或显式事务");
    var metadata=source.getMetaData();
    String url=metadata.getURL();
    String driverUser=metadata.getUserName();
    if(driverUser==null||!username.equals(driverUser))
      throw new IllegalStateException("来源驱动登录用户与审批配置不一致");
    // Runtime metadata must reflect the non-downgradable factory mode.
    // TLS verification occurs in Connector/J during its handshake.
    SemanticRelationRlsTlsConnectionFactory.requireVerifiedUrl(url);
    URI uri;
    try{
      uri=URI.create(url.substring(5));
    }catch(IllegalArgumentException malformed){
      throw new IllegalStateException("来源 JDBC URL 无法严格解析",malformed);
    }
    if(!"mysql".equals(uri.getScheme())||uri.getHost()==null||
       !uri.getHost().equalsIgnoreCase(host)||
       uri.getPort()!=port||uri.getRawUserInfo()!=null||
       uri.getRawFragment()!=null||!("/"+catalog).equals(uri.getRawPath())||
       !catalog.equalsIgnoreCase(source.getCatalog()))
      throw new IllegalStateException("实际 JDBC 服务地址、端口或数据库与审批配置不同");
    // Additional server-side evidence that this precise session negotiated
    // encryption; an empty cipher means plaintext or unsupported status.
    try(var stmt=source.createStatement()){
      stmt.setQueryTimeout(5);
      stmt.setMaxRows(1);
      try(var rs=stmt.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){
        if(!rs.next()||!"Ssl_cipher".equalsIgnoreCase(rs.getString(1)))
          throw new IllegalStateException("无法从源会话读取 TLS 加密状态");
        String cipher=rs.getString(2);
        if(cipher==null||!cipher.matches("[A-Za-z0-9_+-]{1,128}")||rs.next())
          throw new IllegalStateException("源 JDBC 会话未提供有效 TLS 加密套件");
      }
    }
    // CURRENT_USER() is resolved by the server, not by request parameters.
    // SHOW GRANTS must subsequently match this exact effective principal.
    try(var statement=source.createStatement()){
      statement.setQueryTimeout(5);
      statement.setMaxRows(1);
      try(var results=statement.executeQuery("SELECT CURRENT_USER()")){
        if(!results.next())throw new IllegalStateException("无法读取数据库有效身份");
        String principal=results.getString(1);
        if(principal==null||!principal.startsWith(username+"@")||
           !GRANT_HOST.matcher(principal.substring(username.length()+1)).matches()||
           results.next())
          throw new IllegalStateException("数据库端有效身份与审批用户不符");
        return principal;
      }
    }
  }
}
