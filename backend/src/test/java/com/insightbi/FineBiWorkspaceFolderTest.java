package com.insightbi;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Verifies FineBI-style folder organization uses real persisted metadata. */
class FineBiWorkspaceFolderTest {
  JdbcTemplate db;
  BiApi api;
  @BeforeEach void setup(){
    var datasource=new DriverManagerDataSource(
      "jdbc:h2:mem:folder_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "sa","");
    db=new JdbcTemplate(datasource);
    var store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    api=new BiApi(store,db,"placeholder-testing-password");
  }

  @Test void nestedFoldersMoveThemeAndBlockNonemptyRemoval(){
    String parent=String.valueOf(api.createFolder(Map.of("name","运营分析")).get("id"));
    String child=String.valueOf(api.createFolder(Map.of("name","季度报表","parentId",parent)).get("id"));
    assertEquals(2,api.folders().size());
    assertEquals("月度报表",api.renameFolder(child,Map.of("name","月度报表")).get("name"));

    String report=String.valueOf(api.create(Map.of("name","利润分析","mode","grid","parentId",child)).get("id"));
    assertEquals(child,api.dashboards().get(0).get("folder_id"));
    assertThrows(IllegalStateException.class,()->api.deleteFolder(child));
    assertThrows(IllegalStateException.class,()->api.deleteFolder(parent));

    api.moveDashboard(report,new HashMap<>(Map.of("parentId",parent)));
    assertEquals(parent,api.dashboards().get(0).get("folder_id"));
    api.deleteFolder(child);
    assertThrows(IllegalStateException.class,()->api.deleteFolder(parent));
    api.moveDashboard(report,Collections.singletonMap("parentId",null));
    assertNull(api.dashboards().get(0).get("folder_id"));
    api.deleteFolder(parent);
    assertEquals(0,api.folders().size());
    assertEquals(1,api.dashboards().size());
  }

  @Test void analysisKindStoresWithoutBreakingDashboardCompatibility(){
    String subject=String.valueOf(api.create(Map.of("name","渠道分析","mode","grid","kind","analysis")).get("id"));
    String dashboard=String.valueOf(api.create(Map.of("name","经营大屏","mode","free")).get("id"));
    var rows=api.dashboards();
    assertEquals("analysis",rows.stream().filter(r->subject.equals(r.get("id"))).findFirst().orElseThrow().get("kind"));
    assertEquals("dashboard",rows.stream().filter(r->dashboard.equals(r.get("id"))).findFirst().orElseThrow().get("kind"));
    assertThrows(IllegalArgumentException.class,()->api.create(Map.of("name","非法类型","mode","grid","kind","unknown")));
  }
  @Test void publicDataFoldersStoreRealDatasetsSafely(){
    String parent=String.valueOf(api.createDataFolder(Map.of("name","总部数据")).get("id"));
    String child=String.valueOf(api.createDataFolder(Map.of("name","财务主题","parentId",parent)).get("id"));
    assertEquals(2,api.dataFolders().size());
    String datasetId=UUID.randomUUID().toString();
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json,folder_id) VALUES(?,?,?,?,?,?)",
      datasetId,"收入表","connection-1","SELECT 1 AS income","[]",child);
    assertThrows(IllegalStateException.class,()->api.deleteDataFolder(child));
    assertThrows(IllegalStateException.class,()->api.deleteDataFolder(parent));
    api.moveDataset(datasetId,Map.of("parentId",parent));
    assertEquals(parent,api.datasets().get(0).get("folder_id"));
    api.deleteDataFolder(child);
    assertThrows(IllegalStateException.class,()->api.deleteDataFolder(parent));
    api.moveDataset(datasetId,Collections.singletonMap("parentId",null));
    assertNull(api.datasets().get(0).get("folder_id"));
    api.deleteDataFolder(parent);
    assertEquals(0,api.dataFolders().size());
    assertEquals(1,api.datasets().size());
    assertThrows(java.util.NoSuchElementException.class,
      ()->api.moveDataset(datasetId,Map.of("parentId","invalid")));
  }
  @Test void invalidFolderReferencesDoNotCreateThemes(){
    assertThrows(java.util.NoSuchElementException.class,
      ()->api.create(Map.of("name","错误分组","mode","grid","parentId","missing")));
    assertThrows(java.util.NoSuchElementException.class,
      ()->api.createFolder(Map.of("name","子文件夹","parentId","missing")));
    assertEquals(0,api.dashboards().size());
    assertEquals(0,api.folders().size());
  }
}
