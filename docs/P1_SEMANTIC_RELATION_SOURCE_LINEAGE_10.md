# P1-SEMANTIC-RELATION-SOURCE-LINEAGE-10：RLS 源数据血缘与预聚合安全门禁

日期：2026-10-17；分支：feat/finebi-analysis-engine-p0。

## 本批最小交付

只修改双侧 RLS 的**管理员编译预览**入口，增加 `SemanticRelationSourceLineageGuard`。先通过现有 `ReadOnlySqlGuard` 的 SELECT AST 和函数允许表校验，再使用 JSQLParser AST 验证源为单个基础表及直接 Column 投影；叠加严格且完整匹配的源 SQL 子语法约束，不对任意 SQL 做尽力改写。字段输出必须能逐个还原为当前字段目录中的原始物理列；不支持表达式、改名、星号、重复列或错误限定符。

所有复杂形式暂时 fail-closed：GROUP BY、HAVING、DISTINCT、预聚合、CTE、UNION、多表 JOIN、派生表、ORDER BY、LIMIT、WHERE 与任何其它本批尚未证明安全的形式。部分原本只满足 SELECT-only 的 SQL 可能不再能获得 RLS 预览：这是保守收紧，而不是报表分析查询能力被删除。

## 回归与权限边界

仅扩展 `SemanticRelationRlsPlanPreviewTest`：允许简单双列直投影及表别名；拒绝聚合、复杂源 SQL、字段投影遗漏/改名、重复列与非本表别名；既有 H2 测试继续验证 LEFT JOIN 权限位于右侧输入、占位符及禁用读者/源版本漂移。

没有变更保守 access-review 和候选快照读者策略；不修改发布接口、ReaderApi、分析 V2 现有查询、不添加 DDL、不运行真实 StarRocks，也不触碰线上 18110/18130。这个安全子集仍不是完整 SQL 血缘推理，不构成真正开放读者多表查询的许可。

## 下一计划节点

先以受控的测试矩阵扩展源 SQL 血缘支持（安全 WHERE 与显式别名的严格解析），再设计可验证的策略快照与执行时校验。未完成前不要绕过当前发布门禁。
