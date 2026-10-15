# P3-09 隔离 MySQL 多读者并发和较大数据集验收

日期：2026-10-15。目标：在 P3-08 的单连接性能测试基础上补充真实的**已发布报表读者**路径。只验收用户权限、查询并发与相对较大的固定模拟数据集，不改业务实现、不接触生产数据、不创建正式账号。

## 验收设计及安全边界

新增 `DisposableReaderConcurrencyTest`，只在明确设置 `BI_ACCEPTANCE_DISPOSABLE_MYSQL=yes` 且环境声明 `127.0.0.1:3306`、账号为 `insight_ci` 时运行。JDBC URL 在代码中固定为临时 MySQL 8.4 服务数据库 `insight_ci_acceptance`。数据库实例由 GitHub Actions `.github/workflows/p3-reader-concurrency.yml` 负责创建，作业结束销毁；不会连接用户之前提供的 MySQL 或 StarRocks。

- 数据源：只在临时库内创建 `insight_ci_reader_volume`，写入 **60,000 条**模拟北京、上海、广州收入记录，每城市 20,000 行，每行收入 10，期望对应城市 SUM 200,000。通过 `finally` 删除此临时表。元数据另外存储于临时 H2 内存数据库。
- 读者：创建 **8 个临时 VIEWER 账号**，使用真实 `ReaderApi.login` 登录 8 个不同会话，每个用户必须持有已经发布的 V2 报表授权；其中 4 人只允许北京、4 人只允许上海。数据访问实际走 `ReaderApi.query` → `GovernanceApi.mandatoryRowFilters` → `AnalysisApi.query` → MySQL Connector/J。
- 权限：逐个验证可见的城市与求和结果匹配，禁止客户端通过提交自定义 dataset/filter 绕过发布配置；未授予报表的账号无法查询。并发结束后撤销一名已登录读者的授权，旧会话必须立即无法查询。
- 并发：通过 8 条工作线程同一栅栏起跑，每线程连续发出 3 次已发布组件查询，共 **24 次请求**。仍遵守系统已有的 4 并发、250 ms 排队限制；超过限额的请求应被正常识别为 BusyException（对应 HTTP 429），不自动重试。成功请求必须全程验证行级权限与汇总结果。
- 指标：记录成功和限流次数、成功查询 P50/P95、最终活跃/等待许可归零；输出到 `target/p3-reader-concurrency.json`。GitHub Workflow 保存 JSON 和 Surefire 报告 14 天。

上述“8 个读者”指真实独立登录身份与请求会话，但**并不是 8 个不同浏览器进程或 8 台客户端**。本测试是 JVM 内部并发调用读者控制器（带会话与 CSRF 拦截校验），不包括 Nginx、外网 RTT 或 WebSocket 压力；不能等同于生产 8 人在线的完整端到端负载测试。

## 运行与结果

GitHub Actions → `P3 Disposable Reader Concurrency Acceptance`；仅测试文件或该工作流发生变化时触发，也支持显式手工启动：

```bash
cd backend
# 只允许 CI 或自建 disposable 的 MySQL 8.4 服务，必须指定安全开关和回环地址
mvn -B -q -Dtest=DisposableReaderConcurrencyTest test
```

若未设置隔离 MySQL 环境变量，会**明确跳过**，不得当作已完成 MySQL 并发实测。每次 CI 跑完后应以当次的 Success/Failure、输出 JSON 和测试报告为准；报告中不输出任何数据源密码或原始业务表数据。

## 结果解释限制

- 60k 合成数据和 24 次请求是中等规模的**有限专项检查**，不是百万行或几十用户生产容量极限，不产生稳定性能 SLA。
- 本测试重点验证数据库连接、读者会话、授权和行级过滤的组合正确性；仅 P50/P95 不能证明性能瓶颈来自网络、SQL 优化器或 FE/BE。
- 429 是设计中的限流结果，不应统计为数据泄露或服务器 500；如果每次请求全被拒绝，则测试必须失败。
- 尚需独立测试真正的 HTTP 会话安全、浏览器端延迟、大型数据集、多节点部署、StarRocks 生产级高并发和回滚方案。本轮无生产部署及全量回归。
