package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** P1-02: published V2 widgets opt into an exact, saved and stale-safe pipeline revision. */
class AnalysisPipelineReuseTest {
  private static final List<FieldCatalog.Field> INPUT=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null)
  );
  // Inline H2 SELECT-only source: no business data, DDL or write queries.
  private static final String SOURCE=
    "SELECT '北京' AS city, CAST(120 AS DECIMAL(12,2)) AS revenue "+
    "UNION ALL SELECT '北京',CAST(80 AS DECIMAL(12,2)) "+
    "UNION ALL SELECT '上海',CAST(200 AS DECIMAL(12,2))";

  private record Fixture(JdbcTemplate db,AnalysisPipelineApi pipeline,AnalysisApi analysis) {}
  private static Fixture fixture(){
    JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:reuse_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    DatabaseStore store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      "ds1","原始收入","conn",SOURCE,"[]");
    FieldCatalog catalog=mock(FieldCatalog.class);
    when(catalog.fields("ds1")).thenReturn(INPUT);
    return new Fixture(db,new AnalysisPipelineApi(store,catalog,db),
      new AnalysisApi(store,db,catalog));
  }
  private static List<Map<String,Object>> steps(){
    return List.of(
      Map.of("id","filter","type","filter","fieldId","city",
        "op","eq","value","北京"),
      Map.of("id","case1","type","case","sourceFieldId","revenue","fieldId","tier",
        "name","收入等级","op","gte","value","100",
        "outputType","STRING","thenValue","高","elseValue","低")
    );
  }
  private static Map<String,Object> widgetRequest(int revision){
    return Map.of("datasetId","ds1","usePipeline",true,"pipelineRevision",revision,
      "dimensions",List.of(Map.of("fieldId","tier")),
      "measures",List.of(Map.of("id","sum","fieldId","revenue","aggregate","SUM")),
      "filters",Map.of("pre",List.of(Map.of("fieldId","revenue","op","gte","value",100)),
        "post",List.of()),
      "limit",10);
  }
  private static List<Map<String,Object>> execute(String sql,List<Object> params)throws Exception {
    var rows=new ArrayList<Map<String,Object>>();
    try(Connection conn=DriverManager.getConnection("jdbc:h2:mem:reuse_query_"+UUID.randomUUID()+";MODE=MySQL","sa","");
        PreparedStatement ps=conn.prepareStatement(sql)){
      for(int i=0;i<params.size();i++)ps.setObject(i+1,params.get(i));
      try(ResultSet rs=ps.executeQuery()){
        var meta=rs.getMetaData();
        while(rs.next()){
          Map<String,Object> row=new LinkedHashMap<>();
          for(int i=1;i<=meta.getColumnCount();i++)
            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
          rows.add(row);
        }
      }
    }
    return rows;
  }

  @Test void exactSavedPipelineRevisionProvidesStableFieldsForV2Charts()throws Exception{
    Fixture fx=fixture();
    assertEquals(1,fx.pipeline.save("ds1",Map.of("steps",steps(),"revision",0)).get("revision"));
    var published=fx.pipeline.reusableFields("ds1");
    assertEquals(1,published.get("revision"));
    @SuppressWarnings("unchecked")
    List<FieldCatalog.Field> fields=(List<FieldCatalog.Field>)published.get("fields");
    assertTrue(fields.stream().anyMatch(f->f.id().equals("tier")&&
      f.sourceColumn().equals("step_tier")&&f.logicalType().equals("STRING")));
    assertEquals(3,fields.size());

    var source=fx.pipeline.reusableSource("ds1",1);
    assertFalse(source.sql().endsWith(" LIMIT ?"));
    // The inner CASE is in SELECT, before earlier WHERE placeholders.
    assertEquals(List.of(new BigDecimal("100"),"高","低","北京"),source.params());
    var requested=widgetRequest(1);
    var compiled=fx.analysis.compile(requested);
    assertEquals(6,compiled.get("bindCount"));
    String sql=String.valueOf(compiled.get("sqlPreview"));
    assertFalse(sql.contains("('高'"));
    var semantic=new AnalysisQueryPlanner(source.fields()).compile(source.sql(),requested);
    List<Object> all=new ArrayList<>(source.params());
    all.addAll(semantic.params());
    assertEquals(List.of(new BigDecimal("100"),"高","低","北京",new BigDecimal("100"),11),all);
    List<Map<String,Object>> rows=execute(sql,all);
    assertEquals(1,rows.size());
    assertEquals("高",rows.get(0).get("d1"));
    assertEquals(0,new BigDecimal(String.valueOf(rows.get(0).get("sum")))
      .compareTo(new BigDecimal("120")));
  }

  @Test void refusesUnsavedStaleAndWrongRevisionWithoutOpeningDataConnection(){
    Fixture fx=fixture();
    assertThrows(IllegalArgumentException.class,()->
      fx.analysis.compile(widgetRequest(1)));
    fx.pipeline.save("ds1",Map.of("steps",steps(),"revision",0));
    assertThrows(IllegalStateException.class,()->
      fx.analysis.compile(widgetRequest(2)));
    fx.db.update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      SOURCE+" UNION ALL SELECT '南京',CAST(30 AS DECIMAL(12,2))","ds1");
    assertThrows(IllegalStateException.class,()->
      fx.analysis.compile(widgetRequest(1)));
  }

  @Test void unprocessedV2QueriesRemainUnchangedAndPipelineBindingIsTyped(){
    Fixture fx=fixture();
    Map<String,Object> ordinary=new HashMap<>(widgetRequest(1));
    ordinary.remove("pipelineRevision");
    ordinary.put("usePipeline",false);
    var result=fx.analysis.compile(ordinary);
    assertEquals(2,result.get("bindCount"),"one filter bind and one limit sentinel");
    ordinary.put("usePipeline","true");
    assertThrows(IllegalArgumentException.class,()->fx.analysis.compile(ordinary));
    ordinary.put("usePipeline",true);
    assertThrows(IllegalArgumentException.class,()->fx.analysis.compile(ordinary));
  }
}
