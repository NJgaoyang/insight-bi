package com.insightbi.analysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * INTERNAL isolation harness for a future bilateral-RLS execution lifecycle.
 * Not injected into Spring endpoints or readers. No SQL or values are exposed.
 *
 * The identity-row lock lives in a separate READ_COMMITTED metadata transaction
 * until AFTER the source statement and connection have been closed. Only
 * compliant governance writers are serialized by this locking protocol.
 *
 * Source SQL and ACL snapshots still require runtime fingerprint revalidation
 * before this can become an actual reader execution path.
 */
final class SemanticRelationRlsExecutionLifecycle {
  @FunctionalInterface interface SourceConnection {
    Connection open() throws SQLException;
  }
  @FunctionalInterface interface StatementProbe {
    void inspect(PreparedStatement prepared) throws SQLException;
  }
  private SemanticRelationRlsExecutionLifecycle(){}

  static void inspect(JdbcTemplate metadata,DataSourceTransactionManager transactions,
                      String reader,String leftId,String rightId,
                      Map<String,FieldCatalog.Field> leftFields,
                      Map<String,FieldCatalog.Field> rightFields,
                      SemanticRelationRlsPlanPreview.Preview plan,
                      SemanticRelationRlsBindPreflight.Attestation expected,
                      String engine,String catalog,SourceConnection source,
                      StatementProbe probe){
    if(TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("不能在不确定的外部事务中嵌套 JOIN 生命周期");
    if(metadata==null||transactions==null||source==null||probe==null||
       engine==null||catalog==null)
      throw new IllegalArgumentException("受控生命周期缺少必要输入");
    // Admission happens before a transaction lock or source JDBC connection.
    try(var reservation=SemanticRelationRlsExecutionBoundary.reserve(plan,expected)){
      var tx=new TransactionTemplate(transactions);
      tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
      tx.setReadOnly(false); // SELECT FOR UPDATE requires a writable metadata txn.
      tx.executeWithoutResult(status->{
        // Lock remains held until this lambda returns and transaction commits.
        SemanticRelationRlsRevocationGate.requireCurrentInTransaction(
          metadata,reader,leftId,rightId,leftFields,rightFields,plan,expected);
        try(Connection connection=source.open()){
          connection.setAutoCommit(false);
          try{
            // Must use the ACTUAL source connection for SHOW GRANTS.
            SemanticRelationRlsSourceGrantGate.require(connection,engine,catalog);
            try(PreparedStatement prepared=SemanticRelationRlsExecutionBoundary.prepare(
                connection,plan,expected,reservation)){
              probe.inspect(prepared); // Test-only; this class executes no SQL itself.
            }
          }finally{
            // Source JDBC is never committed by this metadata proof.
            connection.rollback();
          }
        }catch(SQLException failure){
          throw new IllegalStateException("只读源连接验证、语句准备或关闭失败",failure);
        }
      });
    }
  }
}
