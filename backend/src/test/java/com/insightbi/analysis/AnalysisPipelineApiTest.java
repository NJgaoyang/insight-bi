package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.sql.Connection;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only P1-02 pipeline metadata lifecycle and read-only preview entrypoint. */
class AnalysisPipelineApiTest {
  private static final List<FieldCatalog.Field> FIELDS=List.of(
    new FieldCatalog.Field("revenue","收入","revenue","DECIMAL","BOTH",true,false,"ROW",null),
    new FieldCatalog.Field("city","城市","city","STRING","DIMENSION",false,false,"ROW",null));

  @Test void isolatedPipelineRevisionAndStaleSqlDetection()throws Exception {
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:pipeline_save_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();store.schema(); // migration is idempotent
    String dataset="pipeline_ds";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      dataset,"测试加工","conn","SELECT revenue,city FROM sales","[]");
    var catalog=mock(FieldCatalog.class);
    when(catalog.fields(dataset)).thenReturn(FIELDS);
    var api=new AnalysisPipelineApi(store,catalog,db);
    assertEquals(0,api.get(dataset).get("revision"));
    var steps=List.of(Map.of("id","m","type","formula","fieldId","margin",
      "name","加成","expression","[revenue]*2"));
    assertEquals(1,api.save(dataset,Map.of("steps",steps,"revision",0)).get("revision"));
    assertEquals(steps,api.get(dataset).get("steps"));
    assertEquals(false,api.get(dataset).get("stale"));
    assertThrows(IllegalStateException.class,()->api.save(dataset,
      Map.of("steps",List.of(),"revision",0)));
    assertThrows(IllegalArgumentException.class,()->api.save(dataset,
      Map.of("steps",List.of(Map.of("id","x","type","sql","query","DROP TABLE sales")),
        "revision",1)));
    assertEquals(steps,api.get(dataset).get("steps"));
    assertEquals(2,api.save(dataset,Map.of("steps",List.of(),"revision",1)).get("revision"));
    assertThrows(IllegalStateException.class,()->api.save(dataset,
      Map.of("steps",steps,"revision",1)));
    db.update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT revenue,city FROM new_sales",dataset);
    assertEquals(true,api.get(dataset).get("stale"));
    var stale=assertThrows(IllegalStateException.class,()->api.preview(dataset,
      Map.of("steps",steps,"limit",20)));
    assertTrue(stale.getMessage().contains("重新校验"));
    // Staleness does not execute a changed source and is revalidated on save.
    api.save(dataset,Map.of("steps",steps,"revision",2));
    assertEquals(false,api.get(dataset).get("stale"));
    assertEquals(3,api.get(dataset).get("revision"));
  }

  @Test void previewRequiresJDBCReadOnlyAndDoesNotRunUnsafeSql()throws Exception{
    var store=mock(DatabaseStore.class);
    var catalog=mock(FieldCatalog.class);
    var db=mock(JdbcTemplate.class);
    String dataset="demo";
    when(store.dataset(dataset)).thenReturn(Map.of("sql_body","SELECT revenue FROM sales",
      "connection_id","conn"));
    when(catalog.fields(dataset)).thenReturn(FIELDS);
    when(store.connection("conn")).thenReturn(Map.of("id","conn"));
    Connection connection=mock(Connection.class);
    when(store.open(anyMap())).thenReturn(connection);
    when(connection.isReadOnly()).thenReturn(false);
    var api=new AnalysisPipelineApi(store,catalog,db);
    assertThrows(IllegalStateException.class,()->api.preview(dataset,Map.of(
      "steps",List.of(),"limit",10)));
    verify(store,never()).readAnalysisRows(any(),anyString(),anyList(),anyInt());
    verify(connection).close();
    verify(store,never()).connection("other");
    var compiled=api.compile(dataset,Map.of("steps",List.of(),"limit",10));
    assertTrue(String.valueOf(compiled.get("sqlPreview")).contains(" LIMIT ?"));
    assertEquals(1,compiled.get("bindCount"));
    verify(store,times(1)).open(anyMap());
  }
}
