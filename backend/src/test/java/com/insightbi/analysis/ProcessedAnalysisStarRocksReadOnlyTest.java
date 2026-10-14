package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opt-in P1-02 processing-source reconciliation against the user's dedicated
 * DataForge test StarRocks FE. External SQL is SELECT-only synthetic rows.
 * Metadata writes are only to a private in-memory H2 database.
 */
@EnabledIfEnvironmentVariable(named="P1_STARROCKS_REUSE_E2E",matches="true")
class ProcessedAnalysisStarRocksReadOnlyTest {
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null)
  );
  @Test void savedPipelineV2MatchesIndependentStarRocksSql()throws Exception{
    if(!"true".equals(System.getenv("P0_STARROCKS_NONPROD_CONFIRMED")))
      throw new IllegalStateException("必须明确确认测试环境");
    String url=env("P0_STARROCKS_JDBC_URL"),user=env("P0_STARROCKS_USER");
    DataForgeStarRocksTestAccess.require(url,user,
      "true".equals(System.getenv("P0_DATAFORGE_STARROCKS_TEST")));

    var jdbc=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:p1_starro_reuse_"+UUID.randomUUID()+
      ";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(jdbc,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      "synthetic","Inline testing","sameConn",StarRocksInlineFixture.SOURCE,"[]");
    FieldCatalog catalog=mock(FieldCatalog.class);
    when(catalog.fields("synthetic")).thenReturn(FIELDS);
    var processing=new AnalysisPipelineApi(store,catalog,jdbc);
    var steps=List.of(
      Map.of("id","f1","type","filter","fieldId","city","op","eq","value","北京"),
      Map.of("id","c1","type","case","fieldId","tier","sourceFieldId","revenue",
        "name","收入等级","op","gte","value","150",
        "outputType","STRING","thenValue","高","elseValue","低")
    );
    assertEquals(1,processing.save("synthetic",Map.of(
      "steps",steps,"revision",0)).get("revision"));
    var request=Map.<String,Object>of(
      "datasetId","synthetic","usePipeline",true,"pipelineRevision",1,
      "dimensions",List.of(Map.of("fieldId","tier")),
      "measures",List.of(Map.of("id","amount","fieldId","revenue","aggregate","SUM")),
      "limit",20);
    var source=processing.reusableSource("synthetic",1);
    var semantic=new AnalysisQueryPlanner(source.fields()).compile(source.sql(),request);
    var compiled=new AnalysisApi(store,jdbc,catalog).compile(request);
    assertEquals(semantic.sql(),compiled.get("sqlPreview"));
    var orderedParams=new ArrayList<>(source.params());
    orderedParams.addAll(semantic.params());
    assertEquals(semantic.params().size()+source.params().size(),compiled.get("bindCount"));

    try(Connection conn=DriverManager.getConnection(url,user,env("P0_STARROCKS_PASSWORD"))){
      ReadOnlyConnectionPolicy.require(conn);
      List<Map<String,Object>> actual=query(conn,semantic.sql(),orderedParams);
      List<Map<String,Object>> expected=query(conn,
        "SELECT CASE WHEN x.revenue >= 150 THEN '高' ELSE '低' END AS tier, "+
        "SUM(x.revenue) AS amount FROM ("+StarRocksInlineFixture.SOURCE+") x "+
        "WHERE x.city = '北京' GROUP BY CASE WHEN x.revenue >= 150 THEN '高' ELSE '低' END",
        List.of());
      assertEquals(2,actual.size());
      assertEquals(expected.size(),actual.size());
      for(var row:expected){
        String tier=String.valueOf(row.get("tier"));
        Map<String,Object> match=actual.stream()
          .filter(r->tier.equals(String.valueOf(r.get("d1"))))
          .findFirst().orElseThrow();
        assertEquals(0,new BigDecimal(String.valueOf(row.get("amount")))
          .compareTo(new BigDecimal(String.valueOf(match.get("amount")))));
      }
    }
  }

  private static List<Map<String,Object>> query(
      Connection connection,String sql,List<Object> binds)throws Exception{
    var rows=new ArrayList<Map<String,Object>>();
    try(PreparedStatement stmt=connection.prepareStatement(sql)){
      stmt.setQueryTimeout(15);stmt.setMaxRows(201);
      for(int i=0;i<binds.size();i++)stmt.setObject(i+1,binds.get(i));
      try(ResultSet result=stmt.executeQuery()){
        ResultSetMetaData meta=result.getMetaData();
        while(result.next()){
          Map<String,Object> row=new LinkedHashMap<>();
          for(int i=1;i<=meta.getColumnCount();i++)
            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),result.getObject(i));
          rows.add(row);
        }
      }
    }
    return rows;
  }
  private static String env(String key){
    String v=System.getenv(key);
    if(v==null||v.isBlank())throw new IllegalStateException("测试所需环境变量不存在："+key);
    return v;
  }
}
