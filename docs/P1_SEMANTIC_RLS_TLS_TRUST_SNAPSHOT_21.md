# P1-SEMANTIC-RLS-TLS-TRUST-SNAPSHOT-21 — MySQL TLS 信任库快照隔离

日期：2026-10-17；分支：feat/finebi-analysis-engine-p0。

## 本批修复

P1-20 的 `openApproved` 原本先检查管理员 CA 文件指纹，再将同一文件 URI 传给 MySQL Connector/J。如果原始文件在检查与驱动真正握手之间轮换，驱动就可能读取未经过检查的 CA。

新增仅包内使用的 `SemanticRelationRlsTrustSnapshot`：在审批凭据、元数据事务及 CA 固定指纹校验之后，从来源文件打开拒绝符号链接的只读句柄，读取**不超过 2 MiB** 的原始 PKCS#12 字节，重新解析所有 CA 内容、有效期并核对部署固定指纹。字节暂存在内存，拷贝到操作系统临时目录下的随机文件；Linux/POSIX 严格要求目录权限 `0700`，临时信任库创建时 `0600`，写完收紧为 `0400`。不支持 POSIX 文件权限的环境直接拒绝，不尝试不安全降级。拷贝完成清零临时原始字节数组。快照不共享、不缓存。

`SemanticRelationRlsTlsConnectionFactory.openApproved` 通过同一逻辑构造 `trustCertificateKeyStoreUrl` 指向**私有快照**，不再指向运维可轮换的原始 CA 文件。使用 Connector/J `sslMode=VERIFY_IDENTITY` 执行握手，在连接建立或失败后立即删除快照文件及目录；如果已得到 JDBC 连接但快照删除失败，则关闭连接并拒绝返回。原有 `prepareApproved` 保留为无网络的事前验证，不是握手或执行授权。

## 受影响区域专项验收

扩展 `SemanticRelationRlsTlsConnectionFactoryTest`，H2 元数据 + 自建、一次性 CA 根证书：在模拟握手回调期间替换运维 CA 文件，断言驱动所见快照内容和指纹始终维持旧批准值；原文件轮换后后续启动必须拒绝，不能自动回退。异常握手应清除快照，2 MiB 以上、不匹配指纹、符号链接均在驱动调用前拒绝。验证仅容许当前操作系统用户访问快照文件，并保证结束后目录被清理。

测试握手回调使用包内模拟连接注入，只验证文件隔离及销毁时序；**没有连接真实 MySQL，也没有证明真实服务器证书 SAN/主机名以及吊销状态**。

## 限制与风险

快照可以隔离**原始 CA 路径**在检查与握手间的替换，但不防同一运行账户或具备 root 权限的恶意进程直接修改刚创建的私有快照；所以不能宣称解决所有本机特权攻击，也不能取代可信部署权限管理。Connector/J 如在成功返回 Connection 后重读此前的 trustCertificateKeyStoreUrl，则快照已被清理，该行为必须在真实驱动环境确认。P1-16 的 H2 调用入口仍保留并且未映射给任何 Reader API；安全连接没有任何生产调用方。

不修改 `DatabaseStore.open()` 或生产数据端口，未开放 Reader JOIN、候选发布、真实业务连接，没有 DDL。后续必须使用真实 MySQL + 正式证书链做一轮握手成功、错误 SAN 和 CA 撤销的隔离联调，再考虑任何执行授权。
