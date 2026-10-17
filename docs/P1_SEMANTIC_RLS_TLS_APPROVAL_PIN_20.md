# P1-SEMANTIC-RLS-TLS-APPROVAL-PIN-20：审批凭据与 CA 轮换拒绝

日期：2026-10-17；分支 feat/finebi-analysis-engine-p0。

## 本批最小交付

继续隔离 P1-19 的 TLS 能力，未变更 `DatabaseStore.open()` 的现有非 TLS 生产连接路径，没有新增 HTTP/Reader JOIN、模型发布或 DDL。

**审批凭据绑定**：P1-17 `SemanticRelationRlsApprovedSourceGate.VerifiedSource` 增加连接 ID 与加密凭据文本 SHA-256 摘要（只用于服务端内部，不向前端输出），并隐藏其调试展示。新增 `SemanticRelationRlsTlsConnectionFactory.prepareApproved`，要求已开启、可写的 READ_COMMITTED 元数据事务，在该事务中 `SELECT ... FOR UPDATE` 重新锁定实际连接元数据，严格对比来源主机、端口、库名、账号及**加密凭据版本**。任一项变化或连接被删除，一律拒绝准备。

**CA 信任锚绑定**：部署侧必须传入固定的预期 CA 证书 SHA-256 指纹（从 PKCS#12 内所有有效 CA 的 DER 字节按固定顺序计算）。每次准备重新打开信任库，拒绝丢失/过期的 CA、非 CA 证书、含私钥的信任库、超出数量上限及不等于已配置指纹的有效 CA 替换或增删。只有管理员先可信地更新部署侧固定指纹，下一次准备才能使用新证书。CA 密码仍不进入 JDBC URL，其他 P1-19 的 `VERIFY_IDENTITY` 严格模式不变。

**安全连接链路**：新增内部 `inspectVerifiedTls`，复用 P1-16 的限流、读者权限行锁、关系源指纹复核与源会话身份校验，仅能通过 `openApproved` 创建 Connector/J 连接；P1-16 的旧 `inspect` 注明仅供 H2 模拟代理测试。本阶段没有任何 Web 路由调用可信连接链路，不会主动连接真实生产库。

## 只验改动范围

只扩展 `SemanticRelationRlsTlsConnectionFactoryTest`，离线构造 H2 登记连接和自建测试 CA：确认持锁事务内的凭据使用、改密码/改账号/改 host 必须重新审批；测试两张不同的有效 CA，未变更固定指纹时拒绝轮换，新指纹确认后才能准备；测试证书过期、缺失指纹、无事务 fail-closed。原有 `SemanticRelationRlsExecutionLifecycleTest` 确保旧 H2 代理测试保持稳定。未执行真实 TLS 握手或 MySQL/StarRocks 查询。

## 剩余安全边界

`prepareApproved` 校验 CA 之后，Connector/J 握手时仍会按路径读取信任库，**外部并发替换文件仍可能形成校验与连接使用之间的 TOCTOU 窗口**，不能把当前检查称为原子证书固定。后续若上线必须用不可替换的只读 CA 文件快照、证书生命周期及真实数据库 TLS 握手验证，结合资源与 RLS 绑定值下发的执行侧安全审计。并且新凭据/CA 固定只针对隔离接口，现有报表查询路径未改变；不应误认为生产全站已经强制 TLS。

下一步聚焦**防止 TLS 信任库文件替换竞态**并设计离线握手模拟及真实驱动联调门槛；在此之前 Reader JOIN 和发布继续禁用。
