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

  // Folder rules are restrictive allow lists: an ACL at a folder narrows the
  // set of readers who ALREADY have a report grant. Parent ACLs are cumulative.
  @GetMapping("/folders/{id}/access")
  List<Map<String,Object>> folderAccess(@PathVariable String id){
    requireFolder(id);
    return db.queryForList("SELECT principal_type,principal_id,permission "+
      "FROM insight_folder_acl WHERE folder_id=? ORDER BY principal_type,principal_id",id);
  }

  @PutMapping("/folders/{id}/access")
  @Transactional
  Map<String,Object> setFolderAccess(@PathVariable String id,@RequestBody Map<String,Object> body){
    requireFolder(id);
    if(body.size()!=1||!(body.get("rules") instanceof List<?> rules)||rules.size()>50)
      throw new IllegalArgumentException("目录授权规则必须为至多 50 个成员");
    Set<String> unique=new HashSet<>();
    List<Map<String,String>> safe=new ArrayList<>();
    for(Object item:rules){
      if(!(item instanceof Map<?,?> entry)||entry.size()!=2||
         !entry.containsKey("principalType")||!entry.containsKey("principalId"))
        throw new IllegalArgumentException("目录规则只允许主体类型与 ID");
      String type=text(entry.get("principalType"),"主体类型",12);
      String principal=text(entry.get("principalId"),"主体 ID",36);
      if(!Set.of("USER","DEPARTMENT").contains(type))
        throw new IllegalArgumentException("目录授权主体类型无效");
      if(!unique.add(type+":"+principal))throw new IllegalArgumentException("重复目录授权");
      String target=type.equals("USER")?"insight_governance_user":"insight_governance_department";
      if(db.queryForObject("SELECT COUNT(*) FROM "+target+" WHERE id=?",
          Integer.class,principal)!=1)
        throw new NoSuchElementException("目录授权主体不存在");
      safe.add(Map.of("principalType",type,"principalId",principal));
    }
    db.update("DELETE FROM insight_folder_acl WHERE folder_id=?",id);
    for(Map<String,String> rule:safe)
      db.update("INSERT INTO insight_folder_acl "+
        "(folder_id,principal_type,principal_id,permission) VALUES(?,?,?,?)",
        id,rule.get("principalType"),rule.get("principalId"),READ);
    return Map.of("folderId",id,"rules",safe);
  }

  private void requireFolder(String folderId){
    if(db.queryForObject("SELECT COUNT(*) FROM insight_folder WHERE id=?",
        Integer.class,folderId)!=1)
      throw new NoSuchElementException("目录不存在");
  }

  // Fail closed on corrupt trees. A folder without ACL retains backward-
  // compatible visibility, but NEVER grants access without a report grant.
  boolean canAccessFolder(String userId,String folderId){
    if(folderId==null)return true;
    if(userId==null||userId.isBlank())return false;
    List<Map<String,Object>> userRows=db.queryForList(
      "SELECT department_id FROM insight_governance_user WHERE id=?",userId);
    if(userRows.size()!=1)return false;
    Set<String> departments=new HashSet<>();
    String dept=(String)userRows.get(0).get("department_id");
    while(dept!=null){
      if(!departments.add(dept)||departments.size()>10)
        throw new IllegalStateException("部门层级异常，拒绝目录访问");
      List<Map<String,Object>> ancestors=db.queryForList(
        "SELECT parent_id FROM insight_governance_department WHERE id=?",dept);
      if(ancestors.size()!=1)throw new IllegalStateException("部门已失效，拒绝目录访问");
      dept=(String)ancestors.get(0).get("parent_id");
    }
    Set<String> visitedFolders=new HashSet<>();
    String folder=folderId;
    while(folder!=null){
      if(!visitedFolders.add(folder)||visitedFolders.size()>20)
        throw new IllegalStateException("资源目录层级异常，拒绝访问");
      List<Map<String,Object>> ancestors=db.queryForList(
        "SELECT parent_id FROM insight_folder WHERE id=?",folder);
      if(ancestors.size()!=1)throw new IllegalStateException("资源目录不存在");
      List<Map<String,Object>> rules=db.queryForList(
        "SELECT principal_type,principal_id FROM insight_folder_acl "+
        "WHERE folder_id=? AND permission=? LIMIT 51",folder,READ);
      if(rules.size()>50)throw new IllegalStateException("目录授权配置超限");
      if(!rules.isEmpty()){
        boolean matched=false;
        for(Map<String,Object> rule:rules){
          String type=String.valueOf(rule.get("principal_type"));
          String principal=String.valueOf(rule.get("principal_id"));
          if(type.equals("USER")&&principal.equals(userId)||
             type.equals("DEPARTMENT")&&departments.contains(principal)){
            matched=true;break;
          }
        }
        if(!matched)return false;
      }
      folder=(String)ancestors.get(0).get("parent_id");
    }
    return true;
  }

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
    db.update("DELETE FROM insight_folder_acl WHERE principal_type='DEPARTMENT' AND principal_id=?",id);
    db.update("DELETE FROM insight_department_dashboard_grant WHERE department_id=?",id);
    db.update("DELETE FROM insight_governance_department WHERE id=?",id);
    return Map.of("ok",true);
  }

  // A department may grant published-read access to its own members and all
  // descendants. No write, dataset, or administrator role inherits from it.
  @GetMapping("/departments/{id}/grants")
  List<Map<String,Object>> departmentGrants(@PathVariable String id){
    requireDepartment(id);
    return db.queryForList("SELECT dashboard_id,permission,created_at "+
      "FROM insight_department_dashboard_grant WHERE department_id=? "+
      "ORDER BY created_at,dashboard_id",id);
  }

  @PostMapping("/departments/{id}/grants")
  @Transactional
  Map<String,Object> grantDepartmentRead(@PathVariable String id,
                                        @RequestBody Map<String,Object> body){
    if(body.size()!=1||!body.containsKey("dashboardId"))
      throw new IllegalArgumentException("部门授权只能指定报表 ID");
    requireDepartment(id);
    String dashboardId=text(body.get("dashboardId"),"报表 ID",128);
    if(db.queryForObject("SELECT COUNT(*) FROM insight_dashboard "+
        "WHERE id=? AND kind='analysis' AND status='published' AND published_json IS NOT NULL",
        Integer.class,dashboardId)!=1)
      throw new NoSuchElementException("已发布分析主题不存在");
    if(db.queryForObject("SELECT COUNT(*) FROM insight_department_dashboard_grant "+
        "WHERE department_id=? AND dashboard_id=? AND permission=?",
        Integer.class,id,dashboardId,READ)>0)
      return Map.of("departmentId",id,"dashboardId",dashboardId,
        "permission",READ,"alreadyGranted",true);
    db.update("INSERT INTO insight_department_dashboard_grant "+
      "(department_id,dashboard_id,permission) VALUES(?,?,?)",id,dashboardId,READ);
    return Map.of("departmentId",id,"dashboardId",dashboardId,
      "permission",READ,"alreadyGranted",false);
  }

  @DeleteMapping("/departments/{id}/grants/{dashboardId}")
  Map<String,Object> revokeDepartmentRead(@PathVariable String id,
                                           @PathVariable String dashboardId){
    requireDepartment(id);
    db.update("DELETE FROM insight_department_dashboard_grant "+
      "WHERE department_id=? AND dashboard_id=? AND permission=?",
      id,dashboardId,READ);
    return Map.of("ok",true);
  }

  private void requireDepartment(String id){
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_department WHERE id=?",
        Integer.class,id)!=1)
      throw new NoSuchElementException("部门不存在");
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
    db.update("UPDATE insight_governance_user SET password_hash=?,enabled=FALSE,auth_version=auth_version+1 WHERE id=?",
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
    db.update("UPDATE insight_governance_user SET enabled=?,auth_version=auth_version+1 WHERE id=?",enabled,id);
    return Map.of("id",id,"enabled",enabled);
  }

  @DeleteMapping("/users/{id}")
  @Transactional
  Map<String,Object> deleteUser(@PathVariable String id){
    if(db.queryForObject("SELECT COUNT(*) FROM insight_governance_user WHERE id=?",Integer.class,id)!=1)
      throw new NoSuchElementException("用户不存在");
    db.update("DELETE FROM insight_folder_acl WHERE principal_type='USER' AND principal_id=?",id);
    db.update("DELETE FROM insight_reader_column_deny WHERE user_id=?",id);
    db.update("DELETE FROM insight_reader_row_policy_value WHERE user_id=?",id);
    db.update("DELETE FROM insight_reader_row_policy WHERE user_id=?",id);
    db.update("DELETE FROM insight_dashboard_grant WHERE user_id=?",id);
    db.update("DELETE FROM insight_governance_user WHERE id=?",id);
    return Map.of("ok",true);
  }

  // Optional exact STRING match, not organizational inheritance.
  // Without a row rule, the existing report grant permits all its configured rows.
  // Column deny is additive and never turns an unauthorized dashboard into
  // an authorized one. Only original catalog fields can be configured.
  @GetMapping("/users/{id}/denied-columns")
  List<Map<String,Object>> deniedColumns(@PathVariable String id){
    requireUser(id);
    return db.queryForList("SELECT dataset_id,field_id FROM insight_reader_column_deny "+
      "WHERE user_id=? ORDER BY dataset_id,field_id",id);
  }

  @PutMapping("/users/{id}/denied-columns/{datasetId}")
  @Transactional
  Map<String,Object> setDeniedColumns(@PathVariable String id,@PathVariable String datasetId,
                                      @RequestBody Map<String,Object> body){
    requireUser(id);
    if(body.size()!=1||!(body.get("fieldIds") instanceof List<?> requested)
       ||requested.size()>20)
      throw new IllegalArgumentException("受限列配置必须为至多 20 个字段 ID");
    if(db.queryForObject("SELECT COUNT(*) FROM insight_dataset WHERE id=?",
         Integer.class,datasetId)!=1)
      throw new NoSuchElementException("数据集不存在");
    Set<String> ids=new LinkedHashSet<>();
    for(Object raw:requested){
      String fieldId=text(raw,"字段 ID",36);
      if(!ids.add(fieldId))throw new IllegalArgumentException("受限字段不得重复");
      if(db.queryForObject("SELECT COUNT(*) FROM insight_dataset_field "+
          "WHERE id=? AND dataset_id=?",Integer.class,fieldId,datasetId)!=1)
        throw new IllegalArgumentException("字段不存在或不属于该数据集");
    }
    db.update("DELETE FROM insight_reader_column_deny WHERE user_id=? AND dataset_id=?",id,datasetId);
    for(String fieldId:ids)
      db.update("INSERT INTO insight_reader_column_deny(user_id,dataset_id,field_id) "+
        "VALUES(?,?,?)",id,datasetId,fieldId);
    return Map.of("datasetId",datasetId,"fieldIds",List.copyOf(ids));
  }

  @DeleteMapping("/users/{id}/denied-columns/{datasetId}")
  Map<String,Object> clearDeniedColumns(@PathVariable String id,@PathVariable String datasetId){
    requireUser(id);
    db.update("DELETE FROM insight_reader_column_deny WHERE user_id=? AND dataset_id=?",id,datasetId);
    return Map.of("ok",true);
  }

  Set<String> deniedFieldIds(String userId,String datasetId){
    if(userId==null||datasetId==null||userId.isBlank()||datasetId.isBlank())return Set.of();
    return Set.copyOf(db.queryForList(
      "SELECT field_id FROM insight_reader_column_deny WHERE user_id=? AND dataset_id=?",
      String.class,userId,datasetId));
  }

  @GetMapping("/users/{id}/row-policies")
  List<Map<String,Object>> rowPolicies(@PathVariable String id){
    requireUser(id);
    List<Map<String,Object>> rows=db.queryForList(
      "SELECT dataset_id,field_id,allowed_value FROM insight_reader_row_policy "+
      "WHERE user_id=? ORDER BY dataset_id",id);
    for(Map<String,Object> row:rows){
      String dataset=String.valueOf(row.get("dataset_id"));
      List<String> values=storedAllowedValues(id,dataset,String.valueOf(row.get("allowed_value")));
      row.put("values",values);
    }
    return rows;
  }

  // An existing exact-match rule is interpreted as a singleton; new requests
  // can opt into up to 20 distinct values without replacing legacy records.
  @PutMapping("/users/{id}/row-policies/{datasetId}")
  @Transactional
  Map<String,Object> setRowPolicy(@PathVariable String id,@PathVariable String datasetId,
                                  @RequestBody Map<String,Object> body){
    requireUser(id);
    if(!Set.of("fieldId","allowedValue","allowedValues").containsAll(body.keySet())||
       !body.containsKey("fieldId")||
       body.containsKey("allowedValue")==body.containsKey("allowedValues"))
      throw new IllegalArgumentException("行级规则仅允许一个字段及单值或多值配置");
    String fieldId=text(body.get("fieldId"),"字段 ID",36);
    List<String> allowedValues=new ArrayList<>();
    if(body.containsKey("allowedValue")){
      allowedValues.add(text(body.get("allowedValue"),"允许值",255));
    }else{
      if(!(body.get("allowedValues") instanceof List<?> requested)||
         requested.isEmpty()||requested.size()>20)
        throw new IllegalArgumentException("允许值数量须为 1 至 20 个");
      Set<String> seen=new HashSet<>();
      for(Object raw:requested){
        String value=text(raw,"允许值",255);
        if(!seen.add(value))throw new IllegalArgumentException("允许值不能重复");
        allowedValues.add(value);
      }
    }
    if(db.queryForObject("SELECT COUNT(*) FROM insight_dataset WHERE id=?",Integer.class,datasetId)!=1)
      throw new NoSuchElementException("数据集不存在");
    Integer valid=db.queryForObject(
      "SELECT COUNT(*) FROM insight_dataset_field WHERE id=? AND dataset_id=? AND logical_type='STRING'",
      Integer.class,fieldId,datasetId);
    if(valid==null||valid!=1)throw new IllegalArgumentException("行限制必须选择当前数据集的原始文本字段");
    db.update("DELETE FROM insight_reader_row_policy_value WHERE user_id=? AND dataset_id=?",id,datasetId);
    db.update("DELETE FROM insight_reader_row_policy WHERE user_id=? AND dataset_id=?",id,datasetId);
    db.update("INSERT INTO insight_reader_row_policy(user_id,dataset_id,field_id,allowed_value) "+
      "VALUES(?,?,?,?)",id,datasetId,fieldId,allowedValues.get(0));
    for(String value:allowedValues){
      db.update("INSERT INTO insight_reader_row_policy_value(user_id,dataset_id,allowed_value) "+
        "VALUES(?,?,?)",id,datasetId,value);
    }
    return Map.of("datasetId",datasetId,"fieldId",fieldId,
      "allowedValue",allowedValues.get(0),"allowedValues",List.copyOf(allowedValues));
  }

  @DeleteMapping("/users/{id}/row-policies/{datasetId}")
  @Transactional
  Map<String,Object> deleteRowPolicy(@PathVariable String id,@PathVariable String datasetId){
    requireUser(id);
    db.update("DELETE FROM insight_reader_row_policy_value WHERE user_id=? AND dataset_id=?",id,datasetId);
    db.update("DELETE FROM insight_reader_row_policy WHERE user_id=? AND dataset_id=?",id,datasetId);
    return Map.of("ok",true);
  }

  private List<String> storedAllowedValues(String userId,String datasetId,String legacyValue){
    List<String> values=db.queryForList(
      "SELECT allowed_value FROM insight_reader_row_policy_value "+
      "WHERE user_id=? AND dataset_id=? ORDER BY allowed_value LIMIT 21",
      String.class,userId,datasetId);
    if(values.isEmpty())return List.of(legacyValue);
    if(values.size()>20||values.stream().anyMatch(v->v==null||v.isBlank()||v.length()>255)||
       !values.contains(legacyValue))
      throw new IllegalStateException("行级权限配置异常");
    return List.copyOf(values);
  }

  /** Reader-only server-managed predicate, never constructed from browser JSON. */
  List<Map<String,Object>> mandatoryRowFilters(String userId,String datasetId){
    List<Map<String,Object>> rows=db.queryForList(
      "SELECT field_id,allowed_value FROM insight_reader_row_policy WHERE user_id=? AND dataset_id=?",
      userId,datasetId);
    if(rows.isEmpty())return List.of();
    if(rows.size()!=1)throw new IllegalStateException("行级权限配置异常");
    List<String> values=storedAllowedValues(userId,datasetId,
      String.valueOf(rows.get(0).get("allowed_value")));
    String fieldId=String.valueOf(rows.get(0).get("field_id"));
    if(values.size()==1)
      return List.of(Map.of("fieldId",fieldId,"op","eq","value",values.get(0)));
    return List.of(Map.of("fieldId",fieldId,"op","in","values",values));
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

  /**
   * Resolve only enabled reader accounts, then merge explicit grants and
   * grants from the current department ancestor chain. No role escalation.
   * Recomputed per request, so membership changes/revocations take effect now.
   */
  Set<String> effectiveDashboardIds(String userId){
    if(userId==null||userId.isBlank())return Set.of();
    List<Map<String,Object>> accounts=db.queryForList(
      "SELECT role,enabled,department_id FROM insight_governance_user WHERE id=?",userId);
    if(accounts.size()!=1)return Set.of();
    Map<String,Object> user=accounts.get(0);
    if(!Boolean.TRUE.equals(user.get("enabled"))||
       !ROLES.contains(String.valueOf(user.get("role"))))return Set.of();
    Set<String> ids=new HashSet<>();
    for(String dashboard:db.queryForList("SELECT dashboard_id FROM insight_dashboard_grant "+
        "WHERE user_id=? AND permission=? LIMIT 10001",String.class,userId,READ)){
      ids.add(dashboard);
    }
    if(ids.size()>10000)throw new IllegalStateException("授权资源过多");

    String dept=(String)user.get("department_id");
    Set<String> visited=new HashSet<>();
    while(dept!=null){
      if(!visited.add(dept)||visited.size()>10)
        throw new IllegalStateException("部门层级异常，拒绝继承权限");
      List<Map<String,Object>> parents=db.queryForList(
        "SELECT parent_id FROM insight_governance_department WHERE id=?",dept);
      if(parents.size()!=1)
        throw new IllegalStateException("部门归属无效，拒绝继承权限");
      for(String dashboard:db.queryForList("SELECT dashboard_id "+
          "FROM insight_department_dashboard_grant WHERE department_id=? "+
          "AND permission=? LIMIT 10001",String.class,dept,READ)){
        ids.add(dashboard);
        if(ids.size()>10000)throw new IllegalStateException("授权资源过多");
      }
      dept=(String)parents.get(0).get("parent_id");
    }
    return Set.copyOf(ids);
  }

  boolean canReadPublished(String userId,String dashboardId){
    if(dashboardId==null||dashboardId.isBlank()||
       !effectiveDashboardIds(userId).contains(dashboardId))return false;
    List<Map<String,Object>> matched=db.queryForList(
      "SELECT folder_id FROM insight_dashboard WHERE id=? AND kind='analysis' "+
      "AND status='published' AND published_json IS NOT NULL",dashboardId);
    return matched.size()==1&&canAccessFolder(userId,(String)matched.get(0).get("folder_id"));
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
