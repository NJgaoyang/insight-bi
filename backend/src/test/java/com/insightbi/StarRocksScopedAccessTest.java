package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.FieldCatalog;
import com.insightbi.analysis.ReadOnlyConnectionPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opt-in only: against a random, disposable StarRocks schema and SELECT-only
 * test principal prepared by scripts/p3-starrocks-isolated-fixture.sh.
 * NEVER run this class with an existing business database.
 */
class StarRocksScopedAccessTest {
  private static final String TABLE="insight_ci_fact";
  private static final String ID="ci-starrocks-scoped-source";
  private static final String DATASET="ci-starrocks-scoped-dataset";

  private static String env(String name){return Optional.ofNullable(System.getenv(name)).orElse("");}

  @Test void v2ReadsActualOlapRowsWhileServerDeniesWrites()throws Exception{
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_SCOPED_STARROCKS")),
      "Requires explicitly prepared disposable StarRocks schema");
    String host=env("BI_ACCEPTANCE_STARROCKS_HOST");
    String database=env("BI_ACCEPTANCE_STARROCKS_SCHEMA");
    String username=env("BI_ACCEPTANCE_STARROCKS_READER");
    String passwordFile=env("BI_ACCEPTANCE_STARROCKS_READER_PASSWORD_FILE");
    assertEquals("81.69.15.136",host,"Test is pinned to the authorized FE");
    assertTrue(database.matches("insight_ci_[a-f0-9]{16}"),"Must use disposable CI schema");
    assertTrue(username.matches("bi_ci_[a-f0-9]{12}"),"Must use ephemeral test reader");
    assertFalse(passwordFile.isBlank());
    Path secret=Path.of(passwordFile).toRealPath();
    assertTrue(Files.isRegularFile(secret));
    assertTrue(Files.size(secret)>0&&Files.size(secret)<256);
    String password=Files.readString(secret,StandardCharsets.UTF_8).trim();

    String url="jdbc:mysql://"+host+":9030/"+database+
      "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=8000";

    // Verify SELECT is allowed and DML/DDL are blocked by the server itself.
    // A misconfigured writable account could at most change the disposable CI
    // table; test must fail, and the operator script drops the whole CI schema.
    try(Connection connection=DriverManager.getConnection(url,username,password);
        Statement stmt=connection.createStatement()){
      stmt.setQueryTimeout(8);
      try(var rows=stmt.executeQuery("SELECT COUNT(*) FROM "+TABLE)){
        assertTrue(rows.next());assertEquals(4,rows.getInt(1));
      }
      assertThrows(SQLException.class,()->stmt.executeUpdate(
        "INSERT INTO "+TABLE+" VALUES ('unwanted',9999)"),
        "The real StarRocks account MUST deny INSERT even without JDBC read-only");
      assertThrows(SQLException.class,()->stmt.executeUpdate(
        "CREATE TABLE insight_ci_must_not_create(k INT)"),
        "The real StarRocks account MUST deny CREATE TABLE");
      ReadOnlyConnectionPolicy.require(connection);
      assertTrue(connection.isReadOnly());
    }

    JdbcTemplate metadata=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:ci_starrocks_scoped_"+UUID.randomUUID()+
        ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(metadata,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    store.upsertConnection(Map.of("id",ID,"name","CI scoped StarRocks",
      "engine","starrocks","host",host,"port",9030,"database",database,
      "username",username,"password",password));
    metadata.update("INSERT INTO insight_dataset "+
      "(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      DATASET,"CI scoped rows",ID,"SELECT city,revenue FROM "+TABLE,
      store.json(List.of(Map.of("name","city","type","VARCHAR"),
        Map.of("name","revenue","type","DECIMAL"))));
    var catalog=new FieldCatalog(store,metadata);
    var discovered=catalog.fields(DATASET);
    String city=discovered.stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String amount=discovered.stream().filter(f->"revenue".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    AnalysisApi analysis=new AnalysisApi(store,metadata,catalog);
    Map<String,BigDecimal> all=execute(analysis,city,amount,List.of("北京","上海","广州"));
    assertEquals(Map.of("北京",new BigDecimal("150"),"上海",new BigDecimal("200"),
      "广州",new BigDecimal("300")),all);
    assertEquals(Map.of("北京",new BigDecimal("150"),"上海",new BigDecimal("200")),
      execute(analysis,city,amount,List.of("北京","上海")));
    assertEquals(Map.of("北京",new BigDecimal("150")),
      execute(analysis,city,amount,List.of("北京")));
  }

  private static Map<String,BigDecimal> execute(AnalysisApi api,String city,String measure,
       List<String> permitted)throws Exception{
    var request=Map.of("datasetId",DATASET,
      "dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","m1","fieldId",measure,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(
        Map.of("fieldId",city,"op","in","values",permitted)), "post",List.of()),
      "limit",50);
    Map<String,Object> reply=api.query(request);
    assertEquals(false,reply.get("truncated"));
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> rows=(List<Map<String,Object>>)reply.get("rows");
    Map<String,BigDecimal> amounts=new HashMap<>();
    for(var row:rows){
      var prev=amounts.put(String.valueOf(row.get("d1")),
        new BigDecimal(String.valueOf(row.get("m1"))).setScale(0,java.math.RoundingMode.UNNECESSARY));
      assertNull(prev);
    }
    assertEquals(permitted.size(),amounts.size());
    return amounts;
  }
}
