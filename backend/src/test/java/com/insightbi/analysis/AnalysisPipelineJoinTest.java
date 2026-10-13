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

class AnalysisPipelineJoinTest {
  private static final List<FieldCatalog.Field> LEFT=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("month","月份","month","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null));
  private static final List<FieldCatalog.Field> RIGHT=List.of(
    new FieldCatalog.Field("rcity","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("rmonth","月份","month","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("refund","退款额","refund","DECIMAL","BOTH",true,false,"ROW",null));
  private static final String MAIN="SELECT city,month,revenue FROM sales";
  private static final String SECOND="SELECT city,month,refund FROM archive";
  private static Map<String,Object> join(String type){
    return Map.of("id","joinStep","type","join","sourceDatasetId","archive_ds",
      "joinType",type,"acknowledgeFanout",true,
      "conditions",List.of(Map.of("leftFieldId","city","rightFieldId","rcity"),
        Map.of("leftFieldId","month","rightFieldId","rmonth")),
      "fields",List.of(Map.of("sourceFieldId","refund","fieldId","refundValue","name","退款额")));
  }
  private static AnalysisPipelineCompiler compiler(){
    return new AnalysisPipelineCompiler(LEFT,Map.of(
      "archive_ds",new AnalysisPipelineCompiler.UnionSource(SECOND,RIGHT)));
  }
  private static Connection sample()throws Exception{
    Connection c=DriverManager.getConnection(
      "jdbc:h2:mem:join_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    try(var st=c.createStatement()){
      st.execute("CREATE TABLE sales(city VARCHAR(20),month VARCHAR(10),revenue DECIMAL(12,2))");
      st.execute("CREATE TABLE archive(city VARCHAR(20),month VARCHAR(10),refund DECIMAL(12,2))");
      st.execute("INSERT INTO sales VALUES ('北京','01',100),('北京','02',150),('上海','01',40)");
      st.execute("INSERT INTO archive VALUES ('北京','01',5),('北京','01',7),('上海','01',2),('北京','03',99)");
    }
    return c;
  }
  private static List<Map<String,Object>> execute(Connection c,AnalysisPipelineCompiler.Plan plan)throws Exception{
    List<Map<String,Object>> result=new ArrayList<>();
    try(var st=c.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)st.setObject(i+1,plan.params().get(i));
      try(var rs=st.executeQuery()){
        var meta=rs.getMetaData();
        while(rs.next()){
          var row=new HashMap<String,Object>();
          for(int i=1;i<=meta.getColumnCount();i++)
            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
          result.add(row);
        }
      }
    }
    return result;
  }
  @Test void multiKeyLeftJoinKeepsUnmatchedAndExplicitlyFansOutRepeatedKeys()throws Exception {
    var plan=compiler().compile(MAIN,Map.of("steps",List.of(join("LEFT")),"limit",20));
    assertTrue(plan.sql().contains(" LEFT JOIN "));
    assertFalse(plan.sql().contains("archive_ds"));
    assertEquals(List.of(21),plan.params());
    assertTrue(plan.columns().stream().anyMatch(x->
      x.get("id").equals("refundValue")&&x.get("name").equals("join_refundValue")));
    try(var c=sample()){
      var rows=execute(c,plan);
      assertEquals(4,rows.size());
      var unmatched=rows.stream().filter(x->"02".equals(x.get("month"))).findFirst().orElseThrow();
      assertNull(unmatched.get("join_refundvalue"));
      assertEquals(2,rows.stream().filter(x->
        "北京".equals(x.get("city"))&&"01".equals(x.get("month"))).count());
    }
  }
  @Test void innerJoinAndSubsequentGroupingBehavePredictably()throws Exception {
    var filter=Map.of("id","onlyBeijing","type","filter","fieldId","city","op","eq","value","北京");
    var group=Map.of("id","grouping","type","group","dimensions",List.of("city"),
      "measures",List.of(
        Map.of("id","totalRefund","fieldId","refundValue","aggregate","SUM"),
        Map.of("id","matches","fieldId","*","aggregate","COUNT")));
    var plan=compiler().compile(MAIN,Map.of("steps",List.of(join("INNER"),filter,group)));
    assertTrue(plan.sql().contains(" INNER JOIN "));
    assertEquals("北京",plan.params().get(0));
    try(var c=sample()){
      var rows=execute(c,plan);
      assertEquals(1,rows.size());
      assertEquals(0,new BigDecimal(String.valueOf(rows.get(0).get("m_totalrefund")))
        .compareTo(new BigDecimal("12")));
      assertEquals(2,new BigDecimal(String.valueOf(rows.get(0).get("m_matches"))).intValueExact());
    }
  }
  @Test void rejectUnapprovedFanoutKeysOutputAliasesAndSqlFragments(){
    var compiler=compiler();
    var unapproved=new HashMap<>(join("INNER"));
    unapproved.remove("acknowledgeFanout");
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,Map.of("steps",List.of(unapproved))));
    var wrong=new HashMap<>(join("INNER"));
    wrong.put("joinType","CROSS");
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,Map.of("steps",List.of(wrong))));
    wrong.put("joinType","INNER");
    wrong.put("conditions",List.of(Map.of("leftFieldId","revenue","rightFieldId","rcity")));
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,Map.of("steps",List.of(wrong))));
    wrong.put("conditions",List.of(Map.of("leftFieldId","city","rightFieldId","rcity"),
      Map.of("leftFieldId","city","rightFieldId","rcity")));
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,Map.of("steps",List.of(wrong))));
    wrong.put("conditions",List.of(Map.of("leftFieldId","city","rightFieldId","rcity")));
    wrong.put("fields",List.of(Map.of("sourceFieldId","refund","fieldId","city","name","duplicate")));
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,Map.of("steps",List.of(wrong))));
    wrong.put("fields",List.of(Map.of("sourceFieldId","refund","fieldId","ref; DROP TABLE","name","bad")));
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,Map.of("steps",List.of(wrong))));
    assertThrows(IllegalArgumentException.class,()->compiler.compile(MAIN,
      Map.of("steps",List.of(Map.of("id","first","type","filter","fieldId","city",
        "op","eq","value","北京"),join("INNER")))));
    var unsafe=new AnalysisPipelineCompiler(LEFT,Map.of(
      "archive_ds",new AnalysisPipelineCompiler.UnionSource(
        "SELECT city,month,refund FROM archive; DELETE FROM sales",RIGHT)));
    assertThrows(IllegalArgumentException.class,()->unsafe.compile(MAIN,Map.of("steps",List.of(join("INNER")))));
  }
  @Test void secondarySourceChangesInvalidateSavedJoinAndCrossConnectionIsRejected()throws Exception {
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:joinapi_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    for(var entry:Map.of("main_ds",MAIN,"archive_ds",SECOND).entrySet())
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        entry.getKey(),entry.getKey(),"shared_conn",entry.getValue(),"[]");
    var catalog=mock(FieldCatalog.class);
    when(catalog.fields("main_ds")).thenReturn(LEFT);
    when(catalog.fields("archive_ds")).thenReturn(RIGHT);
    var api=new AnalysisPipelineApi(store,catalog,db);
    assertEquals(1,api.save("main_ds",Map.of("revision",0,"steps",List.of(join("LEFT")))).get("revision"));
    assertEquals(false,api.get("main_ds").get("stale"));
    db.update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city,month,refund FROM archive_v2","archive_ds");
    assertEquals(true,api.get("main_ds").get("stale"));
    assertThrows(IllegalStateException.class,()->api.preview("main_ds",
      Map.of("steps",List.of(join("LEFT")),"limit",20)));
    db.update("UPDATE insight_dataset SET connection_id=? WHERE id=?",
      "other_conn","archive_ds");
    assertThrows(IllegalArgumentException.class,()->api.compile("main_ds",
      Map.of("steps",List.of(join("LEFT")))));
  }
}
