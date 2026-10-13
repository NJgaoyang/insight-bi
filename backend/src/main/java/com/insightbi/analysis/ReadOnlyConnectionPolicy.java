package com.insightbi.analysis;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Fail closed before V2 SQL execution if JDBC cannot enter read-only mode.
 * Note: JDBC readOnly is only a driver hint. Deployment must also use a DB
 * account without INSERT/UPDATE/DELETE/DDL/ADMIN privileges.
 */
public final class ReadOnlyConnectionPolicy {
  private ReadOnlyConnectionPolicy(){}

  public static void require(Connection connection){
    try{
      connection.setReadOnly(true);
      if(!connection.isReadOnly())
        throw new IllegalStateException("数据源连接未进入 JDBC 只读状态，已拒绝分析查询");
    }catch(SQLException error){
      throw new IllegalStateException("数据源不支持强制 JDBC 只读，已拒绝分析查询");
    }
  }
}
