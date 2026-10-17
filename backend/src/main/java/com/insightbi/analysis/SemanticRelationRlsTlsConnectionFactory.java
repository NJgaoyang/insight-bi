package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Opt-in, internal MySQL Connector/J TLS configuration. Not wired to the
 * legacy DatabaseStore.open(), Reader API, or any deployed source pool.
 * The trust store must contain at least one currently valid CA certificate.
 */
final class SemanticRelationRlsTlsConnectionFactory {
  private static final Pattern HOST=Pattern.compile(
    "[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]");
  private static final Pattern ID=Pattern.compile("[A-Za-z_][A-Za-z_0-9]{0,63}");
  private SemanticRelationRlsTlsConnectionFactory(){}

  static final class Settings {
    private final String url;
    private final Properties properties;
    private Settings(String url,Properties properties){
      this.url=url;
      this.properties=properties;
    }
    String url(){return url;}
    Properties properties(){
      var copy=new Properties();
      copy.putAll(properties);
      return copy;
    }
    @Override public String toString(){return "VerifiedMySqlTlsSettings[credentials=REDACTED]";}
  }

  static Settings prepare(Map<String,Object> approved,DatabaseStore store,
                          Path trustStore,char[] trustPassword)
      throws IOException,GeneralSecurityException{
    if(approved==null||store==null)throw new IllegalArgumentException("来源连接缺失");
    if(!"mysql".equals(approved.get("engine")))
      throw new IllegalStateException("隔离 TLS 工厂仅支持 MySQL");
    String host=string(approved,"host");
    String database=string(approved,"db_name");
    String username=string(approved,"username");
    if(host.length()>253||!HOST.matcher(host).matches()||host.contains("..")||
       host.startsWith("-")||host.endsWith("-")||
       !ID.matcher(database).matches()||!ID.matcher(username).matches()||
       !(approved.get("port") instanceof Number number)||
       number.intValue()<1||number.intValue()>65535)
      throw new IllegalStateException("连接主机、端口或账户标识不合法");
    if(trustStore==null||trustPassword==null||trustPassword.length==0||
       Files.isSymbolicLink(trustStore)||!Files.isRegularFile(trustStore)||
       !Files.isReadable(trustStore))
      throw new IllegalStateException("TLS CA 信任库不存在或不安全");
    Path path=trustStore.toRealPath();
    var roots=KeyStore.getInstance("PKCS12");
    try(var in=Files.newInputStream(path)){
      roots.load(in,trustPassword);
    }
    boolean trusted=false;
    for(var aliases=roots.aliases();aliases.hasMoreElements();){
      String alias=aliases.nextElement();
      if(roots.isCertificateEntry(alias) &&
         roots.getCertificate(alias) instanceof X509Certificate cert &&
         cert.getBasicConstraints()>=0){
        cert.checkValidity();
        trusted=true;
      }
    }
    if(!trusted)throw new IllegalStateException("TLS 信任库必须包含有效 CA 证书");
    String secret=store.decrypt(string(approved,"credential"));
    if(secret.isEmpty())throw new IllegalStateException("连接密码为空");
    int port=((Number)approved.get("port")).intValue();
    String url="jdbc:mysql://"+host+":"+port+"/"+database+
      "?sslMode=VERIFY_IDENTITY&fallbackToSystemTrustStore=false"+
      "&allowPublicKeyRetrieval=false&connectTimeout=5000&socketTimeout=5000";
    requireVerifiedUrl(url);
    var props=new Properties();
    props.setProperty("user",username);
    props.setProperty("password",secret);
    props.setProperty("trustCertificateKeyStoreUrl",path.toUri().toString());
    props.setProperty("trustCertificateKeyStoreType","PKCS12");
    props.setProperty("trustCertificateKeyStorePassword",new String(trustPassword));
    props.setProperty("sslMode","VERIFY_IDENTITY");
    props.setProperty("fallbackToSystemTrustStore","false");
    props.setProperty("allowPublicKeyRetrieval","false");
    return new Settings(url,props);
  }

  /** Future callers must obtain the approved row while holding its metadata lock. */
  static Connection open(Map<String,Object> approved,DatabaseStore store,
                         Path trustStore,char[] password)
      throws SQLException,IOException,GeneralSecurityException{
    Settings settings=prepare(approved,store,trustStore,password);
    return DriverManager.getConnection(settings.url(),settings.properties());
  }

  static void requireVerifiedUrl(String jdbcUrl){
    if(jdbcUrl==null||!jdbcUrl.startsWith("jdbc:mysql://")||jdbcUrl.length()>1024)
      throw new IllegalStateException("JDBC 地址必须为单主机 MySQL TLS 连接");
    java.net.URI parsed;
    try{parsed=java.net.URI.create(jdbcUrl.substring(5));}
    catch(IllegalArgumentException error){
      throw new IllegalStateException("MySQL JDBC 地址格式无效",error);
    }
    if(!"mysql".equals(parsed.getScheme())||parsed.getHost()==null||
       parsed.getPort()<1||parsed.getPort()>65535||
       parsed.getRawUserInfo()!=null||parsed.getRawFragment()!=null||
       parsed.getRawPath()==null||!parsed.getRawPath().matches("/[A-Za-z_][A-Za-z_0-9]{0,63}"))
      throw new IllegalStateException("MySQL 地址包含不允许的主机、认证信息或路径");
    String query=parsed.getRawQuery();
    if(query==null||query.isBlank())
      throw new IllegalStateException("MySQL TLS 连接缺少安全参数");
    var entries=new HashMap<String,String>();
    for(String item:query.split("&",-1)){
      int pos=item.indexOf('=');
      if(pos<1||item.indexOf('=',pos+1)>=0||pos==item.length()-1)
        throw new IllegalStateException("JDBC 参数缺少唯一赋值");
      String key=item.substring(0,pos);
      String value=item.substring(pos+1);
      if(!Set.of("sslMode","fallbackToSystemTrustStore",
          "allowPublicKeyRetrieval","connectTimeout","socketTimeout").contains(key)||
         entries.putIfAbsent(key,value)!=null)
        throw new IllegalStateException("JDBC 地址包含未知、重复或可能降级的参数");
    }
    if(!"VERIFY_IDENTITY".equals(entries.get("sslMode"))||
       !"false".equals(entries.get("fallbackToSystemTrustStore"))||
       !"false".equals(entries.get("allowPublicKeyRetrieval"))||
       !"5000".equals(entries.get("connectTimeout"))||
       !"5000".equals(entries.get("socketTimeout")))
      throw new IllegalStateException("MySQL TLS 身份校验或超时配置不能降级");
  }

  private static String string(Map<String,Object> data,String key){
    if(!(data.get(key) instanceof String s)||s.isBlank())
      throw new IllegalStateException("来源连接字段缺失: "+key);
    return s;
  }
}
