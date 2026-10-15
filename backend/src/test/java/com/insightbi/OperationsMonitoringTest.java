package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.HttpStatus;
import com.insightbi.analysis.AnalysisQueryLimiter;

class OperationsMonitoringTest {
  private static JdbcTemplate healthyDatabase(){
    return new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:operations_"+UUID.randomUUID()+
        ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
  }

  @Test void readinessShowsOnlyBooleanAndDoesNotLeakJdbcFailures(){
    OperationsMetrics metrics=new OperationsMetrics();
    var success=new OperationsApi(healthyDatabase(),metrics);
    var ready=success.ready();
    assertEquals(HttpStatus.OK,ready.getStatusCode());
    assertEquals(Map.of("ready",true),ready.getBody());
    assertEquals("no-store",ready.getHeaders().getCacheControl());

    var failed=new OperationsApi(new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:tcp://127.0.0.1:9/invalid","sa","")),metrics);
    var down=failed.ready();
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE,down.getStatusCode());
    assertEquals(Map.of("ready",false),down.getBody());
    String body=String.valueOf(down.getBody());
    assertFalse(body.contains("127.0.0.1"));
    assertFalse(body.contains("jdbc:"));
    assertEquals("degraded",failed.status().getBody().get("state"));
    assertEquals("no-store",failed.status().getHeaders().getCacheControl());
  }

  @Test void operatorMetricsAreBoundedAndContainNoRequestsOrIdentity()throws Exception{
    OperationsMetrics metrics=new OperationsMetrics();
    var statuses=List.of(200,201,400,401,403,429,500);
    for(int code:statuses){
      var req=new MockHttpServletRequest("GET","/api/datasets/secret-id");
      req.addParameter("password","do-not-show");
      req.addHeader("Authorization","Bearer do-not-show");
      var res=new MockHttpServletResponse();
      metrics.preHandle(req,res,new Object());
      res.setStatus(code);
      metrics.afterCompletion(req,res,new Object(),null);
    }
    Map<String,Object> s=metrics.snapshot();
    assertEquals(7L,s.get("requestCount"));
    assertEquals(2L,s.get("successCount"));
    assertEquals(4L,s.get("clientErrorCount"));
    assertEquals(1L,s.get("serverErrorCount"));
    assertEquals(2L,s.get("accessDeniedCount"));
    assertTrue(((Number)s.get("uptimeSeconds")).longValue()>=0);
    String raw=s.toString();
    for(String forbidden:List.of("secret-id","do-not-show","password",
      "Authorization","/api/datasets"))assertFalse(raw.contains(forbidden));

    var op=new OperationsApi(healthyDatabase(),metrics);
    var status=op.status();
    assertEquals("healthy",status.getBody().get("state"));
    assertEquals(7L,((Map<?,?>)status.getBody().get("requests")).get("requestCount"));
    assertEquals(4,((Map<?,?>)status.getBody().get("analysis")).get("concurrencyLimit"));
    assertFalse(status.getBody().containsKey("connectionString"));
  }

  @Test void readerCannotAccessOperationsAndReadinessRequiresNoLogin()throws Exception{
    JdbcTemplate db=healthyDatabase();
    ReaderAccessInterceptor auth=new ReaderAccessInterceptor(db);
    var publicRequest=new MockHttpServletRequest("GET","/api/ready");
    assertTrue(auth.preHandle(publicRequest,new MockHttpServletResponse(),new Object()));
    var readerRequest=new MockHttpServletRequest("GET","/api/admin/operations/status");
    var readerSession=new MockHttpSession();
    readerSession.setAttribute("readerId","viewer");
    readerRequest.setSession(readerSession);
    var denied=new MockHttpServletResponse();
    assertFalse(auth.preHandle(readerRequest,denied,new Object()));
    assertEquals(401,denied.getStatus());
    var adminRequest=new MockHttpServletRequest("GET","/api/admin/operations/status");
    var adminSession=new MockHttpSession();
    adminSession.setAttribute("admin",true);
    adminRequest.setSession(adminSession);
    assertTrue(auth.preHandle(adminRequest,new MockHttpServletResponse(),new Object()));
  }

  @Test void adminSourceStateDoesNotRevealHostsAndCooldownPreventsRepeatedReconnect()throws Exception{
    JdbcTemplate db=healthyDatabase();
    var store=new DatabaseStore(db,new com.fasterxml.jackson.databind.ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    db.update("INSERT INTO insight_connection(id,name,engine,host,port,db_name,username,credential) "+
      "VALUES(?,?,?,?,?,?,?,?)","source1","经营仓库","starrocks","internal-private.example",
      9030,"warehouse","svc-reader",store.encrypt("not-for-dashboard"));
    var guard=new com.insightbi.analysis.SourceRecoveryGuard();
    var operator=new OperationsApi(db,new OperationsMetrics(),store,guard);
    var records=operator.sources().getBody();
    assertEquals(1,records.size());
    assertEquals("经营仓库",records.get(0).get("name"));
    String safe=records.toString();
    assertFalse(safe.contains("internal-private.example"));
    assertFalse(safe.contains("not-for-dashboard"));
    assertFalse(safe.contains("svc-reader"));
    for(int i=0;i<2;i++)
      assertThrows(com.insightbi.analysis.SourceRecoveryGuard.Unavailable.class,
        ()->guard.execute("source1",()->{
          throw new java.sql.SQLTransientConnectionException("sensitive-host", "08001");
        }));
    assertEquals("open",operator.sources().getBody().get(0).get("state"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE,operator.recheck("source1").getStatusCode());
    assertFalse(operator.recheck("source1").getBody().toString().contains("sensitive-host"));
    var auth=new ReaderAccessInterceptor(db);
    var request=new MockHttpServletRequest("POST","/api/admin/operations/sources/source1/recheck");
    var session=new MockHttpSession();
    session.setAttribute("admin",true);
    session.setAttribute("csrf","secret-csrf");
    request.setSession(session);
    var denied=new MockHttpServletResponse();
    assertFalse(auth.preHandle(request,denied,new Object()));
    assertEquals(403,denied.getStatus());
    request.addHeader("X-CSRF-Token","secret-csrf");
    assertTrue(auth.preHandle(request,new MockHttpServletResponse(),new Object()));
  }

  @Test void operationalViewSeparatesJdbcTimeoutsAndGlobalErrorIsSanitized(){
    var telemetry=new com.insightbi.analysis.AnalysisExecutionMetrics();
    telemetry.record(System.nanoTime(),new java.sql.SQLTimeoutException(
      "jdbc:mysql://internal-host:9030 password=confidential","HYT00"));
    var operations=new OperationsApi(healthyDatabase(),new OperationsMetrics(),
      null,null,telemetry);
    var execution=(Map<?,?>)operations.status().getBody().get("queryExecution");
    assertEquals(1L,execution.get("timedOutQueries"));
    assertEquals(1L,execution.get("failedQueries"));
    assertFalse(execution.toString().contains("internal-host"));
    var response=new ApiErrors().timeout(new java.sql.SQLTimeoutException(
      "jdbc:mysql://internal-host:9030 password=confidential"));
    assertEquals(504,response.getStatusCode().value());
    assertFalse(response.getBody().toString().contains("confidential"));
  }

  @Test void queryLimiterReportsLoadAndRejectionsAndRestoresPermit(){
    AnalysisQueryLimiter limiter=new AnalysisQueryLimiter(1,0);
    assertEquals(0,limiter.snapshot().active());
    try(AnalysisQueryLimiter.Lease lease=limiter.acquire()){
      assertEquals(1,limiter.snapshot().active());
      assertThrows(AnalysisQueryLimiter.BusyException.class,limiter::acquire);
      assertEquals(1,limiter.snapshot().rejected());
    }
    assertEquals(0,limiter.snapshot().active());
    assertEquals(0,limiter.snapshot().waiting());
    assertEquals(1,limiter.snapshot().capacity());
    assertEquals(429,new ApiErrors().busy(
      new AnalysisQueryLimiter.BusyException("no sql here")).getStatusCode().value());
  }
}
