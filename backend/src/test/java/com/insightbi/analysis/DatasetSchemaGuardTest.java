package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only the dataset schema update gate is tested; no external source connection. */
class DatasetSchemaGuardTest {
 record Setup(DatabaseStore store,JdbcTemplate db,String dataset,String city,String amount,
              DatasetSchemaGuard guard) {}

 static List<Map<String,Object>> columns(Object... items){
  List<Map<String,Object>> fields=new ArrayList<>();
  for(int i=0;i<items.length;i+=2)
    fields.add(Map.of("name",items[i],"type",items[i+1]));
  return fields;
 }
 static Setup fixture(){
  String url="jdbc:h2:mem:schema_guard_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  var db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
  var store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  String id="guard_dataset";
  db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
    id,"Guard","conn","SELECT city,amount FROM sales",
    store.json(columns("city","VARCHAR","amount","DECIMAL")));
  var fields=new FieldCatalog(store,db).fields(id);
  String city=fields.stream().filter(f->"city".equals(f.sourceColumn())).findFirst().orElseThrow().id();
  String amount=fields.stream().filter(f->"amount".equals(f.sourceColumn())).findFirst().orElseThrow().id();
  return new Setup(store,db,id,city,amount,new DatasetSchemaGuard(store,db));
 }
 static void snapshot(Setup f,String json,String published){
  f.db.update("INSERT INTO insight_dashboard(id,name,mode,content_json,published_json,status,revision) VALUES(?,?,?,?,?,?,?)",
    UUID.randomUUID().toString(),"Snapshot","grid",json,published,"draft",1);
 }
 @Test void removedPhysicalSourceIsRejectedWhenFormulaDependsOnIt(){
  var f=fixture();
  var catalog=new FieldCatalog(f.store,f.db);
  var api=new AnalysisApi(f.store,f.db,catalog);
  api.addFormula(f.dataset,Map.of("name","净额","expression","["+f.amount+"]+1"));
  var ex=assertThrows(IllegalArgumentException.class,
    ()->f.guard.check(f.dataset,columns("city","VARCHAR","other","INTEGER")));
  assertTrue(ex.getMessage().contains("计算字段"),ex.getMessage());
  assertEquals("SELECT city,amount FROM sales",
    f.db.queryForObject("SELECT sql_body FROM insight_dataset WHERE id=?",String.class,f.dataset));
 }
 @Test void draftPublishedAndHistoryBindingsAreProtected(){
  for(String kind:List.of("draft","published","version")){
   var f=fixture();
   String bound=f.store.json(Map.of("widgets",List.of(Map.of("datasetId",f.dataset,
     "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId",f.city)),
       "measures",List.of(Map.of("id","m1","fieldId",f.amount,"aggregate","SUM")))))));
   if(kind.equals("version")){
    f.db.update("INSERT INTO insight_dashboard_version(id,dashboard_id,version_no,name,mode,content_json) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(),"report",1,"历史","grid",bound);
   }else snapshot(f,kind.equals("draft")?bound:"{}",kind.equals("published")?bound:null);
   var ex=assertThrows(IllegalArgumentException.class,
     ()->f.guard.check(f.dataset,columns("amount","DECIMAL")));
   assertTrue(ex.getMessage().contains("仍引用"),kind+": "+ex.getMessage());
  }
 }
 @Test void typeChangeInvalidatingMeasureIsRejectedAndCompatibleEditsAllowed(){
  var f=fixture();
  String data=f.store.json(Map.of("widgets",List.of(Map.of("datasetId",f.dataset,
    "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId",f.city)),
      "measures",List.of(Map.of("fieldId",f.amount,"id","m1","aggregate","SUM")))))));
  snapshot(f,data,null);
  var ex=assertThrows(IllegalArgumentException.class,
    ()->f.guard.check(f.dataset,columns("city","VARCHAR","amount","VARCHAR")));
  assertTrue(ex.getMessage().contains("数值指标"),ex.getMessage());
  assertDoesNotThrow(()->f.guard.check(f.dataset,
    columns("city","VARCHAR","amount","DECIMAL","new_city","VARCHAR")));
 }
 @Test void legacyFieldAndDateGroupingTypeChangeAreAlsoProtected(){
  var f=fixture();
  snapshot(f,f.store.json(Map.of("widgets",List.of(Map.of("datasetId",f.dataset,
    "dimension","city","measure","amount")))),null);
  assertThrows(IllegalArgumentException.class,
    ()->f.guard.check(f.dataset,columns("amount","DECIMAL")));
  var next=fixture();
  var dateFields=columns("day","DATE","amount","DECIMAL");
  next.db.update("UPDATE insight_dataset SET fields_json=? WHERE id=?",
    next.store.json(dateFields),next.dataset);
  var date=new FieldCatalog(next.store,next.db).fields(next.dataset).stream()
    .filter(x->x.sourceColumn().equals("day")).findFirst().orElseThrow().id();
  snapshot(next,next.store.json(Map.of("widgets",List.of(Map.of("datasetId",next.dataset,
    "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId",date,"granularity","month")),
      "measures",List.of(Map.of("fieldId",next.amount,"id","m1","aggregate","SUM"))))))),null);
  assertThrows(IllegalArgumentException.class,
    ()->next.guard.check(next.dataset,columns("day","VARCHAR","amount","DECIMAL")));
 }
}
