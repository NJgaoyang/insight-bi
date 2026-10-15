package com.insightbi;

import com.insightbi.analysis.AnalysisApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Deliberately separate reader surface. Admin endpoints remain inaccessible.
 * All data queries are compiled from a published widget snapshot, never from
 * arbitrary dataset IDs, field IDs or SQL submitted by an ordinary user.
 */
@RestController
@RequestMapping("/api/reader")
class ReaderApi {
  private final JdbcTemplate db;
  private final DatabaseStore store;
  private final GovernanceApi governance;
  private final AnalysisApi analysis;
  private final ConcurrentHashMap<String,ArrayDeque<Long>> failedLogins=new ConcurrentHashMap<>();
  private static final String DUMMY_HASH=ReaderPassword.hash("reader-login-constant-not-a-real-password");

  ReaderApi(JdbcTemplate db,DatabaseStore store,GovernanceApi governance,AnalysisApi analysis){
    this.db=db;this.store=store;this.governance=governance;this.analysis=analysis;
  }

  @PostMapping("/login")
  Map<String,Object> login(@RequestBody Map<String,Object> request,HttpServletRequest servlet){
    if(request.size()!=2||!(request.get("username") instanceof String user)||
       !(request.get("password") instanceof String password)||
       user.length()>80||password.length()>128)
      throw new IllegalArgumentException("账号或密码错误");
    String username=user.toLowerCase(Locale.ROOT);
    String ip=servlet.getRemoteAddr();
    ArrayDeque<Long> recent=failedLogins.computeIfAbsent(ip,k->new ArrayDeque<>());
    long now=System.currentTimeMillis();
    synchronized(recent){
      while(!recent.isEmpty()&&recent.peekFirst()<now-900_000L)recent.pollFirst();
      if(recent.size()>=8)throw new IllegalArgumentException("登录失败过多，请15分钟后重试");
    }
    List<Map<String,Object>> found=db.queryForList(
      "SELECT id,role,enabled,password_hash FROM insight_governance_user WHERE username=?",username);
    Map<String,Object> row=found.size()==1?found.get(0):Map.of();
    String credential=(String)row.get("password_hash");
    boolean verified=ReaderPassword.matches(password,credential==null?DUMMY_HASH:credential);
    boolean allowed=found.size()==1&&verified&&
      Boolean.TRUE.equals(row.get("enabled"))&&
      Set.of("VIEWER","EDITOR").contains(String.valueOf(row.get("role")));
    if(!allowed){
      synchronized(recent){recent.addLast(now);}
      throw new IllegalArgumentException("账号或密码错误");
    }
    failedLogins.remove(ip);
    HttpSession prior=servlet.getSession(false);
    if(prior!=null)prior.invalidate();
    HttpSession session=servlet.getSession(true);
    session.setAttribute("readerId",String.valueOf(row.get("id")));
    session.setAttribute("readerCredential",credential);
    session.setAttribute("csrf",UUID.randomUUID().toString());
    return Map.of("user",username,"role",row.get("role"),"csrf",session.getAttribute("csrf"));
  }

  @GetMapping("/me")
  Map<String,Object> me(HttpSession session){
    String id=(String)session.getAttribute("readerId");
    Map<String,Object> user=db.queryForMap(
      "SELECT username,display_name,role FROM insight_governance_user WHERE id=?",id);
    return Map.of("user",user.get("username"),"displayName",user.get("display_name"),
      "role",user.get("role"),"csrf",session.getAttribute("csrf"));
  }

  @PostMapping("/logout")
  Map<String,Object> logout(HttpSession session){
    session.invalidate();return Map.of("ok",true);
  }

  @GetMapping("/catalog")
  List<Map<String,Object>> catalog(HttpSession session){
    String userId=(String)session.getAttribute("readerId");
    return db.queryForList(
      "SELECT d.id,d.name,d.kind,d.status FROM insight_dashboard d "+
      "JOIN insight_dashboard_grant g ON g.dashboard_id=d.id "+
      "WHERE g.user_id=? AND g.permission='READ_PUBLISHED' AND d.kind='analysis' "+
      "AND d.status='published' AND d.published_json IS NOT NULL ORDER BY d.name,d.id LIMIT 200",
      userId);
  }

  @GetMapping("/dashboards/{id}")
  Map<String,Object> report(@PathVariable String id,HttpSession session){
    Map<String,Object> row=published(id,(String)session.getAttribute("readerId"));
    Map<String,Object> content=store.map(String.valueOf(row.get("published_json")));
    // This reader is V2-only. A legacy widget could query admin-only V1 APIs;
    // fail closed until that pathway has its own validated viewer query contract.
    for(Object item:widgets(content)){
      if(!(item instanceof Map<?,?> widget)||!(widget.get("analysisV2") instanceof Map))
        throw new IllegalStateException("报表包含尚未开放的旧版组件");
    }
    return Map.of("id",id,"name",row.get("name"),"kind","analysis",
      "status","published","mode",row.get("mode"),"content",content);
  }

  @PostMapping("/dashboards/{id}/widgets/{widgetId}/query")
  Map<String,Object> query(@PathVariable String id,@PathVariable String widgetId,
     @RequestBody Map<String,Object> userParameters,HttpSession session)throws Exception{
    // No caller-supplied dataset, dimensions, measures, pipeline versions, SQL,
    // filters or limit. Queries can only execute the saved, published V2 widget.
    if(!userParameters.isEmpty())throw new IllegalArgumentException("只允许查询已发布的组件配置");
    Map<String,Object> row=published(id,(String)session.getAttribute("readerId"));
    Map<String,Object> content=store.map(String.valueOf(row.get("published_json")));
    Map<?,?> selected=null;
    for(Object item:widgets(content)){
      if(item instanceof Map<?,?> widget&&widgetId.equals(widget.get("id"))){
        selected=widget;break;
      }
    }
    if(selected==null||!(selected.get("analysisV2") instanceof Map<?,?> config)||
       !(selected.get("datasetId") instanceof String datasetId)||datasetId.isBlank())
      throw new NoSuchElementException("已发布组件不存在");
    Map<String,Object> safe=new LinkedHashMap<>();
    for(String key:List.of("usePipeline","pipelineRevision","dimensions","measures",
                           "filters","rank","share","running","periodCompare","sort")){
      if(config.containsKey(key))safe.put(key,config.get(key));
    }
    int limit=config.get("limit") instanceof Number n?n.intValue():100;
    safe.put("limit",Math.min(Math.max(limit,1),200));
    safe.put("datasetId",datasetId);
    return analysis.query(safe);
  }

  private Map<String,Object> published(String dashboardId,String userId){
    if(!governance.canReadPublished(userId,dashboardId))
      throw new NoSuchElementException("已授权报表不存在");
    List<Map<String,Object>> results=db.queryForList(
      "SELECT id,name,kind,mode,published_json FROM insight_dashboard "+
      "WHERE id=? AND kind='analysis' AND status='published' AND published_json IS NOT NULL",
      dashboardId);
    if(results.size()!=1)throw new NoSuchElementException("已授权报表不存在");
    return results.get(0);
  }

  private static List<?> widgets(Map<String,Object> content){
    if(!(content.get("widgets") instanceof List<?> list)||list.size()>200)
      throw new IllegalStateException("已发布组件配置无效");
    return list;
  }
}
