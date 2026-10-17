package com.insightbi.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRelationRlsPlanPreviewTest {
  private record F(DatabaseStore store,JdbcTemplate db,FieldCatalog catalog,
                   SemanticRelationApi api,String id,String leftCity,String leftTenant,
                   String rightCity,String rightTenant){}
  private static final String READER="rls_reader";
  private static F fixture(){
    var db=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:mem:rls_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    db.execute("CREATE TABLE rls_orders(city VARCHAR(40),tenant VARCHAR(40))");
    db.execute("CREATE TABLE rls_cities(city VARCHAR(40),tenant VARCHAR(40))");
    db.update("INSERT INTO rls_orders VALUES(?,?)","Paris","A");
    db.update("INSERT INTO rls_orders VALUES(?,?)","Rome","A");
    db.update("INSERT INTO rls_orders VALUES(?,?)","Paris","B");
    db.update("INSERT INTO rls_cities VALUES(?,?)","Paris","B");
    db.update("INSERT INTO rls_cities VALUES(?,?)","Paris","C");
    db.update("INSERT INTO rls_cities VALUES(?,?)","Rome","C");
    for(String[] ds:List.of(new String[]{"left","rls_orders"},
                          new String[]{"right","rls_cities"}))
      db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
        "VALUES(?,?,?,?,?)",ds[0],ds[0],"connection","SELECT city,tenant FROM "+ds[1],
        store.json(List.of(Map.of("name","city","type","VARCHAR"),
                           Map.of("name","tenant","type","VARCHAR"))));
    var catalog=new FieldCatalog(store,db);
    String lc=field(catalog.fields("left"),"city"),lt=field(catalog.fields("left"),"tenant"),
      rc=field(catalog.fields("right"),"city"),rt=field(catalog.fields("right"),"tenant");
    db.update("INSERT INTO insight_governance_user "+
      "(id,username,display_name,role,enabled) VALUES(?,?,?,?,?)",
      READER,"rls_reader","RLS Reader","VIEWER",true);
    var api=new SemanticRelationApi(store,db,catalog);
    var rel=api.create(Map.of("name","Row-policy join preview","sourceDatasetId","left",
      "targetDatasetId","right","pairs",
      List.of(Map.of("leftFieldId",lc,"rightFieldId",rc))));
    api.approve(rel.id(),Map.of("revision",1));
    return new F(store,db,catalog,api,rel.id(),lc,lt,rc,rt);
  }
  private static String field(List<FieldCatalog.Field> fields,String name){
    return fields.stream().filter(f->name.equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
  }
  private static Map<String,Object> body(F f,String join){
    return Map.of("revision",1,"readerId",READER,"joinType",join,
      "leftFieldIds",List.of(f.leftCity()),"rightFieldIds",List.of(f.rightCity()));
  }
  private static void policy(F f,String dataset,String fieldId,String primary,String... others){
    f.db().update("INSERT INTO insight_reader_row_policy "+
      "(user_id,dataset_id,field_id,allowed_value) VALUES(?,?,?,?)",
      READER,dataset,fieldId,primary);
    if(others.length>0){
      f.db().update("INSERT INTO insight_reader_row_policy_value VALUES(?,?,?)",
        READER,dataset,primary);
      for(String value:others)
        f.db().update("INSERT INTO insight_reader_row_policy_value VALUES(?,?,?)",
          READER,dataset,value);
    }
  }
  @Test void leftJoinPreservesRowsAfterIndependentLeftAndRightFilters(){
    var f=fixture();policy(f,"left",f.leftTenant(),"A");
    policy(f,"right",f.rightTenant(),"B","X");
    var p=f.api().rlsPlanPreview(f.id(),body(f,"LEFT"));
    assertEquals("COMPILE_ONLY_DUAL_SOURCE_RLS",p.scope());
    assertEquals(1,p.leftBindCount());assertEquals(2,p.rightBindCount());
    assertEquals(3,p.totalBindCount());
    assertTrue(p.leftPolicyApplied());assertTrue(p.rightPolicyApplied());
    assertEquals(2,p.columns().size());
    String sql=p.sqlPreview();
    assertTrue(sql.contains("bi_rls_left."+FormulaCompiler.quote("tenant")+" = ?"));
    assertTrue(sql.contains("bi_rls_right."+FormulaCompiler.quote("tenant")+" IN (?,?)"));
    assertTrue(sql.contains("LEFT JOIN (SELECT * FROM ("));
    assertFalse(sql.contains("'A'"));assertFalse(sql.contains("'B'"));
    assertTrue(sql.endsWith("LIMIT 200"));
    // Test-only H2 execution: this production endpoint returns no binding values.
    var rows=f.db().query(sql,ps->{
      ps.setString(1,"A");ps.setString(2,"B");ps.setString(3,"X");
    },(rs,i)->List.of(rs.getString(1),rs.getString(2)==null?"NULL":rs.getString(2)));
    assertEquals(2,rows.size());
    assertTrue(rows.contains(List.of("Paris","Paris")));
    assertTrue(rows.contains(List.of("Rome","NULL")));
  }
  @Test void innerJoinAndNoPolicyDoNotInventFilters(){
    var f=fixture();
    var empty=f.api().rlsPlanPreview(f.id(),body(f,"INNER"));
    assertEquals(0,empty.totalBindCount());
    assertFalse(empty.sqlPreview().contains("bi_rls_left"));
    policy(f,"right",f.rightTenant(),"B");
    var one=f.api().rlsPlanPreview(f.id(),body(f,"INNER"));
    assertEquals(0,one.leftBindCount());assertEquals(1,one.rightBindCount());
    assertEquals(2,f.db().query(one.sqlPreview(),ps->ps.setString(1,"B"),
      (rs,i)->rs.getString(1)).size());
  }
  @Test void injectedPolicyValuesAreNeverInSqlAndDoNotAlterMeaning(){
    var f=fixture();String inject="B' OR 1=1 --";
    policy(f,"right",f.rightTenant(),inject);
    var p=f.api().rlsPlanPreview(f.id(),body(f,"LEFT"));
    assertFalse(p.sqlPreview().contains(inject));
    assertEquals(1,p.rightBindCount());
    var rows=f.db().query(p.sqlPreview(),ps->ps.setString(1,inject),
      (rs,i)->rs.getString(2));
    assertEquals(3,rows.size());
    assertTrue(rows.stream().allMatch(Objects::isNull));
  }
  @Test void deniesUnknownFieldsInactiveReaderOrDriftFailClosed(){
    var f=fixture();
    f.db().update("INSERT INTO insight_reader_column_deny VALUES(?,?,?)",
      READER,"right",f.rightTenant());
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("DELETE FROM insight_reader_column_deny WHERE user_id=?",READER);
    policy(f,"left",f.leftCity(),"Paris");
    f.db().update("UPDATE insight_reader_row_policy SET field_id=? WHERE user_id=?",
      "unknown",READER);
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("UPDATE insight_reader_row_policy SET field_id=?,allowed_value=? WHERE user_id=?",
      f.leftCity()," ",READER);
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("DELETE FROM insight_reader_row_policy WHERE user_id=?",READER);
    f.db().update("UPDATE insight_governance_user SET enabled=FALSE WHERE id=?",READER);
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("UPDATE insight_governance_user SET enabled=TRUE WHERE id=?",READER);
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city FROM rls_orders","left");
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city,tenant FROM rls_orders","left");
    f.api().update(f.id(),Map.of("revision",1,"name","New rev","pairs",
      List.of(Map.of("leftFieldId",f.leftCity(),"rightFieldId",f.rightCity()))));
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
  }
  @Test void malformedMultiValuesAndHiddenRlsFieldFailClosed(){
    var f=fixture();policy(f,"right",f.rightTenant(),"B","C");
    f.db().update("DELETE FROM insight_reader_row_policy_value "+
      "WHERE user_id=? AND allowed_value=?",READER,"B");
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("DELETE FROM insight_reader_row_policy_value WHERE user_id=?",READER);
    for(int i=0;i<21;i++)
      f.db().update("INSERT INTO insight_reader_row_policy_value VALUES(?,?,?)",
        READER,"right","v"+i);
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
    f.db().update("DELETE FROM insight_reader_row_policy_value WHERE user_id=?",READER);
    f.db().update("UPDATE insight_dataset_field SET hidden=TRUE WHERE id=?",f.rightTenant());
    f.api().approve(f.id(),Map.of("revision",1));
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
  }
  @Test void onlyDirectPhysicalSingleTableProjectionQualifiesForDualRls(){
    var f=fixture();
    policy(f,"left",f.leftTenant(),"A");
    var plan=f.api().rlsPlanPreview(f.id(),body(f,"LEFT"));
    assertEquals(1,plan.leftBindCount());
    assertTrue(plan.sqlPreview().contains("bi_rls_left"));
    // Once the model explicitly re-approves its source fingerprint, still
    // disallow every form where filtering AFTER source SQL is not provable.
    List<String> risky=List.of(
      "SELECT city, COUNT(*) AS tenant FROM rls_orders GROUP BY city",
      "SELECT DISTINCT city,tenant FROM rls_orders",
      "SELECT city, tenant FROM rls_orders ORDER BY city",
      "SELECT city, tenant FROM rls_orders LIMIT 5",
      "SELECT city, tenant FROM rls_orders UNION ALL SELECT city,tenant FROM rls_orders",
      "SELECT o.city,o.tenant FROM rls_orders o JOIN rls_cities c ON o.city=c.city",
      "SELECT city,tenant FROM (SELECT city,tenant FROM rls_orders) x",
      "WITH v AS (SELECT city,tenant FROM rls_orders) SELECT city,tenant FROM v",
      "SELECT city, CONCAT(tenant,'x') AS tenant FROM rls_orders",
      "SELECT * FROM rls_orders"
    );
    for(String source:risky){
      f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",source,"left");
      assertThrows(RuntimeException.class,()->
        SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(source,
          FieldCatalog.index(f.catalog().fields("left"))),source);
    }
  }

  @Test void mismatchedProjectionAndQualifierCannotMasqueradeAsPolicyColumn(){
    var f=fixture();
    var fields=FieldCatalog.index(f.catalog().fields("left"));
    for(String sql:List.of(
        "SELECT city FROM rls_orders",
        "SELECT tenant FROM rls_orders",
        "SELECT x.city,x.tenant FROM rls_orders o",
        "SELECT city, city FROM rls_orders",
        "SELECT city,tenant FROM rls_orders; DROP TABLE rls_orders",
        "SELECT 'Paris' AS city, tenant FROM rls_orders"))
      assertThrows(RuntimeException.class,()->
        SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(sql,fields),sql);
    assertDoesNotThrow(()->SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(
      "SELECT o.city,o.tenant FROM rls_orders o",fields));
    assertDoesNotThrow(()->SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(
      "SELECT city,tenant FROM rls_orders",fields));
  }

  @Test void sourceWhereAndIdentityAliasesPreserveBilateralRlsJoinSemantics(){
    var f=fixture();
    policy(f,"left",f.leftTenant(),"A");
    policy(f,"right",f.rightTenant(),"B");
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city AS city, tenant AS tenant FROM rls_orders WHERE tenant='A'","left");
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "SELECT city,tenant FROM rls_cities WHERE tenant='B'","right");
    f.api().approve(f.id(),Map.of("revision",1));
    var plan=f.api().rlsPlanPreview(f.id(),body(f,"LEFT"));
    assertEquals(1,plan.leftBindCount());
    assertEquals(1,plan.rightBindCount());
    assertEquals(2,plan.totalBindCount());
    var rows=f.db().query(plan.sqlPreview(),ps->{
      ps.setString(1,"A");ps.setString(2,"B");
    },(rs,i)->List.of(rs.getString(1),rs.getString(2)==null?"NULL":rs.getString(2)));
    assertEquals(2,rows.size());
    assertTrue(rows.contains(List.of("Paris","Paris")));
    assertTrue(rows.contains(List.of("Rome","NULL")));
  }

  @Test void directColumnRenameAndBoundedAndPredicatesHaveVerifiableLineage(){
    var map=Map.of(
      "renamed",new FieldCatalog.Field("renamed","Renamed","region_name",
        "STRING","DIMENSION",false,false,"ROW",null),
      "tenant",new FieldCatalog.Field("tenant","Tenant","tenant",
        "STRING","DIMENSION",false,false,"ROW",null));
    assertDoesNotThrow(()->SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(
      "SELECT o.city AS region_name, o.tenant AS tenant FROM rls_orders AS o "+
      "WHERE o.city='Paris' AND o.tenant='A'",map));
    assertDoesNotThrow(()->SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(
      "SELECT city AS region_name,tenant FROM rls_orders WHERE city='Paris'",map));
  }

  @Test void unsafeWhereAndOpaqueAliasConstructsStillFailClosed(){
    var f=fixture();
    var fields=FieldCatalog.index(f.catalog().fields("left"));
    for(String sql:List.of(
      "SELECT city,tenant FROM rls_orders WHERE city='Paris' OR tenant='A'",
      "SELECT city,tenant FROM rls_orders WHERE city IN ('Paris')",
      "SELECT city,tenant FROM rls_orders WHERE city LIKE 'P%'",
      "SELECT city,tenant FROM rls_orders WHERE city=?",
      "SELECT city,tenant FROM rls_orders WHERE city=(SELECT MAX(city) FROM rls_orders)",
      "SELECT city,tenant FROM rls_orders WHERE COUNT(city)=1",
      "SELECT city,tenant FROM rls_orders WHERE other='not-projected'",
      "SELECT city,tenant FROM rls_orders WHERE x.tenant='A'",
      "SELECT city,tenant FROM rls_orders WHERE city='Paris' -- comment",
      "SELECT city,tenant FROM rls_orders WHERE city='Paris' AND tenant='A' "+
        "AND city='Rome' AND tenant='B' AND city='Paris'",
      "SELECT city AS tenant,tenant AS tenant FROM rls_orders",
      "SELECT city AS city,city AS tenant FROM rls_orders",
      "SELECT city AS region_name,tenant FROM rls_orders WHERE city='Paris'",
      "SELECT city,tenant FROM rls_orders WHERE city='O''Reilly'",
      "SELECT city,tenant FROM rls_orders WHERE city='Paris'; DELETE FROM rls_orders"
    ))
      assertThrows(RuntimeException.class,()->
        SemanticRelationSourceLineageGuard.requireDirectPhysicalSource(sql,fields),sql);
  }

  @Test void forgedParametersInvalidJoinAndNonSelectSourceFailClosed(){
    var f=fixture();
    var forged=new LinkedHashMap<>(body(f,"LEFT"));
    forged.put("policyValues",List.of("A"));
    assertThrows(IllegalArgumentException.class,()->
      f.api().rlsPlanPreview(f.id(),forged));
    assertThrows(IllegalArgumentException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"CROSS")));
    f.db().update("UPDATE insight_dataset SET sql_body=? WHERE id=?",
      "DELETE FROM rls_orders","right");
    assertThrows(IllegalStateException.class,()->
      f.api().rlsPlanPreview(f.id(),body(f,"LEFT")));
  }
}
