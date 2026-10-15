package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.FieldCatalog;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end HTTP acceptance, not direct controller calls.
 *
 * A real ephemeral Tomcat server sends JSESSIONID HttpOnly cookies. Eight
 * isolated HttpClient CookieManagers authenticate with reader passwords and
 * query a published dashboard. The only external SQL goes to the throwaway
 * MySQL 8.4 container on loopback.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
  "server.address=127.0.0.1",
  "spring.datasource.url=jdbc:h2:mem:p310_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "BI_CREDENTIAL_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
  "BI_ADMIN_PASSWORD=disposable-ci-admin-password-2026"
})
class HttpReaderSessionAcceptanceTest {
  private static final String SOURCE="ci-http-source";
  private static final String DATASET="ci-http-dataset";
  private static final String DASHBOARD="ci-http-published";
  private static final String WIDGET="ci-http-widget";
  private static final String TABLE="insight_ci_http_reader_fixture";
  private static final String TEST_PASSWORD="ci-http-reader-password-only";
  private static final String MYSQL_URL="jdbc:mysql://127.0.0.1:3306/insight_ci_acceptance"+
    "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=18000";
  private static final ObjectMapper JSON=new ObjectMapper();
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired DatabaseStore store;
  @Autowired GovernanceApi governance;

  private record Reader(String id,String city,HttpClient http,String csrf){}
  private record Reply(int status,String body){}

  private static String env(String key){return Optional.ofNullable(System.getenv(key)).orElse("");}
  private static void onlyDisposable(){
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_DISPOSABLE_MYSQL")),
      "Only the disposable CI MySQL service may be used");
    assertEquals("127.0.0.1",env("BI_ACCEPTANCE_MYSQL_HOST"));
    assertEquals("3306",env("BI_ACCEPTANCE_MYSQL_PORT"));
    assertEquals("insight_ci",env("BI_ACCEPTANCE_MYSQL_USERNAME"));
    assertFalse(env("BI_ACCEPTANCE_MYSQL_PASSWORD").isEmpty());
  }
  private static Connection connect()throws Exception{
    return DriverManager.getConnection(MYSQL_URL,
      env("BI_ACCEPTANCE_MYSQL_USERNAME"),env("BI_ACCEPTANCE_MYSQL_PASSWORD"));
  }
  private static HttpClient newClient(){
    return HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL))
      .connectTimeout(java.time.Duration.ofSeconds(4))
      .build();
  }
  private Reply send(HttpClient client,String method,String path,Object payload,String csrf)
    throws Exception{
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
      .timeout(java.time.Duration.ofSeconds(18));
    if(csrf!=null)request.header("X-CSRF-Token",csrf);
    if("POST".equals(method)){
      request.header("Content-Type","application/json");
      request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
    }else request.GET();
    var response=client.send(request.build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Reply(response.statusCode(),response.body());
  }
  private static Map<String,Object> map(String json)throws Exception{
    return JSON.readValue(json,new TypeReference<Map<String,Object>>(){});
  }

  @Test void browserLikeCookieSessionsEnforceCsrfPublishedAclAndRevocation()throws Exception{
    onlyDisposable();
    try(Connection mysql=connect();Statement schema=mysql.createStatement()){
      schema.execute("CREATE TABLE "+TABLE+"(city VARCHAR(20) NOT NULL,"+
        "revenue DECIMAL(12,2) NOT NULL)");
      try{
        try(PreparedStatement insert=mysql.prepareStatement(
          "INSERT INTO "+TABLE+"(city,revenue) VALUES(?,?)")){
          for(int n=0;n<9000;n++){
            insert.setString(1,n%2==0?"北京":"上海");
            insert.setInt(2,10);
            insert.addBatch();
            if(n%1000==999)insert.executeBatch();
          }
        }
        store.upsertConnection(Map.of("id",SOURCE,"name","Disposable HTTP MySQL",
          "engine","mysql","host","127.0.0.1","port",3306,
          "database","insight_ci_acceptance",
          "username",env("BI_ACCEPTANCE_MYSQL_USERNAME"),
          "password",env("BI_ACCEPTANCE_MYSQL_PASSWORD")));
        db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) "+
          "VALUES(?,?,?,?,?)",DATASET,"HTTP reader test",SOURCE,
          "SELECT city,revenue FROM "+TABLE,
          store.json(List.of(Map.of("name","city","type","VARCHAR"),
            Map.of("name","revenue","type","DECIMAL"))));
        var catalog=new FieldCatalog(store,db);
        var fields=catalog.fields(DATASET);
        String cityField=fields.stream().filter(f->"city".equals(f.sourceColumn()))
          .findFirst().orElseThrow().id();
        String revenueField=fields.stream().filter(f->"revenue".equals(f.sourceColumn()))
          .findFirst().orElseThrow().id();
        var config=Map.of("dimensions",List.of(Map.of("fieldId",cityField)),
          "measures",List.of(Map.of("id","m1","fieldId",revenueField,"aggregate","SUM")),
          "filters",Map.of("pre",List.of(),"post",List.of()),
          "sort",List.of(),"limit",30);
        var widget=Map.of("id",WIDGET,"title","City revenue",
          "type","bar","datasetId",DATASET,"analysisV2",config);
        var content=store.json(Map.of("mode","grid","theme","light","widgets",List.of(widget)));
        db.update("INSERT INTO insight_dashboard"+
          "(id,name,mode,content_json,published_json,status,revision,kind) "+
          "VALUES(?,?,?,?,?,?,?,?)",DASHBOARD,"Published city revenue",
          "grid",content,content,"published",1,"analysis");

        HttpClient anonymous=newClient();
        assertEquals(401,send(anonymous,"GET","/api/reader/catalog",null,null).status());
        assertEquals(401,send(anonymous,"GET","/api/connections",null,null).status());
        assertEquals(401,send(anonymous,"POST",
          "/api/reader/dashboards/"+DASHBOARD+"/widgets/"+WIDGET+"/query",
          Map.of(),null).status());

        List<Reader> readers=new ArrayList<>();
        for(int i=0;i<8;i++){
          String username="ci_http_"+i;
          String city=i%2==0?"北京":"上海";
          var user=governance.createUser(Map.of(
            "username",username,"displayName","HTTP Reader "+i,"role","VIEWER"));
          String id=String.valueOf(user.get("id"));
          governance.setPassword(id,Map.of("password",TEST_PASSWORD));
          governance.setActivation(id,Map.of("enabled",true));
          governance.grantRead(id,Map.of("dashboardId",DASHBOARD));
          governance.setRowPolicy(id,DATASET,
            Map.of("fieldId",cityField,"allowedValue",city));
          HttpClient client=newClient();
          var login=send(client,"POST","/api/reader/login",
            Map.of("username",username,"password",TEST_PASSWORD),null);
          assertEquals(200,login.status(),"HTTP login must create a reader cookie session");
          String csrf=String.valueOf(map(login.body()).get("csrf"));
          assertFalse(csrf.isBlank());
          assertEquals(200,send(client,"GET","/api/reader/catalog",null,null).status());
          assertEquals(200,send(client,"GET",
            "/api/reader/dashboards/"+DASHBOARD,null,null).status());
          assertEquals(401,send(client,"GET","/api/connections",null,null).status(),
            "Reader cookie must never grant admin API access");
          readers.add(new Reader(id,city,client,csrf));
        }

        Reader first=readers.get(0);
        String queryPath="/api/reader/dashboards/"+DASHBOARD+
          "/widgets/"+WIDGET+"/query";
        assertEquals(403,send(first.http(),"POST",queryPath,Map.of(),null).status());
        assertEquals(403,send(first.http(),"POST",queryPath,Map.of(),"wrong-csrf").status());
        assertEquals(400,send(first.http(),"POST",queryPath,
          Map.of("datasetId","forged-unauthorized"),first.csrf()).status());
        assertEquals(200,send(first.http(),"POST",queryPath,Map.of(),first.csrf()).status());

        ExecutorService workers=Executors.newFixedThreadPool(8);
        CountDownLatch ready=new CountDownLatch(8),start=new CountDownLatch(1);
        AtomicInteger success=new AtomicInteger(),limited=new AtomicInteger();
        List<Long> latencies=new CopyOnWriteArrayList<>();
        try{
          List<Future<?>> futures=new ArrayList<>();
          for(Reader reader:readers)futures.add(workers.submit(()->{
            ready.countDown();
            assertTrue(start.await(10,TimeUnit.SECONDS));
            for(int attempt=0;attempt<2;attempt++){
              long began=System.nanoTime();
              Reply reply=send(reader.http(),"POST",queryPath,Map.of(),reader.csrf());
              if(reply.status()==429){limited.incrementAndGet();continue;}
              assertEquals(200,reply.status(),reply.body());
              @SuppressWarnings("unchecked")
              List<Map<String,Object>> rows=(List<Map<String,Object>>)map(reply.body()).get("rows");
              assertEquals(1,rows.size());
              assertEquals(reader.city(),rows.get(0).get("d1"));
              assertEquals(0,new BigDecimal("45000").compareTo(
                new BigDecimal(String.valueOf(rows.get(0).get("m1")))));
              latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));
              success.incrementAndGet();
            }
            return null;
          }));
          assertTrue(ready.await(10,TimeUnit.SECONDS));
          start.countDown();
          for(Future<?> result:futures)result.get(45,TimeUnit.SECONDS);
        }finally{
          start.countDown();workers.shutdownNow();
          assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));
        }
        assertEquals(16,success.get()+limited.get());
        assertTrue(success.get()>0);
        assertEquals(0,AnalysisApi.queryAdmissionSnapshot().active());
        db.update("DELETE FROM insight_dashboard_grant WHERE user_id=? AND dashboard_id=?",
          first.id(),DASHBOARD);
        assertEquals(404,send(first.http(),"POST",queryPath,Map.of(),first.csrf()).status(),
          "Revoking report grant must invalidate cached access immediately");
        Reader second=readers.get(1);
        governance.setActivation(second.id(),Map.of("enabled",false));
        assertEquals(401,send(second.http(),"POST",queryPath,Map.of(),second.csrf()).status(),
          "Disabling account must invalidate existing HTTP session");

        long p50=percentile(latencies,50),p95=percentile(latencies,95);
        String report="{\"records\":9000,\"httpReaders\":8,\"plannedQueries\":16,"+
          "\"successQueries\":"+success.get()+",\"admissionRejected\":"+limited.get()+
          ",\"httpLatencyP50Ms\":"+p50+",\"httpLatencyP95Ms\":"+p95+
          ",\"csrf\":\"enforced\",\"rowAcl\":\"enforced\","+
          "\"revocation\":\"immediate\",\"disabledAccount\":\"rejected\"}\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/p3-http-reader-acceptance.json"),
          report,StandardCharsets.UTF_8);
        System.out.println("P3_HTTP_READER_ACCEPTANCE "+report.trim());
      }finally{
        schema.execute("DROP TABLE IF EXISTS "+TABLE);
      }
    }
  }

  private static long percentile(List<Long> data,int pct){
    if(data.isEmpty())return -1;
    List<Long> sorted=new ArrayList<>(data);
    Collections.sort(sorted);
    return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*pct/100d)-1));
  }
}
