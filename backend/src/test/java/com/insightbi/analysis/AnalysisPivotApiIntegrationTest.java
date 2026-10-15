package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** HTTP-equivalent V2 controller query flow: same JDBC session and trusted planner for rollups. */
class AnalysisPivotApiIntegrationTest {
 @Test void exactResponseAndTruncatedFailClosedFromActualAnalysisApi() throws Exception{
   var url="jdbc:h2:mem:api_pivot_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
   var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
   var store=spy(new DatabaseStore(jdbc,new ObjectMapper(),
     Base64.getEncoder().encodeToString(new byte[32])));
   store.schema();
   jdbc.execute("CREATE TABLE sales(city VARCHAR(40), quarter_name VARCHAR(20), amount DECIMAL(16,2), buyer VARCHAR(30))");
   jdbc.execute("INSERT INTO sales VALUES ('北京','一季度',100.10,'A'),('北京','二季度',9.90,'A'),"+
       "('北京','二季度',10.00,'B'),('上海','一季度',30.00,'C')");
   jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
     "pivot-dataset","真实聚合","conn","SELECT city,quarter_name,amount,buyer FROM sales",store.json(List.of(
       Map.of("name","city","type","VARCHAR","numeric",false),
       Map.of("name","quarter_name","type","VARCHAR","numeric",false),
       Map.of("name","amount","type","DECIMAL","numeric",true),
       Map.of("name","buyer","type","VARCHAR","numeric",false)
     )));
   doReturn(Map.of("id","conn")).when(store).connection("conn");
   doAnswer(invocation->{
     var conn=spy(DriverManager.getConnection(url,"sa",""));
     doReturn(true).when(conn).isReadOnly();
     return conn;
   }).when(store).open(anyMap());
   var fields=new FieldCatalog(store,jdbc);
   var all=fields.fields("pivot-dataset");
   var aliases=new HashMap<String,String>();
   for(var f:all)aliases.put(f.sourceColumn(),f.id());
   Map<String,Object> query=new LinkedHashMap<>();
   query.put("datasetId","pivot-dataset");
   query.put("dimensions",List.of(Map.of("fieldId",aliases.get("city")),
      Map.of("fieldId",aliases.get("quarter_name"))));
   query.put("measures",List.of(
     Map.of("id","sum","aggregate","SUM","fieldId",aliases.get("amount")),
     Map.of("id","average","aggregate","AVG","fieldId",aliases.get("amount")),
     Map.of("id","customers","aggregate","COUNT","distinct",true,"fieldId",aliases.get("buyer"))));
   query.put("filters",Map.of("pre",List.of(Map.of("fieldId",aliases.get("city"),"op","eq","value","北京")),
       "post",List.of()));
   query.put("sort",List.of());
   query.put("pivot",Map.of("showTotals",true));
   query.put("limit",5);
   var api=new AnalysisApi(store,jdbc,fields);
   var result=api.query(query);
   assertEquals(false,result.get("truncated"));
   assertEquals(2,((List<?>)result.get("rows")).size());
   @SuppressWarnings("unchecked")
   var totals=(Map<String,Object>)result.get("pivotTotals");
   assertEquals(true,totals.get("exact"));
   @SuppressWarnings("unchecked")
   var grand=(Map<String,Object>)totals.get("grand");
   assertEquals("120.00",grand.get("sum"));
   assertEquals(0,new BigDecimal("40").compareTo(new BigDecimal(String.valueOf(grand.get("average")))));
   assertEquals("2",grand.get("customers"));
   query.put("limit",1);
   var limited=api.query(query);
   assertEquals(true,limited.get("truncated"));
   assertEquals(false,((Map<?,?>)limited.get("pivotTotals")).get("exact"));
   query.put("limit",5);
   query.put("filters",Map.of("pre",List.of(),"post",List.of(Map.of("ref","sum","op","gte","value",1))));
   var post=api.query(query);
   assertEquals(false,((Map<?,?>)post.get("pivotTotals")).get("exact"));
   assertNotNull(post.get("rows"));
 }
}
