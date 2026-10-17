# P1-SEMANTIC-RLS-PREEXEC-AUTH-15：撤权行锁及来源账号权限校验

日期：2026-10-17；分支：feat/finebi-analysis-engine-p0。

## 开发范围

新增仅包内调用的 SemanticRelationRlsRevocationGate.requireCurrentInTransaction。调用方必须持有可写 READ_COMMITTED 的 Spring 元数据事务，先对 insight_governance_user 的读者记录 SELECT ... FOR UPDATE 加锁，再读取当前行列权限并复核 P1-13 双侧参数指纹。未来若接入执行器，元数据锁必须保持到源查询结束或取消；**本批没有开放执行器或读者 JOIN**。

同步修改治理写路径：行权限和列拒绝的新增、更新、删除及账号删除都在首条 ACL 写入前锁住同一个读者行。角色变更、禁用、密码更新本来直接 UPDATE 该身份行，故天然与行锁冲突。绕过治理 API 的外部 SQL 仍可能打破这个锁协议，不能将它称为完整的跨数据库撤权保证。

新增 SemanticRelationRlsSourceGrantGate，仅支持 MySQL 的严格 SHOW GRANTS 语法，必须能识别同一账号的全局 USAGE 与当前库范围的 SELECT，拒绝跨库、全局 SELECT、DDL/DML、角色、GRANT OPTION 和未知授权。需从调用者自己持有的 JDBC 连接读取 SHOW GRANTS，检查元数据库名与真实连接 catalog 一致。**StarRocks 目前一律拒绝**，因为尚未验证其授权语法；不以 JDBC readOnly 标志代替真实账户最小权限。

## 定向验收

新增 SemanticRelationRlsRevocationGateTest：H2 DataSourceTransactionManager + 两个真实线程验证锁内预检、并发撤权等待、失效策略拒绝、READ_COMMITTED/事务读写模式约束；用纯文本及测试代理连接验证 MySQL 账号权限白名单。只验修改影响的后台测试；无任何生产 JDBC/StarRocks 访问。

## 安全边界和后续

本组件没有接入 SemanticRelationRlsExecutionBoundary.prepare、ReaderApi 或正式发布；账号授权校验未在真实 MySQL/StarRocks 上核对，未来还需源侧最小权限证据、同生命周期的业务查询撤权复核、数据库服务器资源限流和重试/取消语义。无 DDL、不影响 18110 或 18130。