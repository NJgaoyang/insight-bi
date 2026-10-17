package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRelationRlsRevocationGateTest {
  private record Fixture(JdbcTemplate db,DataSourceTransactionManager txManager,
      Map<String,FieldCatalog.Field> left,Map<String,FieldCatalog.Field> right,
      SemanticRelationRlsPlanPreview.Preview plan,
      SemanticRelationRlsBindPreflight.Attestation original){}
  private static final String USER="preexec_reader";
  private Fixture fixture(){
    var data=new DriverManagerDataSource(
      "jdbc:h2:mem:preexec_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000","sa","");
    var db=new JdbcTemplate(data);
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    db.execute("CREATE TABLE preexec_l(city VARCHAR(40),tenant VARCHAR(40))");
    db.execute("CREATE TABLE preexec_r(city VARCHAR(40),tenant VARCHAR(40))");
    for(var ds:List.of(new String[]{"left","preexec_l"},new String[]{"right","preexec_r"}))
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
        "VALUES(?,?,?,?,?)",ds[0],ds[0],"test",
        "SELECT city,tenant FROM "+ds[1],
        store.json(List.of(Map.of("name","city","type","VARCHAR"),
          Map.of("name","tenant","type","VARCHAR"))));
    var catalog=new FieldCatalog(store,db);
    var l=FieldCatalog.index(catalog.fields("left"));
    var r=FieldCatalog.index(catalog.fields("right"));
    String lc=find(l,"city"),rc=find(r,"city"),lt=find(l,"tenant");
    db.update("INSERT INTO insight_governance_user "+
      "(id,username,display_name,role,enabled) VALUES(?,?,?,?,?)",
      USER,USER,"Reader","VIEWER",true);
    db.update("INSERT INTO insight_reader_row_policy "+
      "(user_id,dataset_id,field_id,allowed_value) VALUES(?,?,?,?)",
      USER,"left",lt,"A");
    var plan=SemanticRelationRlsPlanPreview.compile(db,USER,
      store.dataset("left"),store.dataset("right"),"left","right",
      List.of(new SemanticRelationApi.Pair(lc,rc)),l,r,1,
      Map.of("joinType","LEFT","leftFieldIds",List.of(lc),"rightFieldIds",List.of(rc)));
    var att=SemanticRelationRlsBindPreflight.inspect(db,USER,"left","right",l,r,plan);
    return new Fixture(db,new DataSourceTransactionManager(data),l,r,plan,att);
  }
  private static String find(Map<String,FieldCatalog.Field> fields,String col){
    return fields.values().stream().filter(f->col.equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
  }
  private static TransactionTemplate tx(Fixture f){
    var t=new TransactionTemplate(f.txManager());
    t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return t;
  }
  private static void check(Fixture f){
    SemanticRelationRlsRevocationGate.requireCurrentInTransaction(
      f.db(),USER,"left","right",f.left(),f.right(),f.plan(),f.original());
  }

  @Test void preflightNeedsWritableReadCommittedTransactionAndCurrentIdentity(){
    var f=fixture();
    assertThrows(IllegalStateException.class,()->check(f));
    var t=tx(f);t.setReadOnly(true);
    assertThrows(IllegalStateException.class,()->t.execute(s->{check(f);return null;}));
    var repeat=tx(f);
    repeat.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThrows(IllegalStateException.class,()->repeat.execute(s->{check(f);return null;}));
    assertDoesNotThrow(()->tx(f).execute(s->{check(f);return null;}));
    f.db().update("UPDATE insight_reader_row_policy SET allowed_value='B' WHERE user_id=?",USER);
    assertThrows(IllegalStateException.class,()->
      tx(f).execute(s->{check(f);return null;}));
  }

  @Test void policyRevocationWaitsForHeldIdentityLock()throws Exception{
    var f=fixture();
    var locked=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    var started=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(2);
    try{
      var reader=pool.submit(()->tx(f).execute(s->{
        check(f);locked.countDown();
        try{
          if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("wait timeout");
        }catch(InterruptedException e){
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
        return null;
      }));
      assertTrue(locked.await(2,TimeUnit.SECONDS));
      var writer=pool.submit(()->tx(f).execute(s->{
        started.countDown();
        SemanticRelationRlsRevocationGate.lockForGovernanceMutation(f.db(),USER);
        f.db().update("UPDATE insight_reader_row_policy SET allowed_value='B' WHERE user_id=?",USER);
        f.db().update("UPDATE insight_governance_user SET auth_version=auth_version+1 "+
          "WHERE id=?",USER);
        return null;
      }));
      assertTrue(started.await(2,TimeUnit.SECONDS));
      assertThrows(TimeoutException.class,()->writer.get(150,TimeUnit.MILLISECONDS));
      release.countDown();
      reader.get(3,TimeUnit.SECONDS);
      writer.get(3,TimeUnit.SECONDS);
      assertThrows(IllegalStateException.class,()->
        tx(f).execute(s->{check(f);return null;}));
    }finally{
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test void grantsMustBeUsageAndOnlySelectedSchema(){
    var rows=List.of("GRANT USAGE ON *.* TO 'report'@'%'",
      "GRANT SELECT ON analytics.* TO 'report'@'%'");
    assertDoesNotThrow(()->SemanticRelationRlsSourceGrantGate.validate("analytics",rows));
    for(var bad:List.of("GRANT SELECT ON *.* TO 'report'@'%'",
       "GRANT INSERT ON analytics.* TO 'report'@'%'",
       "GRANT SELECT, UPDATE ON analytics.* TO 'report'@'%'",
       "GRANT SELECT ON other.* TO 'report'@'%'",
       "GRANT SELECT ON analytics.* TO 'report'@'%' WITH GRANT OPTION",
       "GRANT SELECT ON analytics.* TO 'wrong'@'%'",
       "GRANT role TO 'report'@'%'"))
      assertThrows(IllegalStateException.class,()->
        SemanticRelationRlsSourceGrantGate.validate("analytics",
          List.of(rows.get(0),rows.get(1),bad)),bad);
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsSourceGrantGate.validate("analytics",List.of(rows.get(0))));
  }

  @Test void sourceGrantProofIsConnectionScopedAndRejectsStarRocks()throws Exception{
    var grants=List.of("GRANT USAGE ON *.* TO 'report'@'%'",
      "GRANT SELECT ON analytics.* TO 'report'@'%'");
    final int[] index={0};
    ResultSet result=(ResultSet)Proxy.newProxyInstance(
      ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},
      (p,m,a)->{
        if(m.getName().equals("next"))return index[0]++<grants.size();
        if(m.getName().equals("getString"))return grants.get(index[0]-1);
        if(m.getName().equals("close"))return null;
        throw new IllegalStateException("Unexpected ResultSet call");
      });
    Statement statement=(Statement)Proxy.newProxyInstance(
      Statement.class.getClassLoader(),new Class<?>[]{Statement.class},
      (p,m,a)->{
        if(m.getName().equals("executeQuery")&&"SHOW GRANTS".equals(a[0]))return result;
        if(m.getName().equals("close"))return null;
        throw new IllegalStateException("Unexpected statement call");
      });
    Connection connection=(Connection)Proxy.newProxyInstance(
      Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
      (p,m,a)->switch(m.getName()){
        case "isClosed","getAutoCommit" -> false;
        case "getCatalog" -> "analytics";
        case "createStatement" -> statement;
        default -> throw new IllegalStateException("Unexpected connection call");
      });
    assertDoesNotThrow(()->SemanticRelationRlsSourceGrantGate.require(
      connection,"mysql","analytics"));
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsSourceGrantGate.require(connection,"starrocks","analytics"));
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsSourceGrantGate.require(connection,"mysql","other"));
  }
}
