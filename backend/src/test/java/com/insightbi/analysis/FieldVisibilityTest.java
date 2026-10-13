package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Field hiding is a display preference; existing stable-ID references still work. */
class FieldVisibilityTest {
 static record Setup(DatabaseStore store,JdbcTemplate db,String dataset,AnalysisApi api,FieldCatalog catalog){}
 static Setup setup(){
  String url="jdbc:h2:mem:hidden_fields_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
  DatabaseStore store=new DatabaseStore(db,new ObjectMapper(),
    Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  String dataset="hidden_dataset";
  db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
    dataset,"Hidden","conn","SELECT city,amount FROM sales",
    store.json(List.of(Map.of("name","city","type","VARCHAR","numeric",false),
      Map.of("name","amount","type","DECIMAL","numeric",true))));
  FieldCatalog catalog=new FieldCatalog(store,db);
  return new Setup(store,db,dataset,new AnalysisApi(store,db,catalog),catalog);
 }
 @Test void hideAndUnhideWithoutBreakingSavedBindingsOrFormulas(){
  var setup=setup();
  String city=setup.catalog.fields(setup.dataset).stream()
    .filter(f->"city".equals(f.sourceColumn())).findFirst().orElseThrow().id();
  assertFalse(setup.catalog.fields(setup.dataset).get(0).hidden());
  var hidden=setup.api.setFieldVisibility(setup.dataset,city,Map.of("hidden",true));
  assertTrue(hidden.hidden());
  assertEquals(city,hidden.id());
  assertEquals("city",hidden.sourceColumn());

  var catalog=setup.catalog.fields(setup.dataset);
  assertTrue(catalog.stream().filter(f->f.id().equals(city)).findFirst().orElseThrow().hidden());
  var compiled=new AnalysisQueryPlanner(catalog).compile(
    "SELECT city,amount FROM sales",
    Map.of("dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("fieldId",catalog.get(1).id(),"id","sum","aggregate","SUM"))));
  assertTrue(compiled.sql().contains("biq.`city`"));
  assertEquals("STRING",new FormulaCompiler(FieldCatalog.index(catalog)).compile("["+city+"]").outputType());

  var visible=setup.api.setFieldVisibility(setup.dataset,city,Map.of("hidden",false));
  assertFalse(visible.hidden());
  assertThrows(IllegalArgumentException.class,
    ()->setup.api.setFieldVisibility(setup.dataset,city,Map.of("hidden","true")));
  assertThrows(NoSuchElementException.class,
    ()->setup.api.setFieldVisibility(setup.dataset,"unknown",Map.of("hidden",true)));
 }

 @Test void oldPhysicalFieldTableMigratesAndSecondBootstrapIsIdempotent(){
  String url="jdbc:h2:mem:legacy_field_hidden_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
  db.execute("CREATE TABLE insight_dataset_field ("+
    "id VARCHAR(36) PRIMARY KEY,dataset_id VARCHAR(36) NOT NULL,source_name VARCHAR(128) NOT NULL,"+
    "display_name VARCHAR(150) NOT NULL,logical_type VARCHAR(20) NOT NULL,"+
    "role VARCHAR(20) NOT NULL,ordinal INT NOT NULL,UNIQUE(dataset_id,source_name))");
  db.update("INSERT INTO insight_dataset_field(id,dataset_id,source_name,display_name,logical_type,role,ordinal) "+
    "VALUES(?,?,?,?,?,?,?)","old-id","d1","city","城市","STRING","DIMENSION",0);
  var store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  assertEquals(Boolean.FALSE,db.queryForObject(
    "SELECT hidden FROM insight_dataset_field WHERE id=?",Boolean.class,"old-id"));
  store.schema();
  db.update("UPDATE insight_dataset_field SET hidden=TRUE WHERE id=?","old-id");
  assertEquals(Boolean.TRUE,db.queryForObject(
    "SELECT hidden FROM insight_dataset_field WHERE id=?",Boolean.class,"old-id"));
  assertEquals("old-id",db.queryForObject(
    "SELECT id FROM insight_dataset_field WHERE source_name=?",String.class,"city"));
 }
}
