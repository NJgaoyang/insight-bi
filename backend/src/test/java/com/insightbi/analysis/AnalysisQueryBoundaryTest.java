package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real JDBC execution on disposable H2 rows; no production database or network. */
class AnalysisQueryBoundaryTest {
  @Test void dimensionOnlyGroupingAndAccurateOverflowEvenAt500() throws Exception {
    String url="jdbc:h2:mem:analysis_bound_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    var store=spy(new DatabaseStore(jdbc,new ObjectMapper(),
       Base64.getEncoder().encodeToString(new byte[32])));
    store.schema();
    jdbc.execute("CREATE TABLE sales(city VARCHAR(40), revenue DECIMAL(12,2))");
    jdbc.update("INSERT INTO sales VALUES('City-000',10),('City-000',20)");
    String dataset="bound-ds";
    String fieldsJson=store.json(List.of(
       Map.of("name","city","type","VARCHAR","numeric",false),
       Map.of("name","revenue","type","DECIMAL","numeric",true)));
    jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
       dataset,"Boundary","conn","SELECT city,revenue FROM sales",fieldsJson);
    doReturn(Map.of("id","conn")).when(store).connection("conn");
    doAnswer(inv->DriverManager.getConnection(url,"sa","")).when(store).open(anyMap());
    var catalog=new FieldCatalog(store,jdbc);
    String city=catalog.fields(dataset).stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    var api=new AnalysisApi(store,jdbc,catalog);
    var req=new HashMap<String,Object>();
    req.put("datasetId",dataset);
    req.put("dimensions",List.of(Map.of("fieldId",city)));
    req.put("measures",List.of());
    req.put("sort",List.of(Map.of("ref",city,"direction","asc")));
    req.put("limit",1);
    var initial=api.query(req);
    assertFalse((Boolean)initial.get("truncated"),"duplicate raw rows are one distinct dimension");
    assertEquals(1,rows(initial).size());

    // 500 distinct groups must not be marked truncated.
    for(int i=1;i<500;i++)
      jdbc.update("INSERT INTO sales VALUES(?,?)",String.format("City-%03d",i),BigDecimal.valueOf(i));
    req.put("limit",500);
    var exact=api.query(req);
    assertEquals(500,rows(exact).size());
    assertFalse((Boolean)exact.get("truncated"),"exactly 500 is not overflow");

    // Sentinel row 501 is observed but must never leak into the response.
    jdbc.update("INSERT INTO sales VALUES('City-500',500)");
    var overflow=api.query(req);
    assertEquals(500,rows(overflow).size());
    assertTrue((Boolean)overflow.get("truncated"),"501 distinct groups overflows by one");
    assertEquals("City-499",rows(overflow).get(499).get("d1"));

    // A V1 caller retains the 500-row reader contract.
    try(var connection=DriverManager.getConnection(url,"sa","")){
      assertEquals(500,store.readRows(connection,"SELECT city FROM sales ORDER BY city",List.of(),501).size());
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String,Object>> rows(Map<String,Object> response){
    return (List<Map<String,Object>>)response.get("rows");
  }
}
