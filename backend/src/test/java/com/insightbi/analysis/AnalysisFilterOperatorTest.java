package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Focused SQL execution for the operators now surfaced in the analysis filter editor. */
class AnalysisFilterOperatorTest {
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("day","日期","order_date","DATE","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null));

  private static List<Map<String,Object>> query(Connection conn,Map<String,Object> filters)throws Exception{
    Map<String,Object> request=new HashMap<>();
    request.put("dimensions",List.of(Map.of("fieldId","city")));
    request.put("measures",List.of(Map.of("id","sum","fieldId","revenue","aggregate","SUM")));
    request.put("filters",filters);
    var plan=new AnalysisQueryPlanner(FIELDS).compile(
      "SELECT city,revenue,order_date FROM sales",request);
    List<Map<String,Object>> out=new ArrayList<>();
    try(PreparedStatement statement=conn.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)statement.setObject(i+1,plan.params().get(i));
      try(ResultSet r=statement.executeQuery()){
        while(r.next())out.add(Map.of("city",r.getString("d1")==null?"<NULL>":r.getString("d1"),
          "sum",r.getBigDecimal("sum")==null?BigDecimal.ZERO:r.getBigDecimal("sum")));
      }
    }
    return out;
  }
  @Test void stringContainsEscapesWildcardsAndNullOrInRemainTyped() throws Exception{
    String url="jdbc:h2:mem:filter_operator_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    try(Connection c=DriverManager.getConnection(url,"sa","")){
      try(Statement s=c.createStatement()){
        s.execute("CREATE TABLE sales(city VARCHAR(40),revenue DECIMAL(12,2),order_date DATE)");
        s.execute("INSERT INTO sales VALUES"+
          "('北_%',12,'2026-01-05'),('北xy',22,'2026-01-10'),"+
          "('北京',30,'2026-01-15'),(NULL,40,'2026-01-20')");
      }
      Map<String,Object> contains=Map.of("pre",List.of(Map.of(
        "fieldId","city","op","contains","value","_%")),"post",List.of());
      List<Map<String,Object>> exact=query(c,contains);
      assertEquals(1,exact.size());
      assertEquals("北_%",exact.get(0).get("city"));
      assertEquals(0,new BigDecimal("12").compareTo((BigDecimal)exact.get(0).get("sum")));

      Map<String,Object> nullFilter=Map.of("pre",List.of(Map.of("fieldId","city","op","isNull")),
        "post",List.of());
      List<Map<String,Object>> missing=query(c,nullFilter);
      assertEquals(1,missing.size());
      assertEquals("<NULL>",missing.get(0).get("city"));

      Map<String,Object> in=Map.of("pre",List.of(Map.of("fieldId","city",
        "op","in","values",List.of("北_%","北京"))),"post",List.of());
      assertEquals(2,query(c,in).size());

      Map<String,Object> dateFilter=Map.of("pre",List.of(Map.of("fieldId","day",
        "op","lte","value","2026-01-10")),"post",List.of());
      assertEquals(2,query(c,dateFilter).size());
      var typed=new AnalysisQueryPlanner(FIELDS).compile(
        "SELECT city,revenue,order_date FROM sales",
        Map.of("dimensions",List.of(Map.of("fieldId","city")),
          "measures",List.of(Map.of("id","sum","fieldId","revenue","aggregate","SUM")),
          "filters",dateFilter));
      assertInstanceOf(java.sql.Date.class,typed.params().get(0));

      var planner=new AnalysisQueryPlanner(FIELDS);
      assertThrows(IllegalArgumentException.class,()->planner.compile("SELECT city,revenue,order_date FROM sales",
        Map.of("dimensions",List.of(Map.of("fieldId","city")),
          "measures",List.of(Map.of("id","sum","fieldId","revenue","aggregate","SUM")),
          "filters",Map.of("pre",List.of(Map.of("fieldId","revenue","op","contains","value","1"))))));
    }
  }
}
