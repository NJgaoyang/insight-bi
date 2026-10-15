package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.FieldCatalog;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReaderApiTest {
  private JdbcTemplate db;
  private DatabaseStore store;
  private GovernanceApi governance;
  private ReaderApi reader;
  private ReaderAccessInterceptor interceptor;
  private BiApi admin;
  private CapturedAnalysis analysis;

  static class CapturedAnalysis extends AnalysisApi{
    Map<String,Object> received;
    CapturedAnalysis(DatabaseStore store,JdbcTemplate db){
      super(store,db,new FieldCatalog(store,db));
    }
    @Override public Map<String,Object> query(Map<String,Object> request){
      received=new LinkedHashMap<>(request);
      return Map.of("columns",List.of(),"rows",List.of(),"truncated",false);
    }
  }

  @BeforeEach void setup(){
    db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:reader_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    governance=new GovernanceApi(db);
    admin=new BiApi(store,db,"admin-password-at-least-12-chars");
    analysis=new CapturedAnalysis(store,db);
    reader=new ReaderApi(db,store,governance,analysis);
    interceptor=new ReaderAccessInterceptor(db);
  }

  private String user(){
    return (String)governance.createUser(
      Map.of("username","analyst01","displayName","安全读者","role","VIEWER")).get("id");
  }
  private MockHttpServletRequest login(String username,String password){
    MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/reader/login");
    request.setRemoteAddr("192.0.2.8");
    reader.login(Map.of("username",username,"password",password),request);
    return request;
  }
  private boolean permitted(HttpSession session,String method,String path,String csrf)throws Exception{
    var req=new MockHttpServletRequest(method,path);
    if(session!=null)req.setSession((org.springframework.mock.web.MockHttpSession)session);
    if(csrf!=null)req.addHeader("X-CSRF-Token",csrf);
    return interceptor.preHandle(req,new MockHttpServletResponse(),new Object());
  }

  @Test void exactCityRuleIsInjectedBeforeAggregationAndNeverComesFromClient()throws Exception{
    String userId=user();
    governance.setPassword(userId,Map.of("password","complex-password-of-reader-1"));
    governance.setActivation(userId,Map.of("enabled",true));
    db.execute("CREATE TABLE sales(city VARCHAR(80),revenue DECIMAL(12,2))");
    db.update("INSERT INTO sales(city,revenue) VALUES(?,?)","北京",100);
    db.update("INSERT INTO sales(city,revenue) VALUES(?,?)","上海",200);
    db.update("INSERT INTO sales(city,revenue) VALUES(?,?)","北京",50);
    String dataset="ds-city";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      dataset,"经营城市","unused","SELECT city,revenue FROM sales",
      store.json(List.of(Map.of("name","city","type","VARCHAR"),
                         Map.of("name","revenue","type","DECIMAL"))));
    var fields=new FieldCatalog(store,db).fields(dataset);
    String city=fields.stream().filter(x->"city".equals(x.sourceColumn())).findFirst().orElseThrow().id();
    String revenue=fields.stream().filter(x->"revenue".equals(x.sourceColumn())).findFirst().orElseThrow().id();
    assertThrows(IllegalArgumentException.class,()->
      governance.setRowPolicy(userId,dataset,
        Map.of("fieldId",revenue,"allowedValue","200")));
    assertThrows(IllegalArgumentException.class,()->
      governance.setRowPolicy(userId,dataset,
        Map.of("fieldId",city,"allowedValue","北京","sql","OR 1=1")));
    governance.setRowPolicy(userId,dataset,Map.of("fieldId",city,"allowedValue","北京"));
    assertEquals(1,governance.rowPolicies(userId).size());
    String dashboard=(String)admin.create(
      Map.of("name","城市报表","mode","grid","kind","analysis")).get("id");
    var bindings=Map.of("dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","m1","fieldId",revenue,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(),"post",List.of()),"sort",List.of(),"limit",100);
    var widget=Map.of("id","w1","title","收入","datasetId",dataset,"type","bar","analysisV2",bindings);
    db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
      store.json(Map.of("mode","grid","theme","light","widgets",List.of(widget))),dashboard);
    governance.grantRead(userId,Map.of("dashboardId",dashboard));
    HttpSession session=login("analyst01","complex-password-of-reader-1").getSession(false);
    assertThrows(IllegalArgumentException.class,()->
      reader.query(dashboard,"w1",Map.of("filters",Map.of("pre",List.of())),session));
    reader.query(dashboard,"w1",Map.of(),session);
    var filters=(Map<?,?>)analysis.received.get("filters");
    assertEquals(List.of(Map.of("fieldId",city,"op","eq","value","北京")),filters.get("pre"));
    var plan=new com.insightbi.analysis.AnalysisQueryPlanner(fields)
      .compile("SELECT city,revenue FROM sales",analysis.received);
    assertTrue(plan.sql().contains(" WHERE "));
    assertEquals("北京",plan.params().get(0));
    try(var conn=db.getDataSource().getConnection();
        var statement=conn.prepareStatement(plan.sql())){
      for(int i=0;i<plan.params().size();i++)statement.setObject(i+1,plan.params().get(i));
      try(var rows=statement.executeQuery()){
        assertTrue(rows.next());
        assertEquals("北京",rows.getString("d1"));
        assertEquals(0,new java.math.BigDecimal("150").compareTo(rows.getBigDecimal("m1")));
        assertFalse(rows.next());
      }
    }
    // Multiple allowed cities remain pre-aggregation binds, never SQL text.
    governance.setRowPolicy(userId,dataset,Map.of("fieldId",city,
      "allowedValues",List.of("北京","上海")));
    assertEquals(2,((List<?>)governance.rowPolicies(userId).get(0).get("values")).size());
    reader.query(dashboard,"w1",Map.of(),session);
    var multiple=(List<?>)((Map<?,?>)analysis.received.get("filters")).get("pre");
    assertEquals("in",((Map<?,?>)multiple.get(0)).get("op"));
    var multiPlan=new com.insightbi.analysis.AnalysisQueryPlanner(fields)
      .compile("SELECT city,revenue FROM sales",analysis.received);
    assertTrue(multiPlan.sql().contains(" IN (?,?)"));
    assertEquals(Set.of("北京","上海"),new HashSet<>(multiPlan.params().subList(0,2)));
    try(var conn=db.getDataSource().getConnection();
        var stmt=conn.prepareStatement(multiPlan.sql())){
      for(int i=0;i<multiPlan.params().size();i++)
        stmt.setObject(i+1,multiPlan.params().get(i));
      try(var result=stmt.executeQuery()){
        java.math.BigDecimal total=java.math.BigDecimal.ZERO;
        int count=0;
        while(result.next()){total=total.add(result.getBigDecimal("m1"));count++;}
        assertEquals(2,count);
        assertEquals(0,new java.math.BigDecimal("350").compareTo(total));
      }
    }
    assertThrows(IllegalArgumentException.class,()->governance.setRowPolicy(userId,dataset,
      Map.of("fieldId",city,"allowedValues",List.of("北京","北京"))));
    assertThrows(IllegalArgumentException.class,()->governance.setRowPolicy(userId,dataset,
      Map.of("fieldId",city,"allowedValues",List.of())));
    assertThrows(IllegalArgumentException.class,()->governance.setRowPolicy(userId,dataset,
      Map.of("fieldId",city,"allowedValues",
        java.util.stream.IntStream.range(0,21).mapToObj(String::valueOf).toList())));
    assertThrows(IllegalArgumentException.class,()->governance.setRowPolicy(userId,dataset,
      Map.of("fieldId",city,"allowedValue","北京","allowedValues",List.of("上海"))));
    assertEquals(2,((List<?>)governance.rowPolicies(userId).get(0).get("values")).size(),
      "invalid replacement must preserve old rule");
    // An old singleton record without children must still restrict rows.
    db.update("DELETE FROM insight_reader_row_policy_value WHERE user_id=? AND dataset_id=?",
      userId,dataset);
    reader.query(dashboard,"w1",Map.of(),session);
    assertEquals(List.of(Map.of("fieldId",city,"op","eq","value","北京")),
      ((Map<?,?>)analysis.received.get("filters")).get("pre"));
    // A malicious value remains a bind parameter; it can never become SQL.
    governance.setRowPolicy(userId,dataset,
      Map.of("fieldId",city,"allowedValue","北京' OR 1=1"));
    reader.query(dashboard,"w1",Map.of(),session);
    var malicious=new com.insightbi.analysis.AnalysisQueryPlanner(fields)
      .compile("SELECT city,revenue FROM sales",analysis.received);
    assertFalse(malicious.sql().contains("OR 1=1"));
    assertEquals("北京' OR 1=1",malicious.params().get(0));
    governance.deleteRowPolicy(userId,dataset);
    assertTrue(governance.mandatoryRowFilters(userId,dataset).isEmpty());
    governance.setRowPolicy(userId,dataset,Map.of("fieldId",city,"allowedValue","北京"));
    governance.deleteUser(userId);
    assertEquals(0,db.queryForObject(
      "SELECT COUNT(*) FROM insight_reader_row_policy WHERE user_id=?",Integer.class,userId));
  }

  @Test void passwordLoginIsExplicitAndCannotBecomeAdmin()throws Exception{
    String id=user();
    assertThrows(IllegalArgumentException.class,()->login("analyst01","not-the-password"));
    assertThrows(IllegalStateException.class,()->
      governance.setActivation(id,Map.of("enabled",true)));
    assertThrows(IllegalArgumentException.class,()->
      governance.setPassword(id,Map.of("password","short")));
    governance.setPassword(id,Map.of("password","complex-password-of-reader-1"));
    String passwordHash=db.queryForObject(
      "SELECT password_hash FROM insight_governance_user WHERE id=?",String.class,id);
    assertNotEquals("complex-password-of-reader-1",passwordHash);
    assertTrue(passwordHash.startsWith("pbkdf2-sha256$"));
    assertTrue(ReaderPassword.matches("complex-password-of-reader-1",passwordHash));
    assertFalse(ReaderPassword.matches("wrong",passwordHash));
    assertThrows(IllegalArgumentException.class,()->
      login("analyst01","complex-password-of-reader-1"),"disabled users cannot login");
    governance.setActivation(id,Map.of("enabled",true));
    MockHttpServletRequest successful=login("analyst01","complex-password-of-reader-1");
    HttpSession session=successful.getSession(false);
    assertNotNull(session);
    assertEquals(id,session.getAttribute("readerId"));
    assertNull(session.getAttribute("admin"));
    assertTrue(permitted(session,"GET","/api/reader/catalog",null));
    assertFalse(permitted(session,"GET","/api/datasets",null));
    assertFalse(permitted(session,"GET","/api/analysis/v2/compile",null));
    assertFalse(permitted(session,"GET","/api/governance/users",null));
    assertFalse(permitted(session,"POST","/api/reader/logout",null));
    assertTrue(permitted(session,"POST","/api/reader/logout",(String)session.getAttribute("csrf")));
    governance.setActivation(id,Map.of("enabled",false));
    assertFalse(permitted(session,"GET","/api/reader/catalog",null),"disable revokes active sessions");
    governance.setActivation(id,Map.of("enabled",true));
    assertFalse(permitted(session,"GET","/api/reader/catalog",null),
      "re-activation must not revalidate a prior session");
    MockHttpServletRequest newLogin=login("analyst01","complex-password-of-reader-1");
    HttpSession newerSession=newLogin.getSession(false);
    assertTrue(permitted(newerSession,"GET","/api/reader/catalog",null));
    governance.setPassword(id,Map.of("password","new-complex-password-for-reader-1"));
    assertFalse(permitted(session,"GET","/api/reader/catalog",null),"reset revokes old sessions");
    assertFalse(permitted(newerSession,"GET","/api/reader/catalog",null),
      "password reset revokes the latest login too");
  }

  @Test void readerGetsOnlyPublishedAuthorizedReportAndPinnedWidgetQuery()throws Exception{
    String userId=user();
    governance.setPassword(userId,Map.of("password","complex-password-of-reader-1"));
    governance.setActivation(userId,Map.of("enabled",true));
    String dashboard=(String)admin.create(
      Map.of("name","经营分析","mode","grid","kind","analysis")).get("id");
    String unrelated=(String)admin.create(
      Map.of("name","保密分析","mode","grid","kind","analysis")).get("id");
    Map<String,Object> bindings=Map.of(
      "dimensions",List.of(Map.of("fieldId","city")),
      "measures",List.of(Map.of("id","m1","fieldId","revenue","aggregate","SUM")),
      "filters",Map.of("pre",List.of(),"post",List.of()),"sort",List.of(),"limit",900);
    Map<String,Object> widget=Map.of("id","w1","title","收入","datasetId","trusted-dataset",
      "type","bar","analysisV2",bindings);
    String snapshot=store.json(Map.of("mode","grid","theme","light","widgets",List.of(widget)));
    db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
      snapshot,dashboard);
    db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
      snapshot,unrelated);
    governance.grantRead(userId,Map.of("dashboardId",dashboard));
    HttpSession session=login("analyst01","complex-password-of-reader-1").getSession(false);
    assertEquals(1,reader.catalog(session).size());
    assertEquals("经营分析",reader.catalog(session).get(0).get("name"));
    assertEquals("analysis",reader.report(dashboard,session).get("kind"));
    assertThrows(NoSuchElementException.class,()->reader.report(unrelated,session));
    assertThrows(NoSuchElementException.class,()->
      reader.query(unrelated,"w1",Map.of(),session));
    assertThrows(NoSuchElementException.class,()->
      reader.query(dashboard,"outside-widget",Map.of(),session));
    assertThrows(IllegalArgumentException.class,()->
      reader.query(dashboard,"w1",Map.of("datasetId","unauthorized-data"),session));
    assertThrows(IllegalArgumentException.class,()->
      reader.query(dashboard,"w1",Map.of("filters",List.of()),session));
    reader.query(dashboard,"w1",Map.of(),session);
    assertNotNull(analysis.received);
    assertEquals("trusted-dataset",analysis.received.get("datasetId"));
    assertEquals(200,analysis.received.get("limit"));
    assertEquals(bindings.get("dimensions"),analysis.received.get("dimensions"));
    assertFalse(analysis.received.containsKey("tableHiddenColumnIds"));
    // Published pivot rollups are enabled exclusively from the trusted
    // snapshot; viewer callers cannot submit their own pivot or raw SQL.
    Map<String,Object> pivotBindings=new LinkedHashMap<>(bindings);
    pivotBindings.put("pivot",Map.of("showTotals",true,"rowOrder","asc","columnOrder","desc"));
    Map<String,Object> pivotWidget=Map.of("id","w1","title","收入交叉表",
      "datasetId","trusted-dataset","type","pivot","analysisV2",pivotBindings);
    db.update("UPDATE insight_dashboard SET published_json=? WHERE id=?",
      store.json(Map.of("mode","grid","widgets",List.of(pivotWidget))),dashboard);
    reader.query(dashboard,"w1",Map.of(),session);
    assertEquals(pivotBindings.get("pivot"),analysis.received.get("pivot"));
    assertThrows(IllegalArgumentException.class,()->
      reader.query(dashboard,"w1",Map.of("pivot",Map.of("showTotals",true)),session));
    db.update("UPDATE insight_dashboard SET published_json=NULL,status='draft' WHERE id=?",dashboard);
    assertThrows(NoSuchElementException.class,()->reader.report(dashboard,session));
    assertTrue(reader.catalog(session).isEmpty());
  }

  @Test void inheritedDepartmentGrantsKeepCatalogReportAndQueryConsistent()throws Exception{
    String userId=user();
    governance.setPassword(userId,Map.of("password","complex-password-of-reader-1"));
    governance.setActivation(userId,Map.of("enabled",true));
    String parent=(String)governance.createDepartment(Map.of("name","管理大区")).get("id");
    String child=(String)governance.createDepartment(Map.of("name","城市团队","parentId",parent)).get("id");
    String outside=(String)governance.createDepartment(Map.of("name","无权团队")).get("id");
    governance.assignDepartment(userId,Map.of("departmentId",child));
    String dashboard=(String)admin.create(
      Map.of("name","继承授权主题","mode","grid","kind","analysis")).get("id");
    String secret=(String)admin.create(
      Map.of("name","未授权主题","mode","grid","kind","analysis")).get("id");
    var settings=Map.of("dimensions",List.of(Map.of("fieldId","city")),
      "measures",List.of(Map.of("id","m1","fieldId","revenue","aggregate","SUM")),
      "filters",Map.of("pre",List.of(),"post",List.of()),"sort",List.of(),"limit",30);
    var widget=Map.of("id","w1","title","收入","type","table",
      "datasetId","trusted-data","analysisV2",settings);
    String published=store.json(Map.of("mode","grid","theme","light",
      "widgets",List.of(widget)));
    db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
      published,dashboard);
    db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
      published,secret);
    governance.grantDepartmentRead(parent,Map.of("dashboardId",dashboard));
    HttpSession session=login("analyst01","complex-password-of-reader-1").getSession(false);
    assertEquals(List.of(dashboard),reader.catalog(session).stream().map(x->x.get("id")).toList());
    assertEquals("继承授权主题",reader.report(dashboard,session).get("name"));
    reader.query(dashboard,"w1",Map.of(),session);
    assertEquals("trusted-data",analysis.received.get("datasetId"));
    assertThrows(NoSuchElementException.class,()->reader.report(secret,session));
    assertThrows(NoSuchElementException.class,()->
      reader.query(secret,"w1",Map.of(),session));
    governance.assignDepartment(userId,Map.of("departmentId",outside));
    assertTrue(reader.catalog(session).isEmpty());
    assertThrows(NoSuchElementException.class,()->reader.report(dashboard,session));
    assertThrows(NoSuchElementException.class,()->
      reader.query(dashboard,"w1",Map.of(),session));
    governance.assignDepartment(userId,Map.of("departmentId",child));
    assertEquals(1,reader.catalog(session).size());
    governance.revokeDepartmentRead(parent,dashboard);
    assertTrue(reader.catalog(session).isEmpty());
    assertThrows(NoSuchElementException.class,()->reader.report(dashboard,session));
  }

  @Test void columnDenialsHidePublishedReportsAndBlockRawAggregateAndCalculatedLeaks()throws Exception{
    String userId=user();
    governance.setPassword(userId,Map.of("password","complex-password-of-reader-1"));
    governance.setActivation(userId,Map.of("enabled",true));
    String ds="protected-data";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      ds,"部门经营","unused","SELECT city,revenue,salary FROM sales",
      store.json(List.of(Map.of("name","city","type","VARCHAR"),
        Map.of("name","revenue","type","DECIMAL"),
        Map.of("name","salary","type","DECIMAL"))));
    var fields=new FieldCatalog(store,db).fields(ds);
    String city=fields.stream().filter(x->x.sourceColumn().equals("city"))
      .findFirst().orElseThrow().id();
    String revenue=fields.stream().filter(x->x.sourceColumn().equals("revenue"))
      .findFirst().orElseThrow().id();
    String salary=fields.stream().filter(x->x.sourceColumn().equals("salary"))
      .findFirst().orElseThrow().id();
    String computed="computed-sensitive";
    db.update("INSERT INTO insight_calculated_field(id,dataset_id,name,expression,phase,output_type) VALUES(?,?,?,?,?,?)",
      computed,ds,"薪资派生",salary,"ROW","DECIMAL");

    var safeCfg=Map.of("dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","sum_revenue","fieldId",revenue,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(),"post",List.of()),"limit",40);
    var deniedCfg=Map.of("dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","sum_salary","fieldId",salary,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(),"post",List.of()),"limit",40);
    var computedCfg=Map.of("dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","sum_computed","fieldId",computed,"aggregate","SUM")),
      "filters",Map.of("pre",List.of(),"post",List.of()),"limit",40);

    String safe=(String)admin.create(Map.of("name","经营指标","kind","analysis","mode","grid")).get("id");
    String denied=(String)admin.create(Map.of("name","薪资数据","kind","analysis","mode","grid")).get("id");
    String calc=(String)admin.create(Map.of("name","薪资衍生","kind","analysis","mode","grid")).get("id");
    String processed=(String)admin.create(Map.of("name","加工分析","kind","analysis","mode","grid")).get("id");
    java.util.function.BiConsumer<String,Map<String,Object>> release=(id,config)->{
      Map<String,Object> widget=Map.of("id","w1","title","敏感字段测试",
        "datasetId",ds,"type","bar","analysisV2",config);
      db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
        store.json(Map.of("mode","grid","widgets",List.of(widget))),id);
      governance.grantRead(userId,Map.of("dashboardId",id));
    };
    release.accept(safe,safeCfg);
    release.accept(denied,deniedCfg);
    release.accept(calc,computedCfg);
    var pipelineCfg=new HashMap<String,Object>(safeCfg);
    pipelineCfg.put("usePipeline",true);
    pipelineCfg.put("pipelineRevision",1);
    release.accept(processed,pipelineCfg);
    HttpSession session=login("analyst01","complex-password-of-reader-1").getSession(false);
    assertEquals(4,reader.catalog(session).size());

    assertThrows(IllegalArgumentException.class,()->
      governance.setDeniedColumns(userId,ds,Map.of("fieldIds",List.of("unknown"))));
    assertThrows(IllegalArgumentException.class,()->
      governance.setDeniedColumns(userId,ds,Map.of("fieldIds",List.of(salary,salary))));
    assertThrows(IllegalArgumentException.class,()->
      governance.setDeniedColumns(userId,ds,Map.of("fieldIds",List.of(salary),"admin",true)));
    governance.setDeniedColumns(userId,ds,Map.of("fieldIds",List.of(salary)));
    assertEquals(1,governance.deniedColumns(userId).size());
    assertEquals(List.of(safe),reader.catalog(session).stream().map(v->v.get("id")).toList());
    assertEquals("经营指标",reader.report(safe,session).get("name"));
    reader.query(safe,"w1",Map.of(),session);
    assertEquals(ds,analysis.received.get("datasetId"));
    for(String forbidden:List.of(denied,calc,processed)){
      assertThrows(NoSuchElementException.class,()->reader.report(forbidden,session));
      assertThrows(NoSuchElementException.class,()->
        reader.query(forbidden,"w1",Map.of(),session));
    }
    // Revoking the denial restores access without rewriting published content.
    governance.setDeniedColumns(userId,ds,Map.of("fieldIds",List.of()));
    assertEquals(4,reader.catalog(session).size());
    reader.query(denied,"w1",Map.of(),session);
    governance.setDeniedColumns(userId,ds,Map.of("fieldIds",List.of(revenue)));
    assertThrows(NoSuchElementException.class,()->reader.report(safe,session));
    assertTrue(reader.catalog(session).stream().noneMatch(v->safe.equals(v.get("id"))));
    governance.deleteUser(userId);
    assertEquals(0,db.queryForObject(
      "SELECT COUNT(*) FROM insight_reader_column_deny WHERE user_id=?",Integer.class,userId));
  }

  @Test void folderAclConsistentlyRestrictsReaderCatalogDetailAndQuery()throws Exception{
    String userId=user();
    governance.setPassword(userId,Map.of("password","complex-password-of-reader-1"));
    governance.setActivation(userId,Map.of("enabled",true));
    String root=(String)admin.createFolder(Map.of("name","分析库")).get("id");
    String restricted=(String)admin.createFolder(Map.of(
      "name","敏感分析","parentId",root)).get("id");
    String account=(String)admin.create(Map.of(
      "name","部门财务","kind","analysis","mode","grid")).get("id");
    admin.moveDashboard(account,Map.of("parentId",restricted));
    var widget=Map.of("id","w1","title","收入","type","bar","datasetId","ds1",
      "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId","city")),
        "measures",List.of(Map.of("id","m1","fieldId","revenue","aggregate","SUM")),
        "limit",50));
    db.update("UPDATE insight_dashboard SET status='published',published_json=? WHERE id=?",
      store.json(Map.of("mode","grid","widgets",List.of(widget))),account);
    governance.grantRead(userId,Map.of("dashboardId",account));
    HttpSession session=login("analyst01","complex-password-of-reader-1").getSession(false);
    assertEquals(1,reader.catalog(session).size());
    reader.query(account,"w1",Map.of(),session);

    String other=(String)governance.createUser(Map.of(
      "username","bobby","displayName","Bob","role","VIEWER")).get("id");
    governance.setFolderAccess(root,Map.of("rules",List.of(
      Map.of("principalType","USER","principalId",other))));
    assertTrue(reader.catalog(session).isEmpty());
    assertThrows(NoSuchElementException.class,()->reader.report(account,session));
    assertThrows(NoSuchElementException.class,()->
      reader.query(account,"w1",Map.of(),session));

    governance.setFolderAccess(root,Map.of("rules",List.of(
      Map.of("principalType","USER","principalId",userId))));
    assertEquals(1,reader.catalog(session).size());
    assertEquals(account,reader.report(account,session).get("id"));
    reader.query(account,"w1",Map.of(),session);
    admin.moveDashboard(account,new HashMap<>(Map.of("parentId","")));
    assertEquals(1,reader.catalog(session).size(),
      "moving out of restricted folder updates reader visibility without republishing");
  }
}
