# P1-SEMANTIC-RELATION-ACCESS-REVIEW-06：预发布读者字段权限审查

2026-10-17；分支 feat/finebi-analysis-engine-p0。

## 新增后端接口

- POST /api/model/semantic-relations/{id}/access-review：严格输入 revision、readerId、joinType、leftFieldIds、rightFieldIds。管理员 Session + CSRF。
- GET /api/model/semantic-relations/{id}/access-review?readerId=...：返回 MISSING / READY_FOR_REVIEW / BLOCKED / STALE，不暴露源 SQL 或字段明细。
- 复用已确认 revision、源 SQL/物理字段指纹、同连接及 SQL 编译预览的校验；仅编译 SQL，不执行。
- 针对一个已启用 VIEWER/EDITOR 读者评估两侧策略：任一数据集存在该读者的列 deny 或行规则，拒绝生成审查记录，因为目前没有双侧 RLS 和可靠的源 SQL 列血缘。
- 持久化预发布审查快照：relationId + readerId 组成主键，记录 relationRevision、sourceHash、policyHash、bindingJson 及时间；权限/读者 auth_version/源定义/关系修订变化后动态返回 BLOCKED 或 STALE。
- 使用独立 insight_semantic_access_review 表，通过幂等 CREATE TABLE IF NOT EXISTS 创建。不自动回填、不赋予权限。

## 明确的边界

READY_FOR_REVIEW 只代表当前这个读者在元数据层面暂未触发阻断条件，绝不是读者授权、报表发布许可、SQL 执行许可或已完成双侧 RLS。没有报表、版本号或实际访问授权绑定；仍需独立的审批与发布安全步骤。
本批不修改 ReaderApi 的权限判断、不改变现有报表发布或任何已有 SQL 查询行为，也不部署 18110/18130。
对于数据库只读视图背后的隐藏血缘，本门禁依赖保守的全数据集 deny/row 检查，不能替代完整解析。权限绕回原状时仅比较当前快照，不提供审计级单调版本证明。

## 测试

Java/H2 定向验证：无批准时拒绝、批准后存储单个读者的固定绑定、禁止 CROSS/非法字段、无授权写入，任何列 deny（包括未投影的非键列）、行政策、账号禁用、auth_version、SQL 源和关系修订变化导致阻断/失效，Schema 幂等。
CI：P1 Semantic Relationship Drafts Scoped、P0 Changed-Area Checks；上线 DDL 前仍需 H2 元数据库一致性备份与恢复演练。

## 下一批

正式的报表绑定还需不可变发布快照、左右表行级权限下推、权限策略变更触发失效、关键关联模式下的执行限制和权限场景测试。不得将预发布 READY_FOR_REVIEW 直接当作 JOIN 执行凭据。
