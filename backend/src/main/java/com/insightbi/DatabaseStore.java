package com.insightbi;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;

@Service
public class DatabaseStore {
 final JdbcTemplate jdbc;
 final ObjectMapper mapper;
 final SecureRandom random=new SecureRandom();
 final byte[] key;
 public DatabaseStore(JdbcTemplate jdbc,ObjectMapper mapper,@Value("${BI_CREDENTIAL_KEY:}") String secret){
  this.jdbc=jdbc;this.mapper=mapper;
  if(secret.isBlank())throw new IllegalStateException("BI_CREDENTIAL_KEY must be configured");
  this.key=Base64.getDecoder().decode(secret);
  if(key.length!=32)throw new IllegalStateException("BI_CREDENTIAL_KEY must be a base64 32-byte key");
 }
 @PostConstruct public void schema(){
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_connection (id VARCHAR(36) PRIMARY KEY, name VARCHAR(150) NOT NULL, engine VARCHAR(20) NOT NULL, host VARCHAR(200) NOT NULL, port INT NOT NULL, db_name VARCHAR(120) NOT NULL, username VARCHAR(120) NOT NULL, credential LONGTEXT NOT NULL)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_dataset (id VARCHAR(36) PRIMARY KEY, name VARCHAR(150) NOT NULL, connection_id VARCHAR(36) NOT NULL, sql_body LONGTEXT NOT NULL, fields_json LONGTEXT NOT NULL)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_dashboard (id VARCHAR(36) PRIMARY KEY, name VARCHAR(150) NOT NULL, mode VARCHAR(12) NOT NULL, content_json LONGTEXT NOT NULL, published_json LONGTEXT, status VARCHAR(15) NOT NULL, revision INT NOT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_dashboard_version (id VARCHAR(36) PRIMARY KEY, dashboard_id VARCHAR(36) NOT NULL, version_no INT NOT NULL, name VARCHAR(150) NOT NULL, mode VARCHAR(12) NOT NULL, content_json LONGTEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE (dashboard_id,version_no))");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_folder (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_id VARCHAR(36), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  boolean hasFolder=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
    try(ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"insight_dashboard","folder_id")){
      return columns.next();
    }
  }));
  if(!hasFolder)jdbc.execute("ALTER TABLE insight_dashboard ADD COLUMN folder_id VARCHAR(36)");
  boolean hasKind=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
    try(ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"insight_dashboard","kind")){
      return columns.next();
    }
  }));
  if(!hasKind)jdbc.execute("ALTER TABLE insight_dashboard ADD COLUMN kind VARCHAR(15) NOT NULL DEFAULT 'dashboard'");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_data_folder (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_id VARCHAR(36), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  boolean hasDataFolder=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
    try(ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"insight_dataset","folder_id")){
      return columns.next();
    }
  }));
  if(!hasDataFolder)jdbc.execute("ALTER TABLE insight_dataset ADD COLUMN folder_id VARCHAR(36)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_dataset_field (id VARCHAR(36) PRIMARY KEY, dataset_id VARCHAR(36) NOT NULL, source_name VARCHAR(128) NOT NULL, display_name VARCHAR(150) NOT NULL, logical_type VARCHAR(20) NOT NULL, role VARCHAR(20) NOT NULL, ordinal INT NOT NULL, hidden BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(dataset_id,source_name))");
  boolean hasFieldHidden=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
    try(ResultSet columns=connection.getMetaData().getColumns(connection.getCatalog(),null,"insight_dataset_field","hidden")){
      return columns.next();
    }
  }));
  if(!hasFieldHidden)jdbc.execute("ALTER TABLE insight_dataset_field ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE");
  jdbc.execute("CREATE TABLE IF NOT EXISTS insight_calculated_field (id VARCHAR(36) PRIMARY KEY, dataset_id VARCHAR(36) NOT NULL, name VARCHAR(100) NOT NULL, expression VARCHAR(1500) NOT NULL, phase VARCHAR(20) NOT NULL, output_type VARCHAR(20) NOT NULL, revision INT NOT NULL DEFAULT 1, UNIQUE(dataset_id,name))");



 }
 public String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException(e);}}
 public Map<String,Object> map(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){throw new IllegalArgumentException(e);}}
 public List<Map<String,Object>> arr(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){throw new IllegalArgumentException(e);}}
 public String encrypt(String plain){
  try {byte[] iv=new byte[12];random.nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] crypt=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));byte[] result=new byte[iv.length+crypt.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(crypt,0,result,iv.length,crypt.length);return Base64.getEncoder().encodeToString(result);}
  catch(Exception e){throw new IllegalStateException("credential encryption failed");}
 }
 public String decrypt(String text){
  try{byte[] all=Base64.getDecoder().decode(text);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(all,12)));return new String(c.doFinal(all,12,all.length-12),StandardCharsets.UTF_8);}
  catch(Exception e){throw new IllegalStateException("credential decryption failed");}
 }
 public List<Map<String,Object>> connections(){
  return jdbc.queryForList("SELECT id,name,engine,host,port,db_name,username FROM insight_connection ORDER BY name");
 }
 public Map<String,Object> connection(String id){
  var list=jdbc.queryForList("SELECT * FROM insight_connection WHERE id=?",id);
  if(list.isEmpty())throw new NoSuchElementException("数据连接不存在");
  return list.get(0);
 }
 public void upsertConnection(Map<String,Object> o){
  String id=str(o,"id",UUID.randomUUID().toString()),name=str(o,"name",""),engine=str(o,"engine","mysql"),host=str(o,"host",""),database=str(o,"database",""),username=str(o,"username","");
  int port=number(o.get("port"),engine.equals("starrocks")?9030:3306);
  if(name.isBlank()||host.isBlank()||database.isBlank()||username.isBlank()||!Set.of("mysql","starrocks").contains(engine)||port<1||port>65535)throw new IllegalArgumentException("请填写合法的连接信息");
  String password=str(o,"password","");
  int exists=jdbc.queryForObject("SELECT COUNT(*) FROM insight_connection WHERE id=?",Integer.class,id);
  if(exists>0){
   String encrypted=password.isBlank()?String.valueOf(connection(id).get("credential")):encrypt(password);
   jdbc.update("UPDATE insight_connection SET name=?,engine=?,host=?,port=?,db_name=?,username=?,credential=? WHERE id=?",name,engine,host,port,database,username,encrypted,id);
  }else{
   if(password.isBlank())throw new IllegalArgumentException("新连接需要密码");
   jdbc.update("INSERT INTO insight_connection(id,name,engine,host,port,db_name,username,credential) VALUES (?,?,?,?,?,?,?,?)",id,name,engine,host,port,database,username,encrypt(password));
  }
 }
 public Connection open(Map<String,Object> c)throws Exception{
  String engine=String.valueOf(c.get("engine")),host=String.valueOf(c.get("host")),db=String.valueOf(c.get("db_name")),user=String.valueOf(c.get("username")),pass=decrypt(String.valueOf(c.get("credential")));
  int port=((Number)c.get("port")).intValue();
  String url="jdbc:mysql://"+host+":"+port+"/"+db+"?useUnicode=true&characterEncoding=UTF-8&useSSL=false&connectTimeout=5000&socketTimeout=18000&allowPublicKeyRetrieval=true";
  Connection conn=DriverManager.getConnection(url,user,pass);
  try{conn.setReadOnly(true);}catch(Exception ignored){}
  return conn;
 }
 public Connection test(Map<String,Object> o)throws Exception {
  Map<String,Object> c=new HashMap<>();
  c.put("engine",str(o,"engine","mysql"));c.put("host",str(o,"host",""));c.put("port",number(o.get("port"),3306));c.put("db_name",str(o,"database",""));c.put("username",str(o,"username",""));c.put("credential",encrypt(str(o,"password","")));
  if(str(o,"password","").isEmpty()&&!str(o,"id","").isEmpty())c.put("credential",connection(str(o,"id","")).get("credential"));
  return open(c);
 }
 public static String str(Map<String,Object> obj,String key,String fallback){Object v=obj.get(key);return v==null?fallback:String.valueOf(v).trim();}
 public static int number(Object value,int fallback){try{return Integer.parseInt(String.valueOf(value));}catch(Exception e){return fallback;}}
 public static String safeId(String s){if(!s.matches("[\\p{L}\\p{N}_.$-]{1,128}"))throw new IllegalArgumentException("字段或表名包含不支持的字符");return "`"+s.replace("`","``")+"`";}
 public static String safeSql(String sql){
  String s=sql.trim();
  if(s.length()>16000||!s.matches("(?is)^select\\s+.*")||s.contains(";")||s.contains("--")||s.contains("/*")||s.contains("*/")||s.contains("#")||s.matches("(?is).*\\b(into|outfile|dumpfile|load_file|sleep|benchmark)\\b.*"))throw new IllegalArgumentException("仅允许安全的单条 SELECT 查询");
  return s;
 }
 public Map<String,Object> dataset(String id){var a=jdbc.queryForList("SELECT * FROM insight_dataset WHERE id=?",id);if(a.isEmpty())throw new NoSuchElementException("数据集不存在");return a.get(0);}
 public List<Map<String,Object>> datasets(){
  var rows=jdbc.queryForList("SELECT id,name,connection_id,sql_body,fields_json,folder_id FROM insight_dataset ORDER BY name");
  for(var r:rows){r.put("fields",arr(String.valueOf(r.remove("fields_json"))));}
  return rows;
 }
 public List<Map<String,Object>> readRows(Connection c,String sql,List<Object> params,int limit)throws Exception{
  // Legacy V1 and previews always retain the original maximum of 500.
  return readRowsBounded(c,sql,params,limit,500);
 }
 /** Internal V2 overflow probe; caller trims the sentinel row before returning data. */
 public List<Map<String,Object>> readAnalysisRows(Connection c,String sql,List<Object> params,int requestedLimit)throws Exception{
  if(requestedLimit<1||requestedLimit>500)throw new IllegalArgumentException("分析行数上限无效");
  return readRowsBounded(c,sql,params,requestedLimit+1,501);
 }
 private List<Map<String,Object>> readRowsBounded(Connection c,String sql,List<Object> params,int limit,int cap)throws Exception{
  int rowCap=Math.min(cap,limit);
  List<Map<String,Object>> data=new ArrayList<>();
  try(PreparedStatement ps=c.prepareStatement(sql)){
   ps.setQueryTimeout(15);ps.setMaxRows(rowCap);
   for(int i=0;i<params.size();i++)ps.setObject(i+1,params.get(i));
   try(ResultSet rs=ps.executeQuery()){
    ResultSetMetaData meta=rs.getMetaData();int n=meta.getColumnCount();
    while(data.size()<rowCap&&rs.next()){
     Map<String,Object> row=new LinkedHashMap<>();
     for(int j=1;j<=n;j++){
      Object x=rs.getObject(j);
      if(x instanceof java.sql.Date||x instanceof Timestamp||x instanceof Time)x=x.toString();
      row.put(meta.getColumnLabel(j),x);
     }
     data.add(row);
    }
   }
  }
  return data;
 }
 public List<Map<String,Object>> readFields(Connection c,String sql)throws Exception{
  try(PreparedStatement ps=c.prepareStatement("SELECT * FROM ("+safeSql(sql)+") insight_meta WHERE 1=0")){
   ps.setQueryTimeout(12);try(ResultSet r=ps.executeQuery()){var m=r.getMetaData();List<Map<String,Object>> out=new ArrayList<>();for(int j=1;j<=m.getColumnCount();j++){String name=m.getColumnLabel(j);String type=m.getColumnTypeName(j);out.add(Map.of("name",name,"type",type,"numeric",Set.of(Types.INTEGER,Types.BIGINT,Types.DOUBLE,Types.FLOAT,Types.DECIMAL,Types.NUMERIC,Types.SMALLINT,Types.TINYINT,Types.REAL).contains(m.getColumnType(j))));}return out;}
  }
 }
}
