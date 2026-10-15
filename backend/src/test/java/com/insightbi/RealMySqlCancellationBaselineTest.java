package com.insightbi;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.analysis.AnalysisExecutionMetrics;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * True Connector/J cancellation + bounded latency baseline.
 * Safety: only disposable GitHub Actions MySQL on 127.0.0.1; no user data.
 */
class RealMySqlCancellationBaselineTest {
  private static final String DATABASE="insight_ci_acceptance";
  private static final String TABLE="insight_ci_latency_fixture";
  private static final String URL="jdbc:mysql://127.0.0.1:3306/"+DATABASE+
    "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=18000";

  private static String env(String key){return Optional.ofNullable(System.getenv(key)).orElse("");}
  private static void disposableOnly(){
    Assumptions.assumeTrue("yes".equals(env("BI_ACCEPTANCE_DISPOSABLE_MYSQL")),
      "This test runs only in a disposable MySQL CI container");
    assertEquals("127.0.0.1",env("BI_ACCEPTANCE_MYSQL_HOST"));
    assertEquals("3306",env("BI_ACCEPTANCE_MYSQL_PORT"));
    assertEquals("insight_ci",env("BI_ACCEPTANCE_MYSQL_USERNAME"));
    assertFalse(env("BI_ACCEPTANCE_MYSQL_PASSWORD").isBlank());
  }
  private static Connection connect()throws SQLException{
    return DriverManager.getConnection(URL,
      env("BI_ACCEPTANCE_MYSQL_USERNAME"),env("BI_ACCEPTANCE_MYSQL_PASSWORD"));
  }

  @Test void connectorJActuallyCancelsFifteenSecondV2QueryAndConnectionRecovers()throws Exception{
    disposableOnly();
    var db=new JdbcTemplate(new DriverManagerDataSource(URL,
      env("BI_ACCEPTANCE_MYSQL_USERNAME"),env("BI_ACCEPTANCE_MYSQL_PASSWORD")));
    var store=new DatabaseStore(db,new ObjectMapper(),
      Base64.getEncoder().encodeToString(new byte[32]));
    var metrics=new AnalysisExecutionMetrics();
    try(Connection connection=connect()){
      connection.setReadOnly(true);
      assertTrue(connection.isReadOnly());
      long started=System.nanoTime();
      SQLException error=assertThrows(SQLException.class,()->
        // SLEEP is only synthetic waiting on the disposable MySQL instance;
        // it never references any existing table or business data.
        store.readAnalysisRows(connection,
          "SELECT SLEEP(25) AS artificial_delay, 1 AS marker",List.of(),1));
      long elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
      metrics.record(started,error);
      assertTrue(AnalysisExecutionMetrics.isTimeout(error),
        "Connector/J must classify this deadline as a query timeout, SQLState="+error.getSQLState());
      assertTrue(elapsedMs>=10000 && elapsedMs<23000,
        "Expected 15s query cancellation, elapsedMs="+elapsedMs);
      assertEquals(1L,metrics.snapshot().get("timedOutQueries"));
      assertEquals(1L,metrics.snapshot().get("failedQueries"));
      // Cancel should not strand the physical connection or leave a running query.
      try(Statement statement=connection.createStatement()){
        statement.setQueryTimeout(3);
        try(ResultSet result=statement.executeQuery("SELECT 1")){
          assertTrue(result.next());
          assertEquals(1,result.getInt(1));
        }
      }
      assertEquals(1,store.readAnalysisRows(connection,
        "SELECT 7 AS recovered_value",List.of(),1).size());
      System.out.println("P3_MYSQL_CANCEL cancelled=true timeoutConfiguredSeconds=15"+
        " elapsedMs="+elapsedMs+" sameConnectionRecovered=true");
    }
  }

  @Test void tinyReadOnlyFixtureProducesRepeatableSerialAndConcurrentLatencyBaseline()
      throws Exception{
    disposableOnly();
    try(Connection setup=connect();Statement ddl=setup.createStatement()){
      ddl.execute("CREATE TABLE "+TABLE+
        "(city VARCHAR(20) NOT NULL,revenue DECIMAL(12,2) NOT NULL)");
      try{
        try(PreparedStatement insert=setup.prepareStatement(
          "INSERT INTO "+TABLE+"(city,revenue) VALUES(?,?)")){
          for(int n=0;n<240;n++){
            insert.setString(1,switch(n%3){case 0->"北京";case 1->"上海";default->"广州";});
            insert.setInt(2,10);
            insert.addBatch();
          }
          insert.executeBatch();
        }
        List<Long> serial=new ArrayList<>();
        try(Connection c=connect()){
          c.setReadOnly(true);
          for(int n=0;n<3;n++)timedAggregate(c); // warmup
          for(int n=0;n<12;n++)serial.add(timedAggregate(c));
        }
        ExecutorService workers=Executors.newFixedThreadPool(4);
        List<Long> parallel=new ArrayList<>();
        try{
          List<Future<List<Long>>> results=new ArrayList<>();
          for(int worker=0;worker<4;worker++)
            results.add(workers.submit(()->{
              List<Long> durations=new ArrayList<>();
              try(Connection c=connect()){
                c.setReadOnly(true);
                for(int n=0;n<6;n++)durations.add(timedAggregate(c));
              }
              return durations;
            }));
          for(Future<List<Long>> result:results)
            parallel.addAll(result.get(30,TimeUnit.SECONDS));
        }finally{workers.shutdownNow();}
        assertEquals(12,serial.size());
        assertEquals(24,parallel.size());
        long serialP50=percentile(serial,50),serialP95=percentile(serial,95);
        long concurrentP50=percentile(parallel,50),concurrentP95=percentile(parallel,95);
        assertTrue(concurrentP95<10000,
          "Small CI fixture unexpectedly slow: p95="+concurrentP95+" ms");
        String report="{\"fixtureRows\":240,\"serialQueries\":12,\"serialP50Ms\":"+
          serialP50+",\"serialP95Ms\":"+serialP95+
          ",\"concurrency\":4,\"concurrentQueries\":24,\"concurrentP50Ms\":"+
          concurrentP50+",\"concurrentP95Ms\":"+concurrentP95+"}\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/p3-mysql-latency-baseline.json"),
          report,StandardCharsets.UTF_8);
        System.out.println("P3_MYSQL_LATENCY_BASELINE "+report.trim());
      }finally{
        ddl.execute("DROP TABLE IF EXISTS "+TABLE);
      }
    }
  }

  private static long timedAggregate(Connection connection)throws Exception{
    long started=System.nanoTime();
    try(PreparedStatement statement=connection.prepareStatement(
      "SELECT city,SUM(revenue) AS total FROM "+TABLE+
        " WHERE city IN (?,?) GROUP BY city ORDER BY city")){
      statement.setQueryTimeout(3);
      statement.setString(1,"北京");
      statement.setString(2,"上海");
      try(ResultSet result=statement.executeQuery()){
        int count=0;
        while(result.next()){
          assertEquals(0,new BigDecimal("800").compareTo(result.getBigDecimal("total")));
          count++;
        }
        assertEquals(2,count);
      }
    }
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
  }

  private static long percentile(List<Long> samples,int pct){
    var sorted=new ArrayList<>(samples);
    Collections.sort(sorted);
    return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*pct/100.0)-1));
  }
}
