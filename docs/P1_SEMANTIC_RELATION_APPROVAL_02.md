# P1-SEMANTIC-RELATION-APPROVAL-02：语义关系草稿的版本确认

本批仅对现有元数据草稿添加**管理员版本确认状态**，不提供查询执行权限，也不替代对多表模型的 SQL 编译、数据源权限校验、风险与发布审批。

- 数据库：对 `insight_semantic_relation` 增量添加 `approved_revision INT NULL`、`approved_at TIMESTAMP NULL`。旧表无需清理，首次升级默认待确认。确认记录只针对当前修订号；新的编辑会递增 `revision` 并清空两列。
- API：`POST /api/model/semantic-relations/{id}/approve` 携带 `{revision}`。沿用原管理员认证/CSRF，在事务内锁定关系草稿，验证客户端版本和当前两数据集同连接、stable 字段仍存在、未隐藏/未计算、类型兼容，再设置已确认的修订号。版本冲突、字段失效、关系删除和非法数据均拒绝。
- 列表根据 **当前** 保存版本、批准版本和重新检查的字段有效性动态呈现 `DRAFT`、`APPROVED` 或 `INVALID`。旧版本的批准标志不能延续到编辑后的新版本。管理员界面只对待确认的有效关系显示“确认版本”；失效关系不能确认。
- 现有加工 JOIN SQL 不引用此表，读者端与已发布报表也不读取此状态。这里的 `APPROVED` 仅意为元数据设计完成确认，**绝不代表可执行或已发布**。不增加多用户权限、业务表 DDL 或 StarRocks 数据访问。
- Java/H2 `SemanticRelationApiTest` 扩展已确认、过期版本拒绝、编辑后撤销状态、二次确认、字段失效后的拒绝以及空白名称；Chromium `semantic_relation_drafts_browser_smoke.cjs` 增补创建、确认、编辑导致重置、删除。Vue 构建和定向 CI 沿用 `semantic-relation-scoped.yml`。

开发机上的 18130 预览不会自动覆盖，线上 18110 服务不变。后续才考虑能安全执行的受控多表语义模型编译和字段权限闭环。
