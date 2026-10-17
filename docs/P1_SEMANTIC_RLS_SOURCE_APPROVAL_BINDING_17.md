# P1-SEMANTIC-RLS-SOURCE-APPROVAL-BINDING-17：审批来源、真实连接配置与物理字段闭环

日期：2026-10-17。分支：feat/finebi-analysis-engine-p0。

## 本批交付

本次只收紧 P1-16 的**隔离内部生命周期**，不新增 REST 接口、不执行实际 Reader 查询。

1. 扩展 `SemanticRelationSourceFingerprint`：对已经注册在 `insight_connection` 的来源，新审批指纹使用版本化 SHA-256，除既有两侧数据集 ID、SQL、连接 ID、完整物理字段目录外，也绑定连接元数据 `engine,host,port,db_name,username,credential`。凭据只进入服务端 SHA-256，不返回到前端。修改连接主机、数据库、账号或凭据后，已有审批应失效，需要管理员重新确认；纯展示名变更仍忽略。
2. 新增包内 `SemanticRelationRlsApprovedSourceGate`：必须在读者身份行锁所在的元数据事务里验证关系存在、审批修订、双侧来源 ID 与连接 ID 一致；锁定关系、两侧数据集行、现有字段目录行和连接记录，按新版本指纹对比存储的审批散列。再按当前已审批的关联键和原始字段 ID 重新编译双侧 RLS SQL，要求 SQL 内容、字段 ID 投影和左右绑定指纹完全一致，否则在借用业务连接**之前**拒绝。
3. `SemanticRelationRlsExecutionLifecycle` 不再接受由外部调用者自行提供的 engine/catalog 字符串：只能从已锁定且通过审批的连接记录获取，传入原有来源 `SHOW GRANTS` 白名单。仍然在元数据事务与行锁存在期间完成源语句回调并关闭源资源。

## 旧批准与数据迁移边界

为了保留旧的**草稿、管理员编译预览**及隔离 H2 元数据测试兼容，未注册的连接 ID 在已有审批 API 中仍使用旧 SHA-256 格式；**执行前新增的内部来源门禁对这种关系始终 fail-closed**。旧版已存在且连接已注册的审批哈希与新版不匹配，需要重新审批后才能通过执行隔离准备。本批无 DDL，无自动批量重审批，不启用任何旧凭证。

## 本批定向测试

仅扩展 `SemanticRelationRlsExecutionLifecycleTest`，H2 初始化登记模拟 MySQL 连接并明确执行管理员关系确认；原两线程撤权等待、连接异常释放等用例保留，并补充连接 host/credential 改动、左右 SQL、物理字段隐藏、关系版本、关联键及连接记录被删除时，来源 JDBC 连接不被借用的断言。所有真实 SQL 查询仅在 H2 测试回调中执行；MySQL 授权与只读提示仍由隔离代理模拟。

## 安全限制

本批仍不证明外部 JDBC 连接实际连到了审批配置的 host/port，也无法单靠 READ_COMMITTED 锁阻止在治理协议外插入新的字段或直接修改 ACL。完整的 SQL/RLS 参数真实绑定、生产只读账号与业务 JDBC 撤权事务一致性尚未实测。StarRocks 仍拒绝。Reader API、候选快照的正式发布以及生产 JOIN 仍全部关闭。没有访问或部署 18110/18130。

下一节点应验证实际驱动的连接身份与最小权限，并进一步堵住运行时字段目录的竞态，而不是提前发布 JOIN。
