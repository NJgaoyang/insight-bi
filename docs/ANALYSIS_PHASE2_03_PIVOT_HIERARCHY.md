# ANALYSIS-PHASE2-03 · V2 两级行分组及逐列小计（2026-10-15）

## 目标与实现

接续 `ANALYSIS-PHASE2-01`（二维交叉表）和 `ANALYSIS-PHASE2-02`（服务端精确合计），本批扩展**三维 V2 交叉表**：字段槽的三个维度依次为「一级行维度、二级行维度、列维度」；原先两个维度的视图不变。指标仍支持 1–5 个，使用原稳定 fieldId、服务端结构化查询和现有数据源/字段、权限校验。

- V2 编辑器显式说明维度顺序，三个维度时增加**两级行分组小计开关**；行/列排序及合计开关继续保留。客户端只负责显示；拖动维度槽即可调整层次，快照保存使用原 `analysisV2.dimensions` 和可选 `analysisV2.pivot.showSubtotals`。
- 后端 `PivotRollup` 对三维查询执行有限只读 SQL：`GROUP BY (行1,行2,列)` 得到明细分组；`GROUP BY (行1,行2)` 得到叶子行合计；`GROUP BY 列` 得到列合计；无维度 GROUP 得到总计；`GROUP BY 行1` 得到一级行合计；`GROUP BY (行1,列)` 得到**每个列分组下的一级行小计**。AVG、COUNT DISTINCT、MIN、MAX 等全部由数据库重新聚合，不能由前端相加。
- 单视图最多 100 个叶子行组、24 个列分组、每次查询最多 500 行，保留 V2 `LIMIT+1` 截断真实性。若原始分组超过上限、任意汇总层次改变了行组/列组、查询存在后置汇总筛选及窗口计算，服务端拒绝合计并明确提示，不返回伪数。所有查询都在同一 JDBC 只读连接及同一次 V2 并发限额之内运行，但**并非多个 SQL 之间的事务一致快照**。
- 服务端响应新增长度有限的 `pivotTotals.parents`（父分组合计）、`pivotTotals.parentCells`（父分组×列维度小计）；必要列与维度 ID 全由已有 Planner 生成，不接受浏览器传入原始 SQL。读取端验证父级/列组键与现有结果一致，对无效值拒绝全部小计。
- 前端显示两级行标题、叶子数据行、父级结束处的小计行、行/列合计，分页仍以 20 个叶子行组为单位；若父级跨页，小计显示在它的**最后一个叶子组所在页**。分页与局部排序只改变布局，不影响服务端合计。
- CSV 继续导出当前 V2 查询的有限分组结果，而不是自称全量结果。旧的 V1 Designer、旧图表 JSON、数据库表、发布权限与审批流程不变；不需要强制迁移历史资源。

## 定向验收

1. `cd backend && mvn -q -o -Dtest=AnalysisPivotHierarchyTest,AnalysisPivotRollupTest,AnalysisPivotApiIntegrationTest,AnalysisPivotGroupedQueryTest,AnalysisQueryPlannerTest,ReaderApiTest test`：隔离 H2 测试三维 Group By、逐列父级小计、重复客户的准确 COUNT DISTINCT、AVG、前置强制过滤保留，以及禁用小计不执行额外查询。
2. `node --experimental-strip-types --test scripts/analysis_phase2_pivot.test.mjs`：二维/三维分组、精确合计、父级列小计、NULL、Decimal、截断/非法元数据。
3. `cd frontend && npm run build`：Vue + TypeScript + Vite 构建。
4. 在隔离静态前端配合 mocked REST 的 Chromium 中运行 `scripts/analysis_phase2_pivot_browser_smoke.cjs`、`scripts/analysis_phase2_pivot_rollup_browser_smoke.cjs`、`scripts/analysis_phase2_pivot_hierarchy_browser_smoke.cjs`，验收 2/3 维度组件、分层行合计及每列小计、保存/刷新/发布及双视图。
5. 另定向执行 `node scripts/finebi_published_views_ui_smoke.cjs`：旧 V1/已发布 V2 查看、管理员部门授权和读者入口验收。之前该测试固定抓取第一张表，页面插入其他资源表后误判部门不存在；只修复 `scripts/finebi_published_views_ui_smoke.cjs` 的部门区域选择器，不改后端授权逻辑。
6. 专项 CI 已映射到 `.github/workflows/analysis-phase2-pivot-scoped.yml`。**只有 GitHub 实际执行通过才能将其标记成功。**

## 尚未完成

行列维度还不能选择任意层级数量或多层列嵌套（现有 V2 最多 3 维）；不支持层级折叠、复杂后置过滤下的层级小计、Excel/PDF 高级导出、表格大规模列虚拟化及跨语句严格一致性。多 SQL 小计实际数据库负载高于单组查询，仍需在专用 StarRocks 测试库做性能边界与 GROUPING SETS 兼容性验证。

本批仅在 `/opt/insight-bi-phase2` 的隔离工作目录开发、定向测试并提交原分支；**未部署到原 18110 服务、未修改业务数据表或线上元数据库、未执行全量回归。**
