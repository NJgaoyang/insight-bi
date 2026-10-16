# ANALYSIS-VIEW-FILTER-02 · 受控双条件 AND 筛选（2026-10-16）

## 交付范围

在已交付视图级筛选与同连接跨数据集显式字段映射之上，每个分析主题仪表板视图最多配置第二个可选的筛选维度（`filter2`）。旧版 `filter`、跨数据集 `mappings` 和阅读接口继续保留，无数据库变更。

- 第二字段必须是**和第一筛选字段相同的数据集**、不同稳定 `fieldId`、已经在当前视图可见 V2 Widget 维度槽中的字段。第一筛选字段仍可显式映射到同连接跨数据集 Widget；第二筛选字段在此批**只作用于原数据集下同时绑定第二维度的组件**，不自动传播到外部数据集。
- 浏览器提供第二字段下拉框与文本值输入。两项都非空时按 AND 叠加两组 `filters.pre.eq`；仅填写第一或第二也可使用。实际值不保存进报表 JSON、版本历史或发布快照；仅保存第二字段的 `datasetId/fieldId/label` 元数据。切换视图清空输入，切换主筛选字段自动清除旧第二字段。复制视图深复制配置，防止串改。
- 已发布读者仍仅调用`/reader/dashboards/{id}/widgets/{widgetId}/query`，允许保留旧请求 `{}` 与 `{viewId,filterValue}`；若存在合法发布配置，增加 `filterValue2`，支持仅传第二值。任意未知请求键、空值、超长值、控制字符、伪造视图或字段都拒绝。读者端无权传字段 ID、映射或 SQL。
- 服务端核对发布快照、视图成员、第一字段配置、第二字段**同数据集且与第一字段不同**、目标 Widget 绑定的实际维度，以及服务器字段目录对应物理列。将两项 AND 过滤与原配置、强制行级过滤合并给 V2 查询计划，以 JDBC 参数绑定，而不是 SQL 拼接。列级访问权限及已发布内容门禁保留。

## 定向验证

- Java/H2: `ReaderViewFilterTest`、`ReaderCrossDatasetFilterTest`、`ReaderApiTest`；测试双条件及单第二条件、权限合并、无发布配置/错误视图、参数白名单和单条件历史兼容。
- Node: `scripts/analysis_view_filter.test.mjs`、`scripts/analysis_workspace_layout.test.mjs`；验证第二字段候选仅同源、不同 ID、两条件 AND、跨数据集不隐式联动与不持久化输入。
- Vue TypeScript/Vite: `npm run build`。
- Chromium: 保留 `analysis_view_filter_browser_smoke.cjs` 原单筛选及跨数据集用例，新增 `analysis_view_filter_multi_browser_smoke.cjs` 覆盖第二字段选择、AND 查询、保存/重进、发布与读者按白名单查询。
- GitHub 定向工作流：`.github/workflows/analysis-view-filter-scoped.yml`。只有执行成功才标注本批 CI PASS，不在开发机离线时声称真实数据源验收。

## 已知限制

这是一批受控的两维度等值筛选，而不是任意多条件表达式编辑器；暂不支持第二筛选字段的跨数据集显式映射、日期范围、OR、候选值级联/权限候选集、无限并发查询或跨报表共享筛选状态。保持既有安全 Planner、只读策略、修订号和发布流程不变；未部署生产 18110、未变动元数据表，也未连接真实 StarRocks 环境。

后续优先完成多表关系图与影响分析（只读模型关系元数据）、可授权的钻取路径和受控候选集，避免直接复制商业 BI 界面。
