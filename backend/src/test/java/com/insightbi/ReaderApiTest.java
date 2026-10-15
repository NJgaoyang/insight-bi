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
    db.update("UPDATE insight_dashboard SET published_json=NULL,status='draft' WHERE id=?",dashboard);
    assertThrows(NoSuchElementException.class,()->reader.report(dashboard,session));
    assertTrue(reader.catalog(session).isEmpty());
  }
}
