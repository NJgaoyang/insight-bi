package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact published-impact acknowledgment is validated by the save endpoint. */
class PipelineChangeImpactTest {
 @Test void saveRequiresFreshExplicitAcknowledgmentWhenPublishedWidgetsArePinned(){
  var db=new JdbcTemplate(new DriverManagerDataSource(
   "jdbc:h2:mem:publish_impact_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  var store=new DatabaseStore(db,new ObjectMapper(),
   Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  String id="sales";
  db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
   id,"Sales","conn","SELECT amount,city FROM sales",store.json(List.of(
    Map.of("name","amount","type","DECIMAL"),Map.of("name","city","type","VARCHAR"))));
  var catalog=mock(FieldCatalog.class);
  when(catalog.fields(id)).thenReturn(List.of(
   new FieldCatalog.Field("amount","收入","amount","DECIMAL","BOTH",true,false,"ROW",null),
   new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null)));
  var api=new AnalysisPipelineApi(store,catalog,db);
  var steps=List.of(Map.of("id","p1","type","formula","fieldId","derived",
   "name","派生金额","expression","[amount]*2"));
  assertEquals(1,api.save(id,Map.of("revision",0,"steps",steps)).get("revision"));
  var published=Map.of("widgets",List.of(
   Map.of("id","w1","datasetId","sales","analysisV2",
    Map.of("usePipeline",true,"pipelineRevision",1)),
   Map.of("id","w2","datasetId","sales","analysisV2",
    Map.of("usePipeline",false,"pipelineRevision",1)),
   Map.of("id","w3","datasetId","other","analysisV2",
    Map.of("usePipeline",true,"pipelineRevision",1))));
  var draft=Map.of("widgets",List.of(
   Map.of("id","w1","datasetId","sales","analysisV2",
    Map.of("usePipeline",true,"pipelineRevision",1))));
  db.update("INSERT INTO insight_dashboard(id,name,mode,content_json,published_json,status,revision) "+
   "VALUES(?,?,?,?,?,?,?)","report","经营分析","grid",store.json(draft),store.json(published),"published",1);
  var before=api.changeImpact(id,1);
  assertEquals(1,before.reports().size());
  assertEquals("经营分析",before.reports().get(0).dashboardName());
  assertEquals(1,before.reports().get(0).widgetCount());
  assertTrue(before.requiresAcknowledgment());
  assertEquals(64,before.token().length());
  assertThrows(IllegalStateException.class,()->api.save(id,Map.of("revision",1,"steps",List.of())));
  assertThrows(IllegalStateException.class,()->api.save(id,
   Map.of("revision",1,"steps",List.of(),"acknowledgePublishedImpact",true,"impactToken","fake")));
  assertEquals(1,api.get(id).get("revision"));
  assertThrows(IllegalStateException.class,()->api.changeImpact(id,0));
  // Changing the published snapshot invalidates an earlier confirmation.
  var changed=Map.of("widgets",List.of(
   Map.of("id","w1","datasetId","sales","analysisV2",
    Map.of("usePipeline",true,"pipelineRevision",1)),
   Map.of("id","w4","datasetId","sales","analysisV2",
    Map.of("usePipeline",true,"pipelineRevision",1))));
  db.update("UPDATE insight_dashboard SET published_json=? WHERE id=?",store.json(changed),"report");
  assertThrows(IllegalStateException.class,()->api.save(id,Map.of(
   "revision",1,"steps",List.of(),"acknowledgePublishedImpact",true,"impactToken",before.token())));
  var fresh=api.changeImpact(id,1);
  assertNotEquals(before.token(),fresh.token());
  assertEquals(2,fresh.reports().get(0).widgetCount());
  assertEquals(2,api.save(id,Map.of("revision",1,"steps",List.of(),
   "acknowledgePublishedImpact",true,"impactToken",fresh.token())).get("revision"));
  assertEquals(2,api.get(id).get("revision"));
  var now=api.changeImpact(id,2);
  assertFalse(now.requiresAcknowledgment());
  assertEquals("",now.token());
  // No extra confirmation is needed for a later edit when all published
  // widgets are already pinned to the older revision.
  assertEquals(3,api.save(id,Map.of("revision",2,"steps",steps)).get("revision"));
 }
 @Test void emptyAndUnpinnedPublishedReportsKeepOriginalSaveSemantics()throws Exception{
  var db=mock(JdbcTemplate.class);
  var store=mock(DatabaseStore.class);
  when(db.queryForList(contains("SELECT id,name,published_json"))).thenReturn(List.of());
  assertFalse(PipelineChangeImpact.inspect(store,db,"demo",1).requiresAcknowledgment());
  assertEquals("",PipelineChangeImpact.inspect(store,db,"demo",0).token());
  verify(store,never()).open(anyMap());
 }
}
