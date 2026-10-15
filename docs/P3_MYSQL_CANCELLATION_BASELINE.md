# P3-08 一次性 MySQL 查询取消与容量基线验收

## 目标和范围

P3-07 已经在 H2/Mockito 中验证 `PreparedStatement.setQueryTimeout(15)`、连接/Statement 资源释放、HTTP 504 与进程内统计。本批只补齐这些行为在真实 MySQL 8.4 + Connector/J 环境的**驱动级专项验收**，不重复执行完整业务回归。

专用 GitHub Actions：`.github/workflows/p3-mysql-timeout-baseline.yml`，使用 Runner 内的一次性 `mysql:8.4` 服务容器。测试全部固定连接 `127.0.0.1:3306/insight_ci_acceptance`，同时检查 `BI_ACCEPTANCE_DISPOSABLE_MYSQL=yes`、主机端口及专用测试账号 `insight_ci`。无用户提供的生产 MySQL/StarRocks 连接、密码或业务数据参与。

## 验收项目

1. **真实驱动取消**：调用系统 `DatabaseStore.readAnalysisRows`（实际设置 `setQueryTimeout(15)`）执行仅包含 `SLEEP(25)` 的合成慢 SELECT；记录客户端结束耗时，确认驱动报告查询超时而非成功返回，且实测耗时明显短于 25 秒。
2. **连接恢复**：捕获查询取消后，保持**同一个** JDBC Connection 并执行 `SELECT 1`，再用系统的 `readAnalysisRows` 执行短 SELECT；验证客户端可复用，且 `AnalysisExecutionMetrics` 正确记录一次失败和一次超时。
3. **小型容量基线**：仅在临时库内建立 `insight_ci_latency_fixture`，插入 240 条合成城市收入数据。执行 3 次预热、12 次串行参数化聚合、4 并发各 6 次聚合（24 次），验证数值一致，并报告样本 P50/P95（毫秒）。SQL 使用固定 `IN (?,?)` 绑定，单查询最大允许 3 秒。
4. **结果记录**：CI 日志仅打印查询取消耗时、测试数据量、样本数量及 P50/P95，不打印连接密码、业务数据或 SQL；`target/p3-mysql-latency-baseline.json` 和测试报告会上传为保留 14 天的工作流 artifact。测试后通过 `finally` 删除临时测试表，Runner 的 MySQL 容器随作业结束销毁。

工作流仅在本批测试文件或专用工作流文件变化时自动运行，也支持手动触发。其他业务提交不会触发这批 15 秒取消测试。

## 解读基线的限制

- CI Runner 内小表的 P50/P95 **不是**生产 MySQL/StarRocks 的 SLA，也不能据此推断百万行数据集性能；执行规模和网络路径都不同。
- JDBC `setQueryTimeout` 的实际效果依赖驱动、数据库版本、网络和服务端取消能力；即便此 MySQL 测试通过，也不能证明 StarRocks 在所有 SQL/网络异常下严格 15 秒停止。
- 只验证指定 SELECT 读路径，不测试前端大量并发用户、V1 查询接口、FE/BE 故障切换或真实集群的慢 SQL 终止。
- 不把 `SQLTimeoutException` 自动视为连接整体故障，也不因超时自动重试 SQL。失败时保留精准测试报告，不启动无界重试。

本次真正执行结果以 Github Actions `P3 MySQL Query Cancellation and Latency Baseline` 工作流为准；**失败、排队或尚未运行时，不得标为验收通过**。
