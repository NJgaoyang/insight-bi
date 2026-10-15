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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Integration acceptance for the disposable Docker FE/BE only.
 * Unlike the optional SELECT-1 smoke, this uses real OLAP-table data through
 * the actual V2 API and compares its result with a real MySQL source.
 */
class StarRocksV2AcceptanceTest {
  private static final String DATABASE="insight_ci_starrocks";
  private static final String TABLE="insight_ci_fact";
  private static final String READ_USER="bi_ci_reader";
  private static final String READ_PASSWORD="disposable-ci-reader-only-2026";
  private static final String ROOT_USER="root";
  private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);

  private static String env(String name){
    return Optional.ofNullable(System.getenv(name)).orElse("");
  }
  private static void requireDisposable(){
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_DISPOSABLE_STARROCKS")),
      "Requires isolated StarRocks Docker cluster, not any external endpoint");
    assertEquals("127.0.0.1",env("BI_ACCEPTANCE_STARROCKS_HOST"));
    assertEquals("9030",env("BI_ACCEPTANCE_STARROCKS_PORT"));
    assertEquals("127.0.0.1",env("BI_ACCEPTANCE_MYSQL_HOST"));
    assertEquals("3306",env("BI_ACCEPTANCE_MYSQL_PORT"));
    assertEquals("yes",env("BI_ACCEPTANCE_DISPOSABLE_MYSQL"));
  }
  private static String url(String database,int port){
    return "jdbc:mysql://127.0.0.1:"+port+"/"+database+
      "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"+
      "&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000";
  }

  @Test void starRocksReadOnlyV2MatchesMysqlAndDeniedWritesStayDenied()throws Exception{
    requireDisposable();
    String mysqlUser=env("BI_ACCEPTANCE_MYSQL_USERNAME");
    String mysqlPassword=env("BI_ACCEPTANCE_MYSQL_PASSWORD");
    assertFalse(mysqlUser.isBlank());

    // Only the CI container's administrative connection performs DDL or load.
    try(Connection admin=DriverManager.getConnection(
          url("",9030),ROOT_USER,env("BI_ACCEPTANCE_STARROCKS_ADMIN_PASSWORD"));
        Statement stmt=admin.createStatement()){
      stmt.setQueryTimeout(20);
      stmt.execute("CREATE DATABASE IF NOT EXISTS "+DATABASE);
      stmt.execute("DROP TABLE IF EXISTS "+DATABASE+"."+TABLE);
      stmt.execute("CREATE TABLE "+DATABASE+"."+TABLE+" ("+
        "city VARCHAR(32) NOT NULL,revenue DECIMAL(12,2) NOT NULL) "+
        "ENGINE=OLAP DUPLICATE KEY(city) DISTRIBUTED BY HASH(city) BUCKETS 1 "+
        "PROPERTIES (\"replication_num\"=\"1\")");
      stmt.execute("INSERT INTO "+DATABASE+"."+TABLE+
        " VALUES ('北京',100),('北京',50),('上海',200),('广州',300)");
      stmt.execute("CREATE USER IF NOT EXISTS '"+READ_USER+
        "'@'%' IDENTIFIED BY '"+READ_PASSWORD+"'");
      stmt.execute("GRANT SELECT ON TABLE "+DATABASE+"."+TABLE+
        " TO USER '"+READ_USER+"'@'%'");
    }

    // A real non-readonly JDBC connection must be denied SQL INSERT by the
    // server-side account ACL (not merely by JDBC's isReadOnly flag).
    try(Connection reader=DriverManager.getConnection(
        url(DATABASE,9030),READ_USER,READ_PASSWORD)){
      try(Statement stmt=reader.createStatement()){
        stmt.setQueryTimeout(10);
        assertThrows(SQLException.class,()->stmt.executeUpdate(
          "INSERT INTO "+TABLE+" VALUES ('不应该写入',9999)"));
        try(ResultSet rs=stmt.executeQuery("SELECT COUNT(*) FROM "+TABLE)){
          assertTrue(rs.next());
          assertEquals(4,rs.getInt(1));
        }
      }
      ReadOnlyConnectionPolicy.require(reader);
      assertTrue(reader.isReadOnly());
    }

    // The fixture is mirrored to a dedicated MySQL CI service. It never
    // connects to a production database or arbitrary caller-supplied host.
    try(Connection mysql=DriverManager.getConnection(
          url("insight_ci_acceptance",3306),mysqlUser,mysqlPassword);
        Statement stmt=mysql.createStatement()){
      stmt.execute("CREATE TABLE IF NOT EXISTS insight_ci_fact ("+
        "city VARCHAR(32), revenue DECIMAL(12,2))");
      stmt.execute("DELETE FROM insight_ci_fact");
      stmt.execute("INSERT INTO insight_ci_fact VALUES "+
        "('北京',100),('北京',50),('上海',200),('广州',300)");
    }

    // The app's internal metadata stays in fresh H2 for this test, so the
    // only external operations during V2 query are parameterized SELECTs.
    var metadata=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:sr_accept_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(metadata,new ObjectMapper(),KEY);
    store.schema();
    store.upsertConnection(Map.of("id","ci-starrocks","name","CI StarRocks",
      "engine","starrocks","host","127.0.0.1","port",9030,
      "database",DATABASE,"username",READ_USER,"password",READ_PASSWORD));
    store.upsertConnection(Map.of("id","ci-mysql","name","CI MySQL",
      "engine","mysql","host","127.0.0.1","port",3306,
      "database","insight_ci_acceptance","username",mysqlUser,"password",mysqlPassword));
    for(String dataset:List.of("ci-sr-dataset","ci-mysql-dataset")){
      String sourceId=dataset.contains("-sr-")?"ci-starrocks":"ci-mysql";
      metadata.update("INSERT INTO insight_dataset "+
        "(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        dataset,dataset,sourceId,"SELECT city,revenue FROM insight_ci_fact",
        store.json(List.of(Map.of("name","city","type","VARCHAR"),
          Map.of("name","revenue","type","DECIMAL"))));
    }

    var fields=new FieldCatalog(store,metadata);
    var v2=new AnalysisApi(store,metadata,fields);
    for(List<String> allowed:List.of(List.of("北京"),List.of("北京","上海"),
      List.of("上海","广州"))){
      Map<String,BigDecimal> starrocks=execute(v2,fields,"ci-sr-dataset",allowed);
      Map<String,BigDecimal> mysql=execute(v2,fields,"ci-mysql-dataset",allowed);
      assertEquals(mysql,starrocks,"Real MySQL and StarRocks V2 aggregates must match");
      assertEquals(allowed.size(),starrocks.size());
      if(allowed.equals(List.of("北京")))
        assertEquals(0,new BigDecimal("150").compareTo(starrocks.get("北京")));
    }
    try(Connection admin=DriverManager.getConnection(
          url(DATABASE,9030),ROOT_USER,env("BI_ACCEPTANCE_STARROCKS_ADMIN_PASSWORD"));
        Statement stmt=admin.createStatement();
        ResultSet result=stmt.executeQuery("SELECT SUM(revenue) FROM "+TABLE)){
      assertTrue(result.next());
      assertEquals(0,new BigDecimal("650").compareTo(result.getBigDecimal(1)));
      // Neither denied INSERT nor BI V2 SELECTs have changed source data.
    }
  }

  private static Map<String,BigDecimal> execute(AnalysisApi api,FieldCatalog fields,
        String dataset,List<String> allowed)throws Exception{
    var discovered=fields.fields(dataset);
    String city=discovered.stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String revenue=discovered.stream().filter(f->"revenue".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    var config=Map.of("datasetId",dataset,
      "dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","m1","fieldId",revenue,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(Map.of("fieldId",city,"op","in","values",allowed)),
        "post",List.of()),"limit",50);
    Map<String,Object> result=api.query(config);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("rows");
    Map<String,BigDecimal> sums=new TreeMap<>();
    for(var row:rows)
      sums.put(String.valueOf(row.get("d1")),
        new BigDecimal(String.valueOf(row.get("m1"))).stripTrailingZeros());
    assertEquals(false,result.get("truncated"));
    return sums;
  }

  @Test void starRocksNetworkOutageFailsFastWithoutQueryReplay()throws Exception{
    requireDisposable();
    var guard=new SourceRecoveryGuard();
    AtomicInteger attempts=new AtomicInteger();
    for(int i=0;i<2;i++){
      assertThrows(SourceRecoveryGuard.Unavailable.class,()->guard.execute(
        "ci-starrocks-offline",()->{
          attempts.incrementAndGet();
          try(Connection unused=DriverManager.getConnection(
            url(DATABASE,19031),"nobody","unused")){
            return "unexpected";
          }
        }));
    }
    assertEquals("open",guard.snapshot("ci-starrocks-offline").state());
    assertThrows(SourceRecoveryGuard.Unavailable.class,
      ()->guard.execute("ci-starrocks-offline",()->{
        attempts.incrementAndGet();return "unexpected";
      }));
    assertEquals(2,attempts.get(),"Open circuit must not replay the failed JDBC operation");
    assertEquals("closed",guard.snapshot("ci-starrocks-healthy").state());
    // The actual FE remains available to the healthy source ID.
    assertTrue(guard.execute("ci-starrocks-healthy",()->{
      try(Connection connection=DriverManager.getConnection(
        url("",9030),ROOT_USER,env("BI_ACCEPTANCE_STARROCKS_ADMIN_PASSWORD"));
        Statement stmt=connection.createStatement();
        ResultSet rs=stmt.executeQuery("SELECT 1")){
        return rs.next()&&rs.getInt(1)==1;
      }
    }));
  }
}
