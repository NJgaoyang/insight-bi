# P1-SEMANTIC-RLS-EXECUTION-LIFECYCLE-16

日期：2026-10-17；分支 feat/finebi-analysis-engine-p0。

新增包内可见的 SemanticRelationRlsExecutionLifecycle，用于测试双侧 RLS 的元数据锁与来源 JDBC 生命周期，未接入 Reader API、Web 端点和实际生产连接。

顺序：取得并发额度 -> 启动 READ_COMMITTED 元数据事务 -> 锁定读者身份并核验行列策略 -> 借用测试来源连接 -> 检查 MySQL SHOW GRANTS 与 JDBC readOnly -> 准备限时、限行的 JDBC 语句 -> 测试回调 -> 关闭语句并回滚、关闭来源连接 -> 提交或回滚元数据事务并释放锁 -> 释放并发额度。

组件本身不执行 SQL、不绑定读者权限值、不发放任何执行授权。H2 专项测试通过模拟 MySQL 授权查询和只读驱动提示来验证撤权等待、资源关闭顺序、异常释放、篡改参数和未知授权拒绝。测试中的模拟代理不等于真实 MySQL 或 StarRocks 授权验证。

真实读者 JOIN 仍关闭。后续需验证两侧批准的来源和连接指纹、执行时授权值绑定、真实源账户只读及跨数据库事务边界；治理 API 之外的写入不受当前应用行锁协议完全约束。无 DDL、无生产部署。
