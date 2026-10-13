package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** P0-03 focused CASE and expression-type tests; no datasource metadata writes. */
class FormulaCompilerTypedTest {
  private static FormulaCompiler compiler(){
    var f=List.of(
      new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null),
      new FieldCatalog.Field("cost","成本","cost","DECIMAL","BOTH",true,false,"ROW",null),
      new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
      new FieldCatalog.Field("day","日期","order_date","DATE","DIMENSION",false,false,"ROW",null),
      new FieldCatalog.Field("ratio","比率",null,"DECIMAL","MEASURE",true,true,"AGGREGATE",
          "SUM([revenue]) / NULLIF(SUM([cost]),0)")
    );
    return new FormulaCompiler(FieldCatalog.index(f));
  }
  @Test void rowCaseBooleanTypeAndSqlEscaping(){
    var p=compiler();
    var conditional=p.compile("CASE WHEN [city] = '北京' THEN [revenue]-[cost] ELSE 0 END");
    assertEquals("ROW",conditional.phase());
    assertEquals("DECIMAL",conditional.outputType());
    assertTrue(conditional.sql().contains("CASE WHEN"));
    assertTrue(conditional.references().containsAll(Set.of("city","revenue","cost")));

    var literal=p.compile("IF([city]='O''Reilly', 1, NULL)");
    assertEquals("ROW",literal.phase());
    assertEquals("DECIMAL",literal.outputType());
    assertTrue(literal.sql().contains("'O''Reilly'"));
    assertEquals("STRING",p.compile("COALESCE([city], '其他')").outputType());
    assertEquals("DATE",p.compile("MIN([day])").outputType());
  }

  @Test void aggregateCaseAndNullPropagation(){
    var p=compiler();
    var formula=p.compile("CASE WHEN SUM([revenue]) = 0 THEN NULL ELSE SUM([cost]) / NULLIF(SUM([revenue]),0) END");
    assertEquals("AGGREGATE",formula.phase());
    assertEquals("DECIMAL",formula.outputType());
    assertTrue(formula.sql().contains("SUM(biq.`revenue`)"));
    assertEquals("DECIMAL",p.compile("IF(TRUE, COALESCE([revenue],0), 1.5)").outputType());
  }

  @Test void rejectUnsafeOrIncompatibleExpressions(){
    var p=compiler();
    assertThrows(IllegalArgumentException.class,()->p.compile("IF(TRUE,[city],[revenue])"));
    assertThrows(IllegalArgumentException.class,()->p.compile("COALESCE([city],[revenue])"));
    assertThrows(IllegalArgumentException.class,()->p.compile("NULLIF([city],[revenue])"));
    assertThrows(IllegalArgumentException.class,()->p.compile("CASE WHEN [revenue] THEN 1 ELSE 0 END"));
    assertThrows(IllegalArgumentException.class,()->p.compile("CASE WHEN TRUE THEN 1 ELSE 'yes' END"));
    assertThrows(IllegalArgumentException.class,()->p.compile("CASE WHEN TRUE THEN 1"));
    assertThrows(IllegalArgumentException.class,()->p.compile("CASE WHEN TRUE THEN 1 WHEN FALSE THEN 2 ELSE 0 END OR 1=1"));
    assertThrows(IllegalArgumentException.class,()->p.compile("CASE WHEN SUM([revenue])>0 THEN [cost] ELSE 0 END"));
    assertThrows(IllegalArgumentException.class,()->p.compile("CASE WHEN TRUE THEN 1 ELSE 2; DELETE FROM sales END"));
    assertThrows(IllegalArgumentException.class,()->p.compile("IF(TRUE, 'unterminated, 0)"));
  }

  @Test void caseSqlExecutesOnMySqlModeH2() throws Exception{
    String url="jdbc:h2:mem:formula_case_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    try(Connection c=DriverManager.getConnection(url,"sa","")){
      try(Statement stmt=c.createStatement()){
        stmt.execute("CREATE TABLE sales(city VARCHAR(30),revenue DECIMAL(12,2),cost DECIMAL(12,2))");
        stmt.execute("INSERT INTO sales VALUES('北京',100,80),('北京',50,10),('上海',75,20)");
      }
      var p=compiler();
      var row=p.compile("CASE WHEN [city]='北京' THEN [revenue]-[cost] ELSE 0 END");
      try(Statement stmt=c.createStatement();ResultSet rs=stmt.executeQuery(
        "SELECT SUM("+row.sql()+") AS total FROM sales biq")){
        assertTrue(rs.next());
        assertEquals(0,new BigDecimal("60").compareTo(rs.getBigDecimal("total")));
      }
      var total=p.compile("CASE WHEN SUM([revenue])=0 THEN NULL ELSE SUM([revenue]) / NULLIF(SUM([cost]),0) END");
      try(Statement stmt=c.createStatement();ResultSet rs=stmt.executeQuery("SELECT "+total.sql()+" AS ratio FROM sales biq")){
        assertTrue(rs.next());
        assertEquals(0,new BigDecimal("225").divide(new BigDecimal("110"),12,java.math.RoundingMode.HALF_UP)
          .compareTo(rs.getBigDecimal("ratio").setScale(12,java.math.RoundingMode.HALF_UP)));
      }
    }
  }
}
