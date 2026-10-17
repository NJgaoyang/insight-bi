package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Only test code opens and inspects a prepared JOIN; no HTTP or live source. */
class SemanticRelationRlsExecutionLifecycleTest {
  private record F(JdbcTemplate db,DataSourceTransactionManager transactions,
    DatabaseStore store,FieldCatalog catalog,String relationId,
    Map<String,FieldCatalog.Field> left,Map<String,FieldCatalog.Field> right,
    SemanticRelationRlsPlanPreview.Preview plan,
    SemanticRelationRlsBindPreflight.Attestation binds,
    DriverManagerDataSource source){}
  private static final String READER="lifecycle_reader";

  private static F fixture(){
    var data=new DriverManagerDataSource(
      "jdbc:h2:mem:rls_lifecycle_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000","sa","");
    var db=new JdbcTemplate(data);
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    db.update("INSERT INTO insight_connection(id,name,engine,host,port,db_name,"+
      "username,credential) VALUES(?,?,?,?,?,?,?,?)",
      "same-isolated-test-source","Isolated","mysql","source.invalid",3306,
      "analytics","reader",store.encrypt("test-credential"));
    db.execute("CREATE TABLE lifecycle_orders(city VARCHAR(40),tenant VARCHAR(40))");
    db.execute("CREATE TABLE lifecycle_cities(city VARCHAR(40),tenant VARCHAR(40))");
    db.update("INSERT INTO lifecycle_orders VALUES('Paris','A')");
    db.update("INSERT INTO lifecycle_orders VALUES('Rome','A')");
    db.update("INSERT INTO lifecycle_orders VALUES('Paris','B')");
    db.update("INSERT INTO lifecycle_cities VALUES('Paris','B')");
    for(var entry:List.of(new String[]{"left","lifecycle_orders"},
                          new String[]{"right","lifecycle_cities"}))
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
        "VALUES(?,?,?,?,?)",entry[0],entry[0],"same-isolated-test-source",
        "SELECT city,tenant FROM "+entry[1],
        store.json(List.of(Map.of("name","city","type","VARCHAR"),
          Map.of("name","tenant","type","VARCHAR"))));
    var catalog=new FieldCatalog(store,db);
    var lf=FieldCatalog.index(catalog.fields("left"));
    var rf=FieldCatalog.index(catalog.fields("right"));
    String lc=field(lf,"city"),rc=field(rf,"city"),lt=field(lf,"tenant"),
      rt=field(rf,"tenant");
    db.update("INSERT INTO insight_governance_user "+
      "(id,username,display_name,role,enabled) VALUES(?,?,?,?,?)",
      READER,READER,"Lifecycle reader","VIEWER",true);
    db.update("INSERT INTO insight_reader_row_policy "+
      "(user_id,dataset_id,field_id,allowed_value) VALUES(?,?,?,?)",
      READER,"left",lt,"A");
    db.update("INSERT INTO insight_reader_row_policy "+
      "(user_id,dataset_id,field_id,allowed_value) VALUES(?,?,?,?)",
      READER,"right",rt,"B");
    var api=new SemanticRelationApi(store,db,catalog);
    var relation=api.create(Map.of("name","Lifecycle approved sources",
      "sourceDatasetId","left","targetDatasetId","right",
      "pairs",List.of(Map.of("leftFieldId",lc,"rightFieldId",rc))));
    api.approve(relation.id(),Map.of("revision",1));
    var plan=SemanticRelationRlsPlanPreview.compile(db,READER,
      store.dataset("left"),store.dataset("right"),"left","right",
      List.of(new SemanticRelationApi.Pair(lc,rc)),lf,rf,1,
      Map.of("joinType","LEFT","leftFieldIds",List.of(lc),"rightFieldIds",List.of(rc)));
    var binds=SemanticRelationRlsBindPreflight.inspect(db,READER,
      "left","right",lf,rf,plan);
    return new F(db,new DataSourceTransactionManager(data),
      store,catalog,relation.id(),lf,rf,plan,binds,data);
  }
  private static String field(Map<String,FieldCatalog.Field> fields,String column){
    return fields.values().stream().filter(f->column.equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
  }
  private static TransactionTemplate updateTransaction(F f){
    var tx=new TransactionTemplate(f.transactions());
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }
  /** H2 is the only database. Proxy simulates a MySQL grant response and a
      truthful JDBC read-only hint strictly inside this isolated test fixture. */
  private static Connection simulatedSource(Connection jdbc,boolean allow,
                                            AtomicBoolean closed,boolean wrongCatalog){
    AtomicBoolean readOnly=new AtomicBoolean();
    return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),
      new Class<?>[]{Connection.class},(proxy,method,args)->{
        String op=method.getName();
        if(op.equals("getCatalog"))return wrongCatalog?"unapproved":"analytics";
        if(op.equals("setReadOnly")){
          readOnly.set((Boolean)args[0]);return null;
        }
        if(op.equals("isReadOnly"))return readOnly.get();
        if(op.equals("createStatement")){
          var grants=allow?List.of(
              "GRANT USAGE ON *.* TO 'reader'@'%'",
              "GRANT SELECT ON analytics.* TO 'reader'@'%'"):
            List.of("GRANT SELECT, UPDATE ON analytics.* TO 'reader'@'%'");
          AtomicInteger index=new AtomicInteger();
          ResultSet result=(ResultSet)Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},
            (p,m,a)->switch(m.getName()){
              case "next" -> index.getAndIncrement()<grants.size();
              case "getString" -> grants.get(index.get()-1);
              case "close" -> null;
              default -> throw new IllegalStateException("Unexpected grants cursor call");
            });
          return Proxy.newProxyInstance(Statement.class.getClassLoader(),
            new Class<?>[]{Statement.class},(p,m,a)->{
              if(m.getName().equals("executeQuery")&&"SHOW GRANTS".equals(a[0]))
                return result;
              if(m.getName().equals("close"))return null;
              throw new IllegalStateException("Unexpected grant statement operation");
            });
        }
        if(op.equals("close")){
          try{return method.invoke(jdbc,args);}
          catch(InvocationTargetException e){throw e.getCause();}
          finally{closed.set(true);}
        }
        try{return method.invoke(jdbc,args);}
        catch(InvocationTargetException e){throw e.getCause();}
      });
  }
  private static void inspect(F f,String engine,boolean grants,
      AtomicBoolean closed,SemanticRelationRlsExecutionLifecycle.StatementProbe probe){
    SemanticRelationRlsExecutionLifecycle.inspect(f.db(),f.transactions(),
      f.store(),f.catalog(),f.relationId(),READER,
      "left","right",f.left(),f.right(),f.plan(),f.binds(),
      ()->simulatedSource(f.source().getConnection(),grants,closed,
        "starrocks".equals(engine)),probe);
  }

  @Test void joinStatementAndSourceClosedBeforeRevocationRowLockReleased()throws Exception{
    var f=fixture();
    var prepared=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    var attempting=new CountDownLatch(1);
    var closed=new AtomicBoolean();
    var pool=Executors.newFixedThreadPool(2);
    try{
      Future<?> source=pool.submit(()->inspect(f,"mysql",true,closed,stmt->{
        assertEquals(5,stmt.getQueryTimeout());
        assertEquals(200,stmt.getMaxRows());
        assertEquals(32,stmt.getFetchSize());
        // Test-only values demonstrate the outer-RLS LEFT JOIN semantics.
        stmt.setString(1,"A");stmt.setString(2,"B");
        try(var rows=stmt.executeQuery()){
          List<String> values=new ArrayList<>();
          while(rows.next())values.add(rows.getString(1)+":"+
            (rows.getString(2)==null?"NULL":rows.getString(2)));
          assertTrue(values.contains("Paris:Paris"));
          assertTrue(values.contains("Rome:NULL"));
          assertEquals(2,values.size());
        }
        prepared.countDown();
        try{
          if(!release.await(3,TimeUnit.SECONDS))
            throw new SQLException("test timed out before source completion");
        }catch(InterruptedException e){
          Thread.currentThread().interrupt();
          throw new SQLException("test interrupted",e);
        }
      }));
      assertTrue(prepared.await(2,TimeUnit.SECONDS));
      Future<Boolean> revoke=pool.submit(()->updateTransaction(f).execute(status->{
        attempting.countDown();
        SemanticRelationRlsRevocationGate.lockForGovernanceMutation(f.db(),READER);
        boolean closedAtLockAcquisition=closed.get();
        f.db().update("UPDATE insight_governance_user SET enabled=FALSE, "+
          "auth_version=auth_version+1 WHERE id=?",READER);
        return closedAtLockAcquisition;
      }));
      assertTrue(attempting.await(2,TimeUnit.SECONDS));
      assertThrows(TimeoutException.class,()->revoke.get(150,TimeUnit.MILLISECONDS));
      assertFalse(closed.get());
      release.countDown();
      source.get(3,TimeUnit.SECONDS);
      assertTrue(revoke.get(3,TimeUnit.SECONDS));
      assertTrue(closed.get());
      assertThrows(IllegalStateException.class,()->
        inspect(f,"mysql",true,new AtomicBoolean(),stmt->fail("must not prepare")));
    }finally{
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test void unauthorizedGrantsOrWrongActualCatalogNeverReachPreparedCallback(){
    var f=fixture();
    var callbackCount=new AtomicInteger();
    var close=new AtomicBoolean();
    assertThrows(IllegalStateException.class,()->
      inspect(f,"mysql",false,close,stmt->callbackCount.incrementAndGet()));
    assertTrue(close.get());
    assertEquals(0,callbackCount.get());
    close.set(false);
    assertThrows(IllegalStateException.class,()->
      inspect(f,"starrocks",true,close,stmt->callbackCount.incrementAndGet()));
    assertTrue(close.get());
    assertEquals(0,callbackCount.get());
    // Failure must release both metadata row lock and admission permit.
    assertDoesNotThrow(()->inspect(f,"mysql",true,new AtomicBoolean(),stmt->{
      assertEquals(200,stmt.getMaxRows());
    }));
  }

  @Test void callbackFailureRollsBackSourceAndReleasesMetadataLock(){
    var f=fixture();
    var closed=new AtomicBoolean();
    assertThrows(IllegalStateException.class,()->
      inspect(f,"mysql",true,closed,stmt->{
        throw new SQLException("isolated probe failed");
      }));
    assertTrue(closed.get());
    assertDoesNotThrow(()->updateTransaction(f).execute(s->{
      SemanticRelationRlsRevocationGate.lockForGovernanceMutation(f.db(),READER);
      return null;
    }));
  }

  @Test void badPlanIsRejectedBeforeBorrowingSourceConnection(){
    var f=fixture();
    var opened=new AtomicInteger();
    var forged=new SemanticRelationRlsBindPreflight.Attestation(
      0,0,"f".repeat(64));
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsExecutionLifecycle.inspect(f.db(),f.transactions(),
        f.store(),f.catalog(),f.relationId(),READER,
        "left","right",f.left(),f.right(),f.plan(),forged,()->{
          opened.incrementAndGet();
          return f.source().getConnection();
        },stmt->fail("must reject before prepare")));
    assertEquals(0,opened.get());
  }

  @Test void approvalGateRejectsSourceSqlConnectionAndFieldDriftBeforeBorrow(){
    var source=fixture();
    source.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city FROM lifecycle_orders","left");
    mustRejectBeforeSource(source);
    var connector=fixture();
    connector.db().update("UPDATE insight_connection SET host=? WHERE id=?",
      "changed.invalid","same-isolated-test-source");
    mustRejectBeforeSource(connector);
    var credential=fixture();
    credential.db().update("UPDATE insight_connection SET credential=? WHERE id=?",
      credential.store().encrypt("new-secret"),"same-isolated-test-source");
    mustRejectBeforeSource(credential);
    var field=fixture();
    field.db().update("UPDATE insight_dataset_field SET hidden=TRUE "+
      "WHERE dataset_id=? AND source_name=?","right","city");
    mustRejectBeforeSource(field);
  }

  @Test void approvalGateRejectsDeletedConnectionAndStaleRelationBeforeBorrow(){
    var missing=fixture();
    missing.db().update("DELETE FROM insight_connection WHERE id=?",
      "same-isolated-test-source");
    mustRejectBeforeSource(missing);
    var noApproval=fixture();
    noApproval.db().update("UPDATE insight_semantic_relation "+
      "SET approved_revision=NULL WHERE id=?",noApproval.relationId());
    mustRejectBeforeSource(noApproval);
    var changedPairs=fixture();
    changedPairs.db().update("UPDATE insight_semantic_relation "+
      "SET pairs_json=? WHERE id=?",changedPairs.store().json(List.of(
        Map.of("leftFieldId",field(changedPairs.left(),"tenant"),
               "rightFieldId",field(changedPairs.right(),"tenant")))),
      changedPairs.relationId());
    mustRejectBeforeSource(changedPairs);
    var revision=fixture();
    revision.db().update("UPDATE insight_semantic_relation "+
      "SET revision=revision+1 WHERE id=?",revision.relationId());
    mustRejectBeforeSource(revision);
  }

  private static void mustRejectBeforeSource(F f){
    var borrowed=new AtomicInteger();
    assertThrows(RuntimeException.class,()->
      SemanticRelationRlsExecutionLifecycle.inspect(
        f.db(),f.transactions(),f.store(),f.catalog(),f.relationId(),READER,
        "left","right",f.left(),f.right(),f.plan(),f.binds(),
        ()->{borrowed.incrementAndGet();return f.source().getConnection();},
        stmt->fail("must reject unapproved source before JDBC")));
    assertEquals(0,borrowed.get());
  }

  @Test void rejectsAmbientTransactionWithoutOpeningSource(){
    var f=fixture();
    var opened=new AtomicInteger();
    assertThrows(IllegalStateException.class,()->updateTransaction(f).execute(s->{
      SemanticRelationRlsExecutionLifecycle.inspect(f.db(),f.transactions(),
        f.store(),f.catalog(),f.relationId(),READER,
        "left","right",f.left(),f.right(),f.plan(),f.binds(),
        ()->{opened.incrementAndGet();return f.source().getConnection();},
        stmt->fail("unexpected"));
      return null;
    }));
    assertEquals(0,opened.get());
  }
}
