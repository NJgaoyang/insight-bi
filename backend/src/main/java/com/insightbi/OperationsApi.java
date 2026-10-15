package com.insightbi;

import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.SourceRecoveryGuard;
import com.insightbi.analysis.ReadOnlyConnectionPolicy;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Statement;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
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
  private final DatabaseStore store;
  private final SourceRecoveryGuard recovery;
  OperationsApi(JdbcTemplate db,OperationsMetrics metrics){
    this.db=db;this.metrics=metrics;this.store=null;this.recovery=null;
  }
  @org.springframework.beans.factory.annotation.Autowired
  OperationsApi(JdbcTemplate db,OperationsMetrics metrics,DatabaseStore store,
                SourceRecoveryGuard recovery){
    this.db=db;this.metrics=metrics;this.store=store;this.recovery=recovery;
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

  @GetMapping("/admin/operations/sources")
  ResponseEntity<List<Map<String,Object>>> sources(){
    List<Map<String,Object>> configured=db.queryForList(
      "SELECT id,name FROM insight_connection ORDER BY name,id LIMIT 200");
    List<Map<String,Object>> visible=new ArrayList<>();
    for(Map<String,Object> item:configured){
      String id=String.valueOf(item.get("id"));
      var state=recovery.snapshot(id);
      visible.add(Map.of("id",id,"name",String.valueOf(item.get("name")),
        "state",state.state(),"consecutiveFailures",state.consecutiveFailures(),
        "retryAfterSeconds",state.retryAfterSeconds()));
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visible);
  }

  /**
   * Manual recovery only checks JDBC connectivity (SELECT 1); never retries
   * a failed analytics SQL query and never leaks source credentials.
   * While open, rechecks are denied until the cooldown expires.
   */
  @PostMapping("/admin/operations/sources/{id}/recheck")
  ResponseEntity<Map<String,Object>> recheck(@PathVariable String id){
    if(id==null||!id.matches("[a-zA-Z0-9_-]{1,64}"))
      throw new IllegalArgumentException("数据源标识无效");
    try{
      boolean okay=recovery.execute(id,()->{
        try(Connection conn=store.open(store.connection(id))){
          ReadOnlyConnectionPolicy.require(conn);
          try(Statement stmt=conn.createStatement()){
            stmt.setQueryTimeout(3);
            try(var rs=stmt.executeQuery("SELECT 1")){
              return rs.next()&&rs.getInt(1)==1;
            }
          }
        }
      });
      if(!okay)throw new SourceRecoveryGuard.Unavailable();
      return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(Map.of("ready",true,"state",recovery.snapshot(id).state()));
    }catch(SourceRecoveryGuard.Unavailable e){
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .cacheControl(CacheControl.noStore())
        .body(Map.of("ready",false,"state",recovery.snapshot(id).state()));
    }catch(Exception unexpected){
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).cacheControl(CacheControl.noStore())
        .body(Map.of("ready",false,"state",recovery.snapshot(id).state()));
    }
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
