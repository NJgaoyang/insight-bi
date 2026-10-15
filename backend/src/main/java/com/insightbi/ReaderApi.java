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
  private final ReaderColumnGuard columnGuard;
  private final AnalysisApi analysis;
  private final ConcurrentHashMap<String,ArrayDeque<Long>> failedLogins=new ConcurrentHashMap<>();
  private static final String DUMMY_HASH=ReaderPassword.hash("reader-login-constant-not-a-real-password");

  ReaderApi(JdbcTemplate db,DatabaseStore store,GovernanceApi governance,AnalysisApi analysis){
    this.db=db;this.store=store;this.governance=governance;this.analysis=analysis;
    this.columnGuard=new ReaderColumnGuard(governance,db);
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
      "SELECT id,role,enabled,password_hash,auth_version FROM insight_governance_user WHERE username=?",username);
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
    session.setAttribute("readerAuthVersion",((Number)row.get("auth_version")).intValue());
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
    Set<String> granted=governance.effectiveDashboardIds(userId);
    if(granted.isEmpty())return List.of();
    // Evaluate the same content-level column policy as report() and query().
    // Do not send denied report names or published JSON to the browser.
    List<String> ids=new ArrayList<>(granted);
    String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
    List<Map<String,Object>> candidates=db.queryForList(
      "SELECT id,name,kind,status,published_json,folder_id FROM insight_dashboard "+
      "WHERE id IN ("+placeholders+") AND kind='analysis' "+
      "AND status='published' AND published_json IS NOT NULL "+
      "ORDER BY name,id LIMIT 1000",ids.toArray());
    List<Map<String,Object>> visible=new ArrayList<>();
    for(Map<String,Object> row:candidates){
      Map<String,Object> content=store.map(String.valueOf(row.get("published_json")));
      if(!governance.canAccessFolder(userId,(String)row.get("folder_id"))||
         !columnGuard.allowed(userId,content))continue;
      visible.add(Map.of("id",row.get("id"),"name",row.get("name"),
        "kind",row.get("kind"),"status",row.get("status")));
      if(visible.size()>=200)break;
    }
    return visible;
  }

  @GetMapping("/dashboards/{id}")
  Map<String,Object> report(@PathVariable String id,HttpSession session){
    Map<String,Object> row=published(id,(String)session.getAttribute("readerId"));
    Map<String,Object> content=store.map(String.valueOf(row.get("published_json")));
    if(!columnGuard.allowed((String)session.getAttribute("readerId"),content))
      throw new NoSuchElementException("已授权报表不可访问");
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
    if(!columnGuard.allowed((String)session.getAttribute("readerId"),content))
      throw new NoSuchElementException("已授权报表不可访问");
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
    // Rollup is an opt-in server operation tied to the trusted published
    // snapshot, never an arbitrary client-supplied query parameter.
    if("pivot".equals(selected.get("type"))){
      safe.put("pivot",config.containsKey("pivot")?config.get("pivot"):Map.of("showTotals",true));
    }
    int limit=config.get("limit") instanceof Number n?n.intValue():100;
    safe.put("limit",Math.min(Math.max(limit,1),200));
    safe.put("datasetId",datasetId);
    // Attach server-managed restrictions only AFTER constructing the immutable
    // published widget request. Client JSON cannot remove, replace or append them.
    List<Map<String,Object>> mandatory=governance.mandatoryRowFilters(
      (String)session.getAttribute("readerId"),datasetId);
    if(!mandatory.isEmpty()){
      Map<?,?> original=safe.get("filters") instanceof Map<?,?> m?m:Map.of();
      Object rawPre=original.get("pre"),rawPost=original.get("post");
      if(rawPre!=null&&!(rawPre instanceof List<?>))
        throw new IllegalArgumentException("已发布明细过滤格式无效");
      if(rawPost!=null&&!(rawPost instanceof List<?>))
        throw new IllegalArgumentException("已发布结果过滤格式无效");
      List<Object> pre=new ArrayList<>();
      if(rawPre instanceof List<?> existing)pre.addAll(existing);
      pre.addAll(mandatory);
      if(pre.size()>20)throw new IllegalArgumentException("已发布过滤条件超过上限");
      safe.put("filters",Map.of("pre",pre,
        "post",rawPost==null?List.of():rawPost));
    }
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
