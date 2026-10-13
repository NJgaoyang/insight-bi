package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Non-production G0 rehearsal on a disposable H2 FILE metadata database.
 * BACKUP TO runs on the source H2 JDBC connection, unlike an unsafe live
 * filesystem copy of a potentially changing .mv.db file.
 */
class MetadataRecoveryRehearsalTest {
  @TempDir Path dir;
  private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);

  @Test void onlineH2BackupChecksumAndRestorePreserveCoreMetadata() throws Exception{
    String url="jdbc:h2:file:"+dir.resolve("active").toAbsolutePath()+";MODE=MySQL";
    JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    DatabaseStore store=new DatabaseStore(jdbc,new ObjectMapper(),KEY);
    store.schema();
    String dataset="dataset-restore";
    jdbc.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      dataset,"恢复验证","test-only","SELECT city,amount FROM sales",
      store.json(List.of(Map.of("name","city","type","VARCHAR","numeric",false),
        Map.of("name","amount","type","DECIMAL","numeric",true))));
    FieldCatalog catalog=new FieldCatalog(store,jdbc);
    String city=catalog.fields(dataset).stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id();
    catalog.renameDisplayName(dataset,city,"城市名称");
    catalog.setHidden(dataset,city,true);
    String formula=String.valueOf(new AnalysisApi(store,jdbc,catalog).addFormula(dataset,
      Map.of("name","收入双倍","expression","["+catalog.fields(dataset).stream()
        .filter(f->"amount".equals(f.sourceColumn())).findFirst().orElseThrow().id()+"]*2"))
      .get("id"));
    String snapshot=store.json(Map.of("widgets",List.of(Map.of(
      "datasetId",dataset,"analysisV2",Map.of(
        "dimensions",List.of(Map.of("fieldId",city)),
        "measures",List.of(Map.of("id","m1","fieldId",formula,"aggregate","SUM")),
        "filters",Map.of("pre",List.of(),"post",List.of()),"sort",List.of(),"limit",200)))));
    jdbc.update("INSERT INTO insight_dashboard(id,name,mode,content_json,published_json,status,revision) "+
      "VALUES(?,?,?,?,?,?,?)","dashboard-restore","恢复测试","grid",snapshot,snapshot,"published",2);
    jdbc.update("INSERT INTO insight_dashboard_version(id,dashboard_id,version_no,name,mode,content_json) "+
      "VALUES(?,?,?,?,?,?)","version-restore","dashboard-restore",1,"第一版","grid",snapshot);

    Path backup=dir.resolve("metadata-consistent.zip");
    // H2's SQL BACKUP command obtains a transactionally consistent MVStore
    // image from an active connection, even while the source file is open.
    jdbc.execute((ConnectionCallback<Void>)c->{
      try(var stmt=c.createStatement()){
        stmt.execute("BACKUP TO '"+backup.toAbsolutePath().toString().replace("'","''")+"'");
      }
      return null;
    });
    assertTrue(Files.size(backup)>0);
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup));
    assertEquals(32,digest.length);

    Path restored=Files.createDirectory(dir.resolve("restored"));
    try(ZipInputStream archive=new ZipInputStream(Files.newInputStream(backup))){
      ZipEntry entry;
      boolean containsDatabase=false;
      while((entry=archive.getNextEntry())!=null){
        Path target=restored.resolve(entry.getName()).normalize();
        if(!target.startsWith(restored))throw new SecurityException("Backup contains an unsafe path");
        if(entry.isDirectory())Files.createDirectories(target);
        else{
          Files.createDirectories(target.getParent());
          Files.copy(archive,target);
          if(entry.getName().endsWith(".mv.db"))containsDatabase=true;
        }
        archive.closeEntry();
      }
      assertTrue(containsDatabase,"H2 BACKUP must contain an MVStore image");
    }
    // Do not modify the live fixture: confirm both databases can be read.
    assertArrayEquals(digest,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup)));
    JdbcTemplate recovery=new JdbcTemplate(new DriverManagerDataSource(
      "jdbc:h2:file:"+restored.resolve("active").toAbsolutePath()+";MODE=MySQL;IFEXISTS=TRUE","sa",""));
    var recoveredStore=new DatabaseStore(recovery,new ObjectMapper(),KEY);
    recoveredStore.schema(); // idempotent DDL after restore
    var restoredFields=new FieldCatalog(recoveredStore,recovery).fields(dataset);
    assertEquals(3,restoredFields.size()); // two physical fields plus one formula
    assertEquals(city,restoredFields.stream().filter(f->"city".equals(f.sourceColumn()))
      .findFirst().orElseThrow().id());
    assertTrue(restoredFields.stream().filter(f->f.id().equals(city)).findFirst().orElseThrow().hidden());
    assertEquals("城市名称",restoredFields.stream().filter(f->f.id().equals(city))
      .findFirst().orElseThrow().name());
    assertEquals(1,recovery.queryForObject(
      "SELECT COUNT(*) FROM insight_dashboard_version WHERE dashboard_id=?",Integer.class,"dashboard-restore"));
    assertEquals(snapshot,recovery.queryForObject(
      "SELECT published_json FROM insight_dashboard WHERE id=?",String.class,"dashboard-restore"));
    assertEquals(formula,recovery.queryForObject(
      "SELECT id FROM insight_calculated_field WHERE dataset_id=?",String.class,dataset));
    assertEquals("SELECT city,amount FROM sales",recovery.queryForObject(
      "SELECT sql_body FROM insight_dataset WHERE id=?",String.class,dataset));
  }
}
