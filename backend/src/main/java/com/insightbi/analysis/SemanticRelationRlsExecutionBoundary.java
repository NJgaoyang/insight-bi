package com.insightbi.analysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Isolated JDBC preparation boundary for future bilateral RLS JOIN work.
 *
 * NOT connected to any API, service, production connection factory or Reader
 * route. It never executes SQL, loads row-policy values, or grants permission.
 * A valid receipt and per-request authorization must be revalidated in an
 * actual execution transaction BEFORE this can ever serve real users.
 *
 * Read-only JDBC is a driver hint. An external SELECT-only DB account, source
 * resource quotas and cancellation are mandatory for future production use.
 */
final class SemanticRelationRlsExecutionBoundary {
  static final int QUERY_TIMEOUT_SECONDS=5;
  static final int MAX_ROWS=200;
  static final int FETCH_SIZE=32;
  private static final AnalysisQueryLimiter LIMITER=new AnalysisQueryLimiter(2,0);

  private SemanticRelationRlsExecutionBoundary(){}

  /** Admission is deliberately separate from JDBC connection acquisition. */
  static Reservation reserve(SemanticRelationRlsPlanPreview.Preview plan,
                             SemanticRelationRlsBindPreflight.Attestation binds){
    requirePlan(plan,binds);
    return new Reservation(LIMITER.acquire());
  }

  /**
   * Configure a caller-owned connection and prepare a bounded read-only JDBC
   * statement. Does not bind values and DOES NOT CALL executeQuery().
   */
  static PreparedStatement prepare(Connection source,
                                   SemanticRelationRlsPlanPreview.Preview plan,
                                   SemanticRelationRlsBindPreflight.Attestation binds,
                                   Reservation reservation)throws SQLException{
    requirePlan(plan,binds);
    if(reservation==null||!reservation.active())
      throw new IllegalStateException("未持有有效的语义 JOIN 执行隔离额度");
    if(source==null||source.isClosed())
      throw new IllegalStateException("语义 JOIN 数据源连接无效");
    if(source.getAutoCommit())
      throw new IllegalStateException("语义 JOIN 必须由调用方持有显式只读事务");
    ReadOnlyConnectionPolicy.require(source);
    PreparedStatement stmt=source.prepareStatement(plan.sqlPreview(),
      java.sql.ResultSet.TYPE_FORWARD_ONLY,java.sql.ResultSet.CONCUR_READ_ONLY);
    try{
      stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      stmt.setMaxRows(MAX_ROWS);
      stmt.setFetchSize(FETCH_SIZE);
      // Reject drivers that silently ignore essential query-limit settings.
      if(stmt.getQueryTimeout()!=QUERY_TIMEOUT_SECONDS||
         stmt.getMaxRows()!=MAX_ROWS)
        throw new IllegalStateException("数据源驱动没有执行 JOIN 必要的超时或行限制");
      return stmt;
    }catch(SQLException|RuntimeException failure){
      try{stmt.close();}catch(SQLException closeFailure){failure.addSuppressed(closeFailure);}
      throw failure;
    }
  }

  private static void requirePlan(SemanticRelationRlsPlanPreview.Preview plan,
                                  SemanticRelationRlsBindPreflight.Attestation binds){
    if(plan==null||binds==null||
       !"COMPILE_ONLY_DUAL_SOURCE_RLS".equals(plan.scope())||
       !("LEFT".equals(plan.joinType())||"INNER".equals(plan.joinType()))||
       plan.sqlPreview()==null||plan.sqlPreview().length()>100000||
       plan.columns()==null||plan.columns().size()<2||plan.columns().size()>20||
       plan.readerAuthVersion()<0||
       plan.leftBindCount()<0||plan.rightBindCount()<0||
       plan.leftBindCount()>20||plan.rightBindCount()>20||
       plan.totalBindCount()!=plan.leftBindCount()+plan.rightBindCount()||
       binds.leftCount()!=plan.leftBindCount()||
       binds.rightCount()!=plan.rightBindCount()||
       binds.bindingHash()==null||!binds.bindingHash().matches("[0-9a-f]{64}")||
       !plan.sqlPreview().endsWith(" LIMIT 200")||
       plan.sqlPreview().chars().filter(c->c=='?').count()!=plan.totalBindCount())
      throw new IllegalStateException("语义 JOIN 隔离门禁检测到无效 SQL 计划或绑定证明");
    // Mandatory SELECT-only AST check is additive to the source lineage gate.
    ReadOnlySqlGuard.validate(plan.sqlPreview());
  }

  static final class Reservation implements AutoCloseable{
    private final AnalysisQueryLimiter.Lease lease;
    private final AtomicBoolean closed=new AtomicBoolean();
    private Reservation(AnalysisQueryLimiter.Lease lease){this.lease=lease;}
    boolean active(){return !closed.get();}
    @Override public void close(){
      if(closed.compareAndSet(false,true))lease.close();
    }
  }
}
