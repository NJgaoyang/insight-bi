package com.insightbi.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightbi.DatabaseStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only tests concurrent field catalog first-load, not the rest of BI. */
class FieldCatalogConcurrencyTest {
  @Test void simultaneousFirstLoadsUseOneStableFieldId() throws Exception {
    String jdbcUrl="jdbc:h2:mem:fields_concurrent_"+UUID.randomUUID()+
      ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
    JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource(jdbcUrl,"sa",""));
    DatabaseStore store=new DatabaseStore(db,new ObjectMapper(),
       Base64.getEncoder().encodeToString(new byte[32]));
    store.schema();
    String dataset="shared";
    db.update("INSERT INTO insight_dataset(id,name,connection_id,sql_body,fields_json) VALUES(?,?,?,?,?)",
      dataset,"Shared Dataset","connection","SELECT city,revenue FROM sales",
      store.json(List.of(Map.of("name","city","type","VARCHAR","numeric",false),
                         Map.of("name","revenue","type","DECIMAL","numeric",true))));
    FieldCatalog catalog=new FieldCatalog(store,db);
    int parallel=12;
    ExecutorService pool=Executors.newFixedThreadPool(parallel);
    CountDownLatch ready=new CountDownLatch(parallel),go=new CountDownLatch(1);
    try {
      List<Future<List<FieldCatalog.Field>>> futures=new ArrayList<>();
      for(int i=0;i<parallel;i++){
        futures.add(pool.submit(()->{
          ready.countDown();
          if(!go.await(5,TimeUnit.SECONDS))throw new IllegalStateException("同步开始超时");
          return catalog.fields(dataset);
        }));
      }
      assertTrue(ready.await(5,TimeUnit.SECONDS));
      go.countDown();
      Set<String> firstIds=new HashSet<>();
      for(var future:futures){
        var fields=future.get(20,TimeUnit.SECONDS);
        assertEquals(2,fields.size());
        firstIds.add(fields.get(0).id());
      }
      assertEquals(1,firstIds.size(),"simultaneous requests must use the same immutable ID");
      assertEquals(2,db.queryForObject(
        "SELECT COUNT(*) FROM insight_dataset_field WHERE dataset_id=?",Integer.class,dataset));
    }finally{
      go.countDown();
      pool.shutdownNow();
    }
  }
}
