package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.*;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** A published view can accept only its pinned dimension value, never arbitrary SQL/filters. */
class ReaderViewFilterTest {
 static class CapturingAnalysis extends AnalysisApi {
  Map<String,Object> received;
  CapturingAnalysis(DatabaseStore store,JdbcTemplate jdbc){
   super(store,jdbc,new FieldCatalog(store,jdbc));
  }
  @Override public Map<String,Object> query(Map<String,Object> request){
   received=new LinkedHashMap<>(request);
   return Map.of("columns",List.of(),"rows",List.of(),"truncated",false);
  }
 }
 @Test void publishedViewFilterComposesWithMandatoryRowAclAndRejectsUnapprovedInputs()throws Exception{
  var jdbc=new JdbcTemplate(new DriverManagerDataSource(
   "jdbc:h2:mem:viewer_filter_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
  var store=new DatabaseStore(jdbc,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
  store.schema();
  var governance=new GovernanceApi(jdbc);
  var admin=new BiApi(store,jdbc,"admin-password-at-least-12-chars");
  var analysis=new CapturingAnalysis(store,jdbc);
  var reader=new ReaderApi(jdbc,store,governance,analysis);
  String userId=(String)governance.createUser(
   Map.of("username","filteruser","displayName","图表读者","role","VIEWER")).get("id");
  governance.setPassword(userId,Map.of("password","reader-filter-password-2026"));
  governance.setActivation(userId,Map.of("enabled",true));
  jdbc.execute("CREATE TABLE sales(city VARCHAR(80),revenue DECIMAL(12,2))");
  jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
   "sales-source","收入数据","test-connection","SELECT city,revenue FROM sales",
   store.json(List.of(Map.of("name","city","type","VARCHAR"),
    Map.of("name","revenue","type","DECIMAL"))));
  var fields=new FieldCatalog(store,jdbc).fields("sales-source");
  String city=fields.stream().filter(f->"city".equals(f.sourceColumn())).findFirst().orElseThrow().id();
  String revenue=fields.stream().filter(f->"revenue".equals(f.sourceColumn())).findFirst().orElseThrow().id();
  var binding=Map.of("dimensions",List.of(Map.of("fieldId",city)),
   "measures",List.of(Map.of("id","total","fieldId",revenue,"aggregate","SUM")),
   "filters",Map.of("pre",List.of(),"post",List.of()),"sort",List.of(),"limit",100);
  var widget1=Map.of("id","w1","title","销售额","datasetId","sales-source","type","bar","analysisV2",binding);
  var widget2=Map.of("id","w2","title","对照指标","datasetId","sales-source","type","metric","analysisV2",binding);
  var filterConfig=Map.of("datasetId","sales-source","fieldId",city,"label","城市");
  var view1=Map.of("id","v1","name","经营","widgetIds",List.of("w1"),
   "filter",filterConfig);
  var view2=Map.of("id","v2","name","其他","widgetIds",List.of("w2"));
  String dashboard=(String)admin.create(Map.of("name","筛选报表","mode","grid","kind","analysis")).get("id");
  jdbc.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
   store.json(Map.of("mode","grid","theme","light","widgets",List.of(widget1,widget2),
    "dashboards",List.of(view1,view2))),dashboard);
  governance.grantRead(userId,Map.of("dashboardId",dashboard));
  governance.setRowPolicy(userId,"sales-source",Map.of("fieldId",city,"allowedValue","北京"));
  var login=new MockHttpServletRequest("POST","/api/reader/login");
  login.setRemoteAddr("192.0.2.15");
  reader.login(Map.of("username","filteruser","password","reader-filter-password-2026"),login);
  HttpSession session=login.getSession(false);

  reader.query(dashboard,"w1",Map.of("viewId","v1","filterValue","上海"),session);
  var pre=(List<?>)((Map<?,?>)analysis.received.get("filters")).get("pre");
  assertEquals(2,pre.size());
  assertEquals(Map.of("fieldId",city,"op","eq","value","上海"),pre.get(0));
  assertEquals(Map.of("fieldId",city,"op","eq","value","北京"),pre.get(1));
  // Even an SQL-shaped value remains a JDBC bind parameter, never SQL text.
  reader.query(dashboard,"w1",
   Map.of("viewId","v1","filterValue","北京' OR 1=1"),session);
  var compiled=new AnalysisQueryPlanner(fields)
   .compile("SELECT city,revenue FROM sales",analysis.received);
  assertFalse(compiled.sql().contains("OR 1=1"));
  assertTrue(compiled.params().contains("北京' OR 1=1"));

  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w1",Map.of("viewId","v2","filterValue","北京"),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w2",Map.of("viewId","v1","filterValue","北京"),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w1",Map.of("viewId","v1","filterValue",""),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w1",Map.of("viewId","v1","filterValue","a".repeat(121)),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w1",Map.of("filters",Map.of("pre",List.of())),session));
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w1",
    Map.of("viewId","v1","filterValue","北京","datasetId","another"),session));
  // No filter remains backward compatible and still retains mandatory policy.
  reader.query(dashboard,"w2",Map.of(),session);
  assertEquals(1,((List<?>)((Map<?,?>)analysis.received.get("filters")).get("pre")).size());

  var malformed=Map.of("id","v1","name","经营","widgetIds",List.of("w1"),
   "filter",Map.of("datasetId","sales-source","fieldId",revenue));
  jdbc.update("UPDATE insight_dashboard SET published_json=? WHERE id=?",
   store.json(Map.of("mode","grid","widgets",List.of(widget1),"dashboards",List.of(malformed))),dashboard);
  assertThrows(IllegalArgumentException.class,()->
   reader.query(dashboard,"w1",Map.of("viewId","v1","filterValue","北京"),session));
 }
}
