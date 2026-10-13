package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Scoped immutable snapshot checks across multiple layers of calculations. */
class FormulaSnapshotGuardTest {
  record Fixture(JdbcTemplate db,DatabaseStore store,AnalysisApi api,String dataset,String amount){}

  private static Fixture fixture(){
    String url="jdbc:h2:mem:formula_snapshot_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    String dataset="snapshot_ds";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      dataset,"Snapshot","no-source","SELECT amount FROM facts",
      store.json(List.of(Map.of("name","amount","type","DECIMAL","numeric",true))));
    var catalog=new FieldCatalog(store,db);
    String amount=catalog.fields(dataset).get(0).id();
    return new Fixture(db,store,new AnalysisApi(store,db,catalog),dataset,amount);
  }

  private static String formula(Fixture f,String name,String expression){
    return String.valueOf(f.api.addFormula(f.dataset,
      Map.of("name",name,"expression",expression)).get("id"));
  }

  private static void version(Fixture f,String content){
    f.db.update("INSERT INTO insight_dashboard_version(id,dashboard_id,version_no,name,mode,content_json) VALUES(?,?,?,?,?,?)",
      UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"History","grid",content);
  }

  private static void dashboard(Fixture f,String draft,String published){
    f.db.update("INSERT INTO insight_dashboard(id,name,mode,content_json,published_json,status,revision) VALUES(?,?,?,?,?,?,?)",
      UUID.randomUUID().toString(),"Saved","grid",draft,published,"draft",1);
  }

  @Test void indirectPublishedReferencePreventsEditingParentFormula(){
    var f=fixture();
    String base=formula(f,"基础利润","["+f.amount+"]+1");
    String child=formula(f,"中间字段","["+base+"]*2");
    String grandchild=formula(f,"结果字段","["+child+"]+3");
    String published=f.store.json(Map.of("widgets",List.of(Map.of("analysisV2",
      Map.of("measures",List.of(Map.of("fieldId",grandchild,"aggregate","SUM")))))));
    dashboard(f,"{}",published);
    assertEquals(Set.of(base,child,grandchild),
      new FormulaSnapshotGuard(f.store,f.db).transitiveDependents(f.dataset,base));
    var ex=assertThrows(IllegalStateException.class,()->f.api.updateFormula(f.dataset,base,
      Map.of("name","基础利润","expression","["+f.amount+"]+2","revision",1)));
    assertTrue(ex.getMessage().contains("发布"),ex.getMessage());
    assertEquals(1,f.db.queryForObject(
      "SELECT revision FROM insight_calculated_field WHERE id=?",Integer.class,base));
    assertThrows(IllegalStateException.class,()->f.api.deleteFormula(f.dataset,base));
  }

  @Test void historicalDirectReferenceBlocksChangesButOrdinaryTextDoesNot(){
    var f=fixture();
    String base=formula(f,"仅文本出现","["+f.amount+"]+1");
    String text=f.store.json(Map.of("widgets",List.of(Map.of("title","说明中提到 "+base,
      "markdown","引用字段号 "+base))));
    version(f,text);
    assertEquals(2,f.api.updateFormula(f.dataset,base,
      Map.of("name","仅文本出现","expression","["+f.amount+"]+2","revision",1)).get("revision"));
    // The unreferenced formula may be removed despite arbitrary occurrence in a title.
    assertEquals(true,f.api.deleteFormula(f.dataset,base).get("ok"));

    String referenced=formula(f,"真实引用","["+f.amount+"]*2");
    version(f,f.store.json(Map.of("widgets",List.of(Map.of(
      "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId",referenced))))))));
    assertThrows(IllegalStateException.class,()->f.api.updateFormula(f.dataset,referenced,
      Map.of("name","真实引用","expression","["+f.amount+"]*3","revision",1)));
  }

  @Test void deletionChecksDraftAndDoesNotBlockUnreferencedUpdate(){
    var f=fixture();
    String id=formula(f,"草稿使用","["+f.amount+"]+1");
    dashboard(f,f.store.json(Map.of("widgets",List.of(Map.of(
      "analysisV2",Map.of("filters",Map.of("pre",
        List.of(Map.of("fieldId",id,"op","gte","value",1)))))))),null);
    assertEquals(2,f.api.updateFormula(f.dataset,id,
      Map.of("name","草稿使用","expression","["+f.amount+"]+2","revision",1)).get("revision"));
    assertThrows(IllegalStateException.class,()->f.api.deleteFormula(f.dataset,id));
  }

  @Test void quotedFormulaTextDoesNotCreatePhantomDependency(){
    var f=fixture();
    String base=formula(f,"基础金额","["+f.amount+"]+1");
    // A string literal may mention another field ID without referencing it.
    String note=formula(f,"静态文字","IF(TRUE, 'note ["+base+"]', 'other')");
    dashboard(f,"{}",f.store.json(Map.of("widgets",List.of(Map.of(
      "analysisV2",Map.of("dimensions",List.of(Map.of("fieldId",note))))))));
    assertEquals(Set.of(base),
      new FormulaSnapshotGuard(f.store,f.db).transitiveDependents(f.dataset,base));
    assertEquals(2,f.api.updateFormula(f.dataset,base,
      Map.of("name","基础金额","expression","["+f.amount+"]+2","revision",1)).get("revision"));
  }

  @Test void corruptPublishedSnapshotFailsClosed(){
    var f=fixture();
    String id=formula(f,"有快照","["+f.amount+"]+1");
    dashboard(f,"{}","{not valid json");
    assertThrows(IllegalStateException.class,()->f.api.updateFormula(f.dataset,id,
      Map.of("name","有快照","expression","["+f.amount+"]+2","revision",1)));
  }
}
