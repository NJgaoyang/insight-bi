package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test actual compiled SQL against disposable H2 MySQL-mode rows. No production StarRocks touched. */
class AnalysisEngineSqlExecutionTest {
  @Test void persistedFieldsFormulaAndPrePostFilters() throws Exception {
    String url="jdbc:h2:mem:p0_analysis_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    var ds=new DriverManagerDataSource(url,"sa","");
    var jdbc=new JdbcTemplate(ds);
    String key=Base64.getEncoder().encodeToString(new byte[32]);
    var store=new DatabaseStore(jdbc,new ObjectMapper(),key);
    store.schema();
    jdbc.execute("CREATE TABLE sales (city VARCHAR(60), revenue DECIMAL(16,2), cost DECIMAL(16,2), profit DECIMAL(16,2))");
    jdbc.update("INSERT INTO sales VALUES ('北京',100,90,10),('北京',200,150,50),('上海',50,40,10),('南京',0,-5,5)");
    String dataset="dataset-sql";
    String json=store.json(List.of(
      Map.of("name","city","type","VARCHAR","numeric",false),
      Map.of("name","revenue","type","DECIMAL","numeric",true),
      Map.of("name","cost","type","DECIMAL","numeric",true),
      Map.of("name","profit","type","DECIMAL","numeric",true)
    ));
    jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
       dataset,"Sales","conn","SELECT city,revenue,cost,profit FROM sales",json);
    var catalog=new FieldCatalog(store,jdbc);
    var first=catalog.fields(dataset);
    var second=catalog.fields(dataset);
    assertEquals(first,second,"stable field IDs must survive repeated requests");
    String city=id(first,"city"),revenue=id(first,"revenue"),cost=id(first,"cost"),profit=id(first,"profit");
    var api=new AnalysisApi(store,jdbc,catalog);
    String formula="SUM(["+profit+"]) / NULLIF(SUM(["+revenue+"]),0)";
    var saved=api.addFormula(dataset,Map.of("name","利润率","expression",formula));
    String rate=String.valueOf(saved.get("id"));
    assertEquals("AGGREGATE",saved.get("phase"));
    assertTrue(catalog.fields(dataset).stream().anyMatch(f->rate.equals(f.id())));
    var planner=new AnalysisQueryPlanner(catalog.fields(dataset));
    Map<String,Object> q=new HashMap<>();
    q.put("dimensions",List.of(Map.of("fieldId",city)));
    q.put("measures",List.of(
       Map.of("id","rev","fieldId",revenue,"aggregate","SUM"),
       Map.of("id","margin","fieldId",rate,"aggregate","SUM")
    ));
    q.put("sort",List.of(Map.of("ref","rev","direction","desc")));
    q.put("limit",20);
    var base=run(store,ds,planner,q);
    assertEquals(4,base.size());
    Map<String,Object> beijing=find(base,"d1","北京");
    assertEquals(0,new BigDecimal("300").compareTo(new BigDecimal(String.valueOf(beijing.get("rev")))));
    assertEquals(0,new BigDecimal("0.2").compareTo(new BigDecimal(String.valueOf(beijing.get("margin")))));
    Map<String,Object> nanjing=find(base,"d1","南京");
    assertNull(nanjing.get("margin"),"division by zero must return NULL");

    q.put("filters",Map.of("pre",List.of(Map.of("fieldId",revenue,"op","gte","value","150")),"post",List.of()));
    var pre=run(store,ds,planner,q);
    assertEquals(1,pre.size());assertEquals(0,new BigDecimal("200").compareTo(new BigDecimal(String.valueOf(pre.get(0).get("rev")))));

    q.put("filters",Map.of("pre",List.of(),"post",List.of(Map.of("ref","rev","op","gte","value","150"))));
    var post=run(store,ds,planner,q);
    assertEquals(1,post.size());assertEquals(0,new BigDecimal("300").compareTo(new BigDecimal(String.valueOf(post.get(0).get("rev")))));
    assertEquals(true,api.deleteFormula(dataset,rate).get("ok"));
  }
  private static String id(List<FieldCatalog.Field> list,String column){
    return list.stream().filter(f->column.equals(f.sourceColumn())).findFirst().orElseThrow().id();
  }
  private static List<Map<String,Object>> run(DatabaseStore store,DriverManagerDataSource ds,
                                                AnalysisQueryPlanner planner,Map<String,Object> request)throws Exception {
    var plan=planner.compile("SELECT city,revenue,cost,profit FROM sales",request);
    try(Connection c=ds.getConnection()){return store.readRows(c,plan.sql(),plan.params(),plan.limit());}
  }
  private static Map<String,Object> find(List<Map<String,Object>> rows,String column,String value){
    return rows.stream().filter(r->value.equals(r.get(column))).findFirst().orElseThrow();
  }
}
