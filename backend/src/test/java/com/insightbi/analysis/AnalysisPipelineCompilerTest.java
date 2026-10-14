package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Only P1-02 ordered SQL-step semantics and injection boundaries. */
class AnalysisPipelineCompilerTest {
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null),
    new FieldCatalog.Field("cost","成本","cost","DECIMAL","BOTH",true,false,"ROW",null)
  );
  private static final String SOURCE="SELECT city,revenue,cost FROM sales";
  private static Map<String,Object> formula(){
    return Map.of("id","derive","type","formula","fieldId","profit",
      "name","利润","expression","[revenue]-[cost]");
  }
  private static Map<String,Object> filter(String field,Object value){
    return Map.of("id","filter1","type","filter","fieldId",field,"op","gte","value",value);
  }
  private static Map<String,Object> group(){
    return Map.of("id","summary","type","group","dimensions",List.of("city"),
      "measures",List.of(Map.of("id","profitSum","fieldId","profit",
        "aggregate","SUM","name","利润合计"),Map.of(
        "id","revenueSum","fieldId","revenue","aggregate","SUM","name","收入合计")));
  }
  private static BigDecimal value(Object raw){return new BigDecimal(String.valueOf(raw));}
  private static List<Map<String,Object>> execute(Connection conn,AnalysisPipelineCompiler.Plan plan)throws Exception{
    List<Map<String,Object>> results=new ArrayList<>();
    try(var stmt=conn.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)stmt.setObject(i+1,plan.params().get(i));
      try(var rs=stmt.executeQuery()){
        var meta=rs.getMetaData();
        while(rs.next()){
          Map<String,Object> row=new HashMap<>();
          for(int i=1;i<=meta.getColumnCount();i++)
            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
          results.add(row);
        }
      }
    }
    return results;
  }
  private static Connection sample()throws Exception{
    Connection c=DriverManager.getConnection("jdbc:h2:mem:pipeline_"+UUID.randomUUID()+
      ";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    try(var stmt=c.createStatement()){
      stmt.execute("CREATE TABLE sales(city VARCHAR(30), revenue DECIMAL(12,2), cost DECIMAL(12,2))");
      stmt.execute("INSERT INTO sales VALUES "+
        "('北京',100,80),('北京',200,150),('上海',50,30),('上海',300,250),('南京',0,0)");
    }
    return c;
  }

  @Test void orderedFilterFormulaGroupAndResultFilterExecuteWithRealH2()throws Exception {
    var steps=List.of(filter("revenue","100"),formula(),group(),
      Map.of("id","resultFilter","type","filter","fieldId","revenueSum",
        "op","gte","value","250"));
    var plan=new AnalysisPipelineCompiler(FIELDS).compile(SOURCE,Map.of("steps",steps,"limit",100));
    assertEquals(4,plan.stepCount());
    assertEquals(3,plan.params().size());
    assertEquals(new BigDecimal("100"),plan.params().get(0));
    assertEquals(new BigDecimal("250"),plan.params().get(1));
    assertEquals(101,plan.params().get(2));
    assertTrue(plan.sql().contains("step_profit"));
    assertTrue(plan.sql().contains("SUM(biq."));
    assertFalse(plan.sql().contains("250"),"Values must only be JDBC binds");
    try(var conn=sample()){
      var rows=execute(conn,plan);
      assertEquals(2,rows.size());
      var beijing=rows.stream().filter(r->"北京".equals(r.get("d_city"))).findFirst().orElseThrow();
      var shanghai=rows.stream().filter(r->"上海".equals(r.get("d_city"))).findFirst().orElseThrow();
      assertEquals(0,value(beijing.get("m_profitsum")).compareTo(new BigDecimal("70")));
      assertEquals(0,value(shanghai.get("m_profitsum")).compareTo(new BigDecimal("50")));
      assertEquals(0,value(beijing.get("m_revenuesum")).compareTo(new BigDecimal("300")));
    }
  }

  @Test void movingFilterAfterAggregationChangesResults()throws Exception {
    var steps=List.of(formula(),group(),
      Map.of("id","lateFilter","type","filter","fieldId","revenueSum",
        "op","gte","value","300"));
    var plan=new AnalysisPipelineCompiler(FIELDS).compile(SOURCE,Map.of("steps",steps));
    try(var c=sample()){
      var rows=execute(c,plan);
      assertEquals(2,rows.size());
      var shanghai=rows.stream().filter(r->"上海".equals(r.get("d_city"))).findFirst().orElseThrow();
      assertEquals(0,value(shanghai.get("m_revenuesum")).compareTo(new BigDecimal("350")));
    }
    var early=List.of(Map.of("id","early","type","filter","fieldId","revenue",
      "op","gte","value","300"),formula(),group());
    var earlyPlan=new AnalysisPipelineCompiler(FIELDS).compile(SOURCE,Map.of("steps",early));
    try(var c=sample()){
      var rows=execute(c,earlyPlan);
      assertEquals(1,rows.size());
      assertEquals(0,value(rows.get(0).get("m_revenuesum")).compareTo(new BigDecimal("300")));
    }
  }

  @Test void rejectStepInjectionSchemaCollisionInvalidTypesAndTooManySteps() {
    var engine=new AnalysisPipelineCompiler(FIELDS);
    assertThrows(IllegalArgumentException.class,()->engine.compile(
      "SELECT revenue FROM sales; DROP TABLE sales",Map.of("steps",List.of())));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(Map.of("id","x","type","formula","name","非法公式",
        "fieldId","hack","expression","SUM([revenue])")))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(Map.of("id","x","type","formula","name","同名ID",
        "fieldId","city","expression","[revenue]+1")))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(Map.of("id","x","type","filter","fieldId","city",
        "op","contains","value","abc"),Map.of("id","x","type","group",
        "dimensions",List.of(),"measures",List.of(Map.of("id","count","fieldId","*",
          "aggregate","COUNT")))))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(Map.of("id","x","type","filter","fieldId","revenue",
        "op","contains","value","30")))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(Map.of("id","x","type","filter","fieldId","missing",
        "op","eq","value","30")))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(Map.of("id","x","type","sql","statement","DELETE FROM sales")))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(group(),Map.of("id","group2","type","group",
        "dimensions",List.of(),"measures",List.of(Map.of("id","n","fieldId","*","aggregate","COUNT")))))));
    assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,Map.of(
      "steps",List.of(),"limit",501)));
  }

  @Test void nullContainsAndInAreBoundAndDoNotBecomeSqlFragments()throws Exception{
    var engine=new AnalysisPipelineCompiler(FIELDS);
    var sqlInjection="上海' OR 1=1 --";
    var plan=engine.compile(SOURCE,Map.of("steps",List.of(
      Map.of("id","a","type","filter","fieldId","city","op","in",
        "values",List.of("上海",sqlInjection)))));
    assertFalse(plan.sql().contains(sqlInjection));
    assertEquals(sqlInjection,plan.params().get(1));
    try(var c=sample()){assertEquals(2,execute(c,plan).size());}
    var nulls=engine.compile(SOURCE,Map.of("steps",List.of(
      Map.of("id","n","type","filter","fieldId","cost","op","isNull"))));
    try(var c=sample()){assertEquals(0,execute(c,nulls).size());}
    var contains=engine.compile(SOURCE,Map.of("steps",List.of(
      Map.of("id","c","type","filter","fieldId","city","op","contains","value","%_"))));
    assertEquals("%!%!_%",contains.params().get(0));
  }

  @Test void secondAggregationConsumesFirstStageMetricsAndRejectsThird()throws Exception{
    var first=Map.<String,Object>of("id","g1","type","group",
      "dimensions",List.of("city"),
      "measures",List.of(Map.of("id","cityTotal","fieldId","revenue",
        "aggregate","SUM","name","城市收入")));
    var second=Map.<String,Object>of("id","g2","type","group",
      "dimensions",List.of(),
      "measures",List.of(Map.of("id","grandTotal","fieldId","cityTotal",
        "aggregate","SUM","name","总收入")));
    var engine=new AnalysisPipelineCompiler(FIELDS);
    var plan=engine.compile(SOURCE,Map.of("steps",List.of(first,second),"limit",10));
    assertTrue(plan.sql().contains("SUM(biq.`m_cityTotal`)"));
    try(var c=sample()){
      var data=execute(c,plan);
      assertEquals(1,data.size());
      assertEquals(0,value(data.get(0).get("m_grandtotal")).compareTo(new BigDecimal("650")));
    }
    var third=Map.<String,Object>of("id","g3","type","group",
      "dimensions",List.of(),
      "measures",List.of(Map.of("id","onceMore","fieldId","grandTotal",
        "aggregate","SUM")));
    assertThrows(IllegalArgumentException.class,()->
      engine.compile(SOURCE,Map.of("steps",List.of(first,second,third))));
  }
}
