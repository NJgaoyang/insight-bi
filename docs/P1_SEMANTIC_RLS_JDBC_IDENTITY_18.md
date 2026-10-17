# P1-SEMANTIC-RLS-JDBC-IDENTITY-18：审批连接与实际 JDBC 身份核对

日期：2026-10-17；分支 feat/finebi-analysis-engine-p0。

## 范围

本批只增强 P1-17 的内部隔离生命周期，不新增 Reader API、不放开 JOIN 或模型发布，也没有连接任何生产业务源。

1. 新增 `SemanticRelationRlsSourceIdentityGate`：在同一 JDBC 事务与连接上，校验驱动 `DatabaseMetaData.getURL()/getUserName()`、连接 `getCatalog()` 与已锁定审批记录中的主机、端口、数据库、账号完全匹配；只允许严格的单主机 `jdbc:mysql://host:port/db`，拒绝 replication/load-balancing URL、混淆路径、主机端口和登录账号漂移；在该连接执行 `SELECT CURRENT_USER()` 获取服务端实际生效账号。
2. `SemanticRelationRlsSourceGrantGate` 接收上述服务端生效账号，要求 `SHOW GRANTS` 的所有授权行都指向同一有效账号，并继续仅允许全局 USAGE 和**审批数据库**级 SELECT，额外角色、全局 SELECT、DML/DDL 和未知授权均拒绝。既有文本语法单测允许离线检查，但生产内部门禁路径只调用带身份参数的严格校验。
3. P1-17 `VerifiedSource` 增加已核对的 host、port、username；生命周期不再允许调用方自己选择这些连接属性。身份检查在设置 JDBC readOnly、准备受限 JOIN 语句之前执行，且撤权锁保持至源资源关闭。

## 定向测试

只扩展 `SemanticRelationRlsExecutionLifecycleTest` 和 `SemanticRelationRlsRevocationGateTest`：H2 测试业务表，使用**仅测试存在的模拟 MySQL 连接代理**提供 URL、驱动账号、`CURRENT_USER()` 与 `SHOW GRANTS`；分别测试主机、端口、数据库名、驱动账号、有效账号、非法 URL、无 URL、异常授权，确保在准备 JOIN 之前拒绝且关闭连接。保留双线程撤权与 SQL 上限测试。

## 风险与明确禁止

数据库驱动自报 URL/登录账号及 `CURRENT_USER()` 只能形成一致性证据，**并不是 TLS 服务器证书验证**。现有 `DatabaseStore.open` 的 JDBC URL 含 `useSSL=false`，不能据此宣称已具备生产可认证连接，需后续单独整改并审计 TLS/证书与真实 MySQL/StarRocks 的最小权限。驱动模拟不能替代真实数据库联调。本批没有新增 DDL、没有访问或部署 18110/18130、没有为读者提供 JOIN 执行能力。

下一节点优先核实真实驱动 TLS 身份配置和连接工厂的凭据边界，继续保持正式发布禁用。
