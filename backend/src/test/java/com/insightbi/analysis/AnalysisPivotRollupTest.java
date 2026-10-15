package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.insightbi.DatabaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real server-side SUM/AVG/COUNT(DISTINCT) at row, column and grand grouping levels. */
class AnalysisPivotRollupTest {
 @Test void exactRollupsAreWeightedDistinctDecimalAndRespectMandatoryPreFilters() throws Exception {
  var ds=new DriverManagerDataSource(
    "jdbc:h2:mem:pivot_rollup_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  var db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE sales (city VARCHAR(50), month_name VARCHAR(30), amount DECIMAL(22,4), customer VARCHAR(30))");
  db.execute("INSERT INTO sales VALUES "+
    "('北京','一月',0.1000,'A'),('北京','一月',9.9000,'A'),"+
    "('北京','二月',4.0000,'B'),('上海','一月',20.0000,'B'),"+
    "('上海','二月',20.0000,'B'),(NULL,'一月',7.0000,'C')");
  var catalog=List.of(
    new FieldCatalog.Field("city-id","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("month-id","月份","month_name","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("amount-id","金额","amount","DECIMAL","MEASURE",true,false,"ROW",null),
    new FieldCatalog.Field("customer-id","客户","customer","STRING","DIMENSION",false,false,"ROW",null));
  var planner=new AnalysisQueryPlanner(catalog);
  var store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
  Map<String,Object> request=new LinkedHashMap<>();
  request.put("datasetId","trusted-ds");
  request.put("dimensions",List.of(Map.of("fieldId","city-id"),Map.of("fieldId","month-id")));
  request.put("measures",List.of(
    Map.of("id","amount","fieldId","amount-id","aggregate","SUM"),
    Map.of("id","average","fieldId","amount-id","aggregate","AVG"),
    Map.of("id","customers","fieldId","customer-id","aggregate","COUNT","distinct",true),
    Map.of("id","row_count","fieldId","amount-id","aggregate","COUNT")));
  request.put("filters",Map.of("pre",List.of(Map.of("fieldId","city-id","op","eq","value","北京")),"post",List.of()));
  request.put("pivot",Map.of("showTotals",true));
  request.put("limit",100);
  var requests=new ArrayList<Map<String,Object>>();
  try(Connection c=ds.getConnection()){
    PivotRollup.Runner reader=body->{
      requests.add(body);
      var plan=planner.compile("SELECT city,month_name,amount,customer FROM sales",body);
      return store.readAnalysisRows(c,plan.sql(),plan.params(),plan.limit());
    };
    var detail=reader.query(request);
    assertEquals(2,detail.size());
    var totals=PivotRollup.compute(request,detail,false,reader);
    assertEquals(true,totals.get("exact"));
    assertEquals(4,requests.size(),"one grouped detail + three read-only SQL rollups");
    assertEquals(1,((List<?>)requests.get(1).get("dimensions")).size());
    assertEquals(101,requests.get(1).get("limit"));
    for(var req:requests)assertEquals(((Map<?,?>)request.get("filters")).get("pre"),
      ((Map<?,?>)req.get("filters")).get("pre"),"never drop mandatory prefilters");
    @SuppressWarnings("unchecked")
    var row=(Map<String,Object>)((List<?>)totals.get("rows")).get(0);
    @SuppressWarnings("unchecked")
    var grand=(Map<String,Object>)totals.get("grand");
    assertEquals("北京",row.get("d1"));
    decimal("14.0000",row.get("amount"));
    decimal("4.6666666666666666666667",row.get("average"),new BigDecimal("0.00000001"));
    decimal("2",row.get("customers"),BigDecimal.ZERO);
    assertEquals(grand.get("amount"),row.get("amount"));
    decimal("2",grand.get("customers"),BigDecimal.ZERO);
    assertEquals("14.0000",grand.get("amount"),"SQL decimals are serialized as strings");
  }
 }

 @Test void refusesUnsafeRollupAndBadOptionsWithoutExecutingSql() throws Exception {
  Map<String,Object> req=new LinkedHashMap<>();
  req.put("dimensions",List.of(Map.of("fieldId","city"),Map.of("fieldId","month")));
  req.put("measures",List.of(Map.of("id","sum","aggregate","SUM","fieldId","amount")));
  req.put("pivot",Map.of("showTotals",true));
  assertTrue(PivotRollup.requested(req));
  req.put("filters",Map.of("pre",List.of(),"post",List.of(Map.of("ref","sum","op","gte","value",1))));
  var count=new AtomicInteger();
  var detail=List.<Map<String,Object>>of(Map.of("d1","北京","d2","一月","sum",1));
  var denied=PivotRollup.compute(req,detail,false,r->{count.incrementAndGet();return detail;});
  assertEquals(false,denied.get("exact"));
  assertEquals(0,count.get());
  req.put("filters",Map.of("pre",List.of(),"post",List.of()));
  req.put("rank",Map.of("ref","sum"));
  assertEquals(false,PivotRollup.compute(req,detail,false,r->{count.incrementAndGet();return detail;}).get("exact"));
  req.remove("rank");
  assertEquals(false,PivotRollup.compute(req,detail,true,r->{count.incrementAndGet();return detail;}).get("exact"));
  assertEquals(0,count.get());
  req.put("pivot",Map.of("showTotals",false));
  assertFalse(PivotRollup.requested(req));
  req.put("pivot",Map.of("unknown","SQL"));
  assertThrows(IllegalArgumentException.class,()->PivotRollup.requested(req));
 }
 private static void decimal(String expected,Object value){
  decimal(expected,value,BigDecimal.ZERO);
 }
 private static void decimal(String expected,Object value,BigDecimal tolerance){
  assertNotNull(value);
  assertTrue(new BigDecimal(String.valueOf(value)).subtract(new BigDecimal(expected))
    .abs().compareTo(tolerance)<=0,"expected "+expected+" but got "+value);
 }
}
