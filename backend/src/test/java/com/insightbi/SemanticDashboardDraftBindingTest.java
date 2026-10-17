package com.insightbi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SemanticDashboardDraftBindingTest {
  private record Setup(DatabaseStore store,JdbcTemplate db,BiApi api,
      SemanticRelationApi relation,String relationId){}
  private static Setup setup(){
    var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:bind_"+
      UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    for(String[] ds:List.of(new String[]{"left","city"},
                          new String[]{"right","region"}))
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
        "VALUES(?,?,?,?,?)",ds[0],ds[0],"same","SELECT "+ds[1]+" FROM table_name",
        store.json(List.of(Map.of("name",ds[1],"type","VARCHAR"))));
    var catalog=new FieldCatalog(store,db);
    String leftField=catalog.fields("left").get(0).id();
    String rightField=catalog.fields("right").get(0).id();
    db.update("INSERT INTO insight_governance_user "+
      "(id,username,display_name,role,enabled) VALUES(?,?,?,?,?)",
      "viewer1","viewer_test","Reader Test","VIEWER",true);
    var api=new SemanticRelationApi(store,db,catalog);
    var draft=api.create(Map.of("name","City relation","sourceDatasetId","left",
      "targetDatasetId","right","pairs",List.of(Map.of(
        "leftFieldId",leftField,"rightFieldId",rightField))));
    api.approve(draft.id(),Map.of("revision",1));
    api.reviewAccess(draft.id(),Map.of("revision",1,"readerId","viewer1",
      "joinType","LEFT","leftFieldIds",List.of(leftField),"rightFieldIds",List.of(rightField)));
    api.sealSnapshot(draft.id(),
      Map.of("revision",1,"readerId","viewer1","acknowledgeNotPublished",true));
    return new Setup(store,db,new BiApi(store,db,"admin-password-of-good-length"),api,draft.id());
  }
  private static Map<String,Object> candidate(Setup f){
    return Map.of("relationId",f.relationId(),"readerId","viewer1","releaseNo",1);
  }
  private static Map<String,Object> widget(Map<String,Object> candidate,String dataset){
    return Map.of("id","w1","datasetId",dataset,"type","bar",
      "analysisV2",Map.of("semanticRelation",candidate));
  }
  private static Map<String,Object> content(Map<String,Object> widget){
    return Map.of("mode","grid","theme","light","widgets",List.of(widget));
  }
  private static String create(Setup f){
    return String.valueOf(f.api().create(Map.of("name","Draft-only","mode","grid",
      "kind","analysis")).get("id"));
  }
  private static Map<String,Object> save(Setup f,String id,Map<String,Object> widget){
    return f.api().update(id,Map.of("name","Draft-only","mode","grid",
      "revision",1,"content",content(widget)));
  }
  @Test void onlyDraftBindsExactSealedCandidateAndPublishFails(){
    var f=setup();
    String id=create(f);
    assertEquals(2,save(f,id,widget(candidate(f),"left")).get("revision"));
    assertTrue(String.valueOf(f.api().get(id,false).get("content")).contains("semanticRelation"));
    assertThrows(IllegalStateException.class,()->f.api().publish(id));
    assertNull(f.db().queryForMap("SELECT published_json FROM insight_dashboard WHERE id=?",id)
      .get("published_json"));
    assertEquals(0,f.db().queryForObject("SELECT COUNT(*) FROM insight_dashboard_version",
      Integer.class));
    assertFalse(new ReaderColumnGuard(new GovernanceApi(f.db()),f.db()).allowed(
      "viewer1",content(widget(candidate(f),"left"))));
    var compiler=new AnalysisApi(f.store(),f.db(),new FieldCatalog(f.store(),f.db()));
    assertThrows(IllegalStateException.class,()->compiler.compile(
      Map.of("datasetId","left","semanticRelation",candidate(f))));
  }
  @Test void staleWrongPrimaryOrForgedCandidateCannotModifyDraft(){
    var f=setup();String id=create(f);
    assertThrows(IllegalStateException.class,()->save(f,id,widget(candidate(f),"right")));
    assertThrows(IllegalStateException.class,()->save(f,id,widget(
      Map.of("relationId",f.relationId(),"readerId","viewer1","releaseNo",99),"left")));
    assertThrows(IllegalArgumentException.class,()->save(f,id,widget(
      Map.of("relationId",f.relationId(),"readerId","viewer1","releaseNo",1,"extra","x"),"left")));
    assertEquals(1,f.db().queryForObject(
      "SELECT revision FROM insight_dashboard WHERE id=?",Integer.class,id));
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city FROM changed_source","left");
    assertThrows(IllegalStateException.class,()->save(f,id,widget(candidate(f),"left")));
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city FROM table_name","left");
    f.db().update("UPDATE insight_governance_user SET auth_version=auth_version+1 WHERE id=?",
      "viewer1");
    assertThrows(IllegalStateException.class,()->save(f,id,widget(candidate(f),"left")));
  }
  @Test void normalPublishUnaffectedAndTamperedPublishedReaderIsDenied(){
    var f=setup();String id=create(f);
    save(f,id,Map.of("id","w1","datasetId","left","type","bar",
      "analysisV2",Map.of("dimensions",List.of(),"measures",List.of())));
    assertEquals(true,f.api().publish(id).get("ok"));
    f.db().update("UPDATE insight_dashboard SET published_json=? WHERE id=?",
      f.store().json(content(widget(candidate(f),"left"))),id);
    assertFalse(new ReaderColumnGuard(new GovernanceApi(f.db()),f.db()).allowed(
      "viewer1",f.store().map(f.db().queryForObject(
        "SELECT published_json FROM insight_dashboard WHERE id=?",String.class,id))));
  }
}
