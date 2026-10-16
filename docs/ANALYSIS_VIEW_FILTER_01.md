# ANALYSIS-VIEW-FILTER-01 · 统一工作台视图级筛选组件（2026-10-16）

## 实现范围

在既有一个分析主题、多视图引用共享 V2 Widget 的架构上，支持每个视图配置**一个受控维度筛选输入框**，实现主题编辑预览、保存/重开、发布以及获授权读者的真实查询联动。这是现有筛选/联动引擎的安全小闭环，**没有新增第三套 Designer、自由 SQL 或全局查询服务**。

- `DashboardView.filter` 可选存储 `datasetId/fieldId/label`，仅允许选择**当前视图中已有 V2 组件的维度槽**，至少有一个目标 Widget 同 datasetId 与 stable fieldId。筛选值是浏览器运行时的临时状态，不写 JSON、历史、缓存或发布快照；复制视图复制配置但不复制运行时值。移除组件、切换数据集后失效的配置由编辑器清理或阻止保存。
- 编辑/管理员已发布页面：仅对同一 datasetId 且**明确绑定该维度**的 Widget 追加一个 `filters.pre` 的 `eq` 过滤；其他视图/数据集/无匹配字段组件不受影响。值在点击「应用」后才查询，避免键入每个字符都发起后台查询。已有组件结构、排序、尺寸及表格查询上限不变。
- 读者页面：只提交 `{viewId,filterValue}`，`ReaderApi` 校验请求**只有这两个键**、视图存在于已发布快照、目标 Widget 在该视图成员列表中、该视图中已授权筛选配置的 `datasetId` 与目标 Widget 的数据集一致、`fieldId` 属于目标 Widget 的已发布维度槽。值限非空文本且不超过 120 字符，拒绝控制字符。
- 读者的行级强制过滤仍由服务器**在可信已发布配置之后**追加，与运行时筛选及发布配置原 `filters.pre` 同时生效，不能被读者覆盖。列级权限仍由 `ReaderColumnGuard` 对整张已发布报表验证；筛选字段因必须是已发布的目标组件维度，不能借此访问未授权列。值仅作为 Planner JDBC 参数参与 SQL，不允许读取端提交任意字段/表达式/SQL。
- 未配置视图筛选的旧 JSON、旧发布快照、旧 API 请求空对象以及 V1 Designer 继续保持原行为。仅在正确配置后允许通过读者端提交受控筛选值，原来伪造 `datasetId`、`filters` 或限制参数的请求继续拒绝。
- 第一批只支持一个视图级等值筛选，不支持多条件/日期范围/值列表，也**不支持跨数据集自动字段匹配**。只有同一数据集且 Widget 使用同一 stable fieldId 的维度才能联动。

## 定向验收

- `cd backend && mvn -B -q -Dtest=ReaderViewFilterTest,ReaderApiTest test`：隔离 H2 元数据、用户角色/已发布快照/行级强制过滤、请求越权和 SQL 参数绑定；旧读者查询测试作为定向兼容用例保留。
- `node --experimental-strip-types --test scripts/analysis_view_filter.test.mjs scripts/analysis_workspace_layout.test.mjs`：同源过滤合法性、多视图隔离、删除悬空字段、无运行时值持久化。
- `cd frontend && npm run build`：TypeScript/Vite。
- `scripts/analysis_view_filter_browser_smoke.cjs`：隔离 Chromium+模拟 REST 真实浏览器检查编辑/应用、跨视图不干扰、保存重进、发布作者阅读、读者受控 `viewId/filterValue` 请求。
- 专项 GitHub CI：`.github/workflows/analysis-view-filter-scoped.yml`。**只有远端 CI 成功才标记本批完成**。原前端非本批页面不进行全量回归。

## 风险边界与后续

服务器依靠发布配置的稳定字段 ID 和已授权内容拒绝任意字段筛选；本批不能声称任意跨数据集列映射已实现。没有过滤值选项的无限 DISTINCT 查询（用户手动输入值），避免无界候选查询。若真实 StarRocks 字段类型有差异，需要后续在专用只读环境完成驱动兼容验收；本批开发机离线，只有 GitHub Actions 隔离 H2/Chromium 验证。

仍待完成：多个筛选器组合、级联下拉的受控候选集、受权限约束的同源跨数据集显式映射、旧 V1 资源迁移，以及生产 HTTPS/备份/上线门禁。**未部署至生产 18110、未迁移数据库、未修改真实业务表。**
