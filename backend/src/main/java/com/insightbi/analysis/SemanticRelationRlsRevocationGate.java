package com.insightbi.analysis;

import java.sql.Connection;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal metadata revocation barrier. It does not authorize or execute
 * JOINs. Future callers must keep the transaction and lock throughout any
 * SQL execution; otherwise revocation can race after this check.
 */
public final class SemanticRelationRlsRevocationGate {
  private SemanticRelationRlsRevocationGate(){}

  /** Governance writes must lock this user BEFORE modifying policy rows. */
  public static void lockForGovernanceMutation(JdbcTemplate db,String reader){
    if(db.queryForList(
      "SELECT id FROM insight_governance_user WHERE id=? FOR UPDATE",reader).size()!=1)
      throw new IllegalStateException("读者账户不存在，无法锁定权限");
  }

  static void requireCurrentInTransaction(JdbcTemplate db,String reader,
      String left,String right,Map<String,FieldCatalog.Field> leftFields,
      Map<String,FieldCatalog.Field> rightFields,
      SemanticRelationRlsPlanPreview.Preview plan,
      SemanticRelationRlsBindPreflight.Attestation expected){
    if(!TransactionSynchronizationManager.isActualTransactionActive()||
       TransactionSynchronizationManager.isCurrentTransactionReadOnly()||
       !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED).equals(
         TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
      throw new IllegalStateException("执行前门禁必须位于可写 READ_COMMITTED 元数据事务");
    if(reader==null||left==null||right==null||plan==null||expected==null)
      throw new IllegalStateException("执行前身份及绑定证据缺失");
    lockForGovernanceMutation(db,reader);
    var current=SemanticRelationRlsBindPreflight.inspect(
      db,reader,left,right,leftFields,rightFields,plan);
    if(current.leftCount()!=expected.leftCount()||
       current.rightCount()!=expected.rightCount()||
       !current.bindingHash().equals(expected.bindingHash()))
      throw new IllegalStateException("读者权限已变更，旧绑定证明失效");
  }
}
