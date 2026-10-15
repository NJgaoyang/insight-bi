package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class MetadataBackupRecoveryTest {
  private static final String SECRET=Base64.getEncoder().encodeToString(new byte[32]);
  private record Instance(JdbcTemplate jdbc,DatabaseStore store,MetadataBackupService backup){}
  private Instance fresh(String passwordKey){
    var dataSource=new DriverManagerDataSource(
      "jdbc:h2:mem:backup_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "sa","");
    var db=new JdbcTemplate(dataSource);
    var mapper=new ObjectMapper();
    var store=new DatabaseStore(db,mapper,passwordKey);
    store.schema();
    return new Instance(db,store,new MetadataBackupService(store,db,mapper));
  }

  @Test void encryptedBackupRoundTripsAllMetadataAndNeverLeaksCredentials(){
    Instance original=fresh(SECRET);
    String credential=original.store.encrypt("hidden-source-password");
    original.jdbc.update(
      "INSERT INTO insight_connection(id,name,engine,host,port,db_name,username,credential) VALUES(?,?,?,?,?,?,?,?)",
      "c1","主连接","MYSQL","127.0.0.1",3306,"reporting","analyst",credential);
    original.jdbc.update(
      "INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      "ds1","收入数据","c1","SELECT city,revenue FROM sales","[]");
    original.jdbc.update(
      "INSERT INTO insight_dashboard(id,name,mode,content_json,published_json,status,revision,kind) VALUES(?,?,?,?,?,?,?,?)",
      "dash1","经营视图","grid","{\"widgets\":[]}","{\"widgets\":[]}","published",2,"analysis");
    original.jdbc.update(
      "INSERT INTO insight_governance_user(id,username,display_name,role,enabled,password_hash,auth_version) VALUES(?,?,?,?,?,?,?)",
      "u1","example","Example","VIEWER",true,"pbkdf2-hash-for-fixture",3);
    original.jdbc.update(
      "INSERT INTO insight_dashboard_grant(user_id,dashboard_id,permission) VALUES(?,?,?)",
      "u1","dash1","READ_PUBLISHED");
    original.jdbc.update(
      "INSERT INTO insight_publication_approval(dashboard_id,state,submitted_revision,reviewed_revision,comment) VALUES(?,?,?,?,?)",
      "dash1","APPROVED",2,2,"金额核对通过");
    original.jdbc.update(
      "INSERT INTO insight_publication_review_event(id,dashboard_id,event_type,revision,actor,comment) VALUES(?,?,?,?,?,?)",
      "approval1","dash1","APPROVED",2,"admin","金额核对通过");

    byte[] artifact=original.backup.exportEncrypted();
    assertTrue(artifact.length>40);
    assertEquals("IBAK1",new String(artifact,0,5,StandardCharsets.US_ASCII));
    String raw=new String(artifact,StandardCharsets.ISO_8859_1);
    assertFalse(raw.contains("hidden-source-password"));
    assertFalse(raw.contains("pbkdf2-hash-for-fixture"));
    assertFalse(raw.contains("SELECT city"));
    var info=original.backup.verify(artifact);
    assertEquals(true,info.get("valid"));
    assertEquals(20,info.get("tableCount"));
    assertTrue(((Number)info.get("rowCount")).longValue()>=7);

    var target=fresh(SECRET);
    var tx=new TransactionTemplate(new DataSourceTransactionManager(target.jdbc.getDataSource()));
    tx.executeWithoutResult(status->target.backup.restoreIntoEmpty(artifact));
    assertEquals(1,target.jdbc.queryForObject("SELECT COUNT(*) FROM insight_connection",Integer.class));
    assertEquals(1,target.jdbc.queryForObject("SELECT COUNT(*) FROM insight_governance_user",Integer.class));
    assertEquals("hidden-source-password",target.store.decrypt(
      target.jdbc.queryForObject("SELECT credential FROM insight_connection WHERE id='c1'",String.class)));
    assertEquals("APPROVED",target.jdbc.queryForObject(
      "SELECT state FROM insight_publication_approval WHERE dashboard_id='dash1'",String.class));
    assertEquals("金额核对通过",target.jdbc.queryForObject(
      "SELECT comment FROM insight_publication_review_event WHERE id='approval1'",String.class));
    assertEquals("pbkdf2-hash-for-fixture",target.jdbc.queryForObject(
      "SELECT password_hash FROM insight_governance_user WHERE id='u1'",String.class));
    assertEquals(3,target.jdbc.queryForObject(
      "SELECT auth_version FROM insight_governance_user WHERE id='u1'",Integer.class));
    assertEquals(20,target.backup.verify(target.backup.exportEncrypted()).get("tableCount"));

    assertThrows(IllegalStateException.class,()->
      tx.executeWithoutResult(status->target.backup.restoreIntoEmpty(artifact)),
      "restoring on a populated database must never overwrite it");
    assertEquals(1,target.jdbc.queryForObject("SELECT COUNT(*) FROM insight_dashboard",Integer.class));
  }

  @Test void rejectsTamperWrongKeyMissingFileAndNeverChangesTarget(){
    var source=fresh(SECRET);
    byte[] backup=source.backup.exportEncrypted();
    var target=fresh(SECRET);
    byte[] tampered=backup.clone();
    tampered[tampered.length-1]^=0x40;
    assertThrows(IllegalArgumentException.class,()->target.backup.verify(tampered));
    assertThrows(IllegalArgumentException.class,()->target.backup.verify(new byte[]{1,2,3}));
    assertThrows(IllegalArgumentException.class,()->target.backup.verify(null));
    var otherKey=new byte[32];
    otherKey[0]=1;
    var wrong=fresh(Base64.getEncoder().encodeToString(otherKey));
    assertThrows(IllegalArgumentException.class,()->wrong.backup.verify(backup));
    assertThrows(IllegalArgumentException.class,()->
      target.backup.restoreIntoEmpty(tampered));
    assertEquals(0,target.jdbc.queryForObject("SELECT COUNT(*) FROM insight_dashboard",Integer.class));
  }

  @Test void exportApiReturnsOnlyEncryptedBlobWithNoStoreAndVerifyMetadata(){
    var source=fresh(SECRET);
    var api=new MetadataBackupApi(source.backup);
    var response=api.export();
    assertEquals("no-store",response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains(".ibak"));
    assertEquals(20,api.verify(response.getBody()).get("tableCount"));
    assertFalse(new String(response.getBody(),StandardCharsets.ISO_8859_1).contains("insight_dashboard"));
  }
}
