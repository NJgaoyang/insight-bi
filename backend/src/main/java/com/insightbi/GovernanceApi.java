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
    return Map.of("roles",List.of("VIEWER","EDITOR"),"loginEnabled",false);
  }

  @GetMapping("/users")
  List<Map<String,Object>> users(){
    return db.queryForList("SELECT id,username,display_name,role,enabled,created_at FROM insight_governance_user ORDER BY created_at,id LIMIT 200");
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
