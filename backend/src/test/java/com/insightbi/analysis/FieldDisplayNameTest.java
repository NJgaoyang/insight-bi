package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Field presentation names are editable but bindings always use immutable field IDs. */
class FieldDisplayNameTest {
  @Test void renamePhysicalLabelsWithoutBreakingSqlBinding(){
    String url="jdbc:h2:mem:display_name_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    DatabaseStore store=new DatabaseStore(db,new ObjectMapper(),
       Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    String dataset="rename-demo";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
       dataset,"展示名称","test","SELECT city,revenue FROM sales",
       store.json(List.of(Map.of("name","city","type","VARCHAR","numeric",false),
         Map.of("name","revenue","type","DECIMAL","numeric",true))));
    FieldCatalog catalog=new FieldCatalog(store,db);
    var original=catalog.fields(dataset);
    String city=original.stream().filter(f->"city".equals(f.sourceColumn())).findFirst().orElseThrow().id();
    String revenue=original.stream().filter(f->"revenue".equals(f.sourceColumn())).findFirst().orElseThrow().id();
    AnalysisApi api=new AnalysisApi(store,db,catalog);
    var renamed=api.renameField(dataset,city,Map.of("displayName","  所属城市  "));
    assertEquals(city,renamed.id());
    assertEquals("所属城市",renamed.name());
    assertEquals("city",renamed.sourceColumn());

    // Duplicate presentation labels are allowed: field identity is ID, not text.
    var sameName=api.renameField(dataset,revenue,Map.of("displayName","所属城市"));
    assertEquals(revenue,sameName.id());
    assertNotEquals(city,sameName.id());
    var fields=catalog.fields(dataset);
    assertEquals(2,fields.size());
    assertEquals(2,fields.stream().filter(f->"所属城市".equals(f.name())).count());

    var plan=new AnalysisQueryPlanner(fields).compile(
      "SELECT city,revenue FROM sales",
      Map.of("dimensions",List.of(Map.of("fieldId",city)),
       "measures",List.of(Map.of("id","sales","fieldId",revenue,"aggregate","SUM"))));
    assertTrue(plan.sql().contains("biq.`city`"));
    assertTrue(plan.sql().contains("biq.`revenue`"));
    assertEquals("所属城市",plan.columns().get(0).get("label"));
    assertThrows(IllegalArgumentException.class,()->api.renameField(dataset,city,Map.of("displayName","  ")));
    assertThrows(IllegalArgumentException.class,()->api.renameField(dataset,city,Map.of("displayName",123)));
    assertThrows(NoSuchElementException.class,()->api.renameField(dataset,"not-a-field",Map.of("displayName","不存在")));
  }
}
