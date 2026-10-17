# P1-SEMANTIC-RLS-PLAN-SNAPSHOT-12：受控编译计划的一致性票据

日期：2026-10-17；分支 `feat/finebi-analysis-engine-p0`。

## 最小交付

新增管理员专用 `POST /api/model/semantic-relations/{id}/rls-plan-snapshot`，沿用固定五字段的 RLS 编译请求，返回 `{status:CAPTURED_ONLY,executable:false,ttlSeconds:600,ticket:<AES-GCM 密文>}`，**绝不发放可执行查询授权**。票据由服务端 `DatabaseStore.encrypt` 现有 AES-GCM 密钥生成，包含关系 ID、当前审批修订、双侧来源 SHA-256 指纹、受控 JOIN/输出字段绑定、生成 SQL 散列、完整读者策略快照散列及签发时间。明文行级 allowed_value 不随响应返回。

新增管理员专用 `POST /api/model/semantic-relations/{id}/rls-plan-verify`，只接受 `{ticket}`。重新读取两侧来源、关联修订和字段目录、账号 role/enabled/auth_version、列拒绝、行策略主记录、所有允许值，并重新运行只读 SQL 编译，对比票据绑定及生成 SQL 散列。返回 `CURRENT` 或 `STALE`，且 `executable` 永远为 `false`；无任何 Reader SQL 执行/发布授权。票据有效期 600 秒，关系与路径不匹配或密文损坏拒绝。

在已有元数据事务的 `REPEATABLE_READ` 快照中生成及核验；生成时编译前后对比源与权限证据，避免返回已知失效的计划，核验时同样双重重读。此处依然无法替代未来实际查询执行时的事务边界、真实权限下推或源连接级授权。

## 专项测试

仅 `SemanticRelationRlsPlanPreviewTest` 增补 H2：加密票据有效性，未提升 auth_version 的后台直接 allowlist 变更仍然触发 STALE，账号认证版本、来源 SQL、禁用账号、JOIN 编译形状变更、密文损坏与过期均拒绝或失效。CI 中已有 P0 变更区域测试自动筛选该类，避免重复跑全量套件。

## 不变边界

无新 DDL、无持久化可执行凭证；没有放宽已有保守 access-review、候选 releaseNo、草稿正式发布及 Reader API 禁止 JOIN 的规则。未访问真实 StarRocks，未部署 18110 / 18130。

下一节点：在真正开放读者侧双侧 JOIN 前，设计并验证逐请求、同事务的策略复核与源连接只读执行约束；不能拿本票据直接充当运行权限。
