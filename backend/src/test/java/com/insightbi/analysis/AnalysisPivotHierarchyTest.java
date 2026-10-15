package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.insightbi.DatabaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Third V2 pivot batch: parent row subtotal is re-aggregated in SQL, not added from leaf COUNT DISTINCT/AVG. */
class AnalysisPivotHierarchyTest {
 @Test void threeDimensionsProduceExactParentSubtotalWithoutLosingReaderPrefilter()throws Exception{
  var ds=new DriverManagerDataSource(
    "jdbc:h2:mem:pivot_hierarchy_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  var jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE region_sales(area VARCHAR(40),city VARCHAR(40),quarter_name VARCHAR(20),amount DECIMAL(18,2),buyer VARCHAR(40))");
  jdbc.execute("INSERT INTO region_sales VALUES"+
    "('北区','北京','一季度',100.10,'A'),"+
    "('北区','北京','二季度',9.90,'A'),"+
    "('北区','天津','一季度',20.00,'A'),"+
    "('南区','上海','一季度',15.00,'B')");
  var planner=new AnalysisQueryPlanner(List.of(
    new FieldCatalog.Field("area-id","区域","area","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("city-id","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("quarter-id","季度","quarter_name","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("amount-id","收入","amount","DECIMAL","MEASURE",true,false,"ROW",null),
    new FieldCatalog.Field("buyer-id","客户","buyer","STRING","DIMENSION",false,false,"ROW",null)));
  var store=new DatabaseStore(jdbc,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
  Map<String,Object> request=new LinkedHashMap<>();
  request.put("datasetId","trusted-source");
  request.put("dimensions",List.of(Map.of("fieldId","area-id"),
    Map.of("fieldId","city-id"),Map.of("fieldId","quarter-id")));
  request.put("measures",List.of(
    Map.of("id","revenue","aggregate","SUM","fieldId","amount-id"),
    Map.of("id","average","aggregate","AVG","fieldId","amount-id"),
    Map.of("id","buyers","aggregate","COUNT","distinct",true,"fieldId","buyer-id")));
  request.put("filters",Map.of("pre",List.of(Map.of("fieldId","area-id","op","eq","value","北区")),"post",List.of()));
  request.put("limit",100);
  request.put("pivot",Map.of("showTotals",true,"showSubtotals",true));
  var calls=new ArrayList<Map<String,Object>>();
  try(Connection conn=ds.getConnection()){
   PivotRollup.Runner runner=body->{
    calls.add(body);
    var plan=planner.compile("SELECT area,city,quarter_name,amount,buyer FROM region_sales",body);
    return store.readAnalysisRows(conn,plan.sql(),plan.params(),plan.limit());
   };
   var detail=runner.query(request);
   assertEquals(3,detail.size());
   var response=PivotRollup.compute(request,detail,false,runner);
   assertEquals(true,response.get("exact"));
   assertEquals(6,calls.size(),"detail, leaf totals, column totals, grand, parent, parent-by-column");
   assertEquals(2,((List<?>)calls.get(1).get("dimensions")).size());
   assertEquals(1,((List<?>)calls.get(4).get("dimensions")).size());
   assertEquals(2,((List<?>)calls.get(5).get("dimensions")).size());
   for(var body:calls)assertEquals(((Map<?,?>)request.get("filters")).get("pre"),
     ((Map<?,?>)body.get("filters")).get("pre"));
   @SuppressWarnings("unchecked")
   var parent=(Map<String,Object>)((List<?>)response.get("parents")).get(0);
   assertEquals("北区",parent.get("d1"));
   assertEquals("130.00",parent.get("revenue"));
   assertTrue(new BigDecimal("43.333333333333").subtract(
     new BigDecimal(String.valueOf(parent.get("average")))).abs().compareTo(new BigDecimal("0.00001"))<=0);
   assertEquals("1",parent.get("buyers"),"parent DISTINCT must not be summed from children");
   @SuppressWarnings("unchecked")
   var parentColumn=(Map<String,Object>)((List<?>)response.get("parentCells")).get(0);
   assertEquals("北区",parentColumn.get("d1"));
   assertTrue(Set.of("一季度","二季度").contains(parentColumn.get("d2")));
   assertEquals("1",parentColumn.get("buyers"),"column subtotal distinct must be SQL aggregated");
   var noSubtotals=new LinkedHashMap<>(request);
   noSubtotals.put("pivot",Map.of("showTotals",true,"showSubtotals",false));
   calls.clear();
   var without=PivotRollup.compute(noSubtotals,detail,false,runner);
   assertEquals(true,without.get("exact"));
   assertFalse(without.containsKey("parents"));
   assertEquals(3,calls.size(),"optional subtotal disabled must save one DB query");
  }
 }
}
