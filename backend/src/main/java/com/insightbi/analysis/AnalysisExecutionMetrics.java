package com.insightbi.analysis;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Process-local, constant-memory query execution counters.
 * Stores no dataset ID, SQL, bind values, credentials or exception messages.
 */
@Component
public final class AnalysisExecutionMetrics {
  private final LongAdder completed=new LongAdder();
  private final LongAdder failed=new LongAdder();
  private final LongAdder timedOut=new LongAdder();
  private final LongAdder slow=new LongAdder();
  private final LongAdder executionMillis=new LongAdder();

  public void record(long startedNanos,Exception error){
    long duration=Math.max(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedNanos));
    executionMillis.add(duration);
    if(duration>=3000)slow.increment();
    if(error==null)completed.increment();
    else{
      failed.increment();
      if(isTimeout(error))timedOut.increment();
    }
  }

  public Map<String,Object> snapshot(){
    return Map.of(
      "completedQueries",completed.sum(),
      "failedQueries",failed.sum(),
      "timedOutQueries",timedOut.sum(),
      "slowQueries",slow.sum(),
      "totalExecutionMs",executionMillis.sum());
  }

  public static boolean isTimeout(Throwable error){
    int depth=0;
    for(Throwable current=error;current!=null&&depth++<12;current=current.getCause()){
      if(current instanceof SQLTimeoutException)return true;
      if(current instanceof SQLException sql){
        String state=sql.getSQLState();
        if("HYT00".equalsIgnoreCase(state)||"HYT01".equalsIgnoreCase(state))
          return true;
      }
      if(current==current.getCause())break;
    }
    return false;
  }
}
