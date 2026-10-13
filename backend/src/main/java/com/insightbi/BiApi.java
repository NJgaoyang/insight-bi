package com.insightbi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.insightbi.analysis.DatasetSchemaGuard;
import com.insightbi.analysis.DatasetSqlChangePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import jakarta.servlet.http.*;

@Configuration class SecurityConfig implements WebMvcConfigurer {
 @Override public void addInterceptors(InterceptorRegistry r){r.addInterceptor(new HandlerInterceptor(){
  @Override public boolean preHandle(HttpServletRequest q,HttpServletResponse a,Object h)throws Exception{
   String p=q.getRequestURI();if(!p.startsWith("/api/")||p.equals("/api/login")||p.equals("/api/health"))return true;
   HttpSession s=q.getSession(false);if(s==null||!Boolean.TRUE.equals(s.getAttribute("admin"))){a.sendError(401);return false;}
   if(!Set.of("GET","HEAD","OPTIONS").contains(q.getMethod())&&!Objects.equals(q.getHeader("X-CSRF-Token"),s.getAttribute("csrf"))){a.sendError(403);return false;}
   return true;
  }});}
}
@RestController @RequestMapping("/api")
class BiApi {
 final DatabaseStore s;final JdbcTemplate db;final String password;
 final ConcurrentHashMap<String,ArrayDeque<Long>> loginFailures=new ConcurrentHashMap<>();
 BiApi(DatabaseStore s,JdbcTemplate db,@Value("${BI_ADMIN_PASSWORD:}")String p){this.s=s;this.db=db;password=p;if(p.length()<12)throw new IllegalStateException("Missing admin password");}
 @GetMapping("/health")Map<String,Object> health(){return Map.of("ok",true);}
 @PostMapping("/login")Map<String,Object> login(@RequestBody Map<String,Object> x,HttpServletRequest r){
  String ip=r.getRemoteAddr();var attempts=loginFailures.computeIfAbsent(ip,k->new ArrayDeque<Long>());long now=System.currentTimeMillis();
  synchronized(attempts){while(!attempts.isEmpty()&&attempts.peekFirst()<now-900_000)attempts.pollFirst();if(attempts.size()>=8)throw new IllegalArgumentException("登录失败过多，请15分钟后重试");}
  boolean valid="admin".equals(str(x,"username",""))&&MessageDigest.isEqual(str(x,"password","").getBytes(StandardCharsets.UTF_8),password.getBytes(StandardCharsets.UTF_8));
  if(!valid){synchronized(attempts){attempts.addLast(now);}throw new IllegalArgumentException("账号或密码错误");}
  loginFailures.remove(ip);
  HttpSession old=r.getSession(false);if(old!=null)old.invalidate();HttpSession session=r.getSession(true);session.setAttribute("admin",true);session.setAttribute("csrf",UUID.randomUUID().toString());return Map.of("user","admin","csrf",session.getAttribute("csrf"));
 }
 @GetMapping("/me")Map<String,Object> me(HttpSession x){return Map.of("user","admin","csrf",x.getAttribute("csrf"));}
 @PostMapping("/logout")Map<String,Object> logout(HttpSession x){x.invalidate();return Map.of("ok",true);}
 @GetMapping("/connections")List<Map<String,Object>> connections(){return s.connections();}
 @PostMapping("/connections/test")Map<String,Object> test(@RequestBody Map<String,Object> x)throws Exception{try(Connection c=s.test(x);Statement q=c.createStatement()){q.setQueryTimeout(6);q.executeQuery("SELECT 1").close();return Map.of("ok",true);}}
 @PostMapping("/connections")Map<String,Object> saveConn(@RequestBody Map<String,Object> x){String id=str(x,"id",UUID.randomUUID().toString());x.put("id",id);s.upsertConnection(x);return Map.of("id",id);}
 @DeleteMapping("/connections/{id}")Map<String,Object> deleteConn(@PathVariable String id){int used=db.queryForObject("SELECT COUNT(*) FROM insight_dataset WHERE connection_id=?",Integer.class,id);if(used>0)throw new IllegalArgumentException("连接被数据集使用");db.update("DELETE FROM insight_connection WHERE id=?",id);return Map.of("ok",true);}
 @GetMapping("/connections/{id}/tables")List<String> tables(@PathVariable String id)throws Exception{
  try(Connection c=s.open(s.connection(id));Statement t=c.createStatement();ResultSet r=t.executeQuery("SHOW TABLES")){List<String> out=new ArrayList<>();while(r.next()&&out.size()<1000)out.add(r.getString(1));return out;}
 }
 @GetMapping("/connections/{id}/table-fields")List<Map<String,Object>> tableFields(@PathVariable String id,@RequestParam String table)throws Exception{
  try(Connection c=s.open(s.connection(id))){return s.readFields(c,"SELECT * FROM "+DatabaseStore.safeId(table));}
 }
 @GetMapping("/data-folders")List<Map<String,Object>> dataFolders(){
  return db.queryForList("SELECT id,name,parent_id,created_at FROM insight_data_folder ORDER BY created_at,name");
 }
 void assertDataFolder(String id){
  if(id!=null && db.queryForObject("SELECT COUNT(*) FROM insight_data_folder WHERE id=?",Integer.class,id)!=1)
   throw new NoSuchElementException("公共数据文件夹不存在");
 }
 @PostMapping("/data-folders")Map<String,Object> createDataFolder(@RequestBody Map<String,Object> x){
  String id=UUID.randomUUID().toString(),name=str(x,"name","").trim(),parent=optionalFolder(x);
  if(name.isBlank()||name.length()>100)throw new IllegalArgumentException("公共数据文件夹名称无效");
  assertDataFolder(parent);
  db.update("INSERT INTO insight_data_folder(id,name,parent_id) VALUES(?,?,?)",id,name,parent);
  return Map.of("id",id);
 }
 @PutMapping("/data-folders/{id}")Map<String,Object> renameDataFolder(@PathVariable String id,@RequestBody Map<String,Object> x){
  String name=str(x,"name","").trim();
  if(name.isBlank()||name.length()>100)throw new IllegalArgumentException("文件夹名称无效");
  if(db.update("UPDATE insight_data_folder SET name=? WHERE id=?",name,id)!=1)
    throw new NoSuchElementException("公共数据文件夹不存在");
  return Map.of("id",id,"name",name);
 }
 @DeleteMapping("/data-folders/{id}")Map<String,Object> deleteDataFolder(@PathVariable String id){
  assertDataFolder(id);
  if(db.queryForObject("SELECT COUNT(*) FROM insight_data_folder WHERE parent_id=?",Integer.class,id)>0)
   throw new IllegalStateException("公共数据文件夹包含子目录");
  if(db.queryForObject("SELECT COUNT(*) FROM insight_dataset WHERE folder_id=?",Integer.class,id)>0)
   throw new IllegalStateException("公共数据文件夹内仍有数据集");
  db.update("DELETE FROM insight_data_folder WHERE id=?",id);
  return Map.of("ok",true);
 }
 @PutMapping("/datasets/{id}/folder")Map<String,Object> moveDataset(@PathVariable String id,@RequestBody Map<String,Object> x){
  String folder=optionalFolder(x);
  assertDataFolder(folder);
  if(db.update("UPDATE insight_dataset SET folder_id=? WHERE id=?",folder,id)!=1)
    throw new NoSuchElementException("数据集不存在");
  return Map.of("id",id,"moved",true);
 }
 @GetMapping("/datasets")List<Map<String,Object>> datasets(){return s.datasets();}
 @GetMapping("/datasets/{id}")Map<String,Object> dataset(@PathVariable String id){var a=new LinkedHashMap<>(s.dataset(id));a.put("fields",s.arr(String.valueOf(a.remove("fields_json"))));return a;}
 @Transactional
 @PostMapping("/datasets")Map<String,Object> saveDataset(@RequestBody Map<String,Object> x)throws Exception{
  String id=str(x,"id",UUID.randomUUID().toString()),name=str(x,"name",""),
    connectionId=str(x,"connectionId",""),submittedSql=str(x,"sql","");
  if(name.isBlank()||name.length()>150)throw new IllegalArgumentException("数据集名称无效");
  String folder=optionalFolder(x);assertDataFolder(folder);
  boolean exists=db.queryForObject("SELECT COUNT(*) FROM insight_dataset WHERE id=?",Integer.class,id)>0;
  String oldSql=exists?String.valueOf(s.dataset(id).get("sql_body")):null;
  // Validate dangerous functions/AST before creating a JDBC source connection.
  // Previously saved SQL is grandfathered only when its text is unchanged.
  String sql=DatasetSqlChangePolicy.forSave(submittedSql,oldSql);
  List<Map<String,Object>> fields;try(Connection c=s.open(s.connection(connectionId))){fields=s.readFields(c,sql);}
  if(fields.isEmpty())throw new IllegalArgumentException("SQL 没有字段");
  Map<String,String> fieldRenames=new LinkedHashMap<>();
  Object rawRenames=x.get("fieldRenames");
  if(rawRenames!=null){
   if(!(rawRenames instanceof Map<?,?> names))
    throw new IllegalArgumentException("字段更名映射必须是旧源列到新源列的对象");
   for(var entry:names.entrySet()){
    if(!(entry.getKey() instanceof String old)||!(entry.getValue() instanceof String newer))
     throw new IllegalArgumentException("字段更名映射必须由文本组成");
    fieldRenames.put(old,newer);
   }
  }
  if(!exists&&!fieldRenames.isEmpty())throw new IllegalArgumentException("新建数据集不能使用源字段更名映射");
  if(exists){
   var guard=new DatasetSchemaGuard(s,db);
   guard.check(id,fields,fieldRenames);
   guard.applyRenames(id,fieldRenames);
   db.update("UPDATE insight_dataset SET name=?,connection_id=?,sql_body=?,fields_json=? WHERE id=?",
     name,connectionId,sql,s.json(fields),id);
  }else db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json,folder_id) VALUES(?,?,?,?,?,?)",id,name,connectionId,sql,s.json(fields),folder);
  return Map.of("id",id,"fields",fields);
 }
 @DeleteMapping("/datasets/{id}")Map<String,Object> deleteDataset(@PathVariable String id){
  for(var x:db.queryForList("SELECT content_json FROM insight_dashboard"))if(String.valueOf(x.get("content_json")).contains(id))throw new IllegalArgumentException("报表正在引用该数据集");
  db.update("DELETE FROM insight_dataset WHERE id=?",id);return Map.of("ok",true);
 }
 @GetMapping("/datasets/{id}/preview")Map<String,Object> preview(@PathVariable String id,@RequestParam(defaultValue="50")int limit)throws Exception{
  var x=s.dataset(id);try(Connection c=s.open(s.connection(String.valueOf(x.get("connection_id"))))){
   var rows=s.readRows(c,"SELECT * FROM ("+DatabaseStore.safeSql(String.valueOf(x.get("sql_body")))+") bi_preview LIMIT ?",List.of(Math.max(1,Math.min(limit,100))),100);
   return Map.of("fields",s.arr(String.valueOf(x.get("fields_json"))),"rows",rows);
  }
 }
 @PostMapping("/query")Map<String,Object> query(@RequestBody Map<String,Object> x)throws Exception{
  var ds=s.dataset(str(x,"datasetId",""));List<Map<String,Object>> fields=s.arr(String.valueOf(ds.get("fields_json")));
  Set<String> allowed=new HashSet<>();for(var f:fields)allowed.add(String.valueOf(f.get("name")));
  List<String> dims=strings(x.get("dimensions"));List<Map<String,Object>> measures=maps(x.get("measures")),filters=maps(x.get("filters"));
  List<String> select=new ArrayList<>(),group=new ArrayList<>();List<Object> params=new ArrayList<>();
  for(String f:dims){field(f,allowed);String col="biq."+DatabaseStore.safeId(f);select.add(col+" AS "+DatabaseStore.safeId(f));group.add(col);}
  for(var m:measures){String f=str(m,"field",""),alias=str(m,"alias",f),agg=str(m,"agg","SUM").toUpperCase(Locale.ROOT);
   if(!Set.of("SUM","AVG","MIN","MAX","COUNT").contains(agg))throw new IllegalArgumentException("聚合方式无效");
   if(!f.equals("*"))field(f,allowed);
   select.add(agg+"("+(f.equals("*")?"*":"biq."+DatabaseStore.safeId(f))+") AS "+DatabaseStore.safeId(alias));
  }
  if(select.isEmpty())throw new IllegalArgumentException("请选择维度或指标");
  StringBuilder sql=new StringBuilder("SELECT ").append(String.join(",",select)).append(" FROM (").append(DatabaseStore.safeSql(String.valueOf(ds.get("sql_body")))).append(") biq");
  List<String> where=new ArrayList<>();
  for(var f:filters){String name=str(f,"field",""),op=str(f,"op","eq");field(name,allowed);
   if(op.equals("in")){
    Object raw=f.get("values");
    if(!(raw instanceof List<?> values)||values.isEmpty()||values.size()>50)throw new IllegalArgumentException("多选筛选最多支持50项且不能为空");
    where.add("biq."+DatabaseStore.safeId(name)+" IN ("+String.join(",",Collections.nCopies(values.size(),"?"))+")");
    for(Object v:values){if(v==null||String.valueOf(v).length()>500)throw new IllegalArgumentException("筛选值无效");params.add(String.valueOf(v));}
    continue;
   }
   String operator=switch(op){case "ne"->"<>";case "gt"->">";case "gte"->">=";case "lt"->"<";case "lte"->"<=";case "contains"->"LIKE";case "eq"->"=";default->throw new IllegalArgumentException("筛选方式无效");};
   where.add("biq."+DatabaseStore.safeId(name)+" "+operator+" ?");String val=String.valueOf(f.getOrDefault("value",""));params.add(op.equals("contains")?"%"+val+"%":val);
  }
  if(!where.isEmpty())sql.append(" WHERE ").append(String.join(" AND ",where));
  if(!measures.isEmpty()&&!group.isEmpty())sql.append(" GROUP BY ").append(String.join(",",group));
  String sort=str(x,"sort",""),dir=str(x,"direction","asc").equalsIgnoreCase("desc")?"DESC":"ASC";
  Set<String> canSort=new HashSet<>(dims);for(var m:measures)canSort.add(str(m,"alias",str(m,"field","")));
  if(!sort.isBlank()){if(!canSort.contains(sort))throw new IllegalArgumentException("排序字段无效");sql.append(" ORDER BY ").append(DatabaseStore.safeId(sort)).append(" ").append(dir);}
  int limit=Math.max(1,Math.min(DatabaseStore.number(x.get("limit"),200),500));sql.append(" LIMIT ?");params.add(limit);
  try(Connection c=s.open(s.connection(String.valueOf(ds.get("connection_id"))))){return Map.of("rows",s.readRows(c,sql.toString(),params,limit));}
 }
 static void field(String f,Set<String> allowed){if(!allowed.contains(f))throw new IllegalArgumentException("字段不存在："+f);}
 static String str(Map<String,Object> x,String k,String def){return DatabaseStore.str(x,k,def);}
 static List<String> strings(Object x){if(!(x instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
 @SuppressWarnings("unchecked")static List<Map<String,Object>> maps(Object x){if(!(x instanceof List<?> l))return List.of();return l.stream().filter(Map.class::isInstance).map(z->(Map<String,Object>)z).toList();}
 @GetMapping("/folders")List<Map<String,Object>> folders(){
  return db.queryForList("SELECT id,name,parent_id,created_at FROM insight_folder ORDER BY created_at,name");
 }
 static String optionalFolder(Map<String,Object> x){
  Object raw=x.get("parentId");return raw==null||String.valueOf(raw).isBlank()?null:String.valueOf(raw);
 }
 void assertFolder(String id){
  if(id!=null&&db.queryForObject("SELECT COUNT(*) FROM insight_folder WHERE id=?",Integer.class,id)!=1)
   throw new NoSuchElementException("文件夹不存在");
 }
 @PostMapping("/folders")Map<String,Object> createFolder(@RequestBody Map<String,Object> x){
  String id=UUID.randomUUID().toString(),name=str(x,"name","").trim(),parent=optionalFolder(x);
  if(name.isEmpty()||name.length()>100)throw new IllegalArgumentException("文件夹名称必须为1到100字符");
  assertFolder(parent);
  db.update("INSERT INTO insight_folder(id,name,parent_id) VALUES(?,?,?)",id,name,parent);
  return Map.of("id",id);
 }
 @PutMapping("/folders/{id}")Map<String,Object> renameFolder(@PathVariable String id,@RequestBody Map<String,Object> x){
  String name=str(x,"name","").trim();
  if(name.isEmpty()||name.length()>100)throw new IllegalArgumentException("文件夹名称必须为1到100字符");
  if(db.update("UPDATE insight_folder SET name=? WHERE id=?",name,id)!=1)throw new NoSuchElementException("文件夹不存在");
  return Map.of("id",id,"name",name);
 }
 @DeleteMapping("/folders/{id}")Map<String,Object> deleteFolder(@PathVariable String id){
  assertFolder(id);
  if(db.queryForObject("SELECT COUNT(*) FROM insight_folder WHERE parent_id=?",Integer.class,id)>0)
   throw new IllegalStateException("文件夹包含子文件夹，请先处理子文件夹");
  if(db.queryForObject("SELECT COUNT(*) FROM insight_dashboard WHERE folder_id=?",Integer.class,id)>0)
   throw new IllegalStateException("文件夹内仍有分析主题，请先移动或删除主题");
  db.update("DELETE FROM insight_folder WHERE id=?",id);
  return Map.of("ok",true);
 }
 @PutMapping("/dashboards/{id}/folder")Map<String,Object> moveDashboard(@PathVariable String id,@RequestBody Map<String,Object> x){
  String folder=optionalFolder(x);
  assertFolder(folder);
  if(db.update("UPDATE insight_dashboard SET folder_id=? WHERE id=?",folder,id)!=1)
   throw new NoSuchElementException("分析主题不存在");
  return Map.of("id",id,"moved",true);
 }
 @GetMapping("/dashboards")List<Map<String,Object>> dashboards(){return db.queryForList("SELECT id,name,mode,status,revision,folder_id,kind,updated_at FROM insight_dashboard ORDER BY updated_at DESC");}
 @PostMapping("/dashboards")Map<String,Object> create(@RequestBody Map<String,Object> x){
  String id=UUID.randomUUID().toString(),name=str(x,"name","新仪表板"),mode=str(x,"mode","grid");mode(mode);
  String kind=str(x,"kind","dashboard");
  if(!Set.of("analysis","dashboard").contains(kind))throw new IllegalArgumentException("分析类型无效");
  if(name.isBlank()||name.length()>150)throw new IllegalArgumentException("分析主题名称无效");
  String folder=optionalFolder(x);
  assertFolder(folder);
  db.update("INSERT INTO insight_dashboard(id,name,mode,content_json,status,revision,updated_at,folder_id,kind) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,?,?)",
    id,name,mode,s.json(Map.of("mode",mode,"widgets",List.of(),"theme","light")),"draft",1,folder,kind);
  return Map.of("id",id,"revision",1);
 }
 @GetMapping("/dashboards/{id}")Map<String,Object> get(@PathVariable String id,@RequestParam(defaultValue="false")boolean published){
  var list=db.queryForList("SELECT * FROM insight_dashboard WHERE id=?",id);if(list.isEmpty())throw new NoSuchElementException("仪表板不存在");
  var row=new LinkedHashMap<>(list.get(0));if(published&&(row.get("published_json")==null||!"published".equals(row.get("status"))))throw new NoSuchElementException("该报表尚未发布");Object raw=published?row.get("published_json"):row.get("content_json");
  row.put("content",s.map(String.valueOf(raw)));row.remove("content_json");row.remove("published_json");return row;
 }
 @PutMapping("/dashboards/{id}")Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> x){
  String name=str(x,"name",""),mode=str(x,"mode","grid");mode(mode);int rev=DatabaseStore.number(x.get("revision"),-1);
  if(name.isBlank()||!(x.get("content") instanceof Map))throw new IllegalArgumentException("无效报表配置");
  String json=s.json(x.get("content"));if(json.length()>2_000_000)throw new IllegalArgumentException("内容过大");
  int count=db.update("UPDATE insight_dashboard SET name=?,mode=?,content_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?",name,mode,json,id,rev);
  if(count==0)throw new IllegalStateException("此报表已被修改，请刷新");
  return Map.of("revision",rev+1);
 }
 @GetMapping("/dashboards/{id}/versions")List<Map<String,Object>> versions(@PathVariable String id){
  if(db.queryForObject("SELECT COUNT(*) FROM insight_dashboard WHERE id=?",Integer.class,id)==0)throw new NoSuchElementException("仪表板不存在");
  return db.queryForList("SELECT version_no,name,mode,created_at FROM insight_dashboard_version WHERE dashboard_id=? ORDER BY version_no DESC",id);
 }
 @PostMapping("/dashboards/{id}/versions/{version}/restore")@Transactional Map<String,Object> restore(@PathVariable String id,@PathVariable int version,@RequestBody Map<String,Object> x){
  var rows=db.queryForList("SELECT name,mode,content_json FROM insight_dashboard_version WHERE dashboard_id=? AND version_no=?",id,version);
  if(rows.isEmpty())throw new NoSuchElementException("历史版本不存在");
  var v=rows.get(0);int revision=DatabaseStore.number(x.get("revision"),-1);
  int changed=db.update("UPDATE insight_dashboard SET name=?,mode=?,content_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?",v.get("name"),v.get("mode"),v.get("content_json"),id,revision);
  if(changed!=1)throw new IllegalStateException("报表版本冲突，请刷新后重试");
  return Map.of("revision",revision+1,"restoredVersion",version);
 }
 @PostMapping("/dashboards/{id}/publish")@Transactional Map<String,Object> publish(@PathVariable String id){
  var list=db.queryForList("SELECT name,mode,content_json FROM insight_dashboard WHERE id=?",id);
  if(list.isEmpty())throw new NoSuchElementException("仪表板不存在");
  var row=list.get(0);
  Integer next=db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM insight_dashboard_version WHERE dashboard_id=?",Integer.class,id);
  db.update("UPDATE insight_dashboard SET published_json=content_json,status='published',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
  db.update("INSERT INTO insight_dashboard_version(id,dashboard_id,version_no,name,mode,content_json) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),id,next,row.get("name"),row.get("mode"),row.get("content_json"));
  return Map.of("ok",true,"version",next);
 }
 @PostMapping("/dashboards/{id}/unpublish")Map<String,Object> unpublish(@PathVariable String id){db.update("UPDATE insight_dashboard SET published_json=NULL,status='draft' WHERE id=?",id);return Map.of("ok",true);}
 @DeleteMapping("/dashboards/{id}")@Transactional Map<String,Object> remove(@PathVariable String id){db.update("DELETE FROM insight_dashboard_version WHERE dashboard_id=?",id);db.update("DELETE FROM insight_dashboard WHERE id=?",id);return Map.of("ok",true);}
 static void mode(String s){if(!Set.of("grid","free").contains(s))throw new IllegalArgumentException("画布模式无效");}
}
@RestControllerAdvice class ApiErrors {
 @ExceptionHandler(Exception.class)ResponseEntity<Map<String,Object>> error(Exception x){
  int status=x instanceof NoSuchElementException?404:x instanceof IllegalStateException?409:x instanceof SQLException?502:400;
  String msg=x instanceof SQLException?"数据库查询失败，请检查连接和SQL":x.getMessage();
  if(msg==null||msg.length()>250)msg="操作失败，请检查服务日志";
  return ResponseEntity.status(status).body(Map.of("error",msg));
 }
}
