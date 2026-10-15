# P3-04 真实 JDBC 专项验收（隔离 MySQL / 可选 StarRocks）

## 目的与安全边界

P3 前三批的数据库连接、V2 查询、元数据备份与故障隔离大多是在 H2 或模拟连接异常下验证。本轮增加真正使用 **MySQL Connector/J 与 MySQL 8.4 服务进程**的隔离验收；另提供只读 StarRocks 检查。

**默认的 GitHub Actions 验收绝不连接公司或公网数据库。** 工作流 `.github/workflows/p3-real-jdbc-acceptance.yml` 在 GitHub Runner 上启动一次性 MySQL 8.4 容器，只在回环地址 `127.0.0.1:3306` 执行测试，自动回收运行环境。工作流内的用户名/密码仅属于这个可丢弃的临时容器，不是外部数据源凭据。测试中 SQL DDL/DML 限定到 `insight_ci_source`、`insight_ci_restore` 和 `insight_ci_acceptance` 三个专用名字，并对运行环境增加显式 `BI_ACCEPTANCE_DISPOSABLE_MYSQL=yes`、主机与端口校验。

## 自动验收内容

通过 `mvn -B -q -Dtest=RealJdbcAcceptanceTest test` 执行：

1. 用 **真实 MySQL** 初始化/重复初始化 20 张 Insight BI 元数据表，验证 MySQL 方言、字段变更和数据库索引兼容，而非 H2 模拟。
2. 在临时 `insight_ci_source` 插入固定的北京、上海测试销售记录（北京 100+50，上海 200）；经真实 MySQL JDBC 连接、V2 SQL 规划器与只读连接策略，验证城市过滤、`SUM` 汇总为 150，以及 JDBC 结果。
3. 从临时 MySQL 元数据生成 AES-GCM 加密逻辑备份，解密校验、再恢复到另外一个全新的 `insight_ci_restore` 数据库；检查数据源凭据密文可用、数据集字段及表结构还原，并拒绝覆盖非空库。**业务测试表不会包含在元数据备份里**。
4. 向本机刻意不可达的测试端口建立真实 Connector/J 连接两次，验证后续请求被熔断器拒绝且不会再次连接；正常源仍可执行 `SELECT 1`。
5. 所有测试通过或失败后销毁临时 MySQL 服务。**不得**将这个测试工作流改为指向实际生产元数据库。

GitHub Actions → `P3 Disposable MySQL JDBC Acceptance` 支持手动运行。该验收会随验收测试本身或工作流配置修改触发，不参与每次普通功能提交的全量回归。

## StarRocks 只读验收（需在获准的测试环境执行）

StarRocks 需由操作员提供专用**只读账号**及允许访问的 FE MySQL 协议端口。当前仓库不会保存任何数据库凭据，也不会自动连接历史配置的远程 IP。

示例：

```bash
export BI_ACCEPTANCE_STARROCKS_JDBC_URL='jdbc:mysql://<TEST_FE_HOST>:9030/information_schema?useSSL=false&connectTimeout=3000&socketTimeout=5000'
export BI_ACCEPTANCE_STARROCKS_USERNAME='<read-only-user>'
export BI_ACCEPTANCE_STARROCKS_PASSWORD='<read-only-password>'
cd backend
mvn -B -q '-Dtest=RealJdbcAcceptanceTest#optionalStarRocksPerformsSelectOnlyWithoutSchemaOrDataMutation' test
```

此验证只执行一次 `SELECT 1`，并检查 JDBC 可以进入 read-only 状态，**不执行** CREATE / INSERT / DROP / UPDATE / DELETE。若没有 `BI_ACCEPTANCE_STARROCKS_JDBC_URL`，JUnit 会将此测试明确跳过；**跳过不能报告成通过**。

后续若需 StarRocks 上完整 V2 多维聚合/分组/时间函数真实验收，应另行使用已授权的数据集和现成的只读测试表，以最小权限查询，不在实际集群中随机创建或删除业务表。

## 仍需补齐的生产验收

- 现有业务 MySQL/StarRocks 数据源的网络中断、FE 重启、权限拒绝、长查询中断及重连专项验收；
- 真正最小权限数据源账号与 JDBC read-only 提示的双重验证；
- H2→MySQL 元数据迁移与生产数据一致性核对、旧报表数据验证；
- 负载压测、恢复时间目标（RTO）、恢复点目标（RPO）、监控报警及回滚演练。

在这些场景完成前，本批只能表述为**隔离 MySQL 8.4 实例通过/失败**及**StarRocks 只读验证通过/跳过**，不能说“已验收生产 MySQL/StarRocks”。
