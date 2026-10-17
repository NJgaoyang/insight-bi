# P1-SEMANTIC-RLS-EXECUTION-BOUNDARY-14：隔离的只读 JOIN JDBC 准备门禁

日期：2026-10-17；开发分支：feat/finebi-analysis-engine-p0。

## 本批交付

新增包内可见 `SemanticRelationRlsExecutionBoundary`，**没有绑定任何 Web API、Reader 查询链路或外部连接工厂**。它只提供获取并发额度与准备 JDBC `PreparedStatement` 的内部能力，**不会执行** `executeQuery()`，**不会**读取或者返回任何权限允许值，不持久化或发布计划。

包内 `reserve(plan,attestation)` 在连接被借用之前取得最多两个并发额度（零等待）。`prepare(connection,plan,attestation,reservation)` 验证计划 scope、INNER/LEFT 关联、生成的 200 行上限、投影/参数数量、预检绑定计数与 SHA-256 形状、SQL 只读 SELECT AST、占位符匹配、已持有的额度和调用方显式关闭 autocommit 的 JDBC 事务。连接必须支持 `setReadOnly(true)` 且 `isReadOnly()` 为真；构建 forward-only/read-only `PreparedStatement`，设置 5 秒 query timeout、200 最大返回行和 fetch size=32；数据库驱动忽略关键 timeout/row limit 时拒绝并释放 statement。并发额度需调用方显式关闭，不能在等待额度时先借数据库连接。

## 专项 H2 验证

新测试 `SemanticRelationRlsExecutionBoundaryTest`，在模拟元数据及虚拟隔离 H2 业务表中演示安全编译计划和预检证明如何准备 JDBC 语句，并**仅在测试代码内**显式绑定固定样例值执行 H2 查询以验证 LEFT JOIN 的空右侧行、5 秒超时、200 行上限、只读状态、fetch size、没有调用方事务时拒绝、伪造 SQL 与绑定计数拒绝、释放额度失效和超过两条并发调用立即拒绝。生产中不存在此绑定或执行调用链。

## 尚不能开放读者 JOIN 的原因

JDBC read-only 只是一条驱动提示，不能替代只读账户；超时/返回上限无法严格保证 JOIN 扫描量；此门禁尚未把允许值安全绑定进 SQL，不能处理账号撤权的跨元数据/源连接竞态，也没有数据源白名单和真实 StarRocks 查询资源组约束。因此依旧禁止利用本组件或 P1-12/P1-13 的加密票据向读者授予执行权。无 DDL，不修改 access-review、ReaderApi、发布审批以及 18110/18130 运行环境。

下一节点需优先完成真实执行前读者身份、业务源同连接及账户最小权限证明，并设计撤权与查询并发的竞争测试；明确执行时事务边界后才能考虑新的可发布路径。

### H2 JDBC 只读标志的驱动差异

GitHub Actions 的原生 H2 连接对 `setReadOnly(true)` 不会可靠回报 `isReadOnly() == true`；正式门禁保持 **fail-closed**，原生 H2 的负向用例断言拒绝。只有测试正向隔离演示使用 `readOnlyHintDriverStub` 包装 H2 `Connection` 模拟能正确回报只读状态的驱动，然后才验证 prepared statement 设置和测试内 JOIN 语义。该代理**不是数据库只读保证**，也不允许进入生产运行路径。未来需要真实源数据库以只读用户实测，而不是改写门禁放过原生 H2。
