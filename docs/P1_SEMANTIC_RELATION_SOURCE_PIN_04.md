# P1-SEMANTIC-RELATION-SOURCE-PIN-04：受控语义关系的源版本钉住

2026-10-17 · 开发分支 `feat/finebi-analysis-engine-p0`。

## 本批交付

- 对 `insight_semantic_relation` 增量添加可空的 `approved_source_hash VARCHAR(64)`，由既有 Schema 初始化过程幂等执行；旧确认保持原有记录但因缺指纹将重新进入 DRAFT（不进行未经确认的回填）。
- 确认关系时，在原 CAS 修订校验、同连接和 stable fieldId/type 校验后，记录两侧数据集的 SHA-256 快照指纹。仅哈希落元数据库，不新增 SQL 原文或凭据字段。
- 指纹覆盖两侧数据集 id、connection_id、sql_body、物理字段 stable id、源列名、类型、角色与隐藏状态；按 stable id 排序，与字段显示名或返回顺序无关。
- 列表上存在旧确认但源快照不符时降为 DRAFT 并展示重新确认原因；关联数据集/键本身无效仍为 INVALID。编辑草稿会清空批准版本与指纹。
- SQL 编译预览严格比较当前指纹与批准指纹，源 SQL 或字段发生变化便拒绝；管理员可对当前仍有效的草稿重新确认。
- 沿用管理员 session + CSRF，不改变现有 Pipeline、V2 Query、读者 API、发布资源或线上服务。不执行业务 JDBC SQL。

## 定向测试

```bash
cd backend
mvn -q -B -Dtest=SemanticRelationApiTest,ModelRelationsApiTest test
```

Java/H2：SQL 漂移降级并重新批准、两侧连接同时变化、非关联字段隐藏、纯显示名修改仍可使用、旧无指纹确认拒绝、Schema 幂等执行、更新后清空指纹。
前端：`npm run build` + 隔离 Chromium `scripts/semantic_relation_drafts_browser_smoke.cjs`，检查 DRAFT 原因及重新确认入口。

## 未完成和部署边界

- 仅提交开发分支；生产系统与 18130 预览均不变。
- 上线实际执行新增 DDL 前，需先完成 H2 元数据一致性备份与空库恢复验证。不能以 CI 通过宣称已安全部署。
- JOIN 基数/重复键动态风险、读者行列权限、模型发布快照与真正多表执行仍未完成，留到单独的后续批次。
