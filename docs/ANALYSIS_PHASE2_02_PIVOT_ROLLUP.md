# ANALYSIS-PHASE2-02 · V2 交叉表服务端精确合计（2026-10-15）

## 本批范围

延续已合入 `feat/finebi-analysis-engine-p0` 的 `ANALYSIS-PHASE2-01` 第一批。第一批只允许 SUM/非去重 COUNT 在浏览器对有限分组聚合结果求和。本批改为**服务端独立聚合行合计、列合计和全表总计**，避免将 AVG、COUNT(DISTINCT)、MIN/MAX 等非可加指标直接相加。

没有创建新的原始 SQL API、数据库表或数据集迁移；仍使用 `POST /api/analysis/v2/query` 和原有读者 `/api/reader/dashboards/{id}/widgets/{widgetId}/query`。V1 `ChartWidget.vue` 和 `Designer.vue` 没有改动。

## 协议及安全限制

- V2 交叉表请求增加 **可选** `pivot:{showTotals:true|false,rowOrder?:'asc'|'desc',columnOrder?:'asc'|'desc'}`。服务端严格校验可接受键和值。普通旧 V2 分析没有 `pivot` 时仍只执行一次查询。
- 若启用合计，服务端先执行原有维度×列维度查询；确认没有触发 LIMIT sentinel 截断、行分组数不超过 100、列分组数不超过 24，且没有 AGGREGATE 后置过滤、RANK/SHARE/累计/周期比较这些可能改变分组合计语义的分析附加项，然后在**同一个 JDBC 只读连接与同一次查询并发许可内**执行三次安全查询：只按行维度分组、只按列维度分组、无维度全局聚合。
- 每个额外查询均**重新经过现有 AnalysisQueryPlanner 与加工版本绑定**，保留 `datasetId`、`usePipeline`/`pipelineRevision`、`measures` 和所有 `filters.pre`。读者行级强制过滤在构建受信任查询时由服务器注入，随后连同三次合计查询一起执行；客户端不可能传入读者的任意计算字段或覆盖过滤条件。
- 单次附加行查询最多 102 行（101 + sentinel），列查询最多 26 行（25 + sentinel），全局查询最多 2 行。每次仍受 JDBC 单查询 15 秒超时限制。超过上限、原结果已截断或行列组不一致时返回 `pivotTotals:{exact:false,reason:'...'}` 并隐藏合计，不给出虚假数字。
- 成功返回 `pivotTotals:{exact:true,rows:[{d1,measureId...}],columns:[{d1,measureId...}],grand:{measureId...}}`。Decimal/BigInteger 及计数的 Long 使用字符串编码来保留精度，前端对合计使用 BigInt 定点格式化。NULL 与“(空值)”仍按不同 key 区分；金额单元格仍遵循现有 V2 单元格显示合同（未来可扩展完整 Decimal 单元格传输）。
- 旧前端收到新增字段会忽略它；新前端遇到旧后端没有 `pivotTotals` 时沿用第一批「只对可加指标本地求和」的兼容逻辑；遇到新后端明确拒绝合计，则不会回退到不精确的前端求和。CSV 导出仍仅包含查询到的分组结果，不伪称导出整份全量聚合。
- **一致性边界**：三次服务端聚合和主查询依次在同一个连接执行，未宣称形成 StarRocks 跨语句一致快照。高并发写入下，组数未变化但指标值变化仍可能产生不同时间点读数；真正多语句快照一致性或单 SQL GROUPING SETS 有待后续验证，不把当前功能称为事务级精确快照。

## 本批定向测试（不运行全量回归）

1. `cd backend && mvn -q -o -Dtest=AnalysisPivotApiIntegrationTest,AnalysisPivotRollupTest,AnalysisPivotGroupedQueryTest,AnalysisQueryPlannerTest,ReaderApiTest test`：隔离 H2 MySQL 模式下验证实际 V2 API 查询、平均值加权重新聚合、跨维度去重 COUNT、Decimal 精度、前置过滤保留、结果过滤拒绝、截断拒绝、非法字段与读者已发布快照参数不可篡改。
2. `node --experimental-strip-types --test scripts/analysis_phase2_pivot.test.mjs`：六项纯逻辑测试包括服务端结果严格校验、数值精度和禁用不可靠合计。
3. `cd frontend && npm run build`：V2 前端 TS 及 Vite 构建。
4. `P0_PREVIEW_URL=http://127.0.0.1:18130 node scripts/analysis_phase2_pivot_rollup_browser_smoke.cjs`：独立 Chromium + 模拟 REST 验收行均值、全局均值、发布快照、查询截断和分页；同一套操作没有请求真实业务源。
5. 以上检查映射至 `.github/workflows/analysis-phase2-pivot-scoped.yml`，**GitHub CI 只有在远端实际执行通过后才标记成功**。

## 未完成与下一批

**仍未完成**：第三维度/多层行列分组（结构化多级小计），复杂结果过滤的语义级服务端小计，单 SQL ROLLUP/GROUPING SETS 原子一致性，超大多组的列虚拟化与受控 Excel/PDF，生产 StarRocks 的专门只读整体验收。后续建议先完成「3 维度层次交叉表 + 分层小计」并继续定向测试，不要一并重写旧 V1 Designer。

本批没有生产部署、没有启停 FE/BE、没有触碰真实业务数据表，没有迁移现有 H2 元数据库；仅修改隔离工作目录代码后提交到原分支。
