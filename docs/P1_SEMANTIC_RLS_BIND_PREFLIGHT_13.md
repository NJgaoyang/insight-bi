# P1-SEMANTIC-RLS-BIND-PREFLIGHT-13 · 逐请求双侧绑定校验（2026-10-17）

## 目标和范围

本批只增强 P1-12 管理员 **rls-plan-snapshot / rls-plan-verify**；不增加第三个 REST 接口、不创建真正的数据源执行器。保留原 `@Transactional(readOnly=true, isolation=REPEATABLE_READ)` 的元数据事务，对每次管理员票据生成和核验均新增内部 `SemanticRelationRlsBindPreflight.inspect`，在同一个请求事务里重新读取读者的 role/enabled/auth_version、列拒绝和两侧行策略原始表记录，按 **LEFT 再 RIGHT** 次序确定 0～20 个参数以及字段稳定 ID。

预检比对编译 SQL `?` 个数与每侧策略参数数量（来源仍由 P1-11 严格拒绝运行时问号），严格拒绝孤立的行策略值、行策略字段过期、读者禁用、同身份认证版本漂移及未绑定 SQL。将双侧字段 ID 和完整参数值的有长度前缀 SHA-256 指纹放入原有 AES-GCM 加密快照票据，格式升级 v2；旧 v1 票据 fail-closed。服务端不会把值或绑定数组放入 API，`executable=false` 与正式发布限制不变。

## 验收

仅以 `SemanticRelationRlsPlanPreviewTest` 定向 H2 检查左右绑定数量、值等数量变动导致的 hash 变化、孤立记录、SQL `?` 缺失或多余、旧版票据和受保护绑定散列篡改。原有测试保留并在专项运行中被覆盖。

## 关键限制

重复读元数据的同一数据库事务不能阻止**事务提交后**另一管理员再次改权限，因此此功能只作为后续执行器必须使用的内部准备检查，不能用当前票据在其他事务中直接执行 JOIN。后续如要开放真实读者 JOIN，必须把重新授权、字段校验、只读数据库连接与 JDBC 参数绑定放在实际执行请求的安全边界之内。

未修改 Reader API、审批及发布安全门禁、没有 DDL，也不触碰生产端口 18110 或预览 18130。
