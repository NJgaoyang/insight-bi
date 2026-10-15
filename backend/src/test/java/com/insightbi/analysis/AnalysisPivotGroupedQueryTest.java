package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** ANALYSIS-PHASE2-01: the server, not the browser, must aggregate pivot groups. */
class AnalysisPivotGroupedQueryTest {
 @Test void aggregatedFormulaCannotPretendToBeAdditive(){
  var planner=new AnalysisQueryPlanner(AnalysisQueryPlannerTest.sample());
  var result=planner.compile("SELECT city,order_date,revenue,cost,profit FROM sales",
    Map.of("dimensions",List.of(Map.of("fieldId","city"),Map.of("fieldId","month")),
      "measures",List.of(Map.of("id","rate_metric","fieldId","rate","aggregate","SUM"),
       Map.of("id","profit_sum","fieldId","rowProfit","aggregate","SUM"),
       Map.of("id","profit_avg","fieldId","rowProfit","aggregate","AVG")),
      "limit",20));
  assertEquals(false,result.columns().get(2).get("additive"));
  assertEquals(true,result.columns().get(3).get("additive"));
  assertEquals(false,result.columns().get(4).get("additive"));
 }
 @Test void groupsTwoStableDimensionsAndMultipleMeasuresWithNullsAndDecimal() throws Exception {
  var ds=new DriverManagerDataSource(
    "jdbc:h2:mem:phase2_pivot_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  var db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE sales(city VARCHAR(50), month_name VARCHAR(50), amount DECIMAL(18,2), customer VARCHAR(20))");
  db.execute("INSERT INTO sales VALUES ('北京','一月',0.10,'A'),('北京','一月',0.20,'A'),"+
    "('北京','二月',3.40,'B'),('上海','一月',20.50,'B'),"+
    "(NULL,'一月',1.00,'C'),('北京',NULL,NULL,'D')");
  var fields=List.of(
    new FieldCatalog.Field("stable-city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("stable-month","月份","month_name","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("stable-amount","金额","amount","DECIMAL","MEASURE",true,false,"ROW",null),
    new FieldCatalog.Field("stable-customer","客户","customer","STRING","DIMENSION",false,false,"ROW",null));
  var planner=new AnalysisQueryPlanner(fields);
  Map<String,Object> request=new HashMap<>();
  request.put("dimensions",List.of(Map.of("fieldId","stable-city"),Map.of("fieldId","stable-month")));
  request.put("measures",List.of(
    Map.of("id","sum_amount","fieldId","stable-amount","aggregate","SUM","alias","金额"),
    Map.of("id","count_amount","fieldId","stable-amount","aggregate","COUNT"),
    Map.of("id","avg_amount","fieldId","stable-amount","aggregate","AVG"),
    Map.of("id","distinct_customer","fieldId","stable-customer","aggregate","COUNT","distinct",true)));
  request.put("limit",200);
  var plan=planner.compile("SELECT city,month_name,amount,customer FROM sales",request);
  assertTrue(plan.sql().contains(" GROUP BY biq.`city`, biq.`month_name`"));
  assertEquals("stable-city",plan.columns().get(0).get("id"));
  assertEquals("stable-month",plan.columns().get(1).get("id"));
  assertEquals(true,plan.columns().get(2).get("additive"));
  assertEquals(true,plan.columns().get(3).get("additive"));
  assertEquals(false,plan.columns().get(4).get("additive"));
  assertEquals(false,plan.columns().get(5).get("additive"));
  List<Map<String,Object>> rows;
  try(Connection c=ds.getConnection()){
   rows=new com.insightbi.DatabaseStore(db,new com.fasterxml.jackson.databind.ObjectMapper(),
    Base64.getEncoder().encodeToString(new byte[32])).readRows(c,plan.sql(),plan.params(),plan.limit());
  }
  assertEquals(5,rows.size());
  var jan=rows.stream().filter(row->"北京".equals(row.get("d1"))&&"一月".equals(row.get("d2")))
    .findFirst().orElseThrow();
  assertEquals(0,new BigDecimal("0.30").compareTo(new BigDecimal(String.valueOf(jan.get("sum_amount")))));
  assertEquals(2,((Number)jan.get("count_amount")).intValue());
  assertEquals(1,((Number)jan.get("distinct_customer")).intValue());
  assertEquals(0,new BigDecimal("0.15").compareTo(new BigDecimal(String.valueOf(jan.get("avg_amount")))));
  assertTrue(rows.stream().anyMatch(row->row.get("d1")==null&&"一月".equals(row.get("d2"))));
  assertTrue(rows.stream().anyMatch(row->"北京".equals(row.get("d1"))&&row.get("d2")==null&&row.get("sum_amount")==null));

  request.put("limit",2);
  var limited=planner.compile("SELECT city,month_name,amount,customer FROM sales",request);
  assertEquals(3,limited.params().get(limited.params().size()-1),
    "sentinel must be included so no totals are shown if groups were truncated");
  request.put("dimensions",List.of(Map.of("fieldId","stable-city"),Map.of("fieldId","unapproved")));
  assertThrows(IllegalArgumentException.class,()->
    planner.compile("SELECT city,month_name,amount,customer FROM sales",request));
 }
}
