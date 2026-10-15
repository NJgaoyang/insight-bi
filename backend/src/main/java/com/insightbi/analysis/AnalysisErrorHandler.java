package com.insightbi.analysis;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Consistent author-facing errors for only the new analysis endpoints.
 * Never expose JDBC exceptions, SQL text, hostnames or credentials.
 * Legacy V1 controller retains its existing error behavior.
 */
@RestControllerAdvice(assignableTypes=AnalysisApi.class)
public class AnalysisErrorHandler {
  @ExceptionHandler(SourceRecoveryGuard.Unavailable.class)
  public ResponseEntity<Map<String,String>> sourceUnavailable(SourceRecoveryGuard.Unavailable e){
    return result(HttpStatus.SERVICE_UNAVAILABLE,"数据源暂不可用，请稍后重试");
  }
  @ExceptionHandler(AnalysisQueryLimiter.BusyException.class)
  public ResponseEntity<Map<String,String>> busy(AnalysisQueryLimiter.BusyException e){
    return result(HttpStatus.TOO_MANY_REQUESTS,e.getMessage());
  }
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Map<String,String>> missing(NoSuchElementException e){
    return result(HttpStatus.NOT_FOUND,e.getMessage());
  }
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){
    return result(HttpStatus.BAD_REQUEST,e.getMessage());
  }
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String,String>> conflict(IllegalStateException e){
    String message=e.getMessage();
    // Some third-party drivers use IllegalStateException; do not echo them.
    if(message==null||(!message.startsWith("计算字段")&&!message.startsWith("此计算字段")&&
       !message.startsWith("修改会破坏")&&!message.startsWith("分析查询")))
      message="操作状态已变化，请刷新后重试";
    return result(HttpStatus.CONFLICT,message);
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String,String>> unexpected(Exception e){
    return result(HttpStatus.BAD_GATEWAY,"分析查询失败，请检查数据源或稍后重试");
  }
  private static ResponseEntity<Map<String,String>> result(HttpStatus status,String message){
    return ResponseEntity.status(status).body(Map.of("error",message==null?"操作失败":message));
  }
}
