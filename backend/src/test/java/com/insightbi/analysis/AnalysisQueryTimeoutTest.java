package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Scoped timeout fault-injection: no real database outage or long waits. */
class AnalysisQueryTimeoutTest {
  @Test void timedOutJdbcClosesStatementAndConnectionReleasesPermitAndAllowsNextQuery()throws Exception{
    String url="jdbc:h2:mem:query_timeout_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    DatabaseStore store=spy(new DatabaseStore(jdbc,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32])));
    store.schema();
    jdbc.execute("CREATE TABLE sales(city VARCHAR(30),revenue INT)");
    jdbc.update("INSERT INTO sales VALUES('北京',150)");
    jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
      "VALUES(?,?,?,?,?)","timeout-dataset","Timeout fixture","conn",
      "SELECT city,revenue FROM sales",
      store.json(List.of(Map.of("name","city","type","VARCHAR"),
        Map.of("name","revenue","type","INT"))));
    var catalog=new FieldCatalog(store,jdbc);
    var columns=catalog.fields("timeout-dataset");
    String city=columns.stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String measure=columns.stream().filter(f->"revenue".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    Map<String,Object> query=Map.of("datasetId","timeout-dataset",
      "dimensions",List.of(Map.of("fieldId",city)),
      "measures",List.of(Map.of("id","m1","fieldId",measure,"aggregate","SUM")),
      "limit",10);

    Connection failing=mock(Connection.class);
    PreparedStatement statement=mock(PreparedStatement.class);
    when(failing.isReadOnly()).thenReturn(true);
    when(failing.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenThrow(
      new SQLTimeoutException("PRIVATE jdbc:mysql://secret-host:9030 password=hidden","HYT00"));
    doReturn(Map.of("id","conn")).when(store).connection("conn");
    AtomicInteger attempts=new AtomicInteger();
    doAnswer(inv->{
      if(attempts.incrementAndGet()==1)return failing;
      Connection good=spy(DriverManager.getConnection(url,"sa",""));
      doReturn(true).when(good).isReadOnly();
      return good;
    }).when(store).open(anyMap());
    var telemetry=new AnalysisExecutionMetrics();
    var breaker=new SourceRecoveryGuard();
    var api=new AnalysisApi(store,jdbc,catalog,breaker,telemetry);

    assertThrows(SQLTimeoutException.class,()->api.query(query));
    verify(statement).setQueryTimeout(15);
    verify(statement).setMaxRows(11);
    verify(statement).close();
    verify(failing).close();
    assertEquals("closed",breaker.snapshot("conn").state(),
      "SQL query timeout must not count as connectivity failure");
    assertEquals(0,AnalysisApi.queryAdmissionSnapshot().active());
    assertEquals(1L,telemetry.snapshot().get("timedOutQueries"));
    assertEquals(1L,telemetry.snapshot().get("failedQueries"));
    assertEquals(0L,telemetry.snapshot().get("completedQueries"));

    var success=api.query(query);
    assertEquals(1,((List<?>)success.get("rows")).size());
    assertEquals(2,attempts.get());
    assertEquals(0,AnalysisApi.queryAdmissionSnapshot().active());
    assertEquals(1L,telemetry.snapshot().get("completedQueries"));
    assertEquals(1L,telemetry.snapshot().get("timedOutQueries"));
    assertEquals(HttpStatus.GATEWAY_TIMEOUT,
      new AnalysisErrorHandler().timeout(
        new SQLTimeoutException("jdbc:mysql://secret-host:9030 password=hidden"))
          .getStatusCode());
    assertFalse(new AnalysisErrorHandler().timeout(
      new SQLTimeoutException("jdbc:mysql://secret-host:9030 password=hidden"))
        .getBody().toString().contains("secret-host"));
  }

  @Test void metricsAreBoundedSlowAndTimeoutDistinctFromDriverConnectionFailures(){
    var metrics=new AnalysisExecutionMetrics();
    metrics.record(System.nanoTime()-TimeUnit.SECONDS.toNanos(4),null);
    metrics.record(System.nanoTime(),new SQLTimeoutException("secret","HYT00"));
    metrics.record(System.nanoTime(),new SQLRecoverableException("network","08001"));
    metrics.record(System.nanoTime(),new SQLException("bad SQL","42000"));
    Map<String,Object> view=metrics.snapshot();
    assertEquals(1L,view.get("completedQueries"));
    assertEquals(3L,view.get("failedQueries"));
    assertEquals(1L,view.get("timedOutQueries"));
    assertEquals(1L,view.get("slowQueries"));
    assertTrue(((Long)view.get("totalExecutionMs"))>=4000);
    for(String secret:List.of("secret","network","bad SQL","jdbc:","password"))
      assertFalse(view.toString().contains(secret));
    assertTrue(AnalysisExecutionMetrics.isTimeout(
      new RuntimeException(new SQLException("timed out","HYT01"))));
    assertFalse(AnalysisExecutionMetrics.isTimeout(
      new SQLException("connection refused","08001")));
  }
}
