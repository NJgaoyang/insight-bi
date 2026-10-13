package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Focused API validation contract: canonical AST + 1500-character expressions. */
class AnalysisFormulaValidationTest {
  @Test void validateAndSaveLongFormulaWithoutTouchingLegacyEndpoints(){
    String url="jdbc:h2:mem:formula_validation_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
    JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    DatabaseStore store=new DatabaseStore(jdbc,new ObjectMapper(),
        Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    String dataset="formula-validation";
    jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        dataset,"Formula Validation","readonly-conn","SELECT revenue FROM sales",
        store.json(List.of(Map.of("name","revenue","type","DECIMAL","numeric",true))));
    var catalog=new FieldCatalog(store,jdbc);
    String id=catalog.fields(dataset).get(0).id();
    String expression=String.join("+",Collections.nCopies(6,"["+id+"]"));
    assertTrue(expression.length()>180&&expression.length()<1500);
    var api=new AnalysisApi(store,jdbc,catalog);

    var preview=api.validate(dataset,Map.of("expression",expression));
    assertEquals("ROW",preview.get("phase"));
    assertEquals("DECIMAL",preview.get("outputType"));
    assertEquals("binary",((Map<?,?>)preview.get("canonicalAst")).get("kind"));
    assertTrue(((Set<?>)preview.get("references")).contains(id));
    assertEquals(0,jdbc.queryForObject(
      "SELECT COUNT(*) FROM insight_calculated_field WHERE dataset_id=?",Integer.class,dataset));

    var saved=api.addFormula(dataset,Map.of("name","六项求和","expression",expression));
    String formulaId=String.valueOf(saved.get("id"));
    assertEquals(expression,jdbc.queryForObject(
      "SELECT expression FROM insight_calculated_field WHERE id=?",String.class,formulaId));
    String revised=expression+"+1";
    var result=api.updateFormula(dataset,formulaId,
      Map.of("name","六项求和","expression",revised,"revision",1));
    assertEquals(2,result.get("revision"));
    assertEquals(revised,jdbc.queryForObject(
      "SELECT expression FROM insight_calculated_field WHERE id=?",String.class,formulaId));

    assertThrows(IllegalArgumentException.class,
      ()->api.validate(dataset,Map.of("expression",expression.repeat(10))));
    assertThrows(IllegalArgumentException.class,
      ()->api.addFormula(dataset,Map.of("name","非法","expression","  ")));
  }
}
