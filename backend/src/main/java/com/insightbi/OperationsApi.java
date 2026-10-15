package com.insightbi;

import com.insightbi.analysis.AnalysisApi;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * /api/ready: public minimal readiness only (no connection details).
 * /api/admin/operations/status: existing admin session + CSRF interceptor.
 * No query text, credentials, IP addresses, user identities or hostnames.
 */
@RestController
@RequestMapping("/api")
class OperationsApi {
  private final JdbcTemplate db;
  private final OperationsMetrics metrics;
  OperationsApi(JdbcTemplate db,OperationsMetrics metrics){
    this.db=db;this.metrics=metrics;
  }

  @GetMapping("/ready")
  ResponseEntity<Map<String,Object>> ready(){
    boolean databaseReady=probeDatabase();
    return ResponseEntity.status(databaseReady?HttpStatus.OK:HttpStatus.SERVICE_UNAVAILABLE)
      .cacheControl(CacheControl.noStore())
      .body(Map.of("ready",databaseReady));
  }

  @GetMapping("/admin/operations/status")
  ResponseEntity<Map<String,Object>> status(){
    long start=System.nanoTime();
    boolean dbReady=probeDatabase();
    long latency=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
    var state=AnalysisApi.queryAdmissionSnapshot();
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("state",dbReady?"healthy":"degraded");
    result.put("database",Map.of("ready",dbReady,"probeMs",latency));
    result.put("requests",metrics.snapshot());
    result.put("analysis",Map.of("concurrencyLimit",state.capacity(),
      "activeQueries",state.active(),"waitingQueries",state.waiting(),
      "rejectedQueries",state.rejected()));
    Map<String,Object> pool=poolSnapshot();
    if(!pool.isEmpty())result.put("connectionPool",pool);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }

  private boolean probeDatabase(){
    try{
      return Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>)c->{
        try(Statement stmt=c.createStatement()){
          stmt.setQueryTimeout(2);
          try(var result=stmt.executeQuery("SELECT 1")){
            return result.next()&&result.getInt(1)==1;
          }
        }
      }));
    }catch(Exception failure){
      // Never send JDBC errors/connection details to public readiness callers.
      return false;
    }
  }

  private Map<String,Object> poolSnapshot(){
    DataSource dataSource=db.getDataSource();
    if(!(dataSource instanceof HikariDataSource hikari))return Map.of();
    HikariPoolMXBean pool=hikari.getHikariPoolMXBean();
    if(pool==null)return Map.of();
    return Map.of("active",pool.getActiveConnections(),
      "idle",pool.getIdleConnections(),
      "pending",pool.getThreadsAwaitingConnection(),
      "total",pool.getTotalConnections());
  }
}
