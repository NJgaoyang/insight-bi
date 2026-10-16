package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The P1 pipeline CAST stage uses fixed SQL types and stable field identifiers. */
class AnalysisPipelineCastTest {
 private static final List<FieldCatalog.Field> FIELDS=List.of(
  new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null),
  new FieldCatalog.Field("amount","收入","amount","DECIMAL","BOTH",true,false,"ROW",null),
  new FieldCatalog.Field("day","日期","day_value","DATE","DIMENSION",false,false,"ROW",null));
 private static final String SQL="SELECT city,amount,day_value FROM sales";
 private static Map<String,Object> cast(String id,String from,String to){
  return Map.of("id","step_"+id,"type","cast","fieldId",id,
    "sourceFieldId",from,"name","转换结果","outputType",to);
 }
 private static AnalysisPipelineCompiler.Plan plan(List<Map<String,Object>> steps){
  return new AnalysisPipelineCompiler(FIELDS).compile(SQL,Map.of("steps",steps,"limit",20));
 }
 private static Connection db()throws Exception{
  var connection=DriverManager.getConnection(
    "jdbc:h2:mem:cast_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  try(var stmt=connection.createStatement()){
   stmt.execute("CREATE TABLE sales(city VARCHAR(24),amount DECIMAL(13,2),day_value DATE)");
   stmt.execute("INSERT INTO sales VALUES ('北京',100.10,DATE '2026-10-01'),"+
    "('北京',200.20,DATE '2026-10-02'),('上海',30.00,DATE '2026-10-03')");
  }
  return connection;
 }
 private static List<Map<String,Object>> rows(Connection connection,AnalysisPipelineCompiler.Plan plan)throws Exception{
  var out=new ArrayList<Map<String,Object>>();
  try(var stmt=connection.prepareStatement(plan.sql())){
   int idx=1;for(var p:plan.params())stmt.setObject(idx++,p);
   try(var rs=stmt.executeQuery()){
    var meta=rs.getMetaData();
    while(rs.next()){
     var row=new HashMap<String,Object>();
     for(int i=1;i<=meta.getColumnCount();i++)
      row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
     out.add(row);
    }
   }
  }
  return out;
 }
 @Test void numericConversionSurvivesSubsequentFilterAndGroup()throws Exception{
  var steps=List.<Map<String,Object>>of(
   cast("converted","amount","DECIMAL"),
   Map.of("id","keep","type","filter","fieldId","converted","op","gte","value","100"),
   Map.of("id","aggregate","type","group","dimensions",List.of("city"),"measures",
    List.of(Map.of("id","total","fieldId","converted","aggregate","SUM","name","合计"))));
  var sql=plan(steps);
  assertTrue(sql.sql().contains("CAST("));
  assertTrue(sql.sql().contains("DECIMAL(38,12)"));
  assertTrue(sql.sql().contains("step_converted"));
  try(var c=db()){
   var data=rows(c,sql);
   assertEquals(1,data.size());
   assertEquals("北京",data.get(0).get("d_city"));
   assertEquals(0,new BigDecimal("300.30").compareTo(
      new BigDecimal(data.get(0).get("m_total").toString())));
  }
 }
 @Test void dateAndNumericCanBeConvertedToTextWithoutTouchingTheOriginal()throws Exception{
  var sql=plan(List.of(cast("daytext","day","STRING"),cast("moneytext","amount","STRING")));
  assertEquals(5,sql.columns().size());
  assertEquals("STRING",sql.columns().get(3).get("type"));
  assertEquals("STRING",sql.columns().get(4).get("type"));
  try(var c=db()){
   var row=rows(c,sql).get(0);
   assertNotNull(row.get("amount"));
   assertTrue(String.valueOf(row.get("step_daytext")).trim().startsWith("2026-10-01"));
   assertTrue(String.valueOf(row.get("step_moneytext")).trim().startsWith("100.1"));
  }
 }
 @Test void unsafeCastsAreRejectedBeforeAnySqlExecution(){
  assertThrows(IllegalArgumentException.class,()->plan(List.of(cast("bad","city","DECIMAL"))));
  assertThrows(IllegalArgumentException.class,()->plan(List.of(cast("bad","city","STRING"))));
  assertThrows(IllegalArgumentException.class,()->plan(List.of(cast("bad","amount","DATE"))));
  assertThrows(IllegalArgumentException.class,()->plan(List.of(cast("bad","missing","DECIMAL"))));
  assertThrows(IllegalArgumentException.class,()->plan(List.of(cast("amount","amount","DECIMAL"))));
  var injection=new HashMap<String,Object>(cast("bad","amount","DECIMAL"));
  injection.put("fieldId","bad;DROP TABLE sales");
  assertThrows(IllegalArgumentException.class,()->plan(List.of(injection)));
 }
 @Test void castCannotDuplicateOutputStableFieldId(){
  assertThrows(IllegalArgumentException.class,()->plan(List.of(
   cast("copy","amount","STRING"),cast("copy","amount","DECIMAL"))));
 }
}
