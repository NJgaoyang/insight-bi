package com.insightbi.analysis;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/**
 * Process-local transient JDBC connection circuit breaker for V2 analysis.
 * Never retries SQL or keeps SQL/host/credentials in memory.
 * Two consecutive connection failures open the source for 30 seconds.
 * After cooldown exactly one half-open attempt is allowed.
 */
@Component
public class SourceRecoveryGuard {
  public static final class Unavailable extends RuntimeException {
    public Unavailable(){super("数据源暂不可用，请稍后重试");}
  }
  @FunctionalInterface public interface CheckedOperation<T>{
    T run()throws Exception;
  }
  public record Status(String state,int consecutiveFailures,long retryAfterSeconds){}

  private static final int MAX_TRACKED=500;
  private static final long COOLDOWN_NANOS=TimeUnit.SECONDS.toNanos(30);
  private final ConcurrentHashMap<String,Entry> entries=new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public SourceRecoveryGuard(){this(System::nanoTime);}
  // Package-private clock injection keeps the failure and recovery tests deterministic.
  SourceRecoveryGuard(LongSupplier clock){this.clock=clock;}
  private static final class Entry {
    int failures;
    long openUntil;
    boolean halfOpen;
  }

  public <T> T execute(String sourceId,CheckedOperation<T> operation)throws Exception{
    Entry entry=entry(sourceId);
    boolean probe=false;
    synchronized(entry){
      if(entry.openUntil!=0){
        if(clock.getAsLong()-entry.openUntil<0||entry.halfOpen)
          throw new Unavailable();
        entry.halfOpen=true;
        probe=true;
      }
    }
    try{
      T result=operation.run();
      synchronized(entry){entry.failures=0;entry.openUntil=0;entry.halfOpen=false;}
      return result;
    }catch(Exception failure){
      if(!isConnectionError(failure)){
        // A query syntax/validation failure must not trip a connection circuit.
        if(probe)synchronized(entry){entry.failures=0;entry.openUntil=0;entry.halfOpen=false;}
        throw failure;
      }
      synchronized(entry){
        entry.failures=Math.min(entry.failures+1,2);
        if(entry.failures>=2||probe){
          entry.openUntil=clock.getAsLong()+COOLDOWN_NANOS;
        }
        entry.halfOpen=false;
      }
      throw new Unavailable();
    }
  }

  public Status snapshot(String sourceId){
    Entry entry=entries.get(sourceId);
    if(entry==null)return new Status("closed",0,0);
    synchronized(entry){
      if(entry.openUntil==0)return new Status("closed",entry.failures,0);
      long remaining=entry.openUntil-clock.getAsLong();
      if(remaining>0)
        return new Status("open",entry.failures,
          Math.max(1,TimeUnit.NANOSECONDS.toSeconds(remaining+999_999_999L)));
      return new Status(entry.halfOpen?"checking":"ready_to_retry",entry.failures,0);
    }
  }

  public void clear(String sourceId){if(sourceId!=null)entries.remove(sourceId);}

  private Entry entry(String id){
    if(id==null||id.isBlank()||id.length()>128)throw new IllegalArgumentException("数据源标识无效");
    Entry found=entries.get(id);
    if(found!=null)return found;
    if(entries.size()>=MAX_TRACKED)throw new Unavailable();
    return entries.computeIfAbsent(id,ignored->new Entry());
  }

  static boolean isConnectionError(Throwable error){
    for(Throwable current=error;current!=null;current=current.getCause()){
      if(current instanceof SQLTransientConnectionException||
         current instanceof SQLRecoverableException)return true;
      if(current instanceof SQLException sql){
        String state=sql.getSQLState();
        if(state!=null&&state.startsWith("08"))return true;
      }
      if(current.getCause()==current)break;
    }
    return false;
  }
}
