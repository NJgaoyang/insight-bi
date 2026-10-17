# P1-SEMANTIC-RLS-TLS-CONNECTION-19：独立的 MySQL TLS 身份校验连接工厂

日期：2026-10-17；分支：feat/finebi-analysis-engine-p0。

## 目标和范围

本次新增包内 `SemanticRelationRlsTlsConnectionFactory`，仅构建一条**将来**可用于受控 RLS JOIN 的 MySQL Connector/J 安全连接，**没有接入**现有 `DatabaseStore.open()`、任何 Reader 路由、候选发布或生产数据源。旧有单表仪表板连接方式保持原样，避免未经现场验证的 TLS 改动使线上报表不可用。

新连接工厂只接受审批配置的 mysql 连接信息，要求合法单主机、端口、数据库和账号；传入独立 PKCS#12 CA 信任库与口令。预检要求信任库是可读常规文件、不能是符号链接、能被正确口令载入，并至少含一个当前有效的 X.509 CA 证书。使用 Connector/J `sslMode=VERIFY_IDENTITY`（校验服务器证书及主机名），显式禁用 `fallbackToSystemTrustStore` 和 `allowPublicKeyRetrieval`，设置连接/网络超时各 5000ms。密码和信任库口令存放 JDBC Properties，不作为 URL query，配置对象调试输出不会打印凭据。

`SemanticRelationRlsTlsConnectionFactory.requireVerifiedUrl` 只接受这些预期且唯一的连接参数，拒绝重复 SSL Mode、隐式默认值、`useSSL=false`、`REQUIRED`、`VERIFY_CA`、`PREFERRED`、授权降级选项和带身份/片段/混淆路径的 URL。

同时收紧 P1-18 的 `SemanticRelationRlsSourceIdentityGate`：JDBC 驱动报告 URL 必须符合上述安全子语法，并用同一连接的 `SHOW SESSION STATUS LIKE 'Ssl_cipher'` 读取会话已协商的非空 TLS 套件；然后才进一步核对服务端 `CURRENT_USER()` 与 `SHOW GRANTS`。这只是运行时附加证据，仍必须由真正 Connector/J 执行证书信任链和主机名校验，绝不能仅因 SQL 结果或 URL 声称加密就判定安全。

## 定向测试

新增 `SemanticRelationRlsTlsConnectionFactoryTest`：测试专用、自建 CA 证书写入临时 PKCS#12，验证强制属性及口令不出现在 URL/调试输出；针对错误信任库口令、缺失/空/符号链接、非法 host 和弱 TLS URL 参数 fail-closed。扩展 `SemanticRelationRlsExecutionLifecycleTest`，测试代理返回 SSL cipher、驱动 URL 和实际身份，加入无 TLS/低等级 TLS/混入遗留参数/重复 mode/空 cipher 时全部拒绝，仍不接任何真实 MySQL 或 StarRocks。

## 特别说明

**生产尚不可用。** 现有 `DatabaseStore.open()` 仍含 `useSSL=false`；TLS 工厂不会自动替换该路径，本批也不会部署或读取真实生产凭据。尚未执行真实 MySQL 握手、服务器证书链及主机名匹配测试，CA 吊销、轮换、源端连接池及跨库权限问题尚待验证。由于新安全门禁会拒绝旧连接工厂构造的 URL，受控 JOIN 保持内部隔离，Reader JOIN 和正式发布继续禁用。无 DDL，不操作 18110/18130。

后续若要接入受控读者执行，必须从已锁定的审批连接记录创建验证 TLS 的真实连接、验证证书轮换和运行期撤权；不能通过模拟 H2 代理绕过。
