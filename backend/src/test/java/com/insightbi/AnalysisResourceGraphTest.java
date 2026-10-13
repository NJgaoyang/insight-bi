package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisResourceGraph;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** P1-01: saved theme resources, stable binding identity and snapshot-safe deletion. */
class AnalysisResourceGraphTest {
  private JdbcTemplate db;
  private DatabaseStore store;
  private BiApi api;
  private String first,second;

  @BeforeEach void setup(){
    String url="jdbc:h2:mem:theme_refs_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    api=new BiApi(store,db,"test-secret-at-least-12-chars");
    first=UUID.randomUUID().toString();
    second=UUID.randomUUID().toString();
    for(String dataset:List.of(first,second)){
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        dataset,"测试"+dataset,"conn","SELECT city,amount FROM sample","[]");
    }
  }

  private Map<String,Object> widget(String id,String dataset){
    return Map.of("id",id,"type","bar","datasetId",dataset,
      "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId","stable-field")),
        "measures",List.of(Map.of("fieldId","stable-measure","id","m1","aggregate","SUM"))));
  }

  private Map<String,Object> content(Object... widgets){
    return Map.of("mode","grid","theme","light","widgets",Arrays.asList(widgets));
  }

  @Test void multipleDatasetsAreIndexedAndDraftAndPublishedGraphsAreReadable(){
    String id=String.valueOf(api.create(Map.of("name","经营主题","kind","analysis")).get("id"));
    var snapshot=content(widget("w1",first),widget("w2",second),widget("w3",first));
    var updated=api.update(id,Map.of("name","经营主题","mode","grid","revision",1,
      "content",snapshot));
    assertEquals(2,updated.get("revision"));
    var loaded=api.get(id,false);
    var body=(Map<?,?>)loaded.get("content");
    var indexed=(Map<?,?>)body.get("resourceIndex");
    assertEquals(1,indexed.get("schemaVersion"));
    assertEquals(List.of(first,second),indexed.get("datasetIds"));
    assertEquals(List.of("w1","w2","w3"),indexed.get("widgetIds"));
    assertEquals(3,api.resources(id,false).get("widgetCount"));
    assertEquals(List.of(),api.resources(id,false).get("missingDatasetIds"));

    assertThrows(java.util.NoSuchElementException.class,()->api.resources(id,true));
    api.publish(id);
    assertEquals(List.of(first,second),api.resources(id,true).get("datasetIds"));
    assertEquals(1,api.versions(id).size());

    // A dataset referenced by any of the multiple components cannot be removed.
    assertThrows(IllegalStateException.class,()->api.deleteDataset(first));
    assertThrows(IllegalStateException.class,()->api.deleteDataset(second));
  }

  @Test void historicalReferenceSurvivesRemovingItFromDraftAndUnpublishing(){
    String id=String.valueOf(api.create(Map.of("name","历史主题","kind","analysis")).get("id"));
    api.update(id,Map.of("name","历史主题","mode","grid","revision",1,
      "content",content(widget("w1",first))));
    api.publish(id);
    api.update(id,Map.of("name","历史主题","mode","grid","revision",2,
      "content",content(widget("new1",second))));
    api.unpublish(id);
    assertEquals(List.of(second),api.resources(id,false).get("datasetIds"));
    assertThrows(IllegalStateException.class,()->api.deleteDataset(first),
      "a historical version must protect a dataset after being removed from draft");

    api.remove(id); // deleting the theme also deletes its version records
    assertEquals(true,api.deleteDataset(first).get("ok"));
    assertEquals(true,api.deleteDataset(second).get("ok"));
  }

  @Test void arbitraryTextIsNotAReferenceAndLegacyDashboardIsUnchanged(){
    String id=String.valueOf(api.create(Map.of("name","旧版仪表板","mode","grid")).get("id"));
    var legacy=Map.of("mode","grid","widgets",List.of(
      Map.of("id","legacy1","type","table","datasetId",second,
        "title","备注中提及 "+first)));
    api.update(id,Map.of("name","旧版仪表板","mode","grid","revision",1,
      "content",legacy));
    var loaded=api.get(id,false);
    assertEquals(legacy,loaded.get("content"),
      "P1 graph metadata must not rewrite older dashboard JSON");
    assertEquals(List.of(second),api.resources(id,false).get("datasetIds"));
    assertEquals(true,api.deleteDataset(first).get("ok"));
    assertThrows(IllegalStateException.class,()->api.deleteDataset(second));
  }

  @Test void invalidOrMissingReferencesRejectAnalysisSaveWithoutRevisionChange(){
    String id=String.valueOf(api.create(Map.of("name","验证主题","kind","analysis")).get("id"));
    var duplicates=content(widget("duplicate",first),widget("duplicate",second));
    assertThrows(IllegalArgumentException.class,()->api.update(id,Map.of(
      "name","验证主题","mode","grid","revision",1,"content",duplicates)));
    var missing=content(widget("w1","missing-dataset"));
    assertThrows(IllegalArgumentException.class,()->api.update(id,Map.of(
      "name","验证主题","mode","grid","revision",1,"content",missing)));
    assertEquals(1,api.get(id,false).get("revision"));
    assertEquals(List.of(),((Map<?,?>)api.get(id,false).get("content")).get("widgets"));

    // Explicitly tampered resourceIndex is replaced using actual widget IDs.
    var supplied=new LinkedHashMap<String,Object>(content(widget("real",first)));
    supplied.put("resourceIndex",Map.of("datasetIds",List.of("bad"),"widgetIds",List.of("fake")));
    api.update(id,Map.of("name","验证主题","mode","grid","revision",1,"content",supplied));
    var after=(Map<?,?>)api.get(id,false).get("content");
    var realIndex=(Map<?,?>)after.get("resourceIndex");
    assertEquals(List.of(first),realIndex.get("datasetIds"));
    assertEquals(List.of("real"),realIndex.get("widgetIds"));
  }

  @Test void dashboardViewsReuseWidgetsAndRejectDanglingViewReferences(){
    String subject=String.valueOf(api.create(Map.of("name","多仪表板主题","kind","analysis")).get("id"));
    var existing=content(widget("revenue-widget",first),widget("profit-widget",second));
    var payload=new LinkedHashMap<String,Object>(existing);
    payload.put("dashboards",List.of(
      Map.of("id","overview","name","整体经营","widgetIds",List.of("revenue-widget","profit-widget")),
      Map.of("id","finance","name","财务分析","widgetIds",List.of("profit-widget"))));
    api.update(subject,Map.of("name","多仪表板主题","mode","grid","revision",1,"content",payload));
    var resource=api.resources(subject,false);
    assertEquals(2,resource.get("dashboardCount"));
    assertEquals(List.of("overview","finance"),resource.get("dashboardIds"));
    assertEquals(List.of(first,second),resource.get("datasetIds"));
    var persisted=(Map<?,?>)api.get(subject,false).get("content");
    assertEquals(payload.get("dashboards"),persisted.get("dashboards"));
    var index=(Map<?,?>)persisted.get("resourceIndex");
    assertEquals(List.of("overview","finance"),index.get("dashboardIds"));
    api.publish(subject);
    assertEquals(2,api.resources(subject,true).get("dashboardCount"));

    var invalid=new LinkedHashMap<>(payload);
    invalid.put("dashboards",List.of(Map.of(
      "id","wrong","name","损坏引用","widgetIds",List.of("missing-widget"))));
    assertThrows(IllegalArgumentException.class,()->api.update(subject,Map.of(
      "name","多仪表板主题","mode","grid","revision",2,"content",invalid)));
    invalid.put("dashboards",List.of(
      Map.of("id","same","name","视图1","widgetIds",List.of()),
      Map.of("id","same","name","视图2","widgetIds",List.of())));
    assertThrows(IllegalArgumentException.class,()->api.update(subject,Map.of(
      "name","多仪表板主题","mode","grid","revision",2,"content",invalid)));
    assertEquals(2,api.get(subject,false).get("revision"));
  }

  @Test void evenCorruptedSavedHistoryFailsClosedOnDatasetDeletion(){
    String id=String.valueOf(api.create(Map.of("name","旧坏版本","kind","analysis")).get("id"));
    api.update(id,Map.of("name","旧坏版本","mode","grid","revision",1,
      "content",content(widget("w1",first))));
    api.publish(id);
    db.update("UPDATE insight_dashboard_version SET content_json=? WHERE dashboard_id=?",
      "{not-json",id);
    assertThrows(IllegalStateException.class,()->api.deleteDataset(second),
      "unreadable historical content must fail closed, even if another dataset is targeted");
  }
}
