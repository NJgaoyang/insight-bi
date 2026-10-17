package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2-only isolation checks. Intentionally no Spring HTTP mapping or external
 * StarRocks connection. Test-only literal bindings never leave this class.
 */
class SemanticRelationRlsExecutionBoundaryTest {
  private record Fixture(JdbcTemplate db,SemanticRelationRlsPlanPreview.Preview plan,
                         SemanticRelationRlsBindPreflight.Attestation bindings){}
  private Fixture fixture(boolean policies){
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:join_boundary_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    db.execute("CREATE TABLE isolated_orders(city VARCHAR(40),tenant VARCHAR(40))");
    db.execute("CREATE TABLE isolated_cities(city VARCHAR(40),tenant VARCHAR(40))");
    db.update("INSERT INTO isolated_orders VALUES('Paris','A')");
    db.update("INSERT INTO isolated_orders VALUES('Rome','A')");
    db.update("INSERT INTO isolated_orders VALUES('Paris','B')");
    db.update("INSERT INTO isolated_cities VALUES('Paris','B')");
    db.update("INSERT INTO isolated_cities VALUES('Rome','C')");
    for(var pair:List.of(new String[]{"left","isolated_orders"},
                         new String[]{"right","isolated_cities"})){
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
        "VALUES(?,?,?,?,?)",pair[0],pair[0],"mock_connection",
        "SELECT city,tenant FROM "+pair[1],
        store.json(List.of(Map.of("name","city","type","VARCHAR"),
          Map.of("name","tenant","type","VARCHAR"))));
    }
    FieldCatalog catalog=new FieldCatalog(store,db);
    var left=FieldCatalog.index(catalog.fields("left"));
    var right=FieldCatalog.index(catalog.fields("right"));
    String lc=left.values().stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String lt=left.values().stream().filter(f->"tenant".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String rc=right.values().stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    String rt=right.values().stream().filter(f->"tenant".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    db.update("INSERT INTO insight_governance_user "+
      "(id,username,display_name,role,enabled) VALUES(?,?,?,?,?)",
      "isolation_reader","isolation_reader","Reader","VIEWER",true);
    if(policies){
      db.update("INSERT INTO insight_reader_row_policy "+
        "(user_id,dataset_id,field_id,allowed_value) VALUES(?,?,?,?)",
        "isolation_reader","left",lt,"A");
      db.update("INSERT INTO insight_reader_row_policy "+
        "(user_id,dataset_id,field_id,allowed_value) VALUES(?,?,?,?)",
        "isolation_reader","right",rt,"B");
    }
    var plan=SemanticRelationRlsPlanPreview.compile(db,"isolation_reader",
      store.dataset("left"),store.dataset("right"),"left","right",
      List.of(new SemanticRelationApi.Pair(lc,rc)),left,right,1,
      Map.of("joinType","LEFT","leftFieldIds",List.of(lc),
             "rightFieldIds",List.of(rc)));
    var binds=SemanticRelationRlsBindPreflight.inspect(db,"isolation_reader",
      "left","right",left,right,plan);
    return new Fixture(db,plan,binds);
  }

  @Test void isolatedH2ProbeAppliesLimitsAndPreservesUnmatchedLeftRows()throws Exception{
    var f=fixture(true);
    // Reserve before opening JDBC; no user endpoint ever calls this sequence.
    try(var reservation=SemanticRelationRlsExecutionBoundary.reserve(
          f.plan(),f.bindings());
        Connection source=Objects.requireNonNull(f.db().getDataSource()).getConnection()){
      source.setAutoCommit(false);
      try(PreparedStatement statement=SemanticRelationRlsExecutionBoundary.prepare(
          source,f.plan(),f.bindings(),reservation)){
        assertTrue(source.isReadOnly());
        assertEquals(5,statement.getQueryTimeout());
        assertEquals(200,statement.getMaxRows());
        assertEquals(32,statement.getFetchSize());
        // Test ONLY: fixed metadata values, not exposed by any production API.
        statement.setString(1,"A");
        statement.setString(2,"B");
        List<List<String>> rows=new ArrayList<>();
        try(var result=statement.executeQuery()){
          while(result.next())
            rows.add(List.of(result.getString(1),
              result.getString(2)==null?"NULL":result.getString(2)));
        }
        assertEquals(2,rows.size());
        assertTrue(rows.contains(List.of("Paris","Paris")));
        assertTrue(rows.contains(List.of("Rome","NULL")));
      }finally{source.rollback();}
    }
  }

  @Test void failClosedWhenConnectionHasNoCallerManagedTransaction()throws Exception{
    var f=fixture(false);
    try(var reserve=SemanticRelationRlsExecutionBoundary.reserve(f.plan(),f.bindings());
        var source=Objects.requireNonNull(f.db().getDataSource()).getConnection()){
      assertTrue(source.getAutoCommit());
      assertThrows(IllegalStateException.class,()->
        SemanticRelationRlsExecutionBoundary.prepare(
          source,f.plan(),f.bindings(),reserve));
    }
  }

  @Test void rejectForgedBindEvidenceAndStatementAndReleasedReservation()throws Exception{
    var f=fixture(true);
    var p=f.plan();
    var altered=new SemanticRelationRlsPlanPreview.Preview(p.revision(),p.joinType(),
      p.sqlPreview()+" SELECT * FROM other_table",p.columns(),p.leftBindCount(),
      p.rightBindCount(),p.totalBindCount(),p.leftPolicyApplied(),
      p.rightPolicyApplied(),p.readerAuthVersion(),p.scope(),p.warnings());
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsExecutionBoundary.reserve(altered,f.bindings()));
    var fake=new SemanticRelationRlsBindPreflight.Attestation(
      0,0,"f".repeat(64));
    assertThrows(IllegalStateException.class,()->
      SemanticRelationRlsExecutionBoundary.reserve(p,fake));
    var reserve=SemanticRelationRlsExecutionBoundary.reserve(p,f.bindings());
    reserve.close();
    try(var source=Objects.requireNonNull(f.db().getDataSource()).getConnection()){
      source.setAutoCommit(false);
      assertThrows(IllegalStateException.class,()->
        SemanticRelationRlsExecutionBoundary.prepare(source,p,f.bindings(),reserve));
    }
  }

  @Test void concurrentReservationsBoundedToTwoWithoutConnectionLeak(){
    var f=fixture(false);
    try(var first=SemanticRelationRlsExecutionBoundary.reserve(f.plan(),f.bindings());
        var second=SemanticRelationRlsExecutionBoundary.reserve(f.plan(),f.bindings())){
      assertThrows(AnalysisQueryLimiter.BusyException.class,()->
        SemanticRelationRlsExecutionBoundary.reserve(f.plan(),f.bindings()));
      first.close();
      assertDoesNotThrow(()->{
        try(var third=SemanticRelationRlsExecutionBoundary.reserve(
            f.plan(),f.bindings())){assertTrue(third.active());}
      });
    }
  }
}
