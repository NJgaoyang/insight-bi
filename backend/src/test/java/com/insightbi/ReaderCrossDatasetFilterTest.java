package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.*;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Mapped published filters are exact, authorized, typed and connection-scoped. */
class ReaderCrossDatasetFilterTest {
 static class Capturing extends AnalysisApi {
  Map<String,Object> last;
  Capturing(DatabaseStore s,JdbcTemplate j){super(s,j,new FieldCatalog(s,j));}
  @Override public Map<String,Object> query(Map<String,Object> req){
   last=new LinkedHashMap<>(req);
   return Map.of("columns",List.of(),"rows",List.of(),"truncated",false);
  }
 }
 static String id(List<FieldCatalog.Field> fields,String source){
  return fields.stream().filter(f->source.equals(f.sourceColumn())).findFirst().orElseThrow().id();
 }
 @Test void explicitSameConnectionMappingEnforcesFieldTypeAndMandatoryPolicy()throws Exception{
  var db=new JdbcTemplate(new DriverManagerDataSource(
   "jdbc:h2:mem:cross_filter_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
  var store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  var gov=new GovernanceApi(db);
  var admin=new BiApi(store,db,"admin-password-at-least-12-chars");
  var analysis=new Capturing(store,db);
  var reader=new ReaderApi(db,store,gov,analysis);
  String user=(String)gov.createUser(
   Map.of("username","crossfilter","displayName","受限查看人","role","VIEWER")).get("id");
  gov.setPassword(user,Map.of("password","reader-cross-filter-password-2026"));
  gov.setActivation(user,Map.of("enabled",true));
  db.execute("CREATE TABLE synthetic_sales(city VARCHAR(60),amount DECIMAL(12,2))");
  db.execute("CREATE TABLE synthetic_cost(region VARCHAR(60),amount DECIMAL(12,2))");
  db.execute("CREATE TABLE synthetic_outside(region VARCHAR(60),amount DECIMAL(12,2))");
  db.execute("CREATE TABLE synthetic_types(count_value DECIMAL(12,2),amount DECIMAL(12,2))");
  dataset(db,store,"source","connection1","SELECT city,amount FROM synthetic_sales",
   "city","VARCHAR");
  dataset(db,store,"target","connection1","SELECT region,amount FROM synthetic_cost",
   "region","VARCHAR");
  dataset(db,store,"outside","connection2","SELECT region,amount FROM synthetic_outside",
   "region","VARCHAR");
  dataset(db,store,"mismatch","connection1","SELECT count_value,amount FROM synthetic_types",
   "count_value","DECIMAL");
  var catalog=new FieldCatalog(store,db);
  String sourceField=id(catalog.fields("source"),"city");
  String targetField=id(catalog.fields("target"),"region");
  String outsideField=id(catalog.fields("outside"),"region");
  String mismatchField=id(catalog.fields("mismatch"),"count_value");
  String revenue=id(catalog.fields("target"),"amount");
  String salesMeasure=id(catalog.fields("source"),"amount");
  String outsideMeasure=id(catalog.fields("outside"),"amount");
  String mismatchMeasure=id(catalog.fields("mismatch"),"amount");
  var w1=widget("w1","source",sourceField,salesMeasure);
  var w2=widget("w2","target",targetField,revenue);
  var w3=widget("w3","outside",outsideField,outsideMeasure);
  var w4=widget("w4","mismatch",mismatchField,mismatchMeasure);
  var mappings=List.of(Map.of("widgetId","w2","fieldId",targetField),
   Map.of("widgetId","w3","fieldId",outsideField),
   Map.of("widgetId","w4","fieldId",mismatchField));
  String dashboard=(String)admin.create(Map.of("name","跨数据集权限报表","kind","analysis","mode","grid")).get("id");
  publish(db,store,dashboard,List.of(w1,w2,w3,w4),sourceField,mappings);
  gov.grantRead(user,Map.of("dashboardId",dashboard));
  gov.setRowPolicy(user,"target",Map.of("fieldId",targetField,"allowedValue","华北"));
  var req=new MockHttpServletRequest("POST","/api/reader/login");
  req.setRemoteAddr("192.0.2.55");
  reader.login(Map.of("username","crossfilter","password","reader-cross-filter-password-2026"),req);
  HttpSession session=req.getSession(false);
  reader.query(dashboard,"w2",Map.of("viewId","v1","filterValue","北京"),session);
  var pre=(List<?>)((Map<?,?>)analysis.last.get("filters")).get("pre");
  assertEquals(List.of(Map.of("fieldId",targetField,"op","eq","value","北京"),
   Map.of("fieldId",targetField,"op","eq","value","华北")),pre);
  reader.query(dashboard,"w2",Map.of("viewId","v1","filterValue","北京' OR 1=1"),session);
  var bound=new AnalysisQueryPlanner(catalog.fields("target")).compile(
   "SELECT region,amount FROM synthetic_cost",analysis.last);
  assertTrue(bound.params().contains("北京' OR 1=1"));
  assertFalse(bound.sql().contains("OR 1=1"));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w3",Map.of("viewId","v1","filterValue","北京"),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w4",Map.of("viewId","v1","filterValue","北京"),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w2",Map.of("viewId","fake","filterValue","北京"),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w2",Map.of("viewId","v1","filterValue","北京","fieldId",sourceField),session));
  // No mapping must fail even if the name happens to match.
  publish(db,store,dashboard,List.of(w1,w2),sourceField,List.of());
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w2",Map.of("viewId","v1","filterValue","北京"),session));
  // Mapping must explicitly exist only once.
  publish(db,store,dashboard,List.of(w1,w2),sourceField,
   List.of(Map.of("widgetId","w2","fieldId",targetField),
    Map.of("widgetId","w2","fieldId",targetField)));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w2",Map.of("viewId","v1","filterValue","北京"),session));
 }
 static Map<String,Object> widget(String id,String ds,String dim,String measure){
  var config=Map.of("dimensions",List.of(Map.of("fieldId",dim)),
   "measures",List.of(Map.of("id","m1","fieldId",measure,"aggregate","SUM")),
   "filters",Map.of("pre",List.of(),"post",List.of()),"sort",List.of(),"limit",100);
  return Map.of("id",id,"title",id,"type","bar","datasetId",ds,"analysisV2",config);
 }
 static void dataset(JdbcTemplate db,DatabaseStore store,String id,String conn,String sql,
                     String column,String type){
  db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
   id,id,conn,sql,store.json(List.of(Map.of("name",column,"type",type),
    Map.of("name","amount","type","DECIMAL"))));
 }
 static void publish(JdbcTemplate db,DatabaseStore store,String dashboard,
                     List<Map<String,Object>> widgets,String field,List<Map<String,String>> mapping){
  var filter=Map.of("datasetId","source","fieldId",field,"mappings",mapping);
  var view=Map.of("id","v1","name","一个视图",
   "widgetIds",widgets.stream().map(w->(String)w.get("id")).toList(),"filter",filter);
  db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
   store.json(Map.of("widgets",widgets,"dashboards",List.of(view))),dashboard);
 }
}
