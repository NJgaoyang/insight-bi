package com.insightbi.analysis;

import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Draft and published dependency audit without touching source databases. */
class ModelRelationImpactTest {
 private static Map<String,Object> widget(String id,String ds,boolean pipeline,int revision){
  return Map.of("id",id,"datasetId",ds,
   "analysisV2",Map.of("usePipeline",pipeline,"pipelineRevision",revision));
 }
 @Test void reportsOnlyPinnedV2WidgetsAndFlagsStaleDraftAndPublishedVersions()throws Exception{
  var store=mock(DatabaseStore.class);
  var jdbc=mock(JdbcTemplate.class);
  var catalog=mock(FieldCatalog.class);
  var pipelines=mock(AnalysisPipelineApi.class);
  when(store.dataset("sales")).thenReturn(Map.of("id","sales"));
  when(jdbc.queryForList(contains("FROM insight_dataset_pipeline"),eq("sales")))
   .thenReturn(List.of(Map.of("revision",4)));
  when(jdbc.queryForList(contains("FROM insight_dashboard ORDER BY")))
   .thenReturn(List.of(Map.of("id","report1","name","经营主题",
    "content_json","draft","published_json","published")));
  when(store.map("draft")).thenReturn(Map.of("widgets",List.of(
   widget("w1","sales",true,4),widget("w2","sales",true,2),
   widget("w3","sales",false,4),widget("w4","other",true,2))));
  when(store.map("published")).thenReturn(Map.of("widgets",List.of(
   widget("w1","sales",true,3),widget("w2","other",true,4))));
  var result=new ModelRelationsApi(jdbc,store,catalog,pipelines).impact("sales");
  assertEquals("sales",result.datasetId());
  assertEquals(4,result.pipelineRevision());
  assertEquals(2,result.consumers().size());
  var draft=result.consumers().get(0);
  assertEquals("DRAFT",draft.stage());
  assertEquals("经营主题",draft.dashboardName());
  assertEquals(2,draft.widgetCount());
  assertEquals(1,draft.matchingRevision());
  assertEquals(1,draft.outdatedRevision());
  var published=result.consumers().get(1);
  assertEquals("PUBLISHED",published.stage());
  assertEquals(1,published.outdatedRevision());
  assertEquals(0,published.matchingRevision());
  assertFalse(result.toString().contains("SELECT"));
  verify(store,never()).open(anyMap());
 }
 @Test void emptyOrUnknownDatasetCannotBeInvented(){
  var store=mock(DatabaseStore.class);
  var jdbc=mock(JdbcTemplate.class);
  var api=new ModelRelationsApi(jdbc,store,mock(FieldCatalog.class),mock(AnalysisPipelineApi.class));
  when(store.dataset("missing")).thenThrow(new NoSuchElementException("missing"));
  assertThrows(NoSuchElementException.class,()->api.impact("missing"));
  verify(jdbc,never()).queryForList(anyString());
 }
 @Test void nonPipelineOrBrokenHistoricalContentsAreNotReported(){
  var store=mock(DatabaseStore.class);
  var jdbc=mock(JdbcTemplate.class);
  when(store.dataset("source")).thenReturn(Map.of("id","source"));
  when(jdbc.queryForList(contains("FROM insight_dataset_pipeline"),eq("source")))
   .thenReturn(List.of());
  when(jdbc.queryForList(contains("FROM insight_dashboard ORDER BY")))
   .thenReturn(List.of(Map.of("id","report","name","旧报告",
    "content_json","invalid","published_json","valid")));
  when(store.map("invalid")).thenThrow(new IllegalArgumentException("legacy"));
  when(store.map("valid")).thenReturn(Map.of("widgets",List.of(
    widget("w1","source",false,1),widget("w2","another",true,1))));
  var result=new ModelRelationsApi(jdbc,store,mock(FieldCatalog.class),mock(AnalysisPipelineApi.class))
   .impact("source");
  assertEquals(0,result.pipelineRevision());
  assertTrue(result.consumers().isEmpty());
 }
}
