package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalysisQueryLimiterTest {
  @Test void slotsAreBoundedFairAndReleasedExactlyOnce(){
    AnalysisQueryLimiter limiter=new AnalysisQueryLimiter(2,0);
    var first=limiter.acquire();
    var second=limiter.acquire();
    assertThrows(AnalysisQueryLimiter.BusyException.class,limiter::acquire);
    second.close();
    second.close(); // idempotent: must not over-release
    try(var third=limiter.acquire()){
      assertThrows(AnalysisQueryLimiter.BusyException.class,limiter::acquire);
    }
    try(var fourth=limiter.acquire()){
      assertThrows(AnalysisQueryLimiter.BusyException.class,limiter::acquire);
    }
    first.close();
    try(var a=limiter.acquire();var b=limiter.acquire()){
      assertThrows(AnalysisQueryLimiter.BusyException.class,limiter::acquire);
    }
  }

  @Test void controllerAdviceMapsBusyAndProtectsDatabaseDetails(){
    var handler=new AnalysisErrorHandler();
    var busy=handler.busy(new AnalysisQueryLimiter.BusyException("分析查询繁忙，请稍后重试"));
    assertEquals(429,busy.getStatusCode().value());
    assertEquals("分析查询繁忙，请稍后重试",busy.getBody().get("error"));
    assertEquals(400,handler.invalid(new IllegalArgumentException("无效字段")).getStatusCode().value());
    assertEquals(404,handler.missing(new NoSuchElementException("不存在")).getStatusCode().value());
    var jdbc=handler.unexpected(new SQLException("password=secret jdbc:mysql://host:9030/ods SELECT * FROM private_table"));
    assertEquals(502,jdbc.getStatusCode().value());
    String body=String.valueOf(jdbc.getBody());
    assertFalse(body.contains("secret"));
    assertFalse(body.contains("SELECT"));
    assertFalse(body.contains("9030"));
    var state=handler.conflict(new IllegalStateException("password=not-safe"));
    assertEquals(409,state.getStatusCode().value());
    assertFalse(String.valueOf(state.getBody()).contains("not-safe"));
  }

  @Test void badConcurrencyConfigurationsAreRejected(){
    assertThrows(IllegalArgumentException.class,()->new AnalysisQueryLimiter(0,250));
    assertThrows(IllegalArgumentException.class,()->new AnalysisQueryLimiter(65,250));
    assertThrows(IllegalArgumentException.class,()->new AnalysisQueryLimiter(4,-1));
  }
}
