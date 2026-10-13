package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in P0 StarRocks integration test. Uses a synthetic inline SELECT dataset,
 * never creates, inserts, updates or deletes tables or StarRocks business data.
 * Requires an explicitly confirmed non-production READ ONLY JDBC account.
 */
@EnabledIfEnvironmentVariable(named="P0_STARROCKS_E2E",matches="true")
class StarRocksReadOnlyReconciliationTest {
  private static final String SOURCE=StarRocksInlineFixture.SOURCE;
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("f_city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("f_date","日期","order_date","DATE","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("f_revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null),
    new FieldCatalog.Field("f_cost","成本","cost","DECIMAL","BOTH",true,false,"ROW",null),
    new FieldCatalog.Field("f_profit","利润","profit","DECIMAL","BOTH",true,false,"ROW",null),
    new FieldCatalog.Field("f_margin","利润率",null,"DECIMAL","MEASURE",true,true,"AGGREGATE",
      "SUM([f_profit]) / NULLIF(SUM([f_revenue]),0)")
  );

  @Test void multidimensionalAggregationAndPrePostFilterMatchManualExpectations() throws Exception {
    if(!"true".equals(System.getenv("P0_STARROCKS_NONPROD_CONFIRMED")))
      throw new IllegalStateException("必须明确设置 P0_STARROCKS_NONPROD_CONFIRMED=true 才能对账");
    String url=requiredEnv("P0_STARROCKS_JDBC_URL");
    if(!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("仅允许 StarRocks MySQL 协议 JDBC URL");
    String username=requiredEnv("P0_STARROCKS_USER");
    DataForgeStarRocksTestAccess.require(url,username,
      "true".equals(System.getenv("P0_DATAFORGE_STARROCKS_TEST")));
    try(Connection conn=DriverManager.getConnection(url,username,
            requiredEnv("P0_STARROCKS_PASSWORD"))){
      ReadOnlyConnectionPolicy.require(conn);
      var planner=new AnalysisQueryPlanner(FIELDS);
      var query=new HashMap<String,Object>();
      query.put("dimensions",List.of(Map.of("fieldId","f_city"),Map.of("fieldId","f_date","granularity","month")));
      query.put("measures",List.of(
        Map.of("id","revenue_sum","fieldId","f_revenue","aggregate","SUM"),
        Map.of("id","profit_sum","fieldId","f_profit","aggregate","SUM"),
        Map.of("id","margin","fieldId","f_margin","aggregate","SUM")));
      query.put("sort",List.of(Map.of("ref","revenue_sum","direction","desc")));
      query.put("limit",20);
      List<Map<String,Object>> totals=run(conn,planner.compile(SOURCE,query));
      assertEquals(3,totals.size());
      var beijing=find(totals,"d1","北京");
      equalsDecimal("300",beijing.get("revenue_sum"));
      equalsDecimal("60",beijing.get("profit_sum"));
      equalsDecimal("0.2",beijing.get("margin"));
      assertEquals("2026-01",beijing.get("d2"));
      assertNull(find(totals,"d1","南京").get("margin"),"zero revenue must yield NULL");

      // Independently written SQL baseline: do not assert only fixed constants
      // against a query produced by the same planner being tested.
      List<Map<String,Object>> manual=manual(conn,
        "SELECT biq.city AS city, DATE_FORMAT(biq.order_date,'%Y-%m') AS month, "+
        "SUM(biq.revenue) AS revenue_sum, SUM(biq.profit) AS profit_sum, "+
        "SUM(biq.profit)/NULLIF(SUM(biq.revenue),0) AS margin "+
        "FROM ("+SOURCE+") biq GROUP BY biq.city, DATE_FORMAT(biq.order_date,'%Y-%m')");
      assertEquals(manual.size(),totals.size(),"Manual SQL and planned SQL should group identically");
      for(var expected:manual){
        var actual=find(totals,"d1",String.valueOf(expected.get("city")));
        assertEquals(expected.get("month"),actual.get("d2"));
        equalsDecimal(String.valueOf(expected.get("revenue_sum")),actual.get("revenue_sum"));
        equalsDecimal(String.valueOf(expected.get("profit_sum")),actual.get("profit_sum"));
        if(expected.get("margin")==null)assertNull(actual.get("margin"));
        else assertEquals(0,new BigDecimal(String.valueOf(expected.get("margin")))
          .compareTo(new BigDecimal(String.valueOf(actual.get("margin")))));
      }

      query.put("filters",Map.of("pre",List.of(Map.of("fieldId","f_revenue","op","gte","value",150)),
                                  "post",List.of()));
      var pre=run(conn,planner.compile(SOURCE,query));
      assertEquals(1,pre.size());
      equalsDecimal("200",pre.get(0).get("revenue_sum"));

      query.put("filters",Map.of("pre",List.of(),
        "post",List.of(Map.of("ref","revenue_sum","op","gte","value",150))));
      var post=run(conn,planner.compile(SOURCE,query));
      assertEquals(1,post.size());
      equalsDecimal("300",post.get(0).get("revenue_sum"));

      var distinct=Map.<String,Object>of("dimensions",List.of(),
        "measures",List.of(Map.of("id","cities","fieldId","f_city","aggregate","COUNT","distinct",true)),
        "limit",20);
      equalsDecimal("3",run(conn,planner.compile(SOURCE,distinct)).get(0).get("cities"));
      assertThrows(IllegalArgumentException.class,()->planner.compile(SOURCE,
        Map.of("dimensions",List.of(Map.of("fieldId","f_missing")))));

      // Extra fixture rows exercise repeated values, a NULL decimal and NULL date.
      // The SQL remains an inline SELECT-only derived dataset.
      String edgeSource=StarRocksInlineFixture.EDGE_SOURCE;
      Map<String,Object> counts=Map.of("dimensions",List.of(),"measures",List.of(
        Map.of("id","count_city","fieldId","f_city","aggregate","COUNT"),
        Map.of("id","count_revenue","fieldId","f_revenue","aggregate","COUNT"),
        Map.of("id","unique_revenue","fieldId","f_revenue","aggregate","COUNT","distinct",true),
        Map.of("id","total_revenue","fieldId","f_revenue","aggregate","SUM")));
      var edge=run(conn,planner.compile(edgeSource,counts));
      assertEquals(1,edge.size());
      equalsDecimal("6",edge.get(0).get("count_city"));
      equalsDecimal("5",edge.get(0).get("count_revenue"));
      equalsDecimal("4",edge.get(0).get("unique_revenue"));
      equalsDecimal("400",edge.get(0).get("total_revenue"));
      var manualCounts=manual(conn,"SELECT COUNT(city) AS count_city, "+
        "COUNT(revenue) AS count_revenue, COUNT(DISTINCT revenue) AS unique_revenue, "+
        "SUM(revenue) AS total_revenue FROM ("+edgeSource+") biq");
      for(var key:List.of("count_city","count_revenue","unique_revenue","total_revenue"))
        equalsDecimal(String.valueOf(manualCounts.get(0).get(key)),edge.get(0).get(key));
    }
  }

  private static List<Map<String,Object>> run(Connection conn,AnalysisQueryPlanner.Plan plan)throws Exception{
    List<Map<String,Object>> rows=new ArrayList<>();
    try(PreparedStatement ps=conn.prepareStatement(plan.sql())){
      ps.setQueryTimeout(15);ps.setMaxRows(plan.limit()+1);
      for(int i=0;i<plan.params().size();i++)ps.setObject(i+1,plan.params().get(i));
      try(ResultSet rs=ps.executeQuery()){
        ResultSetMetaData meta=rs.getMetaData();
        while(rs.next()){
          Map<String,Object> row=new LinkedHashMap<>();
          for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getObject(i));
          rows.add(row);
        }
      }
    }
    assertTrue(rows.size()<=plan.limit()+1);
    if(rows.size()>plan.limit())rows.remove(rows.size()-1);
    return rows;
  }
  /** Hand-written SQL is executed independently, with no planner-generated SQL. */
  private static List<Map<String,Object>> manual(Connection conn,String sql)throws Exception {
    var out=new ArrayList<Map<String,Object>>();
    try(PreparedStatement ps=conn.prepareStatement(sql)){
      ps.setQueryTimeout(15);
      try(ResultSet rs=ps.executeQuery()){
        var meta=rs.getMetaData();
        while(rs.next()){
          var row=new LinkedHashMap<String,Object>();
          for(int i=1;i<=meta.getColumnCount();i++)
            row.put(meta.getColumnLabel(i),rs.getObject(i));
          out.add(row);
        }
      }
    }
    return out;
  }
  private static Map<String,Object> find(List<Map<String,Object>> rows,String key,String expected){
    return rows.stream().filter(r->expected.equals(String.valueOf(r.get(key)))).findFirst().orElseThrow();
  }
  private static void equalsDecimal(String expected,Object actual){
    assertNotNull(actual);
    assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))));
  }
  private static String requiredEnv(String key){
    String value=System.getenv(key);
    if(value==null||value.isBlank())throw new IllegalStateException("未配置对账环境变量: "+key);
    return value;
  }
}
