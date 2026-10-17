# P1-SEMANTIC-RELATION-SOURCE-LINEAGE-11：受限 WHERE 与显式字段别名

2026-10-17；分支：feat/finebi-analysis-engine-p0。

## 范围

沿用上一批 `SemanticRelationSourceLineageGuard`，仅扩大**双侧 RLS 管理员只读 SQL 编译预览**允许的源 SQL 子集。必须是单表直接物理列 SELECT，允许 `SELECT physical AS output_name`（稳定输出名映射到一条且仅一条物理列）和最多四个 `AND` 连接的 `physical_column = literal` WHERE；支持有界简单字符串/非负整数值，以及当前表合法限定符。对输出别名/物理来源列去重，字段目录必须逐一对应输出列；WHERE 只能引用该源已经直接投影的原始列。SQL 只读 AST 以及完整 SQL 匹配双层校验，不作 SQL 重写。

## 仍禁止

任意聚合、DISTINCT、GROUP BY、HAVING、WITH/CTE、UNION、JOIN、子查询、表达式投影/改名、星号、ORDER/LIMIT、OR/IN/LIKE、WHERE 函数、任意 '?' 或 SQL 注释等不在允许子语法的内容均安全拒绝。源 WHERE 字面量不是由读者填入的运行时值；仍须源 SQL 确认和双侧指纹审查。采用有意保守的字符串值允许字符范围，暂不支持转义引号、负数或复杂过滤。

## 验收

仅修改 `SemanticRelationSourceLineageGuard.java` 与 `SemanticRelationRlsPlanPreviewTest.java`，专项 H2 用例校验身份别名、WHERE、字段别名的正例与 OR/子查询/未知字段/重复别名/伪造参数等负例；同时验证原有 LEFT JOIN 空右侧行保持不变。CI 只以 `SemanticRelationRlsPlanPreviewTest` 的定向结果为验收准绳，不要求其他模块的全量回归。

不改接口形状、不改 ReaderApi、access-review、候选版本与正式发布门禁、不运行真实 JOIN 或 StarRocks、无 DDL、不部署 18110/18130。

## 下一节点

需要在任何实际读者执行前，把双侧权限的值、身份版本与来源指纹绑定为原子可校验的计划快照，在每次查询前重验权限和来源；当前阶段不解除保守发布阻断。
