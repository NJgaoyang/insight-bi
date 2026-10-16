package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Scoped read-only relationship catalog: existing saved pipelines only. */
class ModelRelationsApiTest {
 private static FieldCatalog.Field field(String id,String name,String type){
  return new FieldCatalog.Field(id,name,name,type,"DIMENSION",false,false,"ROW",null);
 }
 @Test void indexesOnlySavedRelationshipsAndDoesNotLeakSql() throws Exception {
  var store=mock(DatabaseStore.class);
  var jdbc=mock(JdbcTemplate.class);
  var catalog=mock(FieldCatalog.class);
  var pipelines=mock(AnalysisPipelineApi.class);
  when(store.datasets()).thenReturn(List.of(
   Map.of("id","left","name","销售","connection_id","conn","sql_body","SELECT private_secret FROM t"),
   Map.of("id","right","name","费用","connection_id","conn","sql_body","SELECT secret FROM x")));
  var row=Map.<String,Object>of("dataset_id","left","revision",2,
   "config_json","saved", "source_sql_hash","hash");
  when(jdbc.queryForList(anyString())).thenReturn(List.of(row));
  when(store.map("saved")).thenReturn(Map.of("steps",List.of(
   Map.of("id","rel1","type","join","sourceDatasetId","right","joinType","LEFT",
    "conditions",List.of(Map.of("leftFieldId","city","rightFieldId","region")),
    "fields",List.of(Map.of("fieldId","converted","sourceFieldId","expense","name","费用"))),
   Map.of("type","filter","fieldId","city"))));
  when(catalog.fields("left")).thenReturn(List.of(field("city","城市","STRING")));
  when(catalog.fields("right")).thenReturn(List.of(field("region","区域","STRING")));
  when(pipelines.get("left")).thenReturn(Map.of("stale",false));
  when(pipelines.compile(eq("left"),anyMap())).thenReturn(Map.of("sqlPreview","SELECT PRIVATE DATA"));
  var result=new ModelRelationsApi(jdbc,store,catalog,pipelines).list();
  assertEquals(1,result.size());
  var relation=result.get(0);
  assertEquals("ACTIVE",relation.status());
  assertEquals(2,relation.revision());
  assertEquals(1,relation.outputFields());
  assertEquals("城市",relation.fields().get(0).leftName());
  assertEquals("区域",relation.fields().get(0).rightName());
  assertTrue(relation.fields().get(0).compatible());
  String rendered=relation.toString();
  assertFalse(rendered.contains("SELECT"));
  assertFalse(rendered.contains("secret"));
  verify(store,never()).open(anyMap());
 }
 @Test void staleAndCrossConnectionRelationshipsFailClosed() throws Exception {
  var store=mock(DatabaseStore.class);
  var jdbc=mock(JdbcTemplate.class);
  var catalog=mock(FieldCatalog.class);
  var pipelines=mock(AnalysisPipelineApi.class);
  when(store.datasets()).thenReturn(List.of(
   Map.of("id","left","name","销售","connection_id","first"),
   Map.of("id","right","name","退款","connection_id","second")));
  when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("dataset_id","left",
   "config_json","saved","revision",1,"source_sql_hash","hash")));
  when(store.map("saved")).thenReturn(Map.of("steps",List.of(
   Map.of("type","join","sourceDatasetId","right","joinType","LEFT",
    "conditions",List.of(Map.of("leftFieldId","city","rightFieldId","city"))))));
  var result=new ModelRelationsApi(jdbc,store,catalog,pipelines).list();
  assertEquals(1,result.size());
  assertEquals("INVALID",result.get(0).status());
  assertFalse(result.get(0).reason().isBlank());
  verify(pipelines,never()).compile(anyString(),anyMap());
  verify(store,never()).open(anyMap());
 }
 @Test void currentCompilerChecksSavedRelationshipAndStaleVersion() {
  var store=mock(DatabaseStore.class);
  var jdbc=mock(JdbcTemplate.class);
  var catalog=mock(FieldCatalog.class);
  var pipelines=mock(AnalysisPipelineApi.class);
  when(store.datasets()).thenReturn(List.of(
   Map.of("id","main","name","主表","connection_id","same"),
   Map.of("id","aux","name","附表","connection_id","same")));
  when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of(
   "dataset_id","main","config_json","saved","revision",5,"source_sql_hash","hash")));
  when(store.map("saved")).thenReturn(Map.of("steps",List.of(Map.of(
   "type","union","sourceDatasetId","aux","distinct",false))));
  when(pipelines.get("main")).thenReturn(Map.of("stale",true));
  var api=new ModelRelationsApi(jdbc,store,catalog,pipelines);
  assertEquals("STALE",api.list().get(0).status());
  verify(pipelines,never()).compile(anyString(),anyMap());
  when(pipelines.get("main")).thenReturn(Map.of("stale",false));
  doThrow(new IllegalArgumentException("invalid config"))
    .when(pipelines).compile(eq("main"),anyMap());
  assertEquals("INVALID",api.list().get(0).status());
 }
}
