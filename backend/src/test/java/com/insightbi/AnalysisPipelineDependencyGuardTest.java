package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.BiApi;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only saved JOIN/UNION dependency-aware dataset deletion. */
class AnalysisPipelineDependencyGuardTest {
  @Test void sharedSourcesCannotBeDeletedUntilDependentPipelineIsRemoved(){
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:dependency_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    for(String id:List.of("main","joined","unioned","free")){
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        id,id,"conn","SELECT city FROM sales","[]");
    }
    db.update("INSERT INTO insight_dataset_pipeline(dataset_id,config_json,source_sql_hash,revision) VALUES(?,?,?,1)",
      "main",store.json(Map.of("steps",List.of(
        Map.of("type","join","id","join1","sourceDatasetId","joined"),
        Map.of("type","union","id","union1","sourceDatasetId","unioned")))),"hash");
    var api=new BiApi(store,db,"test-secret-at-least-12-chars");
    assertThrows(IllegalStateException.class,()->api.deleteDataset("joined"));
    assertThrows(IllegalStateException.class,()->api.deleteDataset("unioned"));
    assertEquals(true,api.deleteDataset("free").get("ok"));
    // Removing the dependent dataset clears its own pipeline in the same transaction.
    assertEquals(true,api.deleteDataset("main").get("ok"));
    assertEquals(0,db.queryForObject(
      "SELECT COUNT(*) FROM insight_dataset_pipeline WHERE dataset_id=?",Integer.class,"main"));
    assertEquals(true,api.deleteDataset("joined").get("ok"));
    assertEquals(true,api.deleteDataset("unioned").get("ok"));
  }

  @Test void unparseableSavedPipelineFailsClosedInsteadOfSilentlyDeletingSources(){
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:dependency_bad_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    for(String id:List.of("main","secondary"))
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        id,id,"conn","SELECT city FROM sales","[]");
    db.update("INSERT INTO insight_dataset_pipeline(dataset_id,config_json,source_sql_hash,revision) VALUES(?,?,?,1)",
      "main","{invalid json","hash");
    var api=new BiApi(store,db,"test-secret-at-least-12-chars");
    assertThrows(IllegalStateException.class,()->api.deleteDataset("secondary"));
    assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM insight_dataset",Integer.class));
  }

  @Test void descriptionsContainingDatasetIdDoNotCountAsReferences(){
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:dependency_note_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    for(String id:List.of("main","secondary"))
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        id,id,"conn","SELECT city FROM sales","[]");
    db.update("INSERT INTO insight_dataset_pipeline(dataset_id,config_json,source_sql_hash,revision) VALUES(?,?,?,1)",
      "main",store.json(Map.of("steps",List.of(Map.of(
        "id","note","type","filter","name","备注 secondary",
        "fieldId","other","op","eq","value","secondary")))),"hash");
    var api=new BiApi(store,db,"test-secret-at-least-12-chars");
    assertEquals(true,api.deleteDataset("secondary").get("ok"));
  }
}
