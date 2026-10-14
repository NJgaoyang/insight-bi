package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Only P1-02 opt-in widget binding contract: versions pinned on Save/Publish.
 * Creates isolated in-memory metadata; never connects to StarRocks.
 */
class AnalysisPipelineSnapshotBindingTest {
  @Test void saveAndPublishRequireTheExactCurrentPipelineRevision(){
    JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:p1_binding_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    DatabaseStore store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    String datasetId=UUID.randomUUID().toString();
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      datasetId,"测试处理","conn","SELECT city,revenue FROM sales",
      "[{\"name\":\"city\",\"type\":\"VARCHAR\"},{\"name\":\"revenue\",\"type\":\"DECIMAL\"}]");
    var catalog=new FieldCatalog(store,db);
    String cityId=catalog.fields(datasetId).stream()
      .filter(f->"city".equals(f.sourceColumn())).findFirst().orElseThrow().id();
    var pipeline=new AnalysisPipelineApi(store,catalog,db);
    var steps=List.of(Map.of("id","filterCity","type","filter","fieldId",cityId,
      "op","eq","value","北京"));
    assertEquals(1,pipeline.save(datasetId,Map.of("revision",0,"steps",steps)).get("revision"));

    var api=new BiApi(store,db,"test-password-with-16-chars");
    String report=String.valueOf(api.create(Map.of("kind","analysis","name","加工结果主题")).get("id"));
    var selected=Map.of("mode","grid","widgets",List.of(Map.of(
      "id","widget1","datasetId",datasetId,
      "analysisV2",Map.of("usePipeline",true,"pipelineRevision",1))),
      "theme","light");

    assertEquals(2,api.update(report,Map.of("name","加工结果主题","revision",1,
      "content",selected)).get("revision"));
    assertEquals(true,api.publish(report).get("ok"));

    assertEquals(2,pipeline.save(datasetId,Map.of("revision",1,"steps",steps)).get("revision"));
    var error=assertThrows(IllegalStateException.class,()->api.publish(report));
    assertTrue(error.getMessage().contains("最新版本"));
    assertThrows(IllegalStateException.class,()->api.update(report,Map.of(
      "name","加工结果主题","revision",2,"content",selected)));
    assertEquals(2,api.get(report,false).get("revision"));
    assertEquals(1,api.versions(report).size(),"rejected publish cannot append a version");
    var oldPublished=api.get(report,true);
    assertEquals(1,((Number)((Map<?,?>)((Map<?,?>)((List<?>)((Map<?,?>)
      oldPublished.get("content")).get("widgets")).get(0)).get("analysisV2"))
      .get("pipelineRevision")).intValue(),"published snapshot must remain immutable");

    var updated=new LinkedHashMap<String,Object>(selected);
    updated.put("widgets",List.of(Map.of("id","widget1","datasetId",datasetId,
      "analysisV2",Map.of("usePipeline",true,"pipelineRevision",2))));
    assertEquals(3,api.update(report,Map.of("name","加工结果主题","revision",2,
      "content",updated)).get("revision"));
    assertEquals(true,api.publish(report).get("ok"));
  }

  @Test void malformedModeOrMissingVersionIsNotSilentlyDowngraded(){
    var snapshots=List.of(
      Map.of("widgets",List.of(Map.of("id","w","datasetId","x",
        "analysisV2",Map.of("usePipeline","true","pipelineRevision",1)))),
      Map.of("widgets",List.of(Map.of("id","w","datasetId","x",
        "analysisV2",Map.of("usePipeline",true)))));
    for(var snapshot:snapshots)
      assertThrows(IllegalArgumentException.class,()->
        AnalysisResourceGraph.requirePinnedPipelines(snapshot,null));
  }
}
