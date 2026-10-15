package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisApi;
import com.insightbi.analysis.AnalysisQueryLimiter;
import com.insightbi.analysis.FieldCatalog;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Eight separate real reader logins, eight row-level policies, 60k MySQL
 * records, 24 concurrent published-widget requests and immediate revocation.
 *
 * Exclusively for a disposable GitHub Actions mysql:8.4 service. The metadata
 * database is a fresh H2 in-memory instance. No production hosts are used.
 */
class DisposableReaderConcurrencyTest {
  private static final String MYSQL_DB="insight_ci_acceptance";
  private static final String TABLE="insight_ci_reader_volume";
  private static final String DASHBOARD="ci-reader-capacity-report";
  private static final String DATASET="ci-reader-capacity-dataset";
  private static final String WIDGET="ci-revenue-widget";
  private static final String SOURCE="ci-reader-capacity-source";
  private static final String PASSWORD="ci-throwaway-readers-only";
  private static final int ROWS=60000;
  private static final int READERS=8;
  private static final int BURST_CALLS_PER_READER=3;

  private static String env(String name){return Optional.ofNullable(System.getenv(name)).orElse("");}

  private static void onlyDisposableMysql(){
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_DISPOSABLE_MYSQL")),
      "Use only disposable MySQL CI containers, never existing databases");
    assertEquals("127.0.0.1",env("BI_ACCEPTANCE_MYSQL_HOST"));
    assertEquals("3306",env("BI_ACCEPTANCE_MYSQL_PORT"));
    assertEquals("insight_ci",env("BI_ACCEPTANCE_MYSQL_USERNAME"));
    assertFalse(env("BI_ACCEPTANCE_MYSQL_PASSWORD").isBlank());
  }

  private static String url(){
    return "jdbc:mysql://127.0.0.1:3306/"+MYSQL_DB+
      "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=18000";
  }
  private static Connection mysql()throws SQLException{
    return DriverManager.getConnection(url(),
      env("BI_ACCEPTANCE_MYSQL_USERNAME"),env("BI_ACCEPTANCE_MYSQL_PASSWORD"));
  }

  private record ReaderSession(String id,String city,HttpSession session){}

  @Test void concurrentPublishedReaderQueriesRetainRowBoundariesAndReleaseSlots()throws Exception{
    onlyDisposableMysql();
    // The CI container owns this exact fixture. CREATE (without IF EXISTS)
    // is intentional: unexpected preexisting data must fail, not be overwritten.
    try(Connection setup=mysql();Statement ddl=setup.createStatement()){
      ddl.execute("CREATE TABLE "+TABLE+" (city VARCHAR(20) NOT NULL,"+
        " revenue DECIMAL(12,2) NOT NULL, bucket INT NOT NULL)");
      try{
        try(PreparedStatement insert=setup.prepareStatement(
          "INSERT INTO "+TABLE+"(city,revenue,bucket) VALUES(?,?,?)")){
          for(int n=0;n<ROWS;n++){
            insert.setString(1,switch(n%3){case 0->"北京";case 1->"上海";default->"广州";});
            insert.setInt(2,10);
            insert.setInt(3,n%20);
            insert.addBatch();
            if(n%1000==999)insert.executeBatch();
          }
        }
        var metadata=new JdbcTemplate(new DriverManagerDataSource(
          "jdbc:h2:mem:reader_capacity_"+UUID.randomUUID()+
          ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        var store=new DatabaseStore(metadata,new ObjectMapper(),
          Base64.getEncoder().encodeToString(new byte[32]));
        store.schema();
        store.upsertConnection(Map.of("id",SOURCE,"name","Disposable MySQL",
          "engine","mysql","host","127.0.0.1","port",3306,
          "database",MYSQL_DB,"username",env("BI_ACCEPTANCE_MYSQL_USERNAME"),
          "password",env("BI_ACCEPTANCE_MYSQL_PASSWORD")));
        metadata.update("INSERT INTO insight_dataset "+
          "(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
          DATASET,"CI capacity",SOURCE,
          "SELECT city,revenue,bucket FROM "+TABLE,
          store.json(List.of(Map.of("name","city","type","VARCHAR"),
            Map.of("name","revenue","type","DECIMAL"),
            Map.of("name","bucket","type","INT"))));
        var catalog=new FieldCatalog(store,metadata);
        var fields=catalog.fields(DATASET);
        String cityField=fields.stream().filter(f->"city".equals(f.sourceColumn()))
          .findFirst().orElseThrow().id();
        String revenueField=fields.stream().filter(f->"revenue".equals(f.sourceColumn()))
          .findFirst().orElseThrow().id();
        var bindings=Map.of("dimensions",List.of(Map.of("fieldId",cityField)),
          "measures",List.of(Map.of("id","m1","fieldId",revenueField,"aggregate","SUM")),
          "filters",Map.of("pre",List.of(),"post",List.of()),
          "sort",List.of(),"limit",50);
        var widget=Map.of("id",WIDGET,"title","Revenue by city",
          "type","bar","datasetId",DATASET,"analysisV2",bindings);
        String published=store.json(Map.of("mode","grid","theme","light",
          "widgets",List.of(widget)));
        metadata.update("INSERT INTO insight_dashboard"+
          "(id,name,mode,content_json,published_json,status,revision,kind) "+
          "VALUES(?,?,?,?,?,?,?,?)",DASHBOARD,"CI published sales",
          "grid",published,published,"published",1,"analysis");
        var governance=new GovernanceApi(metadata);
        var api=new ReaderApi(metadata,store,governance,
          new AnalysisApi(store,metadata,catalog));
        var interceptor=new ReaderAccessInterceptor(metadata);
        List<ReaderSession> sessions=new ArrayList<>();
        for(int n=0;n<READERS;n++){
          String id="ci-reader-"+n;
          String username="ci_reader_"+n;
          String allowed=n%2==0?"北京":"上海";
          String hash=ReaderPassword.hash(PASSWORD);
          metadata.update("INSERT INTO insight_governance_user"+
            "(id,username,display_name,role,enabled,password_hash,auth_version)"+
            " VALUES(?,?,?,?,?,?,?)",id,username,"Reader "+n,
            "VIEWER",true,hash,1);
          governance.grantRead(id,Map.of("dashboardId",DASHBOARD));
          governance.setRowPolicy(id,DATASET,
            Map.of("fieldId",cityField,"allowedValue",allowed));
          var login=new MockHttpServletRequest("POST","/api/reader/login");
          login.setRemoteAddr("192.0.2."+(20+n));
          api.login(Map.of("username",username,"password",PASSWORD),login);
          HttpSession session=login.getSession(false);
          assertNotNull(session);
          assertEquals(id,session.getAttribute("readerId"));
          assertEquals(1,api.catalog(session).size());
          assertEquals(1,api.report(DASHBOARD,session).size()>=1?1:0);
          assertThrows(IllegalArgumentException.class,()->
            api.query(DASHBOARD,WIDGET,Map.of("datasetId","forged"),session));
          sessions.add(new ReaderSession(id,allowed,session));
        }
        var unauthorized=new org.springframework.mock.web.MockHttpSession();
        unauthorized.setAttribute("readerId","unknown-reader");
        assertThrows(NoSuchElementException.class,()->
          api.query(DASHBOARD,WIDGET,Map.of(),unauthorized));

        // Establish correctness for all eight logged-in identities before
        // the burst. Each identity is granted only one city.
        for(ReaderSession reader:sessions)
          assertRestrictedRow(api,DASHBOARD,WIDGET,reader.session(),reader.city());

        ExecutorService workers=Executors.newFixedThreadPool(READERS);
        CountDownLatch ready=new CountDownLatch(READERS);
        CountDownLatch fire=new CountDownLatch(1);
        AtomicInteger accepted=new AtomicInteger(),limited=new AtomicInteger();
        List<Long> latency=new CopyOnWriteArrayList<>();
        long burstStarted=System.nanoTime();
        try{
          List<Future<?>> futures=new ArrayList<>();
          for(ReaderSession reader:sessions)futures.add(workers.submit(()->{
            ready.countDown();
            if(!fire.await(10,TimeUnit.SECONDS))
              throw new AssertionError("Not all reader workers started");
            for(int attempt=0;attempt<BURST_CALLS_PER_READER;attempt++){
              var request=new MockHttpServletRequest("POST",
                "/api/reader/dashboards/"+DASHBOARD+"/widgets/"+WIDGET+"/query");
              request.setSession((org.springframework.mock.web.MockHttpSession)reader.session());
              request.addHeader("X-CSRF-Token",
                String.valueOf(reader.session().getAttribute("csrf")));
              var response=new MockHttpServletResponse();
              assertTrue(interceptor.preHandle(request,response,new Object()));
              long started=System.nanoTime();
              try{
                assertRestrictedRow(api,DASHBOARD,WIDGET,reader.session(),reader.city());
                accepted.incrementAndGet();
                latency.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
              }catch(AnalysisQueryLimiter.BusyException busy){
                limited.incrementAndGet();
              }
              assertTrue(AnalysisApi.queryAdmissionSnapshot().active()<=4);
            }
            return null;
          }));
          assertTrue(ready.await(10,TimeUnit.SECONDS));
          fire.countDown();
          for(Future<?> future:futures)future.get(50,TimeUnit.SECONDS);
        }finally{
          fire.countDown();
          workers.shutdownNow();
          assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));
        }
        assertEquals(READERS*BURST_CALLS_PER_READER,accepted.get()+limited.get());
        assertTrue(accepted.get()>0);
        assertEquals(0,AnalysisApi.queryAdmissionSnapshot().active());
        assertEquals(0,AnalysisApi.queryAdmissionSnapshot().waiting());
        assertEquals(ROWS,metadataCountFixture());
        // Revoking permission must immediately block an already logged-in user.
        ReaderSession revoked=sessions.get(0);
        metadata.update("DELETE FROM insight_dashboard_grant "+
          "WHERE user_id=? AND dashboard_id=?",revoked.id(),DASHBOARD);
        assertThrows(NoSuchElementException.class,()->
          api.query(DASHBOARD,WIDGET,Map.of(),revoked.session()));

        long p50=percentile(latency,50),p95=percentile(latency,95);
        String json="{\"fixtureRows\":"+ROWS+",\"readerSessions\":"+READERS+
          ",\"plannedBurstRequests\":"+(READERS*BURST_CALLS_PER_READER)+
          ",\"accepted\":"+accepted.get()+",\"admissionRejected\":"+limited.get()+
          ",\"latencyP50Ms\":"+p50+",\"latencyP95Ms\":"+p95+
          ",\"maxConcurrentQueries\":4,\"rowPolicy\":\"enforced\",\"revocation\":\"enforced\"}\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/p3-reader-concurrency.json"),
          json,StandardCharsets.UTF_8);
        System.out.println("P3_READER_CONCURRENCY "+json.trim());
      }finally{
        ddl.execute("DROP TABLE IF EXISTS "+TABLE);
      }
    }
  }

  private static int metadataCountFixture()throws Exception{
    try(Connection connection=mysql();
        Statement statement=connection.createStatement();
        ResultSet result=statement.executeQuery("SELECT COUNT(*) FROM "+TABLE)){
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private static void assertRestrictedRow(ReaderApi api,String dashboard,String widget,
       HttpSession session,String allowed)throws Exception{
    Map<String,Object> result=api.query(dashboard,widget,Map.of(),session);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> rows=(List<Map<String,Object>>)result.get("rows");
    assertEquals(1,rows.size(),"Row ACL must prevent cross-city aggregation");
    assertEquals(allowed,rows.get(0).get("d1"));
    assertEquals(0,new BigDecimal("200000").compareTo(
      new BigDecimal(String.valueOf(rows.get(0).get("m1")))));
    assertEquals(false,result.get("truncated"));
  }

  private static long percentile(List<Long> samples,int pct){
    if(samples.isEmpty())return -1;
    List<Long> sorted=new ArrayList<>(samples);
    Collections.sort(sorted);
    return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*pct/100.0)-1));
  }
}
