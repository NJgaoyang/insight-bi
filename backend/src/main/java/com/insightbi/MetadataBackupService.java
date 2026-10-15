package com.insightbi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypted portable snapshot of ALL application metadata tables.
 *
 * This contains encrypted datasource credentials and hashed user passwords.
 * Keep the backup key separately; never log or serve the decrypted JSON.
 * Restore is deliberately not exposed as a web route. Use restoreIntoEmpty()
 * only with an isolated, empty, freshly initialized database.
 */
@Service
public class MetadataBackupService {
  private static final byte[] MAGIC="IBAK1".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_BYTES=32*1024*1024;
  private static final int MAX_ROWS_PER_TABLE=100000;
  private static final List<String> TABLES=List.of(
    "insight_connection","insight_dataset","insight_dashboard",
    "insight_dashboard_version","insight_publication_audit",
    "insight_publication_approval","insight_publication_review_event",
    "insight_governance_user","insight_dashboard_grant",
    "insight_reader_row_policy","insight_reader_row_policy_value",
    "insight_reader_column_deny","insight_governance_department",
    "insight_department_dashboard_grant","insight_folder",
    "insight_folder_acl","insight_data_folder","insight_dataset_field",
    "insight_calculated_field","insight_dataset_pipeline");
  private static final int IV_SIZE=12;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final byte[] encryptionKey;
  private final SecureRandom secureRandom=new SecureRandom();

  public MetadataBackupService(DatabaseStore store,JdbcTemplate db,ObjectMapper json){
    this.db=db;this.json=json;
    try{
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      digest.update(store.key);
      digest.update("insight-bi:metadata-backup:v1".getBytes(StandardCharsets.UTF_8));
      this.encryptionKey=digest.digest();
    }catch(Exception e){throw new IllegalStateException("无法初始化备份加密",e);}
  }

  record Table(List<String> columns,List<List<String>> rows){}
  record Archive(int format,String createdAt,Map<String,Table> tables){}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public byte[] exportEncrypted(){
    Map<String,Table> tables=new LinkedHashMap<>();
    for(String name:TABLES){
      List<String> columns=columns(name);
      List<List<String>> rows=db.execute((org.springframework.jdbc.core.ConnectionCallback<List<List<String>>>)c->{
        List<List<String>> all=new ArrayList<>();
        try(Statement stmt=c.createStatement()){
          stmt.setQueryTimeout(30);
          stmt.setMaxRows(MAX_ROWS_PER_TABLE+1);
          try(ResultSet rs=stmt.executeQuery("SELECT * FROM "+name)){
            while(rs.next()){
              if(all.size()>=MAX_ROWS_PER_TABLE)
                throw new IllegalStateException("元数据记录过多，备份已取消");
              List<String> row=new ArrayList<>(columns.size());
              for(int i=1;i<=columns.size();i++)row.add(rs.getString(i));
              all.add(row);
            }
          }
        }
        return all;
      });
      tables.put(name,new Table(columns,rows));
    }
    try{
      byte[] clear=json.writeValueAsBytes(new Archive(1,Instant.now().toString(),tables));
      if(clear.length>MAX_BYTES)throw new IllegalStateException("备份文件超过32MB安全上限");
      byte[] iv=new byte[IV_SIZE];secureRandom.nextBytes(iv);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),
        new GCMParameterSpec(128,iv));
      cipher.updateAAD(MAGIC);
      byte[] encrypted=cipher.doFinal(clear);
      ByteArrayOutputStream output=new ByteArrayOutputStream();
      output.write(MAGIC);output.write(iv);output.write(encrypted);
      return output.toByteArray();
    }catch(IllegalStateException e){throw e;}
     catch(Exception e){throw new IllegalStateException("加密备份失败",e);}
  }

  public Map<String,Object> verify(byte[] encrypted){
    Archive archive=decode(encrypted);
    long rows=archive.tables().values().stream().mapToLong(t->t.rows().size()).sum();
    return Map.of("valid",true,"format",archive.format(),"createdAt",
      archive.createdAt(),"tableCount",archive.tables().size(),"rowCount",rows);
  }

  /**
   * Offline recovery primitive; not reachable from HTTP. Source archive must
   * pass GCM authentication, exact schema validation and a total-empty check.
   * The entire insertion is transactional; partial restores roll back.
   */
  @Transactional
  public void restoreIntoEmpty(byte[] encrypted){
    Archive archive=decode(encrypted);
    for(String name:TABLES){
      Integer count=db.queryForObject("SELECT COUNT(*) FROM "+name,Integer.class);
      if(count==null||count!=0)
        throw new IllegalStateException("目标数据库非空，拒绝覆盖现有元数据");
    }
    for(String name:TABLES){
      Table table=archive.tables().get(name);
      List<String> columns=table.columns();
      String placeholders=String.join(",",Collections.nCopies(columns.size(),"?"));
      String sql="INSERT INTO "+name+" ("+String.join(",",columns)+") VALUES ("+placeholders+")";
      db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{
        try(PreparedStatement ps=c.prepareStatement(sql)){
          for(List<String> row:table.rows()){
            for(int i=0;i<row.size();i++){
              if(row.get(i)==null)ps.setNull(i+1,Types.NULL);
              else ps.setString(i+1,row.get(i));
            }
            ps.addBatch();
          }
          if(!table.rows().isEmpty())ps.executeBatch();
        }
        return null;
      });
    }
  }

  private Archive decode(byte[] encoded){
    if(encoded==null||encoded.length<MAGIC.length+IV_SIZE+17||encoded.length>MAX_BYTES+512)
      throw new IllegalArgumentException("备份文件无效或过大");
    ByteBuffer buffer=ByteBuffer.wrap(encoded);
    byte[] magic=new byte[MAGIC.length],iv=new byte[IV_SIZE];
    buffer.get(magic);buffer.get(iv);
    if(!MessageDigest.isEqual(MAGIC,magic))
      throw new IllegalArgumentException("不支持的备份文件格式");
    byte[] cipherText=new byte[buffer.remaining()];buffer.get(cipherText);
    try{
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),
        new GCMParameterSpec(128,iv));
      cipher.updateAAD(MAGIC);
      Archive archive=json.readValue(cipher.doFinal(cipherText),Archive.class);
      validate(archive);
      return archive;
    }catch(Exception e){
      throw new IllegalArgumentException("备份校验失败：格式损坏、密钥不匹配或架构不兼容");
    }
  }

  private void validate(Archive archive){
    if(archive==null||archive.format()!=1||archive.createdAt()==null||
       archive.tables()==null||!archive.tables().keySet().equals(new HashSet<>(TABLES)))
      throw new IllegalArgumentException("备份清单与当前版本不兼容");
    for(String name:TABLES){
      Table table=archive.tables().get(name);
      if(table==null||table.columns()==null||table.rows()==null||
         !table.columns().equals(columns(name))||
         table.rows().size()>MAX_ROWS_PER_TABLE)
        throw new IllegalArgumentException("备份字段结构不匹配");
      for(List<String> row:table.rows())
        if(row==null||row.size()!=table.columns().size())
          throw new IllegalArgumentException("备份记录列数不匹配");
    }
  }

  private List<String> columns(String name){
    if(!TABLES.contains(name))throw new IllegalArgumentException("未登记的元数据表");
    return db.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>)c->{
      try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM "+name+" WHERE 1=0")){
        ResultSetMetaData meta=rs.getMetaData();
        List<String> names=new ArrayList<>();
        for(int i=1;i<=meta.getColumnCount();i++)names.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
        return names;
      }
    });
  }
}
