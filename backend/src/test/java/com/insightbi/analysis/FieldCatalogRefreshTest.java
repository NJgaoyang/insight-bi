package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A refreshed physical type must not invalidate saved field IDs or user labels. */
class FieldCatalogRefreshTest {
 @Test void refreshedTypeAndRolePreserveFieldIdentityAndLabel(){
  String url="jdbc:h2:mem:fieldrefresh_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
  var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
  var store=new DatabaseStore(jdbc,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  String ds="field-refresh";
  jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      ds,"Refresh","conn","SELECT amount FROM sales",
      store.json(List.of(Map.of("name","amount","type","VARCHAR","numeric",false))));
  FieldCatalog catalog=new FieldCatalog(store,jdbc);
  FieldCatalog.Field old=catalog.fields(ds).get(0);
  assertEquals("STRING",old.logicalType());
  assertEquals("DIMENSION",old.role());
  jdbc.update("UPDATE insight_dataset_field SET display_name=? WHERE id=?","业务金额",old.id());

  // Dataset metadata refreshed by editing the SQL while column name stays the same.
  jdbc.update("UPDATE insight_dataset SET fields_json=? WHERE id=?",
      store.json(List.of(Map.of("name","amount","type","DECIMAL","numeric",true))),ds);
  FieldCatalog.Field updated=catalog.fields(ds).get(0);
  assertEquals(old.id(),updated.id());
  assertEquals("业务金额",updated.name());
  assertEquals("DECIMAL",updated.logicalType());
  assertEquals("BOTH",updated.role());
  assertTrue(updated.numeric());
  assertEquals(updated,catalog.fields(ds).get(0));

  jdbc.update("UPDATE insight_dataset SET fields_json=? WHERE id=?",
      store.json(List.of(Map.of("name","amount","type","DATE","numeric",false))),ds);
  FieldCatalog.Field date=catalog.fields(ds).get(0);
  assertEquals(old.id(),date.id());
  assertEquals("DATE",date.logicalType());
  assertEquals("DIMENSION",date.role());
  assertFalse(date.numeric());
 }
}
