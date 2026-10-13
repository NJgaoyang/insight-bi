package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Offline SQL reconciliation of the exact synthetic source used by the
 * opt-in real StarRocks test. This does NOT substitute for a real JDBC E2E.
 */
class StarRocksInlineFixtureTest {
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null),
    new FieldCatalog.Field("profit","利润","profit","DECIMAL","BOTH",true,false,"ROW",null)
  );

  private static List<Map<String,Object>> execute(Connection conn,AnalysisQueryPlanner.Plan plan)throws Exception {
    var result=new ArrayList<Map<String,Object>>();
    try(var ps=conn.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)ps.setObject(i+1,plan.params().get(i));
      try(var rs=ps.executeQuery()){
        var meta=rs.getMetaData();
        while(rs.next()){
          var row=new LinkedHashMap<String,Object>();
          for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getObject(i));
          result.add(row);
        }
      }
    }
    return result;
  }
  private static BigDecimal decimal(Object x){return new BigDecimal(String.valueOf(x));}
  private static Map<String,Object> city(List<Map<String,Object>> rows,String value){
    return rows.stream().filter(r->value.equals(r.get("d1"))).findFirst().orElseThrow();
  }

  @Test void syntheticRowsAndManualCountBaselineMatchPlannedAggregates()throws Exception {
    String url="jdbc:h2:mem:starrocks_inline_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    try(Connection conn=DriverManager.getConnection(url,"sa","")){
      var plan=new AnalysisQueryPlanner(FIELDS).compile(StarRocksInlineFixture.EDGE_SOURCE,
        Map.of("dimensions",List.of(Map.of("fieldId","city")),
          "measures",List.of(
            Map.of("id","city_count","fieldId","city","aggregate","COUNT"),
            Map.of("id","revenue_count","fieldId","revenue","aggregate","COUNT"),
            Map.of("id","distinct_revenue","fieldId","revenue","aggregate","COUNT","distinct",true),
            Map.of("id","revenue_sum","fieldId","revenue","aggregate","SUM"),
            Map.of("id","profit_sum","fieldId","profit","aggregate","SUM")),
          "limit",20));
      var rows=execute(conn,plan);
      assertEquals(3,rows.size());
      var beijing=city(rows,"北京");
      assertEquals(0,decimal(beijing.get("revenue_sum")).compareTo(new BigDecimal("300")));
      var shanghai=city(rows,"上海");
      assertEquals(3,decimal(shanghai.get("city_count")).intValueExact());
      assertEquals(2,decimal(shanghai.get("revenue_count")).intValueExact());
      assertEquals(1,decimal(shanghai.get("distinct_revenue")).intValueExact());
      assertEquals(0,decimal(shanghai.get("revenue_sum")).compareTo(new BigDecimal("100")));
      assertEquals(0,decimal(city(rows,"南京").get("revenue_sum")).compareTo(BigDecimal.ZERO));

      var manual="SELECT COUNT(city) AS total_city, COUNT(revenue) AS revenue_count, "+
        "COUNT(DISTINCT revenue) AS unique_revenue, SUM(revenue) AS revenue_sum, "+
        "COUNT(order_date) AS dated_rows FROM ("+StarRocksInlineFixture.EDGE_SOURCE+") biq";
      try(var st=conn.createStatement();var rs=st.executeQuery(manual)){
        assertTrue(rs.next());
        assertEquals(6,rs.getInt("total_city"));
        assertEquals(5,rs.getInt("revenue_count"));
        assertEquals(4,rs.getInt("unique_revenue"));
        assertEquals(0,rs.getBigDecimal("revenue_sum").compareTo(new BigDecimal("400")));
        assertEquals(5,rs.getInt("dated_rows"),"NULL date should not be counted");
      }
    }
  }

  @Test void sameSourceWhereAndHavingHaveDifferentSemantics()throws Exception {
    String url="jdbc:h2:mem:starrocks_where_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    try(Connection conn=DriverManager.getConnection(url,"sa","")){
      var planner=new AnalysisQueryPlanner(FIELDS);
      var request=new HashMap<String,Object>();
      request.put("dimensions",List.of(Map.of("fieldId","city")));
      request.put("measures",List.of(Map.of("id","sum","fieldId","revenue","aggregate","SUM")));
      request.put("limit",20);
      request.put("filters",Map.of("pre",List.of(Map.of(
        "fieldId","revenue","op","gte","value","150")),"post",List.of()));
      var pre=execute(conn,planner.compile(StarRocksInlineFixture.SOURCE,request));
      assertEquals(1,pre.size());
      assertEquals(0,decimal(pre.get(0).get("sum")).compareTo(new BigDecimal("200")));
      request.put("filters",Map.of("pre",List.of(),
        "post",List.of(Map.of("ref","sum","op","gte","value","150"))));
      var post=execute(conn,planner.compile(StarRocksInlineFixture.SOURCE,request));
      assertEquals(1,post.size());
      assertEquals(0,decimal(post.get(0).get("sum")).compareTo(new BigDecimal("300")));
    }
  }
}
