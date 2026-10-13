package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalysisQueryPlannerTest {
  static List<FieldCatalog.Field> sample(){
    return List.of(
      new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
      new FieldCatalog.Field("month","日期","order_date","DATE","DIMENSION",false,false,"ROW",null),
      new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null),
      new FieldCatalog.Field("cost","成本","cost","DECIMAL","BOTH",true,false,"ROW",null),
      new FieldCatalog.Field("profit","利润","profit","DECIMAL","BOTH",true,false,"ROW",null),
      new FieldCatalog.Field("rate","利润率",null,"DECIMAL","MEASURE",true,true,"AGGREGATE",
          "SUM([profit]) / NULLIF(SUM([revenue]),0)"),
      new FieldCatalog.Field("rowProfit","行利润",null,"DECIMAL","MEASURE",true,true,"ROW","[revenue]-[cost]")
    );
  }
  @Test void typeMapping(){
    assertEquals("DECIMAL",FieldCatalog.typeOf("DECIMAL"));
    assertEquals("DATETIME",FieldCatalog.typeOf("DATETIME"));
    assertEquals("INTEGER",FieldCatalog.typeOf("BIGINT"));
    assertEquals("STRING",FieldCatalog.typeOf("VARCHAR"));
  }
  @Test void rowAggregateAndDivideByZero(){
    var f=new FormulaCompiler(FieldCatalog.index(sample()));
    var row=f.compile("[revenue]-[cost]");
    assertEquals("ROW",row.phase());assertTrue(row.sql().contains("biq.`revenue`"));
    var agg=f.compile("SUM([profit]) / NULLIF(SUM([revenue]),0)");
    assertEquals("AGGREGATE",agg.phase());
    assertTrue(agg.sql().contains("NULLIF(SUM("));
    assertThrows(IllegalArgumentException.class,()->f.compile("SUM([profit])+[revenue]"));
    assertThrows(IllegalArgumentException.class,()->f.compile("SUM(SUM([profit]))"));
    assertThrows(IllegalArgumentException.class,()->f.compile("EXEC([profit])"));
    assertThrows(IllegalArgumentException.class,()->f.compile("SUM([not_exists])"));
    assertThrows(IllegalArgumentException.class,()->f.compile("SUM([profit]); DELETE FROM sales"));
  }
  @Test void multidimensionWhereHavingBindParametersAndTopN(){
    var p=new AnalysisQueryPlanner(sample());
    Map<String,Object> body=new HashMap<>();
    body.put("dimensions",List.of(Map.of("fieldId","city"),Map.of("fieldId","month","granularity","month")));
    body.put("measures",List.of(Map.of("id","revenue_total","fieldId","revenue","aggregate","SUM"),
      Map.of("id","profit_total","fieldId","profit","aggregate","SUM"),
      Map.of("id","profit_rate","fieldId","rate","aggregate","SUM")));
    body.put("filters",Map.of("pre",List.of(Map.of("fieldId","revenue","op","gte","value","150")),
      "post",List.of(Map.of("ref","revenue_total","op","gte","value","150"))));
    body.put("sort",List.of(Map.of("ref","revenue_total","direction","desc")));
    body.put("limit",30);
    var plan=p.compile("SELECT city,order_date,revenue,cost,profit FROM ods_sales",body);
    assertTrue(plan.sql().contains("DATE_FORMAT"));
    assertTrue(plan.sql().contains(" GROUP BY "));
    assertTrue(plan.sql().contains(" WHERE "));
    assertTrue(plan.sql().contains(" HAVING "));
    assertTrue(plan.sql().contains("SUM(biq.`profit`) / NULLIF(SUM(biq.`revenue`),0)"));
    assertEquals(new BigDecimal("150"),plan.params().get(0));
    assertEquals(new BigDecimal("150"),plan.params().get(1));
    assertEquals(31,plan.params().get(2),"Planner binds one additional row for accurate truncation");
    assertEquals(5,plan.columns().size());
  }
  @Test void dimensionOnlyQueryIsDistinctAndDuplicateDimensionRejected(){
    var p=new AnalysisQueryPlanner(sample());
    var req=new HashMap<String,Object>();
    req.put("dimensions",List.of(Map.of("fieldId","city")));
    req.put("measures",List.of());
    req.put("limit",2);
    var plan=p.compile("SELECT city,revenue FROM sales",req);
    assertTrue(plan.sql().contains(" GROUP BY biq.`city`"),
       "dimension-only analysis must collapse source duplicates");
    assertEquals(3,plan.params().get(plan.params().size()-1));
    req.put("dimensions",List.of(Map.of("fieldId","city"),Map.of("fieldId","city")));
    assertThrows(IllegalArgumentException.class,()->p.compile("SELECT city,revenue FROM sales",req));
  }
  @Test void injectionAndWrongTypesAreRejected(){
    var p=new AnalysisQueryPlanner(sample());
    var base=new HashMap<String,Object>();
    base.put("dimensions",List.of(Map.of("fieldId","city")));
    base.put("measures",List.of(Map.of("id","x","fieldId","revenue","aggregate","SUM")));
    base.put("filters",Map.of("pre",List.of(Map.of("fieldId","city","op","in",
      "values",List.of("北京' OR 1=1 --")))));
    var safe=p.compile("SELECT city,revenue FROM sales",base);
    assertFalse(safe.sql().contains("1=1"));assertEquals("北京' OR 1=1 --",safe.params().get(0));
    base.put("dimensions",List.of(Map.of("fieldId","not_authorized")));
    assertThrows(IllegalArgumentException.class,()->p.compile("SELECT city,revenue FROM sales",base));
    base.put("dimensions",List.of(Map.of("fieldId","city")));
    base.put("sort",List.of(Map.of("ref","revenue; DELETE","direction","desc")));
    assertThrows(IllegalArgumentException.class,()->p.compile("SELECT city,revenue FROM sales",base));
    base.put("sort",List.of());base.put("limit",9999);
    assertThrows(IllegalArgumentException.class,()->p.compile("SELECT city,revenue FROM sales",base));
  }
  @Test void selectAstGuardFailsClosed(){
    assertEquals("SELECT city,revenue FROM sales",ReadOnlySqlGuard.validate("SELECT city,revenue FROM sales"));
    assertThrows(IllegalArgumentException.class,()->ReadOnlySqlGuard.validate("DELETE FROM sales"));
    assertThrows(IllegalArgumentException.class,()->ReadOnlySqlGuard.validate("SELECT * FROM sales; DELETE FROM sales"));
    assertThrows(IllegalArgumentException.class,()->ReadOnlySqlGuard.validate("SELECT * FROM sales FOR UPDATE"));
    assertThrows(IllegalArgumentException.class,()->ReadOnlySqlGuard.validate("SELECT SLEEP(10)"));
  }

}
