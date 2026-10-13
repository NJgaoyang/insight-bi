package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Integration of existing POST /datasets write path and explicit fieldRenames. */
class DatasetAliasUpdateIntegrationTest {
 @Test void postDatasetRejectsImplicitRenameButAcceptsExplicitMapping() throws Exception{
  String metadata="jdbc:h2:mem:dataset_meta_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  String business="jdbc:h2:mem:dataset_data_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(metadata,"sa",""));
  JdbcTemplate src=new JdbcTemplate(new DriverManagerDataSource(business,"sa",""));
  src.execute("CREATE TABLE sales(city VARCHAR(50),amount DECIMAL(12,2))");
  src.update("INSERT INTO sales VALUES('北京',100)");
  DatabaseStore storage=spy(new DatabaseStore(db,new ObjectMapper(),
    Base64.getEncoder().encodeToString(new byte[32])));
  storage.schema();
  doReturn(Map.of("id","conn")).when(storage).connection("conn");
  doAnswer(inv->DriverManager.getConnection(business,"sa","")).when(storage).open(anyMap());
  String ds="integration-ds";
  String oldSql="SELECT city,amount FROM sales";
  var fields=List.of(Map.of("name","city","type","VARCHAR","numeric",false),
    Map.of("name","amount","type","DECIMAL","numeric",true));
  db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
    ds,"Old","conn",oldSql,storage.json(fields));
  FieldCatalog catalog=new FieldCatalog(storage,db);
  var oldFields=catalog.fields(ds);
  String cityId=oldFields.stream().filter(x->"city".equals(x.sourceColumn())).findFirst().orElseThrow().id();
  var formulaApi=new AnalysisApi(storage,db,catalog);
  formulaApi.addFormula(ds,Map.of("name","北方收入","expression",
    "IF(["+cityId+"]='北京',1,0)"));
  String snapshot=storage.json(Map.of("widgets",List.of(Map.of("datasetId",ds,
    "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId",cityId)),
      "measures",List.of(Map.of("id","m1","fieldId",oldFields.get(1).id(),"aggregate","SUM")))))));
  db.update("INSERT INTO insight_dashboard(id,name,mode,content_json,status,revision) VALUES(?,?,?,?,?,?)",
    UUID.randomUUID().toString(),"Draft","grid",snapshot,"draft",1);
  BiApi api=new BiApi(storage,db,"test-secret-at-least-12-chars");
  String aliasedSql="SELECT city AS region,amount FROM sales";
  var request=new HashMap<String,Object>(Map.of("id",ds,"name","Renamed",
    "connectionId","conn","sql",aliasedSql));
  assertThrows(IllegalArgumentException.class,()->api.saveDataset(request),
    "Saving the new column name without mapping would lose formula and chart bindings");
  assertEquals(oldSql,db.queryForObject(
    "SELECT sql_body FROM insight_dataset WHERE id=?",String.class,ds));
  assertEquals("city",db.queryForObject("SELECT source_name FROM insight_dataset_field WHERE id=?",
    String.class,cityId));

  request.put("fieldRenames",Map.of("city","region"));
  api.saveDataset(request);
  assertEquals(aliasedSql,db.queryForObject(
    "SELECT sql_body FROM insight_dataset WHERE id=?",String.class,ds));
  var after=catalog.fields(ds);
  var region=after.stream().filter(x->"region".equals(x.sourceColumn())).findFirst().orElseThrow();
  assertEquals(cityId,region.id());
  assertEquals("city",region.name());
  assertTrue(new FormulaCompiler(FieldCatalog.index(after)).compile(
    "IF(["+cityId+"]='北京',1,0)").sql().contains("biq.`region`"));
  assertEquals(2,db.queryForObject(
    "SELECT COUNT(*) FROM insight_dataset_field WHERE dataset_id=?",Integer.class,ds));

  var invalid=new HashMap<>(request);
  invalid.put("sql","SELECT city AS region,amount FROM sales");
  invalid.put("fieldRenames",Map.of("region","amount"));
  assertThrows(IllegalArgumentException.class,()->api.saveDataset(invalid));
 }
}
