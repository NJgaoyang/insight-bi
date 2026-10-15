package com.insightbi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offline-only operator recovery. Explicitly targets a fresh, empty metadata
 * database; this tool never accepts a force-overwrite option.
 *
 * Run with the original BI_CREDENTIAL_KEY and a new, isolated JDBC URL.
 * HTTP restore is deliberately unsupported.
 */
public final class MetadataRestoreCli {
  private MetadataRestoreCli(){}

  public static void main(String[] args)throws Exception{
    if(args.length!=2||!"--confirm-empty-only".equals(args[1]))
      throw new IllegalArgumentException(
        "Usage: MetadataRestoreCli /absolute/path/file.ibak --confirm-empty-only");
    String url=System.getenv("SPRING_DATASOURCE_URL");
    String expected=System.getenv("BI_RESTORE_EXPECTED_JDBC_URL");
    String secret=System.getenv("BI_CREDENTIAL_KEY");
    if(url==null||url.isBlank()||!url.equals(expected)||
       secret==null||secret.isBlank())
      throw new IllegalStateException(
        "必须配置 SPRING_DATASOURCE_URL、匹配的 BI_RESTORE_EXPECTED_JDBC_URL 和原始 BI_CREDENTIAL_KEY");
    Path source=Path.of(args[0]).toAbsolutePath().normalize();
    if(!Files.isRegularFile(source)||Files.size(source)>32*1024*1024+512)
      throw new IllegalArgumentException("加密备份文件不存在或超出上限");
    byte[] encrypted=Files.readAllBytes(source);
    var datasource=new DriverManagerDataSource(url,
      System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME","sa"),
      System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD",""));
    var db=new JdbcTemplate(datasource);
    var store=new DatabaseStore(db,new ObjectMapper(),secret);
    // Idempotent additive initialization is required for an empty target.
    store.schema();
    var service=new MetadataBackupService(store,db,new ObjectMapper());
    var result=service.verify(encrypted);
    System.out.println("加密备份校验通过；数据表："+result.get("tableCount")+
      "，记录："+result.get("rowCount"));
    var tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
    tx.executeWithoutResult(status->service.restoreIntoEmpty(encrypted));
    System.out.println("已恢复到预先确认的空数据库。请在隔离环境完成业务检查后再切换访问。");
  }
}
