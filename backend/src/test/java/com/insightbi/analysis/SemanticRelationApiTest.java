package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Draft-only semantic relationship authoring. Never executes business SQL. */
class SemanticRelationApiTest {
 private static FieldCatalog.Field field(String id,String type){
  return new FieldCatalog.Field(id,id,id,type,"DIMENSION",false,false,"ROW",null);
 }
 private static List<Map<String,Object>> pairs(String left,String right){
  return List.of(Map.of("leftFieldId",left,"rightFieldId",right));
 }
 private record Fixture(DatabaseStore store,JdbcTemplate db,SemanticRelationApi api,FieldCatalog catalog){}
 private static Fixture fixture(){
  var db=new JdbcTemplate(new DriverManagerDataSource(
   "jdbc:h2:mem:semantic_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  var store=new DatabaseStore(db,new ObjectMapper(),
   Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  for(var item:List.of(new String[]{"left","conn"},new String[]{"right","conn"},
                       new String[]{"foreign","other"}))
   db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
     item[0],item[0],item[1],"SELECT 1", "[]");
  var catalog=mock(FieldCatalog.class);
  when(catalog.fields("left")).thenReturn(List.of(field("city","STRING"),field("period","DATE")));
  when(catalog.fields("right")).thenReturn(List.of(field("region","STRING"),field("wrong","DECIMAL")));
  when(catalog.fields("foreign")).thenReturn(List.of(field("city","STRING")));
  return new Fixture(store,db,new SemanticRelationApi(store,db,catalog),catalog);
 }
 @Test void createUpdateListDeleteWithCasAndNoExternalSql(){
  var f=fixture();
  var input=Map.<String,Object>of("name","城市映射","sourceDatasetId","left",
   "targetDatasetId","right","pairs",pairs("city","region"));
  var created=f.api().create(input);
  assertEquals(1,created.revision());assertEquals("DRAFT",created.status());
  assertEquals(1,f.api().list().size());
  assertEquals("city",f.api().list().get(0).pairs().get(0).leftFieldId());
  assertThrows(IllegalStateException.class,()->f.api().create(input));
  var updated=f.api().update(created.id(),Map.of("revision",1,"name","地区对应",
   "pairs",pairs("city","region")));
  assertEquals(2,updated.revision());
  assertThrows(IllegalStateException.class,()->f.api().update(created.id(),Map.of(
   "revision",1,"name","旧版本","pairs",pairs("city","region"))));
  assertThrows(IllegalStateException.class,()->f.api().delete(created.id(),1));
  assertEquals(true,f.api().delete(created.id(),2).get("ok"));
  assertTrue(f.api().list().isEmpty());
 }
 @Test void rejectsCrossConnectionSelfJoinUnknownOrMismatchedKeys(){
  var f=fixture();
  assertThrows(IllegalArgumentException.class,()->f.api().create(Map.of(
   "name","跨库","sourceDatasetId","left","targetDatasetId","foreign",
   "pairs",pairs("city","city"))));
  assertThrows(IllegalArgumentException.class,()->f.api().create(Map.of(
   "name","自关联","sourceDatasetId","left","targetDatasetId","left",
   "pairs",pairs("city","city"))));
  assertThrows(IllegalArgumentException.class,()->f.api().create(Map.of(
   "name","无效类型","sourceDatasetId","left","targetDatasetId","right",
   "pairs",pairs("city","wrong"))));
  assertThrows(IllegalArgumentException.class,()->f.api().create(Map.of(
   "name","非法字段","sourceDatasetId","left","targetDatasetId","right",
   "pairs",pairs("sql;DROP","region"))));
  assertThrows(IllegalArgumentException.class,()->f.api().create(Map.of(
   "name","重复字段","sourceDatasetId","left","targetDatasetId","right",
   "pairs",List.of(pairs("city","region").get(0),pairs("city","region").get(0)))));
  assertEquals(0,f.db().queryForObject(
   "SELECT COUNT(*) FROM insight_semantic_relation",Integer.class));
 }
 @Test void approveIsRevisionPinnedAndNeverEnablesAnExecutableRelationship(){
  var f=fixture();
  var created=f.api().create(Map.of("name","城市映射","sourceDatasetId","left",
   "targetDatasetId","right","pairs",pairs("city","region")));
  assertEquals("APPROVED",f.api().approve(created.id(),Map.of("revision",1)).status());
  assertEquals("APPROVED",f.api().list().get(0).status());
  assertEquals(1,f.db().queryForObject(
   "SELECT approved_revision FROM insight_semantic_relation WHERE id=?",Integer.class,created.id()));
  assertThrows(IllegalStateException.class,()->
   f.api().approve(created.id(),Map.of("revision",2)));
  var edited=f.api().update(created.id(),Map.of(
   "revision",1,"name","更新关系","pairs",pairs("city","region")));
  assertEquals(2,edited.revision());
  assertEquals("DRAFT",f.api().list().get(0).status());
  assertNull(f.db().queryForObject(
   "SELECT approved_revision FROM insight_semantic_relation WHERE id=?",Integer.class,created.id()));
  assertThrows(IllegalStateException.class,()->
   f.api().approve(created.id(),Map.of("revision",1)));
  assertEquals("APPROVED",f.api().approve(created.id(),Map.of("revision",2)).status());
  f.db().update("DELETE FROM insight_dataset WHERE id=?","right");
  assertEquals("INVALID",f.api().list().get(0).status());
  assertThrows(RuntimeException.class,()->
   f.api().approve(created.id(),Map.of("revision",2)));
 }
 @Test void approvedSemanticRelationOnlyCompilesBoundedJoinPreview(){
  var f=fixture();
  f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
    "SELECT '北京' AS city, '2026-10-16' AS period","left");
  f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
    "SELECT '北京' AS region, 8 AS wrong","right");
  var created=f.api().create(Map.of("name","安全关系","sourceDatasetId","left",
    "targetDatasetId","right","pairs",pairs("city","region")));
  var input=Map.<String,Object>of("revision",1,"joinType","LEFT",
    "leftFieldIds",List.of("city"),"rightFieldIds",List.of("region"));
  assertThrows(IllegalStateException.class,()->f.api().compile(created.id(),input));
  f.api().approve(created.id(),Map.of("revision",1));
  var result=f.api().compile(created.id(),input);
  assertEquals(1,result.revision());
  assertEquals(2,result.columns().size());
  assertEquals("LEFT",result.columns().get(0).side());
  assertEquals("RIGHT",result.columns().get(1).side());
  assertTrue(result.sqlPreview().contains("bi_left."+FormulaCompiler.quote("city")+
    " = bi_right."+FormulaCompiler.quote("region")));
  assertTrue(result.sqlPreview().endsWith(" LIMIT 200"));
  assertEquals(1,f.db().queryForList(result.sqlPreview()).size());
  assertThrows(IllegalArgumentException.class,()->f.api().compile(created.id(),
    Map.of("revision",1,"joinType","CROSS","leftFieldIds",List.of("city"),
      "rightFieldIds",List.of("region"))));
  assertThrows(IllegalArgumentException.class,()->f.api().compile(created.id(),
    Map.of("revision",1,"joinType","LEFT","leftFieldIds",List.of("city","city"),
      "rightFieldIds",List.of("region"))));
  assertThrows(IllegalArgumentException.class,()->f.api().compile(created.id(),
    Map.of("revision",1,"joinType","LEFT","leftFieldIds",List.of("sql;DROP"),
      "rightFieldIds",List.of("region"))));
  f.api().update(created.id(),Map.of("revision",1,"name","新版关系",
    "pairs",pairs("city","region")));
  assertThrows(IllegalStateException.class,()->f.api().compile(created.id(),input));
  assertThrows(IllegalStateException.class,()->f.api().compile(created.id(),
    Map.of("revision",2,"joinType","LEFT","leftFieldIds",List.of("city"),
      "rightFieldIds",List.of("region"))));
 }
 @Test void compileFailsClosedWhenSourceOrConnectionChanges(){
  var f=fixture();
  var created=f.api().create(Map.of("name","源变化","sourceDatasetId","left",
    "targetDatasetId","right","pairs",pairs("city","region")));
  f.api().approve(created.id(),Map.of("revision",1));
  var req=Map.<String,Object>of("revision",1,"joinType","INNER",
    "leftFieldIds",List.of("city"),"rightFieldIds",List.of("region"));
  f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
    "DELETE FROM insight_dataset","right");
  // Approved fingerprint mismatch is rejected before parsing the changed SQL.
  assertThrows(IllegalStateException.class,()->f.api().compile(created.id(),req));
  f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?","SELECT 1","right");
  f.db().update("UPDATE insight_dataset SET connection_id=? WHERE id=?","other","right");
  assertThrows(IllegalArgumentException.class,()->f.api().compile(created.id(),req));
 }


 @Test void approvalPinsSourceSqlAndReapprovalRestoresPreview(){
  var f=fixture();
  var draft=f.api().create(Map.of("name","绑定两侧 SQL","sourceDatasetId","left",
    "targetDatasetId","right","pairs",pairs("city","region")));
  f.api().approve(draft.id(),Map.of("revision",1));
  String before=f.db().queryForObject(
    "SELECT approved_source_hash FROM insight_semantic_relation WHERE id=?",
    String.class,draft.id());
  assertNotNull(before);assertEquals(64,before.length());
  f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
    "SELECT '上海' AS city","left");
  assertEquals("DRAFT",f.api().list().get(0).status());
  assertTrue(f.api().list().get(0).reason().contains("重新确认"));
  var req=Map.<String,Object>of("revision",1,"joinType","LEFT",
    "leftFieldIds",List.of("city"),"rightFieldIds",List.of("region"));
  assertThrows(IllegalStateException.class,()->f.api().compile(draft.id(),req));
  assertEquals("APPROVED",f.api().approve(draft.id(),Map.of("revision",1)).status());
  String after=f.db().queryForObject(
    "SELECT approved_source_hash FROM insight_semantic_relation WHERE id=?",
    String.class,draft.id());
  assertNotEquals(before,after);
  assertEquals("APPROVED",f.api().list().get(0).status());
  assertTrue(f.api().compile(draft.id(),req).sqlPreview().contains("LEFT JOIN"));
 }
 @Test void approvalTracksPhysicalFieldsAndConnectionsNotLabels(){
  var f=fixture();
  var draft=f.api().create(Map.of("name","字段绑定","sourceDatasetId","left",
    "targetDatasetId","right","pairs",pairs("city","region")));
  f.api().approve(draft.id(),Map.of("revision",1));
  when(f.catalog().fields("left")).thenReturn(List.of(
    new FieldCatalog.Field("city","城市展示","city","STRING","DIMENSION",false,false,"ROW",null),
    new FieldCatalog.Field("period","月份展示","period","DATE","DIMENSION",false,false,"ROW",null)));
  assertEquals("APPROVED",f.api().list().get(0).status());
  when(f.catalog().fields("left")).thenReturn(List.of(
    field("city","STRING"),
    new FieldCatalog.Field("period","period","period","DATE","DIMENSION",false,false,"ROW",null,true)));
  assertEquals("DRAFT",f.api().list().get(0).status());
  f.api().approve(draft.id(),Map.of("revision",1));
  assertEquals("APPROVED",f.api().list().get(0).status());
  f.db().update("UPDATE insight_dataset SET connection_id=? WHERE id IN (?,?)",
    "new-connection","left","right");
  assertEquals("DRAFT",f.api().list().get(0).status());
 }
 @Test void existingApprovalWithoutFingerprintFailsClosedAndMigrationIsIdempotent(){
  var f=fixture();
  var draft=f.api().create(Map.of("name","历史关系","sourceDatasetId","left",
    "targetDatasetId","right","pairs",pairs("city","region")));
  f.api().approve(draft.id(),Map.of("revision",1));
  f.db().update("UPDATE insight_semantic_relation SET approved_source_hash=NULL WHERE id=?",
    draft.id());
  f.store().schema();f.store().schema();
  assertEquals("DRAFT",f.api().list().get(0).status());
  assertThrows(IllegalStateException.class,()->f.api().compile(draft.id(),
    Map.of("revision",1,"joinType","LEFT","leftFieldIds",List.of("city"),
      "rightFieldIds",List.of("region"))));
  assertEquals("APPROVED",f.api().approve(draft.id(),Map.of("revision",1)).status());
  f.api().update(draft.id(),Map.of("revision",1,"name","新草稿",
    "pairs",pairs("city","region")));
  assertNull(f.db().queryForObject(
    "SELECT approved_source_hash FROM insight_semantic_relation WHERE id=?",
    String.class,draft.id()));
 }

 @Test void rejectsBlankOrSpaceOnlyNames(){
  var f=fixture();
  assertThrows(IllegalArgumentException.class,()->
   f.api().create(Map.of("name","   ","sourceDatasetId","left",
    "targetDatasetId","right","pairs",pairs("city","region"))));
 }
 @Test void staleOrRemovedPhysicalFieldsShowInvalidWithoutExecutingDataQuery(){
  var f=fixture();
  var saved=f.api().create(Map.of("name","初始","sourceDatasetId","left",
   "targetDatasetId","right","pairs",pairs("city","region")));
  f.db().update("DELETE FROM insight_dataset WHERE id=?","right");
  var listed=f.api().list();
  assertEquals(1,listed.size());
  assertEquals(saved.id(),listed.get(0).id());
  assertEquals("INVALID",listed.get(0).status());
  assertThrows(RuntimeException.class,()->f.api().update(saved.id(),Map.of(
   "revision",1,"name","不能保存","pairs",pairs("city","region"))));
  assertEquals(1,f.db().queryForObject("SELECT revision FROM insight_semantic_relation WHERE id=?",
   Integer.class,saved.id()));
 }
}
