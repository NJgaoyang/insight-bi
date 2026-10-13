package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AnalysisFormulaDependencyTest {
  record Fixture(JdbcTemplate db,AnalysisApi api,String dataset,String revenue,String cost) {}

  private static Fixture fixture() {
    String url="jdbc:h2:mem:p0_formula_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    DatabaseStore store=new DatabaseStore(db,new ObjectMapper(),
        Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    String dataset="formula_dataset";
    String fields=store.json(List.of(
        Map.of("name","revenue","type","DECIMAL","numeric",true),
        Map.of("name","cost","type","DECIMAL","numeric",true)));
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
        dataset,"Formula test","not-connected","SELECT revenue,cost FROM sales",fields);
    FieldCatalog catalog=new FieldCatalog(store,db);
    List<FieldCatalog.Field> physical=catalog.fields(dataset);
    String revenue=physical.stream().filter(f->"revenue".equals(f.sourceColumn())).findFirst().orElseThrow().id();
    String cost=physical.stream().filter(f->"cost".equals(f.sourceColumn())).findFirst().orElseThrow().id();
    return new Fixture(db,new AnalysisApi(store,db,catalog),dataset,revenue,cost);
  }

  @Test void updateRejectsSelfCycleAndBreakingDependentFormula() {
    Fixture f=fixture();
    String rowExpr="["+f.revenue()+"]-["+f.cost()+"]";
    String rowId=String.valueOf(f.api().addFormula(f.dataset(),
        Map.of("name","行利润","expression",rowExpr)).get("id"));
    String ratioExpr="SUM(["+rowId+"]) / NULLIF(SUM(["+f.revenue()+"]),0)";
    f.api().addFormula(f.dataset(),Map.of("name","利润率","expression",ratioExpr));

    assertThrows(IllegalArgumentException.class,()->f.api().updateFormula(
        f.dataset(),rowId,Map.of("name","行利润","expression","["+rowId+"]+1","revision",1)));
    assertThrows(IllegalArgumentException.class,()->f.api().updateFormula(
        f.dataset(),rowId,Map.of("name","行利润","expression","SUM(["+f.revenue()+"])","revision",1)));
    assertEquals(rowExpr,f.db().queryForObject(
        "SELECT expression FROM insight_calculated_field WHERE id=?",String.class,rowId));
    assertEquals(1,f.db().queryForObject(
        "SELECT revision FROM insight_calculated_field WHERE id=?",Integer.class,rowId));

    var valid=f.api().updateFormula(f.dataset(),rowId,
        Map.of("name","行利润","expression",rowExpr+"+1","revision",1));
    assertEquals(2,valid.get("revision"));
  }

  @Test void historicalVersionPreventsMutationOrDeletion() {
    Fixture f=fixture();
    String id=String.valueOf(f.api().addFormula(f.dataset(),
        Map.of("name","净利润","expression","["+f.revenue()+"]-["+f.cost()+"]")).get("id"));
    f.db().update("INSERT INTO insight_dashboard_version(id,dashboard_id,version_no,name,mode,content_json) VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),"report",1,"历史版本","grid",
        "{\"widgets\":[{\"fieldId\":\""+id+"\"}]}");

    assertThrows(IllegalStateException.class,()->f.api().updateFormula(
        f.dataset(),id,Map.of("name","净利润","expression","["+f.revenue()+"]+1","revision",1)));
    assertThrows(IllegalStateException.class,()->f.api().deleteFormula(f.dataset(),id));
    assertEquals(1,f.db().queryForObject(
        "SELECT COUNT(*) FROM insight_calculated_field WHERE id=?",Integer.class,id));
  }
}
