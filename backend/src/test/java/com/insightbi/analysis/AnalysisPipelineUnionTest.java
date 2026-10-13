package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** P1-02 same-connection UNION ALL and independent source-change validation. */
class AnalysisPipelineUnionTest {
  private static final List<FieldCatalog.Field> LEFT=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null));
  private static final List<FieldCatalog.Field> RIGHT=List.of(
    new FieldCatalog.Field("rightCity","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("rightRevenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null));
  private static final String MAIN="SELECT city,revenue FROM sales";
  private static final String SECOND="SELECT city,revenue FROM archive";
  private static Map<String,Object> step(){return Map.of(
    "id","append","type","union","sourceDatasetId","archive_ds");}
  private static AnalysisPipelineCompiler compiler(){
    return new AnalysisPipelineCompiler(LEFT,Map.of(
      "archive_ds",new AnalysisPipelineCompiler.UnionSource(SECOND,RIGHT)));
  }
  private static List<Map<String,Object>> rows(Connection conn,AnalysisPipelineCompiler.Plan plan)throws Exception{
    List<Map<String,Object>> out=new ArrayList<>();
    try(var st=conn.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)st.setObject(i+1,plan.params().get(i));
      try(var rs=st.executeQuery()){
        var md=rs.getMetaData();
        while(rs.next()){
          Map<String,Object> row=new HashMap<>();
          for(int i=1;i<=md.getColumnCount();i++)
            row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
          out.add(row);
        }
      }
    }
    return out;
  }

  @Test void exactSameConnectionUnionPreservesDuplicatesAndSupportsLaterGroup()throws Exception{
    var group=Map.of("id","summary","type","group","dimensions",List.of("city"),
      "measures",List.of(Map.of("id","sum","fieldId","revenue","aggregate","SUM"),
        Map.of("id","n","fieldId","*","aggregate","COUNT")));
    var plan=compiler().compile(MAIN,Map.of("steps",List.of(step(),group),"limit",20));
    assertTrue(plan.sql().contains("UNION ALL"));
    assertFalse(plan.sql().contains("archive_ds"));
    assertEquals(List.of(21),plan.params());
    try(var conn=DriverManager.getConnection(
        "jdbc:h2:mem:union_"+UUID.randomUUID()+";MODE=MySQL","sa","")){
      try(var st=conn.createStatement()){
        st.execute("CREATE TABLE sales(city VARCHAR(25), revenue DECIMAL(12,2))");
        st.execute("CREATE TABLE archive(city VARCHAR(25), revenue DECIMAL(12,2))");
        st.execute("INSERT INTO sales VALUES ('北京',100),('上海',40)");
        st.execute("INSERT INTO archive VALUES ('北京',100),('上海',60)");
      }
      var data=rows(conn,plan);
      assertEquals(2,data.size());
      var beijing=data.stream().filter(r->"北京".equals(r.get("d_city"))).findFirst().orElseThrow();
      assertEquals(0,new BigDecimal(String.valueOf(beijing.get("m_sum"))).compareTo(new BigDecimal("200")));
      assertEquals(2,new BigDecimal(String.valueOf(beijing.get("m_n"))).intValueExact());
    }
  }

  @Test void rejectsUnknownSourcesUnexpectedColumnShapesAndWrongOrder(){
    var engine=compiler();
    assertThrows(IllegalArgumentException.class,()->new AnalysisPipelineCompiler(LEFT)
      .compile(MAIN,Map.of("steps",List.of(step()))));
    var malformed=new AnalysisPipelineCompiler(LEFT,Map.of(
      "archive_ds",new AnalysisPipelineCompiler.UnionSource(SECOND,List.of(
        new FieldCatalog.Field("other","Other","other","STRING","BOTH",false,false,"ROW",null)))));
    assertThrows(IllegalArgumentException.class,()->malformed.compile(MAIN,Map.of("steps",List.of(step()))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(MAIN,Map.of(
      "steps",List.of(Map.of("id","f","type","filter","fieldId","city","op","eq","value","北京"),step()))));
    var dangerous=new AnalysisPipelineCompiler(LEFT,Map.of(
      "archive_ds",new AnalysisPipelineCompiler.UnionSource(
        "SELECT city,revenue FROM archive; DELETE FROM sales",RIGHT)));
    assertThrows(IllegalArgumentException.class,()->dangerous.compile(MAIN,Map.of("steps",List.of(step()))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(MAIN,
      Map.of("steps",List.of(Map.of("id","a","type","union","sourceDatasetId","notAllowed")))));
  }

  @Test void secondarySqlChangesMarkSavedPipelineStaleAndBlockPreview()throws Exception{
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:union_api_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    for(var entry:Map.of("main_ds",MAIN,"archive_ds",SECOND).entrySet()){
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        entry.getKey(),entry.getKey(),"same-connection",entry.getValue(),"[]");
    }
    var catalog=mock(FieldCatalog.class);
    when(catalog.fields("main_ds")).thenReturn(LEFT);
    when(catalog.fields("archive_ds")).thenReturn(RIGHT);
    var api=new AnalysisPipelineApi(store,catalog,db);
    assertEquals(1,api.save("main_ds",Map.of("steps",List.of(step()),"revision",0)).get("revision"));
    assertEquals(false,api.get("main_ds").get("stale"));
    db.update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city,revenue FROM archive_v2","archive_ds");
    assertEquals(true,api.get("main_ds").get("stale"));
    assertThrows(IllegalStateException.class,()->api.preview("main_ds",
      Map.of("steps",List.of(step()),"limit",20)));
    assertEquals(2,api.save("main_ds",Map.of("steps",List.of(step()),"revision",1)).get("revision"));
    assertEquals(false,api.get("main_ds").get("stale"));
    db.update("UPDATE insight_dataset SET connection_id=? WHERE id=?","different","archive_ds");
    assertThrows(IllegalArgumentException.class,()->api.compile("main_ds",
      Map.of("steps",List.of(step()),"limit",10)));
  }
}
