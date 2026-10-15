package com.insightbi;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

/**
 * P2-02 provisioned identity and published-report grant foundation.
 *
 * All HTTP routes below remain ADMIN-only under SecurityConfig.
 * A provisioned user has no login route, token or password in this batch.
 * Future ordinary-user authentication must call canReadPublished() before
 * granting access to report content, dataset metadata or query endpoints.
 */
@RestController
@RequestMapping("/api/governance")
class GovernanceApi {
  private static final String READ="READ_PUBLISHED";
  private static final Set<String> ROLES=Set.of("VIEWER","EDITOR");
  private final JdbcTemplate db;

  GovernanceApi(JdbcTemplate db){this.db=db;}

  @GetMapping("/roles")
  Map<String,Object> roles(){
    return Map.of("roles",List.of("VIEWER","EDITOR"),"loginEnabled",true);
  }

  // Department tree is administrative metadata. No automatic permission
  // inheritance or organization-scoped dataset access is enabled yet.
  @GetMapping("/departments")
  List<Map<String,Object>> departments(){
    return db.queryForList(
      "SELECT id,name,parent_id,created_at FROM insight_governance_department ORDER BY created_at,id LIMIT 501");
  }

  @PostMapping("/departments")
  @Transactional
  Map<String,Object> createDepartment(@RequestBody Map<String,Object> body){
    if(!Set.of("name","parentId").containsAll(body.keySet())||!body.containsKey("name"))
      throw new IllegalArgumentException("部门创建参数无效");
    String name=text(body.get("name"),"部门名称",100);
    String parent=departmentParent(body.get("parentId"));
    Map<String,String> hierarchy=departmentTree();
    if(hierarchy.size()>=500)throw new IllegalArgumentException("部门数量超过 500 个");
    String id=UUID.randomUUID().toString();
    hierarchy.put(id,parent);
    validateDepartmentTree(hierarchy);
    requireUniqueSibling(name,parent,null);
    db.update("INSERT INTO insight_governance_department(id,name,parent_id) VALUES(?,?,?)",id,name,parent);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("id",id);result.put("name",name);result.put("parent_id",parent);
    return result;
  }

  @PutMapping("/departments/{id}")
  @Transactional
  Map<String,Object> updateDepartment(@PathVariable String id,@RequestBody Map<String,Object> body){
    if(!Set.of("name","parentId").containsAll(body.keySet())||
       (!body.containsKey("name")&&!body.containsKey("parentId")))
      throw new IllegalArgumentException("部门修改参数无效");
    List<Map<String,Object>> existing=db.queryForList(
      "SELECT name,parent_id FROM insight_governance_department WHERE id=?",id);
    if(existing.size()!=1)throw new NoSuchElementException("部门不存在");
    String name=body.containsKey("name")?text(body.get("name"),"部门名称",100):
      String.valueOf(existing.get(0).get("name"));
    String parent=body.containsKey("parentId")?departmentParent(body.get("parentId")):
      (String)existing.get(0).get("parent_id");
    Map<String,String> hierarchy=departmentTree();
    hierarchy.put(id,parent);
    validateDepartmentTree(hierarchy);
    requireUniqueSibling(name,parent,id);
    db.update("UPDATE insight_governance_department SET name=?,parent_id=? WHERE id=?",name,parent,id);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("id",id);result.put("name",name);result.put("parent_id",parent);
    return result;
  }

  @DeleteMapping("/departments/{id}")
  @Transactional
  Map<String,Object> deleteDepartment(@PathVariable String id){
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_department WHERE id=?",Integer.class,id)!=1)
      throw new NoSuchElementException("部门不存在");
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_department WHERE parent_id=?",Integer.class,id)>0)
      throw new IllegalStateException("部门包含子部门，请先迁移或删除子部门");
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_user WHERE department_id=?",Integer.class,id)>0)
      throw new IllegalStateException("部门仍有用户，请先调整用户归属");
    db.update("DELETE FROM insight_governance_department WHERE id=?",id);
    return Map.of("ok",true);
  }

  @PutMapping("/users/{id}/department")
  @Transactional
  Map<String,Object> assignDepartment(@PathVariable String id,@RequestBody Map<String,Object> body){
    if(body.size()!=1||!body.containsKey("departmentId"))
      throw new IllegalArgumentException("用户归属参数无效");
    requireUser(id);
    String department=departmentParent(body.get("departmentId"));
    if(department!=null&&db.queryForObject(
        "SELECT COUNT(*) FROM insight_governance_department WHERE id=?",Integer.class,department)!=1)
      throw new NoSuchElementException("目标部门不存在");
    db.update("UPDATE insight_governance_user SET department_id=? WHERE id=?",department,id);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("id",id);result.put("department_id",department);
    return result;
  }

  private Map<String,String> departmentTree(){
    List<Map<String,Object>> nodes=departments();
    if(nodes.size()>500)throw new IllegalStateException("部门数量超过上限");
    Map<String,String> hierarchy=new HashMap<>();
    for(Map<String,Object> node:nodes)
      hierarchy.put(String.valueOf(node.get("id")),(String)node.get("parent_id"));
    return hierarchy;
  }

  private static void validateDepartmentTree(Map<String,String> tree){
    for(String node:tree.keySet()){
      Set<String> visited=new HashSet<>();
      String current=node;
      while(current!=null){
        if(!tree.containsKey(current))throw new IllegalArgumentException("父部门不存在");
        if(!visited.add(current))throw new IllegalArgumentException("部门不能成为自身或子部门的下级");
        if(visited.size()>10)throw new IllegalArgumentException("部门层级不能超过 10 层");
        current=tree.get(current);
      }
    }
  }

  private void requireUniqueSibling(String name,String parent,String excludeId){
    Integer existing=parent==null?
      db.queryForObject("SELECT COUNT(*) FROM insight_governance_department WHERE name=? AND parent_id IS NULL AND id<>?",
        Integer.class,name,excludeId==null?"":excludeId):
      db.queryForObject("SELECT COUNT(*) FROM insight_governance_department WHERE name=? AND parent_id=? AND id<>?",
        Integer.class,name,parent,excludeId==null?"":excludeId);
    if(existing!=null&&existing>0)throw new IllegalArgumentException("同一层级已有同名部门");
  }

  private static String departmentParent(Object raw){
    if(raw==null)return null;
    if(!(raw instanceof String id))throw new IllegalArgumentException("部门 ID 必须是文本");
    if(id.isBlank())return null;
    if(id.length()>128)throw new IllegalArgumentException("部门 ID 过长");
    return id;
  }

  @GetMapping("/users")
  List<Map<String,Object>> users(){
    return db.queryForList("SELECT id,username,display_name,role,enabled,department_id,(password_hash IS NOT NULL) AS password_configured,created_at FROM insight_governance_user ORDER BY created_at,id LIMIT 200");
  }

  @PostMapping("/users")
  @Transactional
  Map<String,Object> createUser(@RequestBody Map<String,Object> body){
    if(!Set.of("username","displayName","role").containsAll(body.keySet()))
      throw new IllegalArgumentException("用户创建参数包含未知字段");
    String username=text(body.get("username"),"账号",80).toLowerCase(Locale.ROOT);
    if(!username.matches("[a-z][a-z0-9_.-]{2,79}")||username.equals("admin"))
      throw new IllegalArgumentException("用户账号格式不合法或为保留账号");
    String displayName=text(body.get("displayName"),"显示名称",100);
    String role=text(body.get("role"),"角色",16);
    if(!ROLES.contains(role))throw new IllegalArgumentException("角色不在允许范围");
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_user WHERE username=?",Integer.class,username)>0)
      throw new IllegalArgumentException("该用户账号已存在");
    String id=UUID.randomUUID().toString();
    db.update("INSERT INTO insight_governance_user(id,username,display_name,role,enabled) VALUES(?,?,?,?,FALSE)",
      id,username,displayName,role);
    return Map.of("id",id,"username",username,"role",role,"enabled",false);
  }

  @PutMapping("/users/{id}/role")
  Map<String,Object> setRole(@PathVariable String id,@RequestBody Map<String,Object> body){
    if(body.size()!=1||!body.containsKey("role"))
      throw new IllegalArgumentException("只允许修改角色");
    String role=text(body.get("role"),"角色",16);
    if(!ROLES.contains(role))throw new IllegalArgumentException("角色不在允许范围");
    if(db.update("UPDATE insight_governance_user SET role=? WHERE id=?",role,id)!=1)
      throw new NoSuchElementException("用户不存在");
    return Map.of("id",id,"role",role);
  }


  @PutMapping("/users/{id}/password")
  @Transactional
  Map<String,Object> setPassword(@PathVariable String id,@RequestBody Map<String,Object> body){
    if(body.size()!=1||!body.containsKey("password"))throw new IllegalArgumentException("只允许设置密码");
    requireUser(id);
    String password=ReaderPassword.hash(
      body.get("password") instanceof String value?value:null);
    db.update("UPDATE insight_governance_user SET password_hash=?,enabled=FALSE WHERE id=?",
      password,id);
    // Password reset revokes the account until the administrator re-enables it.
    return Map.of("id",id,"enabled",false,"passwordConfigured",true);
  }

  @PutMapping("/users/{id}/activation")
  @Transactional
  Map<String,Object> setActivation(@PathVariable String id,@RequestBody Map<String,Object> body){
    if(body.size()!=1||!(body.get("enabled") instanceof Boolean enabled))
      throw new IllegalArgumentException("启用状态必须为布尔值");
    requireUser(id);
    if(enabled&&db.queryForObject(
        "SELECT COUNT(*) FROM insight_governance_user WHERE id=? AND password_hash IS NOT NULL AND role IN ('VIEWER','EDITOR')",
        Integer.class,id)!=1)
      throw new IllegalStateException("启用账号前必须设置密码和有效角色");
    db.update("UPDATE insight_governance_user SET enabled=? WHERE id=?",enabled,id);
    return Map.of("id",id,"enabled",enabled);
  }

  @DeleteMapping("/users/{id}")
  @Transactional
  Map<String,Object> deleteUser(@PathVariable String id){
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_user WHERE id=?",Integer.class,id)!=1)
      throw new NoSuchElementException("用户不存在");
    db.update("DELETE FROM insight_dashboard_grant WHERE user_id=?",id);
    db.update("DELETE FROM insight_governance_user WHERE id=?",id);
    return Map.of("ok",true);
  }

  @GetMapping("/users/{id}/grants")
  List<Map<String,Object>> grants(@PathVariable String id){
    requireUser(id);
    return db.queryForList(
      "SELECT g.dashboard_id,g.permission,g.created_at FROM insight_dashboard_grant g WHERE g.user_id=? ORDER BY g.created_at,g.dashboard_id",
      id);
  }

  @PostMapping("/users/{id}/grants")
  @Transactional
  Map<String,Object> grantRead(@PathVariable String id,@RequestBody Map<String,Object> body){
    if(body.size()!=1||!body.containsKey("dashboardId"))
      throw new IllegalArgumentException("仅支持指定已发布报表");
    requireUser(id);
    String dashboardId=text(body.get("dashboardId"),"报表 ID",128);
    if(db.queryForObject("SELECT COUNT(*) FROM insight_dashboard WHERE id=? AND status='published' AND published_json IS NOT NULL",
       Integer.class,dashboardId)!=1)
      throw new NoSuchElementException("已发布报表不存在");
    if(db.queryForObject("SELECT COUNT(*) FROM insight_dashboard_grant WHERE user_id=? AND dashboard_id=? AND permission=?",
       Integer.class,id,dashboardId,READ)>0)
      return Map.of("userId",id,"dashboardId",dashboardId,"permission",READ,"alreadyGranted",true);
    db.update("INSERT INTO insight_dashboard_grant(user_id,dashboard_id,permission) VALUES(?,?,?)",id,dashboardId,READ);
    return Map.of("userId",id,"dashboardId",dashboardId,"permission",READ,"alreadyGranted",false);
  }

  @DeleteMapping("/users/{id}/grants/{dashboardId}")
  Map<String,Object> revokeRead(@PathVariable String id,@PathVariable String dashboardId){
    requireUser(id);
    db.update("DELETE FROM insight_dashboard_grant WHERE user_id=? AND dashboard_id=? AND permission=?",
       id,dashboardId,READ);
    return Map.of("ok",true);
  }

  /** Fail-closed resource policy, not yet exposed as ordinary-user HTTP access. */
  boolean canReadPublished(String userId,String dashboardId){
    if(userId==null||dashboardId==null||userId.isBlank()||dashboardId.isBlank())return false;
    Integer found=db.queryForObject(
      "SELECT COUNT(*) FROM insight_governance_user u JOIN insight_dashboard_grant g ON g.user_id=u.id "+
      "JOIN insight_dashboard d ON d.id=g.dashboard_id WHERE u.id=? AND u.enabled=TRUE "+
      "AND u.role IN ('VIEWER','EDITOR') AND g.dashboard_id=? AND g.permission=? "+
      "AND d.status='published' AND d.published_json IS NOT NULL",
      Integer.class,userId,dashboardId,READ);
    return found!=null&&found==1;
  }

  private void requireUser(String id){
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_user WHERE id=?",Integer.class,id)!=1)
      throw new NoSuchElementException("用户不存在");
  }

  private static String text(Object value,String label,int maxLength){
    if(!(value instanceof String s)||s.isBlank()||s.length()>maxLength)
      throw new IllegalArgumentException(label+"格式无效");
    return s.trim();
  }
}
