package com.insightbi.analysis;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local admission control for expensive V2 JDBC queries.
 * Never occupies a JDBC connection while waiting for a slot.
 */
public final class AnalysisQueryLimiter {
  private final Semaphore permits;
  private final long waitMillis;
  private final int capacity;
  private final AtomicInteger waiting=new AtomicInteger();
  private final LongAdder rejected=new LongAdder();

  public AnalysisQueryLimiter(int concurrentQueries,long waitMillis){
    if(concurrentQueries<1||concurrentQueries>64||waitMillis<0||waitMillis>10000)
      throw new IllegalArgumentException("分析查询并发控制参数无效");
    this.capacity=concurrentQueries;
    this.permits=new Semaphore(concurrentQueries,true);
    this.waitMillis=waitMillis;
  }
  public Lease acquire(){
    waiting.incrementAndGet();
    try{
      if(!permits.tryAcquire(waitMillis,TimeUnit.MILLISECONDS)){
        rejected.increment();
        throw new BusyException("分析查询繁忙，请稍后重试");
      }
      return new Lease(permits);
    }catch(InterruptedException e){
      Thread.currentThread().interrupt();
      rejected.increment();
      throw new BusyException("分析查询已取消");
    }finally{waiting.decrementAndGet();}
  }
  /** Immutable approximate process-local snapshot; no query or SQL content. */
  public record Snapshot(int capacity,int active,int waiting,long rejected){}
  public Snapshot snapshot(){
    return new Snapshot(capacity,capacity-permits.availablePermits(),
      waiting.get(),rejected.sum());
  }
  public static final class BusyException extends RuntimeException{
    public BusyException(String message){super(message);}
  }
  public static final class Lease implements AutoCloseable{
    private final Semaphore permits;
    private final AtomicBoolean closed=new AtomicBoolean();
    private Lease(Semaphore permits){this.permits=permits;}
    @Override public void close(){
      if(closed.compareAndSet(false,true))permits.release();
    }
  }
}
