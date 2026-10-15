package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.FieldCatalog;
import com.insightbi.analysis.ReadOnlyConnectionPolicy;
import com.insightbi.analysis.SourceRecoveryGuard;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs only against the dedicated disposable MySQL CI service, or against an
 * explicitly configured StarRocks account for SELECT 1. No business database
 * is modified and credentials never enter logs or source control.
 */
class RealJdbcAcceptanceTest {
  private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
  private static final String SOURCE_SCHEMA="insight_ci_source";
  private static final String RESTORE_SCHEMA="insight_ci_restore";

  private record Fixture(JdbcTemplate db,DatabaseStore store){}

  private static String env(String name){
    String value=System.getenv(name);
    return value==null?"":value;
  }
  private static String schemaUrl(String schema){
    return "jdbc:mysql://127.0.0.1:3306/"+schema+
      "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=5000";
  }
  private static Fixture fixture(String schema){
    var source=new DriverManagerDataSource(schemaUrl(schema),
      env("BI_ACCEPTANCE_MYSQL_USERNAME"),env("BI_ACCEPTANCE_MYSQL_PASSWORD"));
    var db=new JdbcTemplate(source);
    var store=new DatabaseStore(db,new ObjectMapper(),KEY);
    store.schema();
    return new Fixture(db,store);
  }

  /**
   * The guard is intentionally strict. CI cannot accidentally point the
   * mutating part of this test at an existing MySQL database or remote host.
   */
  private static void requireDisposableMysql(){
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_DISPOSABLE_MYSQL")),
      "Requires a disposable local CI MySQL service");
    assertEquals("127.0.0.1",env("BI_ACCEPTANCE_MYSQL_HOST"));
    assertEquals("3306",env("BI_ACCEPTANCE_MYSQL_PORT"));
    assertFalse(env("BI_ACCEPTANCE_MYSQL_USERNAME").isBlank());
    assertFalse(env("BI_ACCEPTANCE_MYSQL_PASSWORD").isBlank());
    assertFalse(env("BI_ACCEPTANCE_MYSQL_ADMIN_USERNAME").isBlank());
  }

  @Test void actualMysqlExecutesV2QueriesAndEncryptedMetadataRecovery()throws Exception{
    requireDisposableMysql();
    String admin=env("BI_ACCEPTANCE_MYSQL_ADMIN_USERNAME");
    String adminPassword=env("BI_ACCEPTANCE_MYSQL_ADMIN_PASSWORD");
    try(Connection c=DriverManager.getConnection(
          "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true",
          admin,adminPassword);
        Statement statement=c.createStatement()){
      // Destructive work is limited to disposable, fixed-name CI schemas only.
      statement.execute("DROP DATABASE IF EXISTS "+SOURCE_SCHEMA);
      statement.execute("DROP DATABASE IF EXISTS "+RESTORE_SCHEMA);
      statement.execute("CREATE DATABASE "+SOURCE_SCHEMA+" CHARACTER SET utf8mb4");
      statement.execute("CREATE DATABASE "+RESTORE_SCHEMA+" CHARACTER SET utf8mb4");
      String grantUser=env("BI_ACCEPTANCE_MYSQL_USERNAME");
      if(!grantUser.matches("[a-zA-Z_][a-zA-Z0-9_]{0,31}"))
        throw new IllegalArgumentException("Invalid disposable CI user");
      statement.execute("GRANT ALL ON "+SOURCE_SCHEMA+".* TO '"+grantUser+"'@'%'");
      statement.execute("GRANT ALL ON "+RESTORE_SCHEMA+".* TO '"+grantUser+"'@'%'");
    }
    Fixture source=fixture(SOURCE_SCHEMA);
    Fixture target=fixture(RESTORE_SCHEMA);
    source.store.schema();
    source.db.execute("CREATE TABLE insight_ci_fact(city VARCHAR(32),revenue DECIMAL(12,2))");
    source.db.update("INSERT INTO insight_ci_fact VALUES(?,?)","北京",100);
    source.db.update("INSERT INTO insight_ci_fact VALUES(?,?)","北京",50);
    source.db.update("INSERT INTO insight_ci_fact VALUES(?,?)","上海",200);

    String connectionId="ci-source-1";
    source.store.upsertConnection(Map.of(
      "id",connectionId,"name","Disposable CI MySQL","engine","mysql",
      "host","127.0.0.1","port",3306,"database",SOURCE_SCHEMA,
      "username",env("BI_ACCEPTANCE_MYSQL_USERNAME"),
      "password",env("BI_ACCEPTANCE_MYSQL_PASSWORD")));
    String datasetId="ci-dataset-1";
    source.db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
      "VALUES(?,?,?,?,?)",datasetId,"CI sales",connectionId,
      "SELECT city,revenue FROM insight_ci_fact",
      source.store.json(List.of(Map.of("name","city","type","VARCHAR"),
        Map.of("name","revenue","type","DECIMAL"))));
    FieldCatalog catalog=new FieldCatalog(source.store,source.db);
    var fields=catalog.fields(datasetId);
    String city=fields.stream().filter(v->"city".equals(v.sourceColumn()))
      .findFirst().orElseThrow().id();
    String revenue=fields.stream().filter(v->"revenue".equals(v.sourceColumn()))
      .findFirst().orElseThrow().id();
    Map<String,Object> query=Map.of(
      "datasetId",datasetId,
      "dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","m1","fieldId",revenue,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(Map.of("fieldId",city,"op","eq","value","北京")),
        "post",List.of()),
      "limit",30);
    Map<String,Object> result=new AnalysisApi(source.store,source.db,catalog).query(query);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("rows");
    assertEquals(1,rows.size());
    assertEquals("北京",rows.get(0).get("d1"));
    assertEquals(0,new BigDecimal("150").compareTo(
      new BigDecimal(String.valueOf(rows.get(0).get("m1")))));

    try(Connection c=source.store.open(source.store.connection(connectionId))){
      ReadOnlyConnectionPolicy.require(c);
      assertTrue(c.isReadOnly(),"The real JDBC source must accept read-only mode");
      assertEquals(3,source.store.readAnalysisRows(c,
        "SELECT city,revenue FROM insight_ci_fact ORDER BY city",List.of(),3).size());
    }

    var backupService=new MetadataBackupService(source.store,source.db,new ObjectMapper());
    byte[] encrypted=backupService.exportEncrypted();
    assertEquals(20,backupService.verify(encrypted).get("tableCount"));
    var restore=new MetadataBackupService(target.store,target.db,new ObjectMapper());
    assertEquals(20,restore.verify(encrypted).get("tableCount"));
    var tx=new TransactionTemplate(new DataSourceTransactionManager(target.db.getDataSource()));
    tx.executeWithoutResult(status->restore.restoreIntoEmpty(encrypted));
    assertEquals(1,target.db.queryForObject(
      "SELECT COUNT(*) FROM insight_connection",Integer.class));
    assertEquals(1,target.db.queryForObject(
      "SELECT COUNT(*) FROM insight_dataset",Integer.class));
    assertEquals(2,target.db.queryForObject(
      "SELECT COUNT(*) FROM insight_dataset_field",Integer.class));
    assertThrows(IllegalStateException.class,
      ()->tx.executeWithoutResult(status->restore.restoreIntoEmpty(encrypted)));
    assertEquals(env("BI_ACCEPTANCE_MYSQL_PASSWORD"),
      target.store.decrypt(target.db.queryForObject(
        "SELECT credential FROM insight_connection WHERE id=?",String.class,connectionId)));
    assertEquals(3,source.db.queryForObject(
      "SELECT COUNT(*) FROM insight_ci_fact",Integer.class),
      "Backup and recovery must not mutate the source business facts");
    assertEquals(0,target.db.queryForObject(
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='insight_ci_fact'",
      Integer.class,RESTORE_SCHEMA));
  }

  @Test void realMysqlConnectionFailuresDoNotReplayQueriesOrAffectOtherSources()throws Exception{
    requireDisposableMysql();
    var guard=new SourceRecoveryGuard();
    AtomicInteger attempts=new AtomicInteger();
    for(int i=0;i<2;i++){
      assertThrows(SourceRecoveryGuard.Unavailable.class,()->guard.execute("ci-dead-source",()->{
        attempts.incrementAndGet();
        try(Connection ignored=DriverManager.getConnection(
          "jdbc:mysql://127.0.0.1:65534/unused?connectTimeout=300&socketTimeout=300",
          env("BI_ACCEPTANCE_MYSQL_USERNAME"),env("BI_ACCEPTANCE_MYSQL_PASSWORD"))){
          return true;
        }
      }));
    }
    assertEquals("open",guard.snapshot("ci-dead-source").state());
    assertThrows(SourceRecoveryGuard.Unavailable.class,()->guard.execute("ci-dead-source",()->{
      attempts.incrementAndGet();return true;
    }));
    assertEquals(2,attempts.get(),"Open circuit must not make a third JDBC attempt");
    assertEquals("closed",guard.snapshot("ci-healthy-source").state());
    assertTrue(guard.execute("ci-healthy-source",()->{
      try(Connection connection=DriverManager.getConnection(
        schemaUrl(SOURCE_SCHEMA),env("BI_ACCEPTANCE_MYSQL_USERNAME"),
        env("BI_ACCEPTANCE_MYSQL_PASSWORD"));
          Statement statement=connection.createStatement();
          ResultSet result=statement.executeQuery("SELECT 1")){
        return result.next()&&result.getInt(1)==1;
      }
    }));
  }

  @Test void optionalStarRocksPerformsSelectOnlyWithoutSchemaOrDataMutation()throws Exception{
    String url=env("BI_ACCEPTANCE_STARROCKS_JDBC_URL");
    Assumptions.assumeTrue(!url.isBlank(),"StarRocks endpoint not supplied");
    assertTrue(url.startsWith("jdbc:mysql://"),"Only MySQL wire-compatible JDBC URLs allowed");
    String username=env("BI_ACCEPTANCE_STARROCKS_USERNAME");
    String password=env("BI_ACCEPTANCE_STARROCKS_PASSWORD");
    assertFalse(username.isBlank(),"Use a dedicated read-only StarRocks principal");
    try(Connection connection=DriverManager.getConnection(url,username,password)){
      ReadOnlyConnectionPolicy.require(connection);
      try(Statement statement=connection.createStatement()){
        statement.setQueryTimeout(5);
        try(ResultSet rs=statement.executeQuery("SELECT 1")){
          assertTrue(rs.next());
          assertEquals(1,rs.getInt(1));
          assertFalse(rs.next());
        }
      }
    }
  }
}
