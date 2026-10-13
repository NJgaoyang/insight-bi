package com.insightbi.analysis;

import java.util.Locale;
import java.util.Set;

/**
 * Narrow exception for the user-confirmed, dedicated DataForge test StarRocks FE.
 * This only authorizes the synthetic SELECT reconciliation test, not BI server
 * connections, permissions, business writes, or production deployments.
 */
final class DataForgeStarRocksTestAccess {
  private static final Set<String> DATAFORGE_TEST_FE=Set.of(
      "172.17.0.12:9030", "81.69.15.136:9030");

  private DataForgeStarRocksTestAccess(){}

  static void require(String jdbcUrl,String username,boolean confirmedTestEnvironment){
    if(jdbcUrl==null||!jdbcUrl.startsWith("jdbc:mysql://"))
      throw new IllegalArgumentException("仅支持 StarRocks 的 MySQL JDBC 地址");
    if(username==null||username.isBlank())
      throw new IllegalArgumentException("StarRocks 测试账号不能为空");
    if(!Set.of("root","admin","administrator").contains(username.toLowerCase(Locale.ROOT)))
      return;

    String afterPrefix=jdbcUrl.substring("jdbc:mysql://".length());
    int slash=afterPrefix.indexOf('/');
    String address=slash<0?"":afterPrefix.substring(0,slash);
    if(!confirmedTestEnvironment||!DATAFORGE_TEST_FE.contains(address))
      throw new IllegalStateException(
        "管理员账号仅可用于明确确认的 DataForge 专用 StarRocks 测试 FE");
  }
}
