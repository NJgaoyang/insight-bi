package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BiQueryInTest {
 @Test void parameterizedInFilterAndSort() throws Exception {
  String url="jdbc:h2:mem:insight_in_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
  JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
  jdbc.execute("CREATE TABLE IF NOT EXISTS test_sales (region VARCHAR(100), amount INT)");
  jdbc.update("DELETE FROM test_sales");
  jdbc.update("INSERT INTO test_sales VALUES ('North',100),('East',70),('South',50)");
  DatabaseStore storage=mock(DatabaseStore.class);
  String fieldsJson="fields";
  when(storage.dataset("dataset")).thenReturn(Map.of("connection_id","conn","fields_json",fieldsJson,"sql_body","SELECT region,amount FROM test_sales"));
  when(storage.arr(fieldsJson)).thenReturn(List.of(Map.of("name","region"),Map.of("name","amount")));
  when(storage.connection("conn")).thenReturn(Map.of("id","conn"));
  when(storage.open(anyMap())).thenAnswer(inv->DriverManager.getConnection(url,"sa",""));
  doCallRealMethod().when(storage).readRows(any(Connection.class),anyString(),anyList(),anyInt());
  BiApi api=new BiApi(storage,jdbc,"test-secret-at-least-12-chars");
  Map<String,Object> input=new HashMap<>();
  input.put("datasetId","dataset");
  input.put("dimensions",List.of("region"));
  input.put("measures",List.of(Map.of("field","amount","agg","SUM","alias","amount")));
  input.put("sort","amount");
  input.put("direction","desc");
  input.put("limit",2);
  input.put("filters",List.of(Map.of("field","region","op","in","values",List.of("North","East"))));
  @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)api.query(input).get("rows");
  assertEquals(2,rows.size());
  assertEquals("North",rows.get(0).get("region"));
  assertEquals("East",rows.get(1).get("region"));
  input.put("filters",List.of(Map.of("field","region","op","in","values",List.of("North' OR 1=1 --"))));
  @SuppressWarnings("unchecked") List<Map<String,Object>> noInjection=(List<Map<String,Object>>)api.query(input).get("rows");
  assertTrue(noInjection.isEmpty(),"filter values must be SQL parameters");
  input.put("filters",List.of(Map.of("field","region","op","in","values",Collections.nCopies(51,"North"))));
  assertThrows(IllegalArgumentException.class,()->api.query(input));
 }
}
