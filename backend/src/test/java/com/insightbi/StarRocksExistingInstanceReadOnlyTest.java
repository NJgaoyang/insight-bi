package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.AnalysisQueryPlanner;
import com.insightbi.analysis.FieldCatalog;
import com.insightbi.analysis.ReadOnlyConnectionPolicy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Read-only V2 acceptance against an operator-authorized existing StarRocks FE.
 *
 * No CREATE, INSERT, DROP, UPDATE, DELETE or references to business tables:
 * the source is made from SELECT-only literal rows inside one SQL query.
 * All credentials are injected by runtime environment variables and stored
 * transiently in an isolated H2 metadata database, never committed or logged.
 */
class StarRocksExistingInstanceReadOnlyTest {
  static final String INLINE_SQL =
    "SELECT '北京' AS city, CAST(100 AS DECIMAL(12,2)) AS revenue "+
    "UNION ALL SELECT '北京' AS city, CAST(50 AS DECIMAL(12,2)) AS revenue "+
    "UNION ALL SELECT '上海' AS city, CAST(200 AS DECIMAL(12,2)) AS revenue "+
    "UNION ALL SELECT '广州' AS city, CAST(300 AS DECIMAL(12,2)) AS revenue";
  private static final String DATASET="ci-starrocks-inline-only";
  private static final String CONNECTION="ci-starrocks-readonly";
  private static final String HOST="81.69.15.136";
  private static final int PORT=9030;
  private static final String DATABASE="information_schema";

  private record Fixture(DatabaseStore store,JdbcTemplate metadata,FieldCatalog catalog){}

  private static String env(String name){
    return Optional.ofNullable(System.getenv(name)).orElse("");
  }

  private Fixture fixture(String username,String password) {
    JdbcTemplate metadata=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:readonly_starrocks_"+UUID.randomUUID()+
        ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(metadata,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    store.upsertConnection(Map.of("id",CONNECTION,"name","StarRocks read-only acceptance",
      "engine","starrocks","host",HOST,"port",PORT,"database",DATABASE,
      "username",username,"password",password));
    metadata.update("INSERT INTO insight_dataset "+
      "(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      DATASET,"Read-only virtual rows",CONNECTION,INLINE_SQL,
      store.json(List.of(Map.of("name","city","type","VARCHAR"),
        Map.of("name","revenue","type","DECIMAL"))));
    return new Fixture(store,metadata,new FieldCatalog(store,metadata));
  }

  private static Map<String,Object> request(FieldCatalog catalog,List<String> cities){
    var fields=catalog.fields(DATASET);
    String city=fields.stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String revenue=fields.stream().filter(f->"revenue".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    return Map.of("datasetId",DATASET,
      "dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","m1","fieldId",revenue,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(
        Map.of("fieldId",city,"op","in","values",cities)),"post",List.of()),
      "sort",List.of(),"limit",20);
  }

  @Test void offlinePlannerBuildsParameterizedReadOnlyVirtualSource() {
    Fixture f=fixture("offline-user","offline-fixture-password");
    var cfg=request(f.catalog,List.of("北京","上海"));
    var plan=new AnalysisQueryPlanner(f.catalog.fields(DATASET)).compile(INLINE_SQL,cfg);
    assertTrue(plan.sql().contains("UNION ALL"));
    assertTrue(plan.sql().contains(" IN (?,?)"));
    assertTrue(plan.sql().contains("GROUP BY"));
    assertTrue(plan.params().contains("北京"));
    assertTrue(plan.params().contains("上海"));
    assertFalse(plan.sql().contains("DROP TABLE"));
  }

  @Test void optionalExistingStarRocksV2AggregatesOnlyVirtualRows()throws Exception{
    // Explicit opt-in; CI without the real authorized connection skips this,
    // and the offline compiler test remains a meaningful passing check.
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_EXISTING_STARROCKS")),
      "Existing instance not explicitly authorized in this test environment");
    String username=env("BI_ACCEPTANCE_STARROCKS_USERNAME");
    String password=env("BI_ACCEPTANCE_STARROCKS_PASSWORD");
    assertFalse(username.isBlank(),"StarRocks username must be set at runtime");
    assertFalse(password.isBlank(),"StarRocks password must be set at runtime");
    Fixture f=fixture(username,password);

    try(Connection conn=f.store.open(f.store.connection(CONNECTION))){
      ReadOnlyConnectionPolicy.require(conn);
      assertTrue(conn.isReadOnly());
      try(Statement statement=conn.createStatement()){
        statement.setQueryTimeout(8);
        try(ResultSet result=statement.executeQuery("SELECT 1")){
          assertTrue(result.next());
          assertEquals(1,result.getInt(1));
        }
      }
    }

    AnalysisApi analysis=new AnalysisApi(f.store,f.metadata,f.catalog);
    for(List<String> cities:List.of(
        List.of("北京"),List.of("北京","上海"),List.of("上海","广州"))){
      var result=analysis.query(request(f.catalog,cities));
      @SuppressWarnings("unchecked")
      List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("rows");
      assertEquals(cities.size(),rows.size());
      Map<String,BigDecimal> actual=new HashMap<>();
      for(var row:rows)actual.put(String.valueOf(row.get("d1")),
        new BigDecimal(String.valueOf(row.get("m1"))));
      if(cities.contains("北京"))
        assertEquals(0,new BigDecimal("150").compareTo(actual.get("北京")));
      if(cities.contains("上海"))
        assertEquals(0,new BigDecimal("200").compareTo(actual.get("上海")));
      if(cities.contains("广州"))
        assertEquals(0,new BigDecimal("300").compareTo(actual.get("广州")));
      assertEquals(false,result.get("truncated"));
    }
  }
}
