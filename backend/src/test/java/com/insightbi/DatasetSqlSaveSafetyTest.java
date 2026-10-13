package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyMap;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.DatasetSqlChangePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** SQL save gate only; all data uses disposable H2 databases. */
class DatasetSqlSaveSafetyTest {
  @Test void newOrChangedDatasetMustPassAstBeforeOpeningSourceConnection()throws Exception{
    String metadata="jdbc:h2:mem:sql_save_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    var db=new JdbcTemplate(new DriverManagerDataSource(metadata,"sa",""));
    var store=spy(new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32])));
    store.schema();
    var api=new BiApi(store,db,"test-secret-at-least-12-chars");
    var request=new HashMap<String,Object>(Map.of(
      "id","newds","name","Restricted","connectionId","conn",
      "sql","SELECT GET_LOCK('x',1) AS k FROM sales"));
    assertThrows(IllegalArgumentException.class,()->api.saveDataset(request));
    verify(store,never()).open(anyMap());
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM insight_dataset",Integer.class));

    String benign="SELECT city,amount FROM sales";
    assertEquals(benign,DatasetSqlChangePolicy.forSave(benign,null));
    assertThrows(IllegalArgumentException.class,()->DatasetSqlChangePolicy.forSave(
      "SELECT DATABASE() AS secret",null));
    assertThrows(IllegalArgumentException.class,()->DatasetSqlChangePolicy.forSave(
      "SELECT GET_LOCK('x',1) AS k FROM sales",benign));
  }

  @Test void unchangedLegacySqlCanUpdateDisplayMetadataWithoutRequiringNewFunctions() throws Exception{
    String meta="jdbc:h2:mem:sql_legacy_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    String source="jdbc:h2:mem:sql_source_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    var db=new JdbcTemplate(new DriverManagerDataSource(meta,"sa",""));
    var src=new JdbcTemplate(new DriverManagerDataSource(source,"sa",""));
    src.execute("CREATE TABLE sales(city VARCHAR(30),amount DECIMAL(12,2))");
    var store=spy(new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32])));
    store.schema();
    String legacy="SELECT city, amount FROM sales";
    String id="existing";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      id,"Existing","conn",legacy,store.json(List.of(
        Map.of("name","city","type","VARCHAR","numeric",false),
        Map.of("name","amount","type","DECIMAL","numeric",true))));
    doReturn(Map.of("id","conn")).when(store).connection("conn");
    doAnswer(inv->DriverManager.getConnection(source,"sa","")).when(store).open(anyMap());
    var api=new BiApi(store,db,"test-secret-at-least-12-chars");
    var response=api.saveDataset(Map.of("id",id,"name","Renamed Metadata",
      "connectionId","conn","sql",legacy));
    assertEquals(id,response.get("id"));
    assertEquals("Renamed Metadata",db.queryForObject(
      "SELECT name FROM insight_dataset WHERE id=?",String.class,id));
    String historical="SELECT side_effect(amount) FROM sales";
    assertEquals(historical,DatasetSqlChangePolicy.forSave(historical,historical));
    assertThrows(IllegalArgumentException.class,()->DatasetSqlChangePolicy.forSave(
      "SELECT side_effect(amount)+1 FROM sales",historical));
  }
}
