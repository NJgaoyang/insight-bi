# P1-SEMANTIC-RELATION-CARDINALITY-05：语义关系右表键基数抽样诊断

2026-10-17；`feat/finebi-analysis-engine-p0`。

新增管理员端点 `POST /api/model/semantic-relations/{relationId}/join-key-profile`，仅接受 `{"revision":1,"acknowledgeSourceScan":true}`。
服务端检查已批准修订、两侧数据集的 SQL/连接/物理字段指纹及当前关联字段映射，未确认或已变化时在打开业务 JDBC 之前拒绝。
仅使用数据集保存的右表 SELECT 和最多三组稳定关联键，不接受客户端 SQL 或字段名。
先申请独立限流租约（2 并发、250ms 等待），再建立 JDBC 连接并强制只读，Statement 超时 8 秒、结果最多 1 行；右表最多 2000 行样本聚合 NULL 键行、非 NULL 键组、重复键组、额外行、单键最大重复数。
始终标记 `RIGHT_SOURCE_SAMPLE`、`exactSourceCount=false`、`exactJoinRowCount=false`，达到上限时标记 `sampleCapReached=true`。
NULL 键不可经 SQL '=' 命中，不计入重复可关联键组。不执行 JOIN、不返回业务明细、不授予读者权限或影响发布快照。

**限制**：对源 SQL 结果做 LIMIT 仍可能触发复杂底层扫描/排序；使用 SELECT-only 专用账号及授权测试数据，并非生产负载保证。
本批不部署生产或预览服务，未验收真实 StarRocks。

定向测试：`cd backend && mvn -q -B -Dtest=SemanticRelationApiTest,SemanticRelationCardinalityProfileTest,ModelRelationsApiTest test`。
下一批完善 UI 展示、风险承认、行/列权限及发布版本绑定。
