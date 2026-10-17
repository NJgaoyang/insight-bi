package com.insightbi.analysis;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRelationCardinalityProfileTest {
  private static SemanticRelationApi.Pair pair(String right){
    return new SemanticRelationApi.Pair("left",right);
  }
  private static FieldCatalog.Field field(String id){
    return new FieldCatalog.Field(id,id,id,"STRING","DIMENSION",false,false,"ROW",null);
  }
  private static Map<String,FieldCatalog.Field> fields(){
    return Map.of("city",field("city"),"period",field("period"));
  }
  private static Connection source()throws SQLException{
    var c=DriverManager.getConnection(
      "jdbc:h2:mem:semanticcard_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    try(var st=c.createStatement()){
      st.execute("CREATE TABLE lookup(city VARCHAR(20),period VARCHAR(20))");
      st.execute("INSERT INTO lookup VALUES ('A','01'),('A','01'),('B','02'),('C',NULL),(NULL,'09')");
    }
    c.setReadOnly(true);
    return c;
  }
  @Test void duplicateAndNullKeysDoNotConfuseJoinFanout()throws Exception{
    var plan=SemanticRelationCardinalityProfile.plan("SELECT city,period FROM lookup",
      List.of(pair("city"),pair("period")),fields());
    assertTrue(plan.sql().contains("LIMIT 2000"));
    assertFalse(plan.sql().contains(" JOIN "));
    try(var c=source()){
      var result=SemanticRelationCardinalityProfile.run(c,plan);
      assertEquals(5,result.sampledRows());
      assertEquals(2,result.nullKeyRows());
      assertEquals(2,result.keyGroups());
      assertEquals(1,result.duplicateGroups());
      assertEquals(1,result.extraRightRows());
      assertEquals(2,result.maxRightMultiplicity());
      assertTrue(result.hasDuplicateKeys());
      assertFalse(result.sampleCapReached());
      assertFalse(result.exactSourceCount());
      assertFalse(result.exactJoinRowCount());
    }
  }
  @Test void emptyAndAtCapSamplesAreAlwaysMarkedInexact()throws Exception{
    try(var c=source()){
      var empty=SemanticRelationCardinalityProfile.plan(
        "SELECT city FROM lookup WHERE 1=0",List.of(pair("city")),fields());
      var r=SemanticRelationCardinalityProfile.run(c,empty);
      assertEquals(0,r.sampledRows());
      assertEquals(0,r.maxRightMultiplicity());
      var limited=SemanticRelationCardinalityProfile.plan(
        "SELECT city FROM lookup",List.of(pair("city")),fields(),3);
      var sample=SemanticRelationCardinalityProfile.run(c,limited);
      assertEquals(3,sample.sampledRows());
      assertTrue(sample.sampleCapReached());
      assertFalse(sample.exactSourceCount());
    }
  }
  @Test void malformedSqlAndUnknownKeysFailBeforeOpeningJdbc(){
    assertThrows(IllegalArgumentException.class,()->SemanticRelationCardinalityProfile.plan(
      "DELETE FROM lookup",List.of(pair("city")),fields()));
    assertThrows(IllegalArgumentException.class,()->SemanticRelationCardinalityProfile.plan(
      "SELECT city FROM lookup; DROP TABLE lookup",List.of(pair("city")),fields()));
    assertThrows(IllegalArgumentException.class,()->SemanticRelationCardinalityProfile.plan(
      "SELECT city FROM lookup",List.of(pair("missing")),fields()));
    assertThrows(IllegalArgumentException.class,()->SemanticRelationCardinalityProfile.plan(
      "SELECT city FROM lookup",List.of(pair("city"),pair("city")),fields()));
    assertThrows(IllegalArgumentException.class,()->SemanticRelationCardinalityProfile.plan(
      "SELECT city FROM lookup",List.of(pair("city")),fields(),2001));
    var hidden=new FieldCatalog.Field("city","city","city","STRING","DIMENSION",
      false,false,"ROW",null,true);
    assertThrows(IllegalArgumentException.class,()->SemanticRelationCardinalityProfile.plan(
      "SELECT city FROM lookup",List.of(pair("city")),Map.of("city",hidden)));
  }
}
