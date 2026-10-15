package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GovernanceApiTest {
  private JdbcTemplate db;
  private GovernanceApi governance;
  private BiApi reports;

  @BeforeEach void setup(){
    String url="jdbc:h2:mem:governance_"+UUID.randomUUID()+
      ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    db=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
    var store=new DatabaseStore(db,new ObjectMapper(),Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    governance=new GovernanceApi(db);
    reports=new BiApi(store,db,"test-secret-at-least-12-chars");
  }

  @Test void provisionedAccountsCannotLoginAndInvalidRolesFailClosed(){
    assertEquals(true,governance.roles().get("loginEnabled"));
    assertEquals(List.of("VIEWER","EDITOR"),governance.roles().get("roles"));
    var user=governance.createUser(Map.of("username","alice","displayName","Alice","role","VIEWER"));
    assertEquals(false,user.get("enabled"));
    assertEquals("VIEWER",user.get("role"));
    assertEquals(1,governance.users().size());
    var record=governance.users().get(0);
    assertEquals(false,record.get("enabled"));
    assertFalse(record.containsKey("password"));
    assertFalse(record.containsKey("credential"));
    assertThrows(IllegalArgumentException.class,()->
      governance.createUser(Map.of("username","bob","displayName","Bob","role","ADMIN")));
    assertThrows(IllegalArgumentException.class,()->
      governance.createUser(Map.of("username","admin","displayName","Superuser","role","VIEWER")));
    assertThrows(IllegalArgumentException.class,()->
      governance.createUser(Map.of("username","bob","displayName","Bob","role","VIEWER","enabled",true)));
    assertThrows(IllegalArgumentException.class,()->
      governance.createUser(Map.of("username","alice","displayName","Duplicate","role","EDITOR")));
    String userId=(String)user.get("id");
    governance.setRole(userId,Map.of("role","EDITOR"));
    assertEquals("EDITOR",governance.users().get(0).get("role"));
    assertThrows(IllegalArgumentException.class,()->
      governance.setRole(userId,Map.of("role","ADMIN")));
    assertEquals(false,governance.users().get(0).get("enabled"));
  }

  @Test void grantsRequirePublicationAndEffectivePolicyChecksAccountState(){
    String a=(String)governance.createUser(Map.of("username","alice","displayName","Alice","role","VIEWER")).get("id");
    String b=(String)governance.createUser(Map.of("username","bobby","displayName","Bob","role","EDITOR")).get("id");
    String dashboard=(String)reports.create(Map.of("name","部门报表","mode","grid")).get("id");
    assertThrows(NoSuchElementException.class,()->
      governance.grantRead(a,Map.of("dashboardId",dashboard)));
    assertThrows(NoSuchElementException.class,()->
      governance.grantRead(a,Map.of("dashboardId","missing")));
    assertThrows(IllegalArgumentException.class,()->
      governance.grantRead(a,Map.of("dashboardId",dashboard,"permission","WRITE")));
    assertFalse(governance.canReadPublished(a,dashboard));
    reports.publish(dashboard);
    assertEquals(false,governance.grantRead(a,Map.of("dashboardId",dashboard)).get("alreadyGranted"));
    assertEquals(true,governance.grantRead(a,Map.of("dashboardId",dashboard)).get("alreadyGranted"));
    assertEquals(1,governance.grants(a).size());
    assertEquals("READ_PUBLISHED",governance.grants(a).get(0).get("permission"));
    assertFalse(governance.canReadPublished(a,dashboard),"disabled users must have no effective access");
    db.update("UPDATE insight_governance_user SET enabled=TRUE WHERE id=?",a);
    assertTrue(governance.canReadPublished(a,dashboard));
    assertFalse(governance.canReadPublished(b,dashboard));
    assertFalse(governance.canReadPublished(null,dashboard));
    assertFalse(governance.canReadPublished(a,"missing"));
    reports.unpublish(dashboard);
    assertFalse(governance.canReadPublished(a,dashboard),"unpublished resources are denied");
    reports.publish(dashboard);
    assertTrue(governance.canReadPublished(a,dashboard));
    governance.revokeRead(a,dashboard);
    assertFalse(governance.canReadPublished(a,dashboard));
    assertTrue(governance.grants(a).isEmpty());
    governance.deleteUser(a);
    assertTrue(governance.users().stream().noneMatch(u->a.equals(u.get("id"))));
    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM insight_dashboard_grant WHERE user_id=?",
      Integer.class,a));
  }

  @Test void departmentTreeRejectsCyclesOrphansDuplicatesAndNonemptyDeletion(){
    String root=(String)governance.createDepartment(Map.of("name","技术部")).get("id");
    String child=(String)governance.createDepartment(Map.of("name","大数据组","parentId",root)).get("id");
    String leaf=(String)governance.createDepartment(Map.of("name","分析小组","parentId",child)).get("id");
    assertEquals(3,governance.departments().size());
    assertThrows(IllegalArgumentException.class,()->
      governance.createDepartment(Map.of("name","技术部")));
    assertThrows(IllegalArgumentException.class,()->
      governance.createDepartment(Map.of("name","不存在的部门","parentId","not-an-id")));
    assertThrows(IllegalArgumentException.class,()->
      governance.updateDepartment(root,Map.of("parentId",leaf)),
      "cannot move a root under its own descendant");
    assertThrows(IllegalArgumentException.class,()->
      governance.updateDepartment(child,Map.of("parentId",child)));
    assertThrows(IllegalArgumentException.class,()->
      governance.updateDepartment(leaf,Map.of("name","大数据组","parentId",root)));
    assertThrows(IllegalStateException.class,()->governance.deleteDepartment(root));

    String user=(String)governance.createUser(
      Map.of("username","analyst01","displayName","测试分析员","role","VIEWER")).get("id");
    governance.assignDepartment(user,Map.of("departmentId",leaf));
    assertEquals(leaf,governance.users().get(0).get("department_id"));
    assertFalse((Boolean)governance.users().get(0).get("enabled"),
      "department membership must never activate a login");
    assertThrows(IllegalStateException.class,()->governance.deleteDepartment(leaf));
    assertThrows(NoSuchElementException.class,()->
      governance.assignDepartment(user,Map.of("departmentId","invalid")));
    assertEquals(leaf,governance.users().get(0).get("department_id"));
    var clear=new HashMap<String,Object>();clear.put("departmentId",null);
    governance.assignDepartment(user,clear);
    assertNull(governance.users().get(0).get("department_id"));

    governance.updateDepartment(leaf,Map.of("parentId",root,"name","分析小组"));
    assertEquals(root,governance.departments().stream()
      .filter(d->leaf.equals(d.get("id"))).findFirst().orElseThrow().get("parent_id"));
    governance.deleteDepartment(child);
    governance.deleteDepartment(leaf);
    governance.deleteDepartment(root);
    assertTrue(governance.departments().isEmpty());
    assertNull(governance.users().get(0).get("department_id"));
  }

  @Test void migratingExistingDisabledUsersAddsNullableDepartmentWithoutEnablingThem(){
    String id=(String)governance.createUser(
      Map.of("username","staff01","displayName","原有用户","role","VIEWER")).get("id");
    // DB initialization is intentionally idempotent for existing installations.
    var fresh=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    fresh.schema();
    assertEquals(1,governance.users().size());
    assertNull(governance.users().get(0).get("department_id"));
    assertFalse((Boolean)governance.users().get(0).get("enabled"));
    assertThrows(NoSuchElementException.class,()->
      governance.assignDepartment("missing",Map.of("departmentId","none")));
    assertThrows(IllegalArgumentException.class,()->
      governance.assignDepartment(id,Map.of("enabled",true)));
  }
}
