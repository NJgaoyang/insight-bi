package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    return preparePinned(approved,store,trustStore,trustPassword,null);
  }

  private static Settings preparePinned(Map<String,Object> approved,DatabaseStore store,
                          Path trustStore,char[] trustPassword,String expectedCaHash)
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
    String currentCa=caFingerprint(trustStore,trustPassword,new Date());
    if(expectedCaHash!=null&&
       (!expectedCaHash.matches("[0-9a-f]{64}")||
        !expectedCaHash.equals(currentCa)))
      throw new IllegalStateException("证书信任锚已变更，必须重新确认部署 CA 指纹");
    Path path=trustStore.toRealPath();
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

  /**
   * Trust pin is configured outside of the reader request, by the operator.
   * The digest is over sorted DER CA certificates (not filename/PKCS12 salt).
   * Any removed, expired, replaced or added root changes the approved pin.
   */
  static String caFingerprint(Path file,char[] password)
      throws IOException,GeneralSecurityException{
    return caFingerprint(file,password,new Date());
  }
  static String caFingerprint(Path file,char[] password,Date at)
      throws IOException,GeneralSecurityException{
    if(file==null||password==null||password.length==0||at==null||
       Files.isSymbolicLink(file)||!Files.isRegularFile(file)||
       !Files.isReadable(file))
      throw new IllegalStateException("TLS CA 信任库缺失或不是受信常规文件");
    try(var in=Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
      return caFingerprint(in,password,at);
    }
  }
  static String caFingerprint(byte[] data,char[] password,Date at)
      throws IOException,GeneralSecurityException{
    if(data==null||data.length==0||password==null||password.length==0||at==null)
      throw new IllegalStateException("CA 快照内容或验证时间不合法");
    try(var in=new ByteArrayInputStream(data)){
      return caFingerprint(in,password,at);
    }
  }
  private static String caFingerprint(InputStream in,char[] password,Date at)
      throws IOException,GeneralSecurityException{
    var store=KeyStore.getInstance("PKCS12");
    store.load(in,password);
    var certs=new ArrayList<String>();
    for(var aliases=store.aliases();aliases.hasMoreElements();){
      String alias=aliases.nextElement();
      if(!store.isCertificateEntry(alias)||
         !(store.getCertificate(alias) instanceof X509Certificate ca)||
         ca.getBasicConstraints()<0)
        throw new IllegalStateException("信任库包含非 CA 证书或私钥条目");
      ca.checkValidity(at);
      certs.add(Base64.getEncoder().encodeToString(ca.getEncoded()));
    }
    if(certs.isEmpty()||certs.size()>16)
      throw new IllegalStateException("CA 信任库证书数量不合法");
    Collections.sort(certs);
    try{
      var digest=MessageDigest.getInstance("SHA-256");
      for(String cert:certs){
        byte[] der=Base64.getDecoder().decode(cert);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(der.length).array());
        digest.update(der);
      }
      return HexFormat.of().formatHex(digest.digest());
    }catch(NoSuchAlgorithmException unavailable){
      throw new IllegalStateException("SHA-256 不可用",unavailable);
    }
  }

  /**
   * Bind the TLS settings to the CURRENT LOCKED connection definition,
   * including the exact encrypted credential version. A trust anchor hash
   * supplied by trusted deployment configuration is mandatory.
   *
   * This is still a preparation step; it makes no remote source request.
   */
  static Settings prepareApproved(JdbcTemplate db,DatabaseStore store,
      SemanticRelationRlsApprovedSourceGate.VerifiedSource verified,
      Path caFile,char[] caPassword,String expectedCaHash)
      throws IOException,GeneralSecurityException{
    if(db==null||store==null||verified==null||
       !TransactionSynchronizationManager.isActualTransactionActive()||
       TransactionSynchronizationManager.isCurrentTransactionReadOnly()||
       !Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(
          TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())||
       expectedCaHash==null||!expectedCaHash.matches("[0-9a-f]{64}"))
      throw new IllegalStateException("必须在受控元数据写事务中使用已配置的 CA 指纹");
    var rows=db.queryForList(
      "SELECT id,engine,host,port,db_name,username,credential "+
      "FROM insight_connection WHERE id=? FOR UPDATE",verified.connectionId());
    if(rows.size()!=1)throw new IllegalStateException("已批准数据库连接被删除");
    var current=rows.get(0);
    if(!Objects.equals(current.get("id"),verified.connectionId())||
       !Objects.equals(current.get("engine"),verified.engine())||
       !Objects.equals(current.get("host"),verified.host())||
       !Objects.equals(current.get("db_name"),verified.catalog())||
       !Objects.equals(current.get("username"),verified.username())||
       !(current.get("port") instanceof Number n)||n.intValue()!=verified.port()||
       !(current.get("credential") instanceof String encrypted)||
       !SemanticRelationRlsPlanSnapshot.hash(encrypted).equals(verified.credentialHash()))
      throw new IllegalStateException("审批连接账号或加密凭据在 TLS 准备前已变化");
    return preparePinned(current,store,caFile,caPassword,expectedCaHash);
  }

  @FunctionalInterface interface TlsConnector {
    Connection connect(String url,Properties properties)throws SQLException;
  }

  /**
   * The pinned CA is read once into memory and copied to a private POSIX
   * snapshot; Connector/J never uses the operator's mutable CA path.
   * Connection is returned only after snapshot cleanup succeeds. This test
   * seam is package-private and is not wired to any Reader route.
   */
  static Connection openApprovedWithConnector(JdbcTemplate db,DatabaseStore store,
      SemanticRelationRlsApprovedSourceGate.VerifiedSource verified,
      Path caFile,char[] caPassword,String expectedCaHash,TlsConnector connector)
      throws IOException,GeneralSecurityException,SQLException{
    if(connector==null)throw new IllegalArgumentException("TLS 连接驱动不能为空");
    var prepared=prepareApproved(db,store,verified,caFile,caPassword,expectedCaHash);
    Connection connected=null;
    try(var snapshot=SemanticRelationRlsTrustSnapshot.capture(
          caFile,caPassword,expectedCaHash)){
      var properties=prepared.properties();
      properties.setProperty("trustCertificateKeyStoreUrl",snapshot.url());
      connected=connector.connect(prepared.url(),properties);
      if(connected==null)
        throw new IllegalStateException("TLS 驱动没有返回有效连接");
      // Cleanup happens before the connection escapes the approved opener.
      try{
        snapshot.close();
      }catch(IOException failure){
        try{connected.close();}catch(SQLException close){failure.addSuppressed(close);}
        throw failure;
      }
      return connected;
    }
  }

  /** Never pass an arbitrary connection map to this verified-opening path. */
  static Connection openApproved(JdbcTemplate db,DatabaseStore store,
      SemanticRelationRlsApprovedSourceGate.VerifiedSource verified,
      Path caFile,char[] caPassword,String expectedCaHash)
      throws IOException,GeneralSecurityException,SQLException{
    return openApprovedWithConnector(db,store,verified,caFile,caPassword,
      expectedCaHash,DriverManager::getConnection);
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
