package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** P1-02 conditional columns: real SQL execution, bind ordering and safety. */
class AnalysisPipelineCaseTest {
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null)
  );
  private static final String SOURCE="SELECT city,revenue FROM sales";
  private static Connection sample()throws Exception{
    Connection c=DriverManager.getConnection("jdbc:h2:mem:conditional_"+UUID.randomUUID()+
      ";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    try(var st=c.createStatement()){
      st.execute("CREATE TABLE sales(city VARCHAR(20), revenue DECIMAL(12,2))");
      st.execute("INSERT INTO sales VALUES ('北京',150),('北京',90),('上海',210),('南京',NULL)");
    }
    return c;
  }
  private static List<Map<String,Object>> run(Connection c,AnalysisPipelineCompiler.Plan plan)throws Exception{
    List<Map<String,Object>> rows=new ArrayList<>();
    try(var stmt=c.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)stmt.setObject(i+1,plan.params().get(i));
      try(var rs=stmt.executeQuery()){
        var meta=rs.getMetaData();
        while(rs.next()){
          Map<String,Object> row=new HashMap<>();
          for(int i=1;i<=meta.getColumnCount();i++)
            row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
          rows.add(row);
        }
      }
    }
    return rows;
  }
  private static Map<String,Object> condition(){
    return Map.of("id","conditional","type","case","fieldId","tier","sourceFieldId","revenue",
      "op","gte","value","100","name","收入等级","outputType","STRING",
      "thenValue","高","elseValue","低");
  }
  @Test void conditionalBranchesAndNullsAreExecutedNotJustCompiled()throws Exception{
    var plan=new AnalysisPipelineCompiler(FIELDS).compile(SOURCE,
      Map.of("steps",List.of(condition()),"limit",20));
    assertEquals(List.of(new BigDecimal("100"),"高","低",21),plan.params());
    assertFalse(plan.sql().contains("'高'"));
    assertTrue(plan.columns().stream().anyMatch(x->"step_tier".equals(x.get("name"))));
    try(var c=sample()){
      var rows=run(c,plan);
      assertEquals(4,rows.size());
      assertEquals(2,rows.stream().filter(r->"高".equals(r.get("step_tier"))).count());
      assertEquals(2,rows.stream().filter(r->"低".equals(r.get("step_tier"))).count());
    }
  }
  @Test void boundOrderWhenPriorFilterAndConditionalBothHaveParameters()throws Exception{
    var filter=Map.of("id","early","type","filter","fieldId","city","op","eq","value","北京");
    var condition=Map.of("id","late","type","case","fieldId","late","sourceFieldId","revenue",
      "op","gte","value","100","name","达标金额","outputType","DECIMAL",
      "thenValue","2.5","elseValue","0");
    var plan=new AnalysisPipelineCompiler(FIELDS).compile(SOURCE,
      Map.of("steps",List.of(filter,condition),"limit",10));
    assertEquals(new BigDecimal("100"),plan.params().get(0));
    assertEquals(new BigDecimal("2.5"),plan.params().get(1));
    assertEquals(new BigDecimal("0"),plan.params().get(2));
    assertEquals("北京",plan.params().get(3));
    assertEquals(11,plan.params().get(4));
    try(var c=sample()){
      var rows=run(c,plan);
      assertEquals(2,rows.size());
      assertEquals(1,rows.stream().filter(r->
        new BigDecimal(String.valueOf(r.get("step_late"))).compareTo(new BigDecimal("2.5"))==0).count());
    }
  }
  @Test void conditionCanBeGroupedThenUsedByAnotherFilter()throws Exception{
    var group=Map.of("id","grouping","type","group","dimensions",List.of("tier"),
      "measures",List.of(Map.of("id","n","fieldId","*","aggregate","COUNT")));
    var plan=new AnalysisPipelineCompiler(FIELDS).compile(SOURCE,
      Map.of("steps",List.of(condition(),group)));
    try(var c=sample()){
      var rows=run(c,plan);
      assertEquals(2,rows.size());
      assertEquals(4,rows.stream()
        .mapToInt(r->new BigDecimal(String.valueOf(r.get("m_n"))).intValueExact()).sum());
    }
  }
  @Test void rejectsUnsafeIdentifiersTypesBranchesAndRawSql() {
    var engine=new AnalysisPipelineCompiler(FIELDS);
    var raw=new HashMap<String,Object>(condition());
    raw.put("thenValue","x'; DELETE FROM sales; --");
    var safe=engine.compile(SOURCE,Map.of("steps",List.of(raw)));
    assertFalse(safe.sql().contains("DELETE FROM"));
    assertEquals(raw.get("thenValue"),safe.params().get(1));
    for(Map<String,Object> overrides:List.of(
      Map.<String,Object>of("fieldId","step_foo; DROP TABLE"),
      Map.<String,Object>of("fieldId","city"),
      Map.<String,Object>of("op","exec"),
      Map.<String,Object>of("outputType","SQL"),
      Map.<String,Object>of("outputType","DECIMAL","thenValue","not a number"),
      Map.<String,Object>of("sourceFieldId","unknown_field"))){
      var candidate=new HashMap<String,Object>(condition());candidate.putAll(overrides);
      assertThrows(IllegalArgumentException.class,()->engine.compile(SOURCE,
        Map.of("steps",List.of(candidate))),candidate.toString());
    }
  }
}
