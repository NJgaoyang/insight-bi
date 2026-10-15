package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SourceRecoveryGuardTest {
  private static SQLTransientConnectionException disconnected(){
    return new SQLTransientConnectionException("secret-db-host=private", "08001");
  }

  @Test void twoConnectionFailuresOpenOnlyTheImpactedSourceThenAllowOneRecovery()throws Exception{
    AtomicLong nanos=new AtomicLong(TimeUnit.SECONDS.toNanos(100));
    SourceRecoveryGuard guard=new SourceRecoveryGuard(nanos::get);
    AtomicInteger attempts=new AtomicInteger();
    for(int i=0;i<2;i++){
      assertThrows(SourceRecoveryGuard.Unavailable.class,()->
        guard.execute("source-a",()->{attempts.incrementAndGet();throw disconnected();}));
    }
    assertEquals(2,attempts.get());
    assertEquals("open",guard.snapshot("source-a").state());
    assertEquals(30,guard.snapshot("source-a").retryAfterSeconds());
    assertEquals("closed",guard.snapshot("source-b").state());
    String safe=assertThrows(SourceRecoveryGuard.Unavailable.class,()->
      guard.execute("source-a",()->{attempts.incrementAndGet();return "unexpected";}))
      .getMessage();
    assertFalse(safe.contains("secret-db-host"));
    assertEquals(2,attempts.get(),"open circuit must fail fast and never invoke JDBC");
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(29));
    assertThrows(SourceRecoveryGuard.Unavailable.class,()->
      guard.execute("source-a",()->{attempts.incrementAndGet();return true;}));
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
    assertEquals("ready_to_retry",guard.snapshot("source-a").state());
    assertEquals("reconnected",guard.execute("source-a",()->{
      attempts.incrementAndGet();return "reconnected";
    }));
    assertEquals(3,attempts.get());
    assertEquals("closed",guard.snapshot("source-a").state());
    assertEquals(0,guard.snapshot("source-a").consecutiveFailures());
    assertEquals("other",guard.execute("source-b",()->"other"));
  }

  @Test void halfOpenPermitsOnlyOneProbeAndReopensWhenConnectionStillFails()throws Exception{
    AtomicLong nanos=new AtomicLong(TimeUnit.SECONDS.toNanos(1000));
    SourceRecoveryGuard guard=new SourceRecoveryGuard(nanos::get);
    for(int i=0;i<2;i++)assertThrows(SourceRecoveryGuard.Unavailable.class,
      ()->guard.execute("x",()->{throw disconnected();}));
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(31));
    AtomicInteger competingCalls=new AtomicInteger();
    assertThrows(SourceRecoveryGuard.Unavailable.class,()->guard.execute("x",()->{
      assertEquals("checking",guard.snapshot("x").state());
      assertThrows(SourceRecoveryGuard.Unavailable.class,()->guard.execute("x",()->{
        competingCalls.incrementAndGet();return "no";
      }));
      throw disconnected();
    }));
    assertEquals(0,competingCalls.get());
    assertEquals("open",guard.snapshot("x").state());
    assertEquals(30,guard.snapshot("x").retryAfterSeconds());
  }

  @Test void querySyntaxFailureNeverTripsConnectionCircuit()throws Exception{
    var guard=new SourceRecoveryGuard();
    for(int i=0;i<5;i++){
      assertThrows(SQLException.class,()->guard.execute("syntax",()->{
        throw new SQLException("bad SELECT", "42000");
      }));
    }
    assertEquals("closed",guard.snapshot("syntax").state());
    assertEquals(0,guard.snapshot("syntax").consecutiveFailures());
    assertTrue(SourceRecoveryGuard.isConnectionError(new SQLException("lost","08S01")));
    assertFalse(SourceRecoveryGuard.isConnectionError(new SQLException("invalid","42S02")));
    assertThrows(IllegalArgumentException.class,()->guard.execute("../wrong",()->true),
      "bad source identifiers must be rejected");
  }
}
