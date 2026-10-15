package com.insightbi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bounded, process-local operational counters. Never stores paths, SQL, inputs,
 * accounts, tokens, client IPs, request bodies or exception messages.
 *
 * Counters reset at restart: they are telemetry, not persistent audit records.
 */
@Component
public final class OperationsMetrics implements HandlerInterceptor {
  private static final String START_NANOS=OperationsMetrics.class.getName()+".startNanos";
  private final long startNanos=System.nanoTime();
  private final String startedAt=Instant.now().toString();
  private final LongAdder total=new LongAdder();
  private final LongAdder successful=new LongAdder();
  private final LongAdder clientErrors=new LongAdder();
  private final LongAdder serverErrors=new LongAdder();
  private final LongAdder denied=new LongAdder();
  private final LongAdder slow=new LongAdder();
  private final AtomicLong lastFailureEpochMs=new AtomicLong(0L);
  private final AtomicLong lastSlowEpochMs=new AtomicLong(0L);

  @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
    if(req.getRequestURI().startsWith("/api/"))
      req.setAttribute(START_NANOS,System.nanoTime());
    return true;
  }

  @Override public void afterCompletion(HttpServletRequest req,HttpServletResponse res,
      Object handler,Exception ex){
    Object started=req.getAttribute(START_NANOS);
    if(!(started instanceof Long began))return;
    total.increment();
    int status=res.getStatus();
    // An exception might escape before an exception handler sets the response.
    if(ex!=null&&status<400)status=500;
    if(status>=500){
      serverErrors.increment();
      lastFailureEpochMs.set(System.currentTimeMillis());
    }else if(status>=400){
      clientErrors.increment();
      if(status==401||status==403)denied.increment();
      if(status>=429)lastFailureEpochMs.set(System.currentTimeMillis());
    }else successful.increment();
    long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
    if(elapsed>=3000){
      slow.increment();
      lastSlowEpochMs.set(System.currentTimeMillis());
    }
  }

  public Map<String,Object> snapshot(){
    long failures=lastFailureEpochMs.get(),slowAt=lastSlowEpochMs.get();
    return Map.of("startedAt",startedAt,
      "uptimeSeconds",TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-startNanos),
      "requestCount",total.sum(),
      "successCount",successful.sum(),
      "clientErrorCount",clientErrors.sum(),
      "serverErrorCount",serverErrors.sum(),
      "accessDeniedCount",denied.sum(),
      "slowRequestCount",slow.sum(),
      "lastFailureAt",failures==0?"":Instant.ofEpochMilli(failures).toString(),
      "lastSlowRequestAt",slowAt==0?"":Instant.ofEpochMilli(slowAt).toString());
  }
}
