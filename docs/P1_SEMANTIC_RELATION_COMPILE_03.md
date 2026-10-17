# P1-SEMANTIC-RELATION-COMPILE-03：受控关系 SQL 编译预览

本批范围：新增仅管理员可用的已确认关系 SQL 预览。代码与定向测试提交后，CI 结果以 GitHub Actions 为准。未部署。

## API

POST /api/model/semantic-relations/{id}/compile

请求：{"revision":1,"joinType":"LEFT","leftFieldIds":["f_city"],"rightFieldIds":["f_region"]}

必须为当前已批准版本，关联数据集同连接、类型一致且字段仍可用；支持 INNER/LEFT、每侧 1～10 个物理字段、最多 3 组关联键。
客户端不得传 SQL、JOIN 条件或别名；所有物理标识来自服务器字段目录，两侧原 SQL 均经只读 AST/函数白名单验证。
返回 SQL 预览、字段元数据和风险提示，固定 LIMIT 200。无业务 JDBC 调用，不授予任何模型查询或读者权限。

## 兼容与风险

不修改现有数据库表、旧 V1/V2 JSON、发布快照、Reader、Pipeline 或生产服务。
同键重复可能扩大 JOIN 结果，LIMIT 只限制返回行数而不限制源扫描；只编译，绝不作为已生产验收或已执行模型。

## 定向测试

cd backend && mvn -q -B -Dtest=SemanticRelationApiTest,ModelRelationsApiTest test
GitHub Actions：P1 Semantic Relationship Drafts Scoped。
测试覆盖草稿拒绝、已批准版本、实际 H2 联表 SQL、非法模式/字段、更新后批准过期、源 SQL 与连接变化拒绝。

## 下一批

补齐源 SQL 版本指纹、JOIN 基数/成本检查、用户字段与行权限以及发布修订钉住，再讨论是否开放安全执行入口。
