package com.insightbi.analysis;

/**
 * Shared synthetic SQL for local H2 semantics checks and real StarRocks E2E.
 * All records are inline SELECTs; no table is created in StarRocks.
 */
final class StarRocksInlineFixture {
  private StarRocksInlineFixture(){}

  static String row(String city,String day,int revenue,int cost,int profit){
    return "SELECT '"+city+"' AS city, CAST('"+day+"' AS DATE) AS order_date, "+
      "CAST("+revenue+" AS DECIMAL(18,2)) AS revenue, "+
      "CAST("+cost+" AS DECIMAL(18,2)) AS cost, "+
      "CAST("+profit+" AS DECIMAL(18,2)) AS profit";
  }

  static final String SOURCE=String.join(" UNION ALL ",
      row("北京","2026-01-05",100,90,10),
      row("北京","2026-01-10",200,150,50),
      row("上海","2026-01-05",50,40,10),
      row("南京","2026-01-05",0,-5,5));

  static final String EDGE_SOURCE=SOURCE+" UNION ALL "+
      row("上海","2026-01-05",50,40,10)+
      " UNION ALL SELECT '上海' AS city, CAST(NULL AS DATE) AS order_date, "+
      "CAST(NULL AS DECIMAL(18,2)) AS revenue, CAST(NULL AS DECIMAL(18,2)) AS cost, "+
      "CAST(NULL AS DECIMAL(18,2)) AS profit";
}
